package com.mapbox.navigation.core.reroute

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.internal.route.routerOrigin
import com.mapbox.navigation.base.internal.utils.mapToSdkRouteOrigin
import com.mapbox.navigation.base.options.RerouteOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.route.toDirectionsRoutes
import com.mapbox.navigation.core.directions.session.DirectionsSession
import com.mapbox.navigation.core.ev.EVDynamicDataHolder
import com.mapbox.navigation.core.routeoptions.RouteOptionsUpdater
import com.mapbox.navigation.core.trip.session.TripSession
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.ThreadController
import com.mapbox.navigation.utils.internal.ifNonNull
import com.mapbox.navigation.utils.internal.logD
import com.mapbox.navigation.utils.internal.logI
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume

/**
 * Default implementation of [RerouteController]
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal class MapboxRerouteController @VisibleForTesting constructor(
    private val directionsSession: DirectionsSession,
    private val tripSession: TripSession,
    private val routeOptionsUpdater: RouteOptionsUpdater,
    private val rerouteOptions: RerouteOptions,
    threadController: ThreadController,
    private val compositeRerouteOptionsAdapter: MapboxRerouteOptionsAdapter,
) : NavigationRerouteControllerV2 {

    private val observers = CopyOnWriteArraySet<RerouteController.RerouteStateObserver>()

    private val mainJobController: JobControl = threadController.getMainScopeAndRootJob()

    private var rerouteJob: Job? = null

    constructor(
        directionsSession: DirectionsSession,
        tripSession: TripSession,
        routeOptionsUpdater: RouteOptionsUpdater,
        rerouteOptions: RerouteOptions,
        threadController: ThreadController,
        evDynamicDataHolder: EVDynamicDataHolder,
    ) : this(
        directionsSession,
        tripSession,
        routeOptionsUpdater,
        rerouteOptions,
        threadController,
        MapboxRerouteOptionsAdapter(evDynamicDataHolder)
    )

    override var state: RerouteState = RerouteState.Idle
        private set(value) {
            if (field == value) {
                return
            }
            field = value
            observers.forEach { it.onRerouteStateChanged(field) }
        }

    private companion object {
        private const val LOG_CATEGORY = "MapboxRerouteController"

        /**
         * Max dangerous maneuvers radius meters. See [RouteOptions.avoidManeuverRadius]
         */
        private const val MAX_DANGEROUS_MANEUVERS_RADIUS = 1000.0

        /**
         * Apply reroute options. Speed must be provided as **m/s**
         */
        private fun RouteOptions?.applyRerouteOptions(
            rerouteOptions: RerouteOptions,
            speed: Float?
        ): RouteOptions? {
            if (this == null || speed == null) {
                return this
            }

            val builder = toBuilder()

            if (this.profile() == DirectionsCriteria.PROFILE_DRIVING ||
                this.profile() == DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
            ) {
                val avoidManeuverRadius = rerouteOptions.avoidManeuverSeconds
                    .let { speed * it }.toDouble()
                    .takeIf { it >= 1 }
                    ?.coerceAtMost(MAX_DANGEROUS_MANEUVERS_RADIUS)

                builder.avoidManeuverRadius(avoidManeuverRadius)
            }

            return builder.build()
        }
    }

    override fun reroute(routesCallback: RerouteController.RoutesCallback) {
        reroute { routes, _ ->
            routesCallback.onNewRoutes(routes.toDirectionsRoutes())
        }
    }

    override fun reroute(
        params: RerouteParameters,
        callback: NavigationRerouteController.RoutesCallback
    ) {
        interrupt()
        state = RerouteState.FetchingRoute
        logD("Fetching route", LOG_CATEGORY)

        ifNonNull(
            params.routes,
            params.detectedAlternative,
        ) { routes, relevantAlternative ->

            val newList = mutableListOf(relevantAlternative).apply {
                addAll(
                    routes.toMutableList().apply {
                        removeFirst()
                        remove(relevantAlternative)
                    }
                )
            }

            logD("Reroute switch to alternative", LOG_CATEGORY)

            val origin = relevantAlternative.routerOrigin.mapToSdkRouteOrigin()

            state = RerouteState.RouteFetched(origin)
            callback.onNewRoutes(newList, origin)
            state = RerouteState.Idle
            return
        }

        val routeOptions = directionsSession.getPrimaryRouteOptions()
            ?.applyRerouteOptions(
                rerouteOptions,
                tripSession.locationMatcherResult?.enhancedLocation?.speed
            )

        routeOptionsUpdater.update(
            routeOptions,
            tripSession.getRouteProgress(),
            tripSession.locationMatcherResult,
        )
            .let { routeOptionsResult ->
                when (routeOptionsResult) {
                    is RouteOptionsUpdater.RouteOptionsResult.Success -> {
                        val modifiedRerouteOption = compositeRerouteOptionsAdapter.onRouteOptions(
                            routeOptionsResult.routeOptions
                        )
                        request(callback, modifiedRerouteOption)
                    }
                    is RouteOptionsUpdater.RouteOptionsResult.Error -> {
                        state = RerouteState.Failed(
                            message = "Cannot combine route options",
                            throwable = routeOptionsResult.error
                        )
                        state = RerouteState.Idle
                    }
                }
            }
    }

    override fun reroute(callback: NavigationRerouteController.RoutesCallback) {
        val routes = directionsSession.routes
        val routeProgress = tripSession.getRouteProgress()
        reroute(
            RerouteParameters(
                detectedAlternative = routes.firstOrNull {
                    it.id == routeProgress?.routeAlternativeId
                },
                routes = routes
            ),
            callback
        )
    }

    @MainThread
    override fun interrupt() {
        rerouteJob?.cancel()
        rerouteJob = null
        if (state == RerouteState.FetchingRoute) {
            logI(LOG_CATEGORY) {
                "Request interrupted via controller"
            }
        }
        onRequestInterrupted()
    }

    override fun registerRerouteStateObserver(
        rerouteStateObserver: RerouteController.RerouteStateObserver
    ): Boolean {
        mainJobController.scope.launch {
            rerouteStateObserver.onRerouteStateChanged(state)
        }
        return observers.add(rerouteStateObserver)
    }

    override fun unregisterRerouteStateObserver(
        rerouteStateObserver: RerouteController.RerouteStateObserver
    ): Boolean {
        return observers.remove(rerouteStateObserver)
    }

    private fun request(
        callback: NavigationRerouteController.RoutesCallback,
        routeOptions: RouteOptions
    ) {
        rerouteJob = mainJobController.scope.launch {
            when (val result = requestAsync(routeOptions)) {
                is RouteRequestResult.Success -> {
                    state = RerouteState.RouteFetched(result.routerOrigin)
                    state = RerouteState.Idle
                    callback.onNewRoutes(result.routes, result.routerOrigin)
                }
                is RouteRequestResult.Failure -> {
                    state = RerouteState.Failed(
                        "Route request failed",
                        reasons = result.reasons
                    )
                    state = RerouteState.Idle
                }
                is RouteRequestResult.Cancellation -> {
                    if (state == RerouteState.FetchingRoute) {
                        logI("Request canceled via router")
                    }
                    onRequestInterrupted()
                }
            }
        }
    }

    internal fun setRerouteOptionsAdapter(rerouteOptionsAdapter: RerouteOptionsAdapter?) {
        compositeRerouteOptionsAdapter.externalOptionsAdapter = rerouteOptionsAdapter
    }

    private fun onRequestInterrupted() {
        if (state == RerouteState.FetchingRoute) {
            state = RerouteState.Interrupted
            state = RerouteState.Idle
        }
    }

    private suspend fun requestAsync(routeOptions: RouteOptions): RouteRequestResult {
        return suspendCancellableCoroutine { cont ->
            val requestId = directionsSession.requestRoutes(
                routeOptions,
                object : NavigationRouterCallback {
                    override fun onRoutesReady(
                        routes: List<NavigationRoute>,
                        routerOrigin: RouterOrigin
                    ) {
                        if (cont.isActive) {
                            cont.resume(RouteRequestResult.Success(routes, routerOrigin))
                        }
                    }

                    override fun onFailure(
                        reasons: List<RouterFailure>,
                        routeOptions: RouteOptions
                    ) {
                        if (cont.isActive) {
                            cont.resume(RouteRequestResult.Failure(reasons))
                        }
                    }

                    override fun onCanceled(
                        routeOptions: RouteOptions,
                        routerOrigin: RouterOrigin
                    ) {
                        if (cont.isActive) {
                            cont.resume(RouteRequestResult.Cancellation)
                        }
                    }
                }
            )
            cont.invokeOnCancellation {
                directionsSession.cancelRouteRequest(requestId)
            }
        }
    }
}

private sealed class RouteRequestResult {

    class Success(
        val routes: List<NavigationRoute>,
        val routerOrigin: RouterOrigin
    ) : RouteRequestResult()

    class Failure(val reasons: List<RouterFailure>) : RouteRequestResult()

    object Cancellation : RouteRequestResult()
}
