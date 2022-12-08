package com.mapbox.navigation.core.trip.session

import android.location.Location
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.bindgen.Expected
import com.mapbox.navigation.base.ExperimentalMapboxNavigationAPI
import com.mapbox.navigation.base.internal.factory.RoadFactory
import com.mapbox.navigation.base.internal.factory.TripNotificationStateFactory.buildTripNotificationState
import com.mapbox.navigation.base.internal.route.refreshNativePeer
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.roadobject.UpcomingRoadObject
import com.mapbox.navigation.core.BasicSetRoutesInfo
import com.mapbox.navigation.core.SetAlternativeRoutesInfo
import com.mapbox.navigation.core.SetRefreshedRoutesInfo
import com.mapbox.navigation.core.SetRoutesInfo
import com.mapbox.navigation.core.navigator.getCurrentBannerInstructions
import com.mapbox.navigation.core.navigator.getLocationMatcherResult
import com.mapbox.navigation.core.navigator.getRouteProgressFrom
import com.mapbox.navigation.core.navigator.getTripStatusFrom
import com.mapbox.navigation.core.navigator.toFixLocation
import com.mapbox.navigation.core.navigator.toLocation
import com.mapbox.navigation.core.navigator.toLocations
import com.mapbox.navigation.core.trip.service.TripService
import com.mapbox.navigation.core.trip.session.eh.EHorizonObserver
import com.mapbox.navigation.core.trip.session.eh.EHorizonSubscriptionManager
import com.mapbox.navigation.navigator.internal.MapboxNativeNavigator
import com.mapbox.navigation.navigator.internal.MapboxNativeNavigatorImpl
import com.mapbox.navigation.navigator.internal.TripStatus
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.ThreadController
import com.mapbox.navigation.utils.internal.ifNonNull
import com.mapbox.navigation.utils.internal.logD
import com.mapbox.navigation.utils.internal.logW
import com.mapbox.navigator.FallbackVersionsObserver
import com.mapbox.navigator.NavigationStatus
import com.mapbox.navigator.NavigationStatusOrigin
import com.mapbox.navigator.NavigatorObserver
import com.mapbox.navigator.RouteAlternative
import com.mapbox.navigator.RouteState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * Default implementation of [TripSession]
 *
 * @param tripService TripService
 * @param tripSessionLocationEngine the location engine
 * @param navigator Native navigator
 * @param threadController controller for main/io jobs
 */
@MainThread
internal class MapboxTripSession(
    override val tripService: TripService,
    private val tripSessionLocationEngine: TripSessionLocationEngine,
    private val navigator: MapboxNativeNavigator = MapboxNativeNavigatorImpl,
    private val threadController: ThreadController,
    private val eHorizonSubscriptionManager: EHorizonSubscriptionManager
) : TripSession {

    private var isUpdatingRoute = false
    private var updateLegIndexJob: Job? = null

    @VisibleForTesting
    internal var primaryRoute: NavigationRoute? = null

    private companion object {
        private const val LOG_CATEGORY = "MapboxTripSession"
        private const val INDEX_OF_INITIAL_LEG_TARGET = 1
    }

    override suspend fun setRoutes(
        routes: List<NavigationRoute>,
        setRoutesInfo: SetRoutesInfo,
    ): NativeSetRouteResult {
        logD(
            "routes update (reason: ${setRoutesInfo.reason}, " +
                "route IDs: ${routes.map { it.id }}) - starting",
            LOG_CATEGORY
        )
        val result = when (setRoutesInfo) {
            is BasicSetRoutesInfo -> {
                isUpdatingRoute = true
                setRouteToNativeNavigator(routes, setRoutesInfo.legIndex)
                    .also { isUpdatingRoute = false }
            }
            is SetAlternativeRoutesInfo -> {
                isUpdatingRoute = false
                NativeSetRouteValue(
                    routes = routes,
                    nativeAlternatives = navigator.setAlternativeRoutes(routes.drop(1))
                )
            }
            is SetRefreshedRoutesInfo -> {
                if (routes.isNotEmpty()) {
                    val primaryRoute = routes.first()
                    lateinit var refreshRouteResult: Expected<String, List<RouteAlternative>>
                    var lastSavedResultValue: Expected<String, List<RouteAlternative>>? = null
                    for (route in routes) {
                        refreshRouteResult = navigator.refreshRoute(route)
                        if (refreshRouteResult.isValue) {
                            lastSavedResultValue = refreshRouteResult
                        }
                    }
                    // The latest result contains the most actual cumulated data.
                    // TODO API change request NN-110
                    (lastSavedResultValue ?: refreshRouteResult).fold(
                        { NativeSetRouteError(it) },
                        { value ->
                            val refreshedPrimaryRoute = primaryRoute.refreshNativePeer()
                            this@MapboxTripSession.primaryRoute = refreshedPrimaryRoute
                            roadObjects = refreshedPrimaryRoute.upcomingRoadObjects
                            val refreshedRoutes = routes
                                .drop(1)
                                .toMutableList().apply {
                                    add(0, refreshedPrimaryRoute)
                                }
                            NativeSetRouteValue(
                                routes = refreshedRoutes,
                                nativeAlternatives = value
                            )
                        }
                    ).also {
                        logD(
                            "routes update (route IDs: ${routes.map { it.id }}) - refresh finished",
                            LOG_CATEGORY
                        )
                    }
                } else {
                    with("Cannot refresh route. Route can't be null") {
                        logW(this, LOG_CATEGORY)
                        NativeSetRouteError(this)
                    }
                }
            }
        }
        logD(
            "routes update (reason: ${setRoutesInfo.reason}, " +
                "route IDs: ${routes.map { it.id }}) - finished",
            LOG_CATEGORY
        )
        return result
    }

    private suspend fun setRouteToNativeNavigator(
        routes: List<NavigationRoute>,
        legIndex: Int
    ): NativeSetRouteResult {
        logD(
            "native routes update (route IDs: ${routes.map { it.id }}) - starting",
            LOG_CATEGORY
        )
        val newPrimaryRoute = routes.firstOrNull()
        return navigator.setRoutes(
            newPrimaryRoute,
            legIndex,
            routes.drop(1)
        ).onValue {
            updateLegIndexJob?.cancel()
            this@MapboxTripSession.primaryRoute = newPrimaryRoute
            roadObjects = newPrimaryRoute?.upcomingRoadObjects ?: emptyList()
            isOffRoute = false
            invalidateLatestInstructions(
                bannerInstructionEvent.latestInstructionWrapper,
                lastVoiceInstruction
            )
            routeProgress = null
        }.mapValue {
            it.alternatives
        }.fold({ NativeSetRouteError(it) }, { NativeSetRouteValue(routes, it) }).also {
            logD(
                "native routes update (route IDs: ${routes.map { it.id }}) - finished",
                LOG_CATEGORY
            )
        }
    }

    private val mainJobController: JobControl = threadController.getMainScopeAndRootJob()

    private val locationObservers = CopyOnWriteArraySet<LocationObserver>()
    private val routeProgressObservers = CopyOnWriteArraySet<RouteProgressObserver>()
    private val offRouteObservers = CopyOnWriteArraySet<OffRouteObserver>()
    private val stateObservers = CopyOnWriteArraySet<TripSessionStateObserver>()
    private val bannerInstructionsObservers = CopyOnWriteArraySet<BannerInstructionsObserver>()
    private val voiceInstructionsObservers = CopyOnWriteArraySet<VoiceInstructionsObserver>()
    private val roadObjectsOnRouteObservers =
        CopyOnWriteArraySet<RoadObjectsOnRouteObserver>()
    private val fallbackVersionsObservers = CopyOnWriteArraySet<FallbackVersionsObserver>()

    private val bannerInstructionEvent = BannerInstructionEvent()

    @VisibleForTesting
    internal var lastVoiceInstruction: VoiceInstructions? = null

    private var state: TripSessionState = TripSessionState.STOPPED
        set(value) {
            if (field == value) {
                return
            }
            field = value
            stateObservers.forEach { it.onSessionStateChanged(value) }
        }

    private var isOffRoute: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            offRouteObservers.forEach { it.onOffRouteStateChanged(value) }
        }

    private var rawLocation: Location? = null
    override var zLevel: Int? = null
        private set
    private var routeProgress: RouteProgress? = null
    private var roadObjects: List<UpcomingRoadObject> = emptyList()
        set(value) {
            if (field == value) {
                return
            }
            field = value
            roadObjectsOnRouteObservers.forEach { it.onNewRoadObjectsOnTheRoute(value) }
        }

    override var locationMatcherResult: LocationMatcherResult? = null
        private set

    private val nativeFallbackVersionsObserver =
        object : FallbackVersionsObserver {
            override fun onFallbackVersionsFound(versions: MutableList<String>) {
                mainJobController.scope.launch {
                    fallbackVersionsObservers.forEach {
                        it.onFallbackVersionsFound(versions)
                    }
                }
            }

            override fun onCanReturnToLatest(version: String) {
                mainJobController.scope.launch {
                    fallbackVersionsObservers.forEach {
                        it.onCanReturnToLatest(version)
                    }
                }
            }
        }

    init {
        navigator.setNativeNavigatorRecreationObserver {
            if (fallbackVersionsObservers.isNotEmpty()) {
                navigator.setFallbackVersionsObserver(nativeFallbackVersionsObserver)
            }
            if (state == TripSessionState.STARTED) {
                navigator.addNavigatorObserver(navigatorObserver)
            }
        }
    }

    /**
     * Return raw location
     */
    override fun getRawLocation() = rawLocation

    /**
     * Provide route progress
     */
    override fun getRouteProgress() = routeProgress

    /**
     * Current [MapboxTripSession] state
     */
    override fun getState(): TripSessionState = state

    /**
     * Start MapboxTripSession
     */
    override fun start(withTripService: Boolean, withReplayEnabled: Boolean) {
        if (state == TripSessionState.STARTED) {
            return
        }
        navigator.addNavigatorObserver(navigatorObserver)
        if (withTripService) {
            tripService.startService()
        }
        tripSessionLocationEngine.startLocationUpdates(withReplayEnabled) {
            updateRawLocation(it)
        }
        state = TripSessionState.STARTED
    }

    private fun updateRawLocation(rawLocation: Location) {
        val locationHash = rawLocation.hashCode()
        logD(
            "updateRawLocation; system elapsed time: ${System.nanoTime()}; " +
                "location ($locationHash) elapsed time: ${rawLocation.elapsedRealtimeNanos}",
            LOG_CATEGORY
        )
        this.rawLocation = rawLocation
        locationObservers.forEach { it.onNewRawLocation(rawLocation) }
        mainJobController.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            logD(
                "updateRawLocation; notify navigator for ($locationHash) - start",
                LOG_CATEGORY
            )
            navigator.updateLocation(rawLocation.toFixLocation())
            logD(
                "updateRawLocation; notify navigator for ($locationHash) - end",
                LOG_CATEGORY
            )
        }
    }

    /**
     * Returns if the MapboxTripSession is running a foreground service
     */
    override fun isRunningWithForegroundService(): Boolean {
        return tripService.hasServiceStarted()
    }

    @OptIn(ExperimentalMapboxNavigationAPI::class)
    private val navigatorObserver = object : NavigatorObserver {
        override fun onStatus(origin: NavigationStatusOrigin, status: NavigationStatus) {
            logD(
                "navigatorObserver#onStatus; " +
                    "fixLocation elapsed time: ${status.location.monotonicTimestampNanoseconds}, " +
                    "state: ${status.routeState}",
                LOG_CATEGORY
            )
            logD(
                "navigatorObserver#onStatus; banner instruction: [${status.bannerInstruction}]," +
                    " voice instruction: [${status.voiceInstruction}]",
                LOG_CATEGORY
            )

            val tripStatus = status.getTripStatusFrom(primaryRoute)
            val enhancedLocation = tripStatus.navigationStatus.location.toLocation()
            val keyPoints = tripStatus.navigationStatus.keyPoints.toLocations()
            val road = RoadFactory.buildRoadObject(tripStatus.navigationStatus)
            updateLocationMatcherResult(
                tripStatus.getLocationMatcherResult(enhancedLocation, keyPoints, road)
            )
            zLevel = status.layer

            // we should skip RouteProgress, BannerInstructions, isOffRoute state updates while
            // setting a new route
            if (isUpdatingRoute) {
                logD("route progress update dropped - updating routes", LOG_CATEGORY)
                return
            }

            var triggerObserver = false
            if (tripStatus.navigationStatus.routeState != RouteState.INVALID) {
                val nativeBannerInstruction = tripStatus.navigationStatus.bannerInstruction
                val bannerInstructions =
                    tripStatus.navigationStatus.getCurrentBannerInstructions(primaryRoute)
                triggerObserver = bannerInstructionEvent.isOccurring(
                    bannerInstructions,
                    nativeBannerInstruction?.index
                )
            }
            val remainingWaypoints = calculateRemainingWaypoints(tripStatus)
            val latestBannerInstructionsWrapper = bannerInstructionEvent.latestInstructionWrapper
            val routeProgress = getRouteProgressFrom(
                tripStatus.route,
                tripStatus.navigationStatus,
                remainingWaypoints,
                latestBannerInstructionsWrapper?.latestBannerInstructions,
                latestBannerInstructionsWrapper?.latestInstructionIndex,
                lastVoiceInstruction
            ).also {
                if (it == null) {
                    logD(
                        "route progress update dropped - " +
                            "currentPrimaryRoute ID: ${primaryRoute?.id}; " +
                            "currentState: ${status.routeState}",
                        LOG_CATEGORY
                    )
                }
            }
            updateRouteProgress(routeProgress, triggerObserver)
            triggerVoiceInstructionEvent(routeProgress, status)
            isOffRoute = tripStatus.navigationStatus.routeState == RouteState.OFF_ROUTE
        }

        private fun calculateRemainingWaypoints(tripStatus: TripStatus): Int {
            val routeCoordinates = tripStatus.route?.routeOptions?.coordinatesList()
            return if (routeCoordinates != null) {
                val waypointsCount = routeCoordinates.size
                val nextWaypointIndex = normalizeNextWaypointIndex(
                    tripStatus.navigationStatus.nextWaypointIndex
                )
                return waypointsCount - nextWaypointIndex
            } else {
                0
            }
        }

        /**
         * On the Android side, we always start navigation from the current position.
         * So we expect that the next waypoint index will not be less than 1.
         * But the native part considers the origin as a usual waypoint.
         * It can return the next waypoint index 0. Be careful, this case isn't easy to reproduce.
         *
         * For example, nextWaypointIndex=0 leads to an incorrect rerouting.
         * We don't want to get to an initial position even it hasn't been reached yet.
         */
        private fun normalizeNextWaypointIndex(nextWaypointIndex: Int) = max(
            INDEX_OF_INITIAL_LEG_TARGET,
            nextWaypointIndex
        )
    }

    /**
     * Stop MapboxTripSession
     */
    override fun stop() {
        if (state == TripSessionState.STOPPED) {
            return
        }
        navigator.removeNavigatorObserver(navigatorObserver)
        tripService.stopService()
        tripSessionLocationEngine.stopLocationUpdates()
        mainJobController.job.cancelChildren()
        reset()
        state = TripSessionState.STOPPED
    }

    private fun reset() {
        updateLegIndexJob?.cancel()
        locationMatcherResult = null
        rawLocation = null
        zLevel = null
        routeProgress = null
        isOffRoute = false
        eHorizonSubscriptionManager.reset()
    }

    /**
     * Register [LocationObserver] to receive location updates
     */
    override fun registerLocationObserver(locationObserver: LocationObserver) {
        locationObservers.add(locationObserver)
        rawLocation?.let { locationObserver.onNewRawLocation(it) }
        locationMatcherResult?.let { locationObserver.onNewLocationMatcherResult(it) }
    }

    /**
     * Unregister [LocationObserver]
     */
    override fun unregisterLocationObserver(locationObserver: LocationObserver) {
        locationObservers.remove(locationObserver)
    }

    /**
     * Unregister all [LocationObserver]
     *
     * @see [registerLocationObserver]
     */
    override fun unregisterAllLocationObservers() {
        locationObservers.clear()
    }

    /**
     * Register [RouteProgressObserver] to receive information about routing's state
     * like [BannerInstructions], [RouteLegProgress], etc.
     *
     * @see [RouteProgress]
     */
    override fun registerRouteProgressObserver(routeProgressObserver: RouteProgressObserver) {
        routeProgressObservers.add(routeProgressObserver)
        routeProgress?.let { routeProgressObserver.onRouteProgressChanged(it) }
    }

    /**
     * Unregister [RouteProgressObserver]
     */
    override fun unregisterRouteProgressObserver(routeProgressObserver: RouteProgressObserver) {
        routeProgressObservers.remove(routeProgressObserver)
    }

    /**
     * Unregister all [RouteProgressObserver]
     *
     * @see [registerRouteProgressObserver]
     */
    override fun unregisterAllRouteProgressObservers() {
        routeProgressObservers.clear()
    }

    /**
     * Register [OffRouteObserver] to receive notification about off-route events
     */
    override fun registerOffRouteObserver(offRouteObserver: OffRouteObserver) {
        offRouteObservers.add(offRouteObserver)
        offRouteObserver.onOffRouteStateChanged(isOffRoute)
    }

    /**
     * Unregister [OffRouteObserver]
     */
    override fun unregisterOffRouteObserver(offRouteObserver: OffRouteObserver) {
        offRouteObservers.remove(offRouteObserver)
    }

    /**
     * Unregister all [OffRouteObserver]
     *
     * @see [registerOffRouteObserver]
     */
    override fun unregisterAllOffRouteObservers() {
        offRouteObservers.clear()
    }

    /**
     * Register [TripSessionStateObserver] to receive current TripSession's state
     *
     * @see [TripSessionState]
     */
    override fun registerStateObserver(stateObserver: TripSessionStateObserver) {
        stateObservers.add(stateObserver)
        stateObserver.onSessionStateChanged(state)
    }

    /**
     * Unregister [TripSessionStateObserver]
     */
    override fun unregisterStateObserver(stateObserver: TripSessionStateObserver) {
        stateObservers.remove(stateObserver)
    }

    /**
     * Unregister all [TripSessionStateObserver]
     *
     * @see [registerStateObserver]
     */
    override fun unregisterAllStateObservers() {
        stateObservers.clear()
    }

    /**
     * Register [BannerInstructionsObserver]
     */
    override fun registerBannerInstructionsObserver(
        bannerInstructionsObserver: BannerInstructionsObserver
    ) {
        bannerInstructionsObservers.add(bannerInstructionsObserver)
        checkLatestValidBannerInstructionEvent { bannerInstruction ->
            bannerInstructionsObserver.onNewBannerInstructions(bannerInstruction)
        }
    }

    /**
     * Unregister [BannerInstructionsObserver]
     */
    override fun unregisterBannerInstructionsObserver(
        bannerInstructionsObserver: BannerInstructionsObserver
    ) {
        bannerInstructionsObservers.remove(bannerInstructionsObserver)
    }

    /**
     * Unregister all [BannerInstructionsObserver]
     *
     * @see [registerBannerInstructionsObserver]
     */
    override fun unregisterAllBannerInstructionsObservers() {
        bannerInstructionsObservers.clear()
    }

    /**
     * Register [VoiceInstructionsObserver]
     */
    override fun registerVoiceInstructionsObserver(
        voiceInstructionsObserver: VoiceInstructionsObserver
    ) {
        voiceInstructionsObservers.add(voiceInstructionsObserver)
    }

    /**
     * Unregister [VoiceInstructionsObserver]
     */
    override fun unregisterVoiceInstructionsObserver(
        voiceInstructionsObserver: VoiceInstructionsObserver
    ) {
        voiceInstructionsObservers.remove(voiceInstructionsObserver)
    }

    /**
     * Unregister all [VoiceInstructionsObserver]
     *
     * @see [registerVoiceInstructionsObserver]
     */
    override fun unregisterAllVoiceInstructionsObservers() {
        voiceInstructionsObservers.clear()
    }

    /**
     * Follows a new leg of the already loaded directions.
     * Returns an initialized navigation status if no errors occurred
     * otherwise, it returns an invalid navigation status state.
     *
     * @param legIndex new leg index
     *
     * @return an initialized [NavigationStatus] if no errors, invalid otherwise
     */
    override fun updateLegIndex(legIndex: Int, callback: LegIndexUpdatedCallback) {
        var legIndexUpdated = false
        updateLegIndexJob = mainJobController.scope.launch {
            try {
                fun msg(state: String, append: String = ""): String =
                    "update to new leg $state. Leg index: $legIndex, route id: " +
                        "${primaryRoute?.id} + $append"

                logD(LOG_CATEGORY, msg("started"))
                val latestInstructionWrapper = bannerInstructionEvent.latestInstructionWrapper
                val lastVoiceInstruction = lastVoiceInstruction
                legIndexUpdated = navigator.updateLegIndex(legIndex)
                if (legIndexUpdated) {
                    invalidateLatestInstructions(latestInstructionWrapper, lastVoiceInstruction)
                }
                logD(
                    msg(
                        "finished",
                        "(is leg updated: $legIndexUpdated; " +
                            "latestInstructionWrapper: [$latestInstructionWrapper]; " +
                            "lastVoiceInstruction: [$lastVoiceInstruction])"
                    ),
                    LOG_CATEGORY,
                )
            } finally {
                callback.onLegIndexUpdatedCallback(legIndexUpdated)
            }
        }
    }

    override fun registerRoadObjectsOnRouteObserver(
        roadObjectsOnRouteObserver: RoadObjectsOnRouteObserver
    ) {
        roadObjectsOnRouteObservers.add(roadObjectsOnRouteObserver)
        roadObjectsOnRouteObserver.onNewRoadObjectsOnTheRoute(roadObjects)
    }

    override fun unregisterRoadObjectsOnRouteObserver(
        roadObjectsOnRouteObserver: RoadObjectsOnRouteObserver
    ) {
        roadObjectsOnRouteObservers.remove(roadObjectsOnRouteObserver)
    }

    override fun unregisterAllRoadObjectsOnRouteObservers() {
        roadObjectsOnRouteObservers.clear()
    }

    override fun registerEHorizonObserver(eHorizonObserver: EHorizonObserver) {
        eHorizonSubscriptionManager.registerObserver(eHorizonObserver)
    }

    override fun unregisterEHorizonObserver(eHorizonObserver: EHorizonObserver) {
        eHorizonSubscriptionManager.unregisterObserver(eHorizonObserver)
    }

    override fun unregisterAllEHorizonObservers() {
        eHorizonSubscriptionManager.unregisterAllObservers()
    }

    override fun registerFallbackVersionsObserver(
        fallbackVersionsObserver: FallbackVersionsObserver
    ) {
        if (fallbackVersionsObservers.isEmpty()) {
            navigator.setFallbackVersionsObserver(nativeFallbackVersionsObserver)
        }
        fallbackVersionsObservers.add(fallbackVersionsObserver)
    }

    override fun unregisterFallbackVersionsObserver(
        fallbackVersionsObserver: FallbackVersionsObserver
    ) {
        fallbackVersionsObservers.remove(fallbackVersionsObserver)
        if (fallbackVersionsObservers.isEmpty()) {
            navigator.setFallbackVersionsObserver(null)
        }
    }

    override fun unregisterAllFallbackVersionsObservers() {
        fallbackVersionsObservers.clear()
        navigator.setFallbackVersionsObserver(null)
    }

    private fun updateLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
        this.locationMatcherResult = locationMatcherResult
        locationObservers.forEach { it.onNewLocationMatcherResult(locationMatcherResult) }
    }

    private fun updateRouteProgress(
        progress: RouteProgress?,
        shouldTriggerBannerInstructionsObserver: Boolean
    ) {
        routeProgress = progress
        if (tripService.hasServiceStarted()) {
            tripService.updateNotification(buildTripNotificationState(progress))
        }
        progress?.let { progress ->
            logD(
                "dispatching progress update; state: ${progress.currentState}",
                LOG_CATEGORY
            )
            routeProgressObservers.forEach { it.onRouteProgressChanged(progress) }
            if (shouldTriggerBannerInstructionsObserver) {
                checkBannerInstructionEvent { bannerInstruction ->
                    bannerInstructionsObservers.forEach {
                        it.onNewBannerInstructions(bannerInstruction)
                    }
                }
            }
        }
    }

    private fun triggerVoiceInstructionEvent(progress: RouteProgress?, status: NavigationStatus) {
        val voiceInstructions = progress?.voiceInstructions
        val navigatorTriggeredNewInstruction = status.voiceInstruction != null
        if (voiceInstructions != null && navigatorTriggeredNewInstruction) {
            voiceInstructionsObservers.forEach {
                it.onNewVoiceInstructions(voiceInstructions)
            }
            lastVoiceInstruction = progress.voiceInstructions
        }
    }

    private fun checkLatestValidBannerInstructionEvent(
        action: (BannerInstructions) -> Unit
    ) {
        ifNonNull(bannerInstructionEvent.latestBannerInstructions) {
            action(it)
        }
    }

    /**
     * Invalidate latest banner and voice instruction. To get the latest banner instruction wrapper call
     * [BannerInstructionEvent.latestInstructionWrapper], to get the latest voice instruction
     * call [lastVoiceInstruction]
     */
    private fun invalidateLatestInstructions(
        latestInstructionWrapper: BannerInstructionEvent.LatestInstructionWrapper?,
        voiceInstruction: VoiceInstructions?,
    ) {
        bannerInstructionEvent.invalidateLatestBannerInstructions(latestInstructionWrapper)
        if (lastVoiceInstruction == voiceInstruction) {
            lastVoiceInstruction = null
        }
    }

    private fun checkBannerInstructionEvent(
        action: (BannerInstructions) -> Unit
    ) {
        ifNonNull(bannerInstructionEvent.bannerInstructions) { bannerInstructions ->
            action(bannerInstructions)
        }
    }

    private fun checkVoiceInstructionEvent(
        currentVoiceInstructions: VoiceInstructions?,
        action: (VoiceInstructions) -> Unit
    ) {
        ifNonNull(currentVoiceInstructions) { voiceInstructions ->
            action(voiceInstructions)
        }
    }
}
