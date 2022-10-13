package com.mapbox.navigation.core

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.internal.CurrentIndicesFactory
import com.mapbox.navigation.base.options.IncidentsOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalController
import com.mapbox.navigation.core.arrival.ArrivalProgressObserver
import com.mapbox.navigation.core.directions.session.MapboxDirectionsSession
import com.mapbox.navigation.core.directions.session.RoutesExtra
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.directions.session.RoutesUpdatedResult
import com.mapbox.navigation.core.internal.HistoryRecordingStateChangeObserver
import com.mapbox.navigation.core.internal.extensions.registerHistoryRecordingStateChangeObserver
import com.mapbox.navigation.core.internal.extensions.unregisterHistoryRecordingStateChangeObserver
import com.mapbox.navigation.core.internal.telemetry.NavigationCustomEventType
import com.mapbox.navigation.core.navigator.CacheHandleWrapper
import com.mapbox.navigation.core.reroute.NavigationRerouteController
import com.mapbox.navigation.core.reroute.RerouteController
import com.mapbox.navigation.core.reroute.RerouteState
import com.mapbox.navigation.core.routerefresh.RefreshedRouteInfo
import com.mapbox.navigation.core.routerefresh.RouteRefreshStatesObserver
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.NativeSetRouteError
import com.mapbox.navigation.core.trip.session.NativeSetRouteValue
import com.mapbox.navigation.core.trip.session.NavigationSession
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RoadObjectsOnRouteObserver
import com.mapbox.navigation.core.trip.session.TripSessionState
import com.mapbox.navigation.core.trip.session.TripSessionStateObserver
import com.mapbox.navigation.core.trip.session.createSetRouteResult
import com.mapbox.navigation.metrics.internal.TelemetryUtilsDelegate
import com.mapbox.navigation.testing.factories.createDirectionsRoute
import com.mapbox.navigation.testing.factories.createNavigationRoute
import com.mapbox.navigation.testing.factories.createNavigationRoutes
import com.mapbox.navigation.testing.factories.createRouteOptions
import com.mapbox.navigator.FallbackVersionsObserver
import com.mapbox.navigator.NavigatorConfig
import com.mapbox.navigator.RouteAlternative
import com.mapbox.navigator.TilesConfig
import io.mockk.Ordering
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalPreviewMapboxNavigationAPI
@Config(shadows = [ShadowReachabilityFactory::class])
@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
internal class MapboxNavigationTest : MapboxNavigationBaseTest() {

    @Test
    fun sanity() {
        createMapboxNavigation()
        assertNotNull(mapboxNavigation)
    }

    @Test
    fun startSessionWithService() {
        createMapboxNavigation()
        every { directionsSession.initialLegIndex } returns 0
        every { tripSession.isRunningWithForegroundService() } returns true
        mapboxNavigation.startTripSession()

        assertTrue(mapboxNavigation.isRunningForegroundService())
    }

    @Test
    fun startSessionWithoutService() {
        createMapboxNavigation()
        every { directionsSession.initialLegIndex } returns 0
        every { tripSession.isRunningWithForegroundService() } returns false
        mapboxNavigation.startTripSession(false)

        assertFalse(mapboxNavigation.isRunningForegroundService())
    }

    @Test
    fun `trip session route is reset after trip session is restarted`() {
        createMapboxNavigation()
        val primary = mockk<NavigationRoute>()
        val routes = listOf(primary, mockk())
        val currentLegIndex = 3
        every { directionsSession.routes } returns routes
        every { directionsSession.initialLegIndex } returns 2
        every { tripSession.getRouteProgress() } returns mockk {
            every { currentLegProgress } returns mockk {
                every { legIndex } returns currentLegIndex
            }
        }
        mapboxNavigation.stopTripSession()
        mapboxNavigation.startTripSession()

        coVerify(exactly = 1) {
            tripSession.setRoutes(
                routes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, currentLegIndex)
            )
        }
    }

    @Test
    fun `getZLevel returns current z level`() {
        createMapboxNavigation()
        every { tripSession.zLevel } returns 3
        assertEquals(3, mapboxNavigation.getZLevel())
    }

    @Test
    fun init_routesObs_internalRouteObs_navigationSession_and_TelemetryLocAndProgressDispatcher() {
        createMapboxNavigation()
        verify(exactly = 2) { directionsSession.registerRoutesObserver(any()) }
    }

    @Test
    fun init_registerOffRouteObserver() {
        createMapboxNavigation()
        verify(exactly = 1) { tripSession.registerOffRouteObserver(any()) }
    }

    @Test
    fun destroy_unregisterOffRouteObserver() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllOffRouteObservers() }
    }

    @Test
    fun init_registerOffRouteObserver_MapboxNavigation_recreated() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()
        threadController.cancelAllUICoroutines()
        val navigationOptions = provideNavigationOptions().build()

        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)

        verify(exactly = 2) { tripSession.registerOffRouteObserver(any()) }
    }

    @Test
    fun destroy_unregisterAllOffRouteObservers_MapboxNavigation_recreated() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()
        threadController.cancelAllUICoroutines()
        val navigationOptions = provideNavigationOptions().build()
        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)

        mapboxNavigation.onDestroy()

        verify(exactly = 2) { tripSession.unregisterAllOffRouteObservers() }
    }

    @Test
    fun registerLocationObserver() {
        createMapboxNavigation()
        val observer: LocationObserver = mockk()
        mapboxNavigation.registerLocationObserver(observer)

        verify(exactly = 1) { tripSession.registerLocationObserver(observer) }
    }

    @Test
    fun unregisterLocationObserver() {
        createMapboxNavigation()
        val observer: LocationObserver = mockk()
        mapboxNavigation.unregisterLocationObserver(observer)

        verify(exactly = 1) { tripSession.unregisterLocationObserver(observer) }
    }

    @Test
    fun init_registerStateObserver_navigationSession() {
        createMapboxNavigation()
        val arguments = mutableListOf<TripSessionStateObserver>()
        verify(exactly = 2) { tripSession.registerStateObserver(capture(arguments)) }
        assertEquals(listOf(navigationSession, historyRecordingStateHandler), arguments)
    }

    @Test
    fun onDestroy_unregisters_DirectionSession_observers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { directionsSession.unregisterAllRoutesObservers() }
    }

    @Test
    fun onDestroy_unregisters_TripSession_location_observers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllLocationObservers() }
    }

    @Test
    fun onDestroy_unregisters_TripSession_routeProgress_observers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllRouteProgressObservers() }
    }

    @Test
    fun onDestroy_unregisters_TripSession_offRoute_observers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllOffRouteObservers() }
    }

    @Test
    fun onDestroy_unregisters_TripSession_state_observers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllStateObservers() }
    }

    @Test
    fun onDestroy_unregisters_TripSession_routeAlerts_observers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllRoadObjectsOnRouteObservers() }
    }

    @Test
    fun onDestroy_unregisters_HistoryRecordingStateHandler_observers() {
        createMapboxNavigation()

        mapboxNavigation.onDestroy()

        verify(exactly = 1) { historyRecordingStateHandler.unregisterAllStateChangeObservers() }
    }

    @Test
    fun onDestroy_unregisters_DeveloperMetadataAggregator_observers() {
        createMapboxNavigation()

        mapboxNavigation.onDestroy()

        verify(exactly = 1) { developerMetadataAggregator.unregisterAllObservers() }
    }

    @Test
    fun onDestroySetsRoutesToEmpty() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) {
            directionsSession.setRoutes(
                emptyList(),
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP, 0)
            )
        }
    }

    @Test
    fun onDestroyDoesNotSetRoutesToEmptyIfEmptyIsInvalid() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteError("some error")
        mapboxNavigation.onDestroy()

        verify(exactly = 0) {
            directionsSession.setRoutes(any(), any())
        }
    }

    @Test
    fun onDestroyCallsTripSessionStop() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.stop() }
    }

    @Test
    fun onDestroyCallsNativeNavigatorReset() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { navigator.resetRideSession() }
    }

    @Test
    fun unregisterAllBannerInstructionsObservers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllBannerInstructionsObservers() }
    }

    @Test
    fun unregisterAllVoiceInstructionsObservers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllVoiceInstructionsObservers() }
    }

    @Test
    fun unregisterAllNavigationSessionStateObservers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { navigationSession.unregisterAllNavigationSessionStateObservers() }
    }

    @Test
    fun telemetryIsDisabled() {
        every { TelemetryUtilsDelegate.getEventsCollectionState() } returns false

        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 0) {
            MapboxNavigationTelemetry.initialize(any(), any(), any(), any())
        }
        verify(exactly = 0) { MapboxNavigationTelemetry.destroy(any()) }
    }

    @ExperimentalPreviewMapboxNavigationAPI
    @Test(expected = IllegalStateException::class)
    fun telemetryIsDisabledTryToGetFeedbackMetadataWrapper() {
        every { TelemetryUtilsDelegate.getEventsCollectionState() } returns false

        createMapboxNavigation()
        mapboxNavigation.provideFeedbackMetadataWrapper()
    }

    @ExperimentalPreviewMapboxNavigationAPI
    fun telemetryIsDisabledTryToPostFeedback() {
        every { TelemetryUtilsDelegate.getEventsCollectionState() } returns false

        createMapboxNavigation()

        mapboxNavigation.postUserFeedback(mockk(), mockk(), mockk(), mockk(), mockk())
        mapboxNavigation.postUserFeedback(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

        verify(exactly = 0) {
            MapboxNavigationTelemetry.postUserFeedback(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun unregisterAllTelemetryObservers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { MapboxNavigationTelemetry.destroy(eq(mapboxNavigation)) }
    }

    @Test
    fun unregisterAllTelemetryObserversIsCalledAfterTripSessionStop() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verifyOrder {
            tripSession.stop()
            MapboxNavigationTelemetry.destroy(mapboxNavigation)
        }
    }

    @Test
    fun unregisterAllArrivalObservers() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { arrivalProgressObserver.unregisterAllObservers() }
    }

    @Test
    fun current_route_geometry_index_provider() {
        createMapboxNavigation()
        verify(exactly = 1) {
            tripSession.registerRouteProgressObserver(currentIndicesProvider)
        }
    }

    @Test
    fun arrival_controller_register() {
        createMapboxNavigation()
        clearMocks(tripSession, answers = false)
        val arrivalController: ArrivalController = mockk()

        mapboxNavigation.setArrivalController(arrivalController)

        verify(exactly = 1) {
            tripSession.registerRouteProgressObserver(ofType(ArrivalProgressObserver::class))
        }
    }

    @Test
    fun arrival_controller_unregister() {
        createMapboxNavigation()
        val arrivalController: ArrivalController? = null

        mapboxNavigation.setArrivalController(arrivalController)

        verify { tripSession.unregisterRouteProgressObserver(any<ArrivalProgressObserver>()) }
    }

    @Test
    fun offroute_lead_to_reroute() {
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(rerouteController)
        val observers = mutableListOf<OffRouteObserver>()
        verify { tripSession.registerOffRouteObserver(capture(observers)) }

        observers.forEach {
            it.onOffRouteStateChanged(true)
        }

        verify(exactly = 1) { rerouteController.reroute(any()) }
        verify(ordering = Ordering.ORDERED) {
            tripSession.registerOffRouteObserver(any())
            rerouteController.reroute(any())
        }
    }

    @Test
    fun `new routes are set after reroute`() {
        val newRoutes = listOf(mockk<NavigationRoute>(relaxed = true), mockk(relaxed = true))
        val navigationRerouteController: NavigationRerouteController = mockk(relaxed = true) {
            every { reroute(any<NavigationRerouteController.RoutesCallback>()) } answers {
                (firstArg() as NavigationRerouteController.RoutesCallback)
                    .onNewRoutes(newRoutes, mockk(relaxed = true))
            }
        }
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteValue(newRoutes, emptyList())

        createMapboxNavigation()
        mapboxNavigation.setRerouteController(navigationRerouteController)
        val observers = mutableListOf<OffRouteObserver>()
        verify { tripSession.registerOffRouteObserver(capture(observers)) }

        observers.forEach {
            it.onOffRouteStateChanged(true)
        }
        coVerify(exactly = 1) {
            directionsSession.setRoutes(
                newRoutes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_REROUTE, 0)
            )
        }
    }

    @Test
    fun `new routes are not set after reroute if they are invalid`() {
        val newRoutes = listOf(mockk<NavigationRoute>(relaxed = true), mockk(relaxed = true))
        val navigationRerouteController: NavigationRerouteController = mockk(relaxed = true) {
            every { reroute(any<NavigationRerouteController.RoutesCallback>()) } answers {
                (firstArg() as NavigationRerouteController.RoutesCallback)
                    .onNewRoutes(newRoutes, mockk(relaxed = true))
            }
        }
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteError("some error")

        createMapboxNavigation()
        mapboxNavigation.setRerouteController(navigationRerouteController)
        val observers = mutableListOf<OffRouteObserver>()
        verify { tripSession.registerOffRouteObserver(capture(observers)) }

        observers.forEach {
            it.onOffRouteStateChanged(true)
        }
        coVerify(exactly = 0) {
            directionsSession.setRoutes(any(), any())
        }
    }

    @Test
    fun `set reroute controller in fetching state sets routes to session`() {
        val newRoutes = listOf(mockk<NavigationRoute>(relaxed = true), mockk(relaxed = true))
        val oldController = mockk<RerouteController>(relaxed = true) {
            every { state } returns RerouteState.FetchingRoute
        }
        val navigationRerouteController: NavigationRerouteController = mockk(relaxed = true) {
            every { reroute(any<NavigationRerouteController.RoutesCallback>()) } answers {
                (firstArg() as NavigationRerouteController.RoutesCallback)
                    .onNewRoutes(newRoutes, mockk(relaxed = true))
            }
        }
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteValue(newRoutes, emptyList())

        createMapboxNavigation()
        mapboxNavigation.setRerouteController(oldController)
        mapboxNavigation.setRerouteController(navigationRerouteController)
        coVerify(exactly = 1) {
            directionsSession.setRoutes(
                newRoutes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_REROUTE, 0)
            )
        }
    }

    @Test
    fun `set reroute controller in fetching state does not set invalid routes to session`() {
        val newRoutes = listOf(mockk<NavigationRoute>(relaxed = true), mockk(relaxed = true))
        val oldController = mockk<RerouteController>(relaxed = true) {
            every { state } returns RerouteState.FetchingRoute
        }
        val navigationRerouteController: NavigationRerouteController = mockk(relaxed = true) {
            every { reroute(any<NavigationRerouteController.RoutesCallback>()) } answers {
                (firstArg() as NavigationRerouteController.RoutesCallback)
                    .onNewRoutes(newRoutes, mockk(relaxed = true))
            }
        }
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteError("some error")

        createMapboxNavigation()
        mapboxNavigation.setRerouteController(oldController)
        mapboxNavigation.setRerouteController(navigationRerouteController)
        coVerify(exactly = 0) {
            directionsSession.setRoutes(any(), any())
        }
    }

    @Test
    fun `set legacy reroute controller in fetching state sets routes to session`() {
        val newRoutes = emptyList<DirectionsRoute>()
        val oldController = mockk<RerouteController>(relaxed = true) {
            every { state } returns RerouteState.FetchingRoute
        }
        val rerouteController: RerouteController = mockk(relaxed = true) {
            every { reroute(any()) } answers {
                (firstArg() as RerouteController.RoutesCallback).onNewRoutes(newRoutes)
            }
        }
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteValue(emptyList(), emptyList())

        createMapboxNavigation()
        mapboxNavigation.setRerouteController(oldController)
        mapboxNavigation.setRerouteController(rerouteController)
        coVerify(exactly = 1) {
            directionsSession.setRoutes(
                any(),
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_REROUTE, 0)
            )
        }
    }

    @Test
    fun `set legacy reroute controller in fetching state does not set invalid routes to session`() {
        val newRoutes = emptyList<DirectionsRoute>()
        val oldController = mockk<RerouteController>(relaxed = true) {
            every { state } returns RerouteState.FetchingRoute
        }
        val rerouteController: RerouteController = mockk(relaxed = true) {
            every { reroute(any()) } answers {
                (firstArg() as RerouteController.RoutesCallback).onNewRoutes(newRoutes)
            }
        }
        coEvery {
            tripSession.setRoutes(any(), any())
        } returns NativeSetRouteError("some error")

        createMapboxNavigation()
        mapboxNavigation.setRerouteController(oldController)
        mapboxNavigation.setRerouteController(rerouteController)
        coVerify(exactly = 0) {
            directionsSession.setRoutes(any(), any())
        }
    }

    @Test
    fun reRoute_not_called() {
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(rerouteController)
        val offRouteObserverSlot = slot<OffRouteObserver>()
        verify { tripSession.registerOffRouteObserver(capture(offRouteObserverSlot)) }

        offRouteObserverSlot.captured.onOffRouteStateChanged(false)

        verify(exactly = 0) { rerouteController.reroute(any()) }
    }

    @Test
    fun internalRouteObserver_notEmpty() {
        createMapboxNavigation()
        val primary: NavigationRoute = mockk {
            every { directionsRoute } returns mockk()
        }
        val secondary: NavigationRoute = mockk {
            every { directionsRoute } returns mockk()
        }
        val routes = listOf(primary, secondary)
        val reason = RoutesExtra.ROUTES_UPDATE_REASON_NEW
        val routeObserversSlot = mutableListOf<RoutesObserver>()
        every { tripSession.getState() } returns TripSessionState.STARTED
        verify { directionsSession.registerRoutesObserver(capture(routeObserversSlot)) }

        routeObserversSlot.forEach {
            it.onRoutesChanged(RoutesUpdatedResult(routes, reason))
        }

        coVerifyOrder {
            currentIndicesProvider.clear()
            routeRefreshController.refresh(routes)
        }
    }

    @Test
    fun internalRouteObserver_empty() {
        createMapboxNavigation()
        val routes = emptyList<NavigationRoute>()
        val reason = RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP
        val routeObserversSlot = mutableListOf<RoutesObserver>()
        every { tripSession.getState() } returns TripSessionState.STARTED
        verify { directionsSession.registerRoutesObserver(capture(routeObserversSlot)) }

        routeObserversSlot.forEach {
            it.onRoutesChanged(RoutesUpdatedResult(routes, reason))
        }

        coVerify(exactly = 1) {
            currentIndicesProvider.clear()
        }
        coVerify(exactly = 0) { routeRefreshController.refresh(any()) }
    }

    @Test
    fun internalRouteObserver_preview() {
        createMapboxNavigation()
        val routes = createNavigationRoutes()
        val reason = RoutesExtra.ROUTES_UPDATE_REASON_PREVIEW
        val routeObserversSlot = mutableListOf<RoutesObserver>()
        every { tripSession.getState() } returns TripSessionState.STARTED
        verify { directionsSession.registerRoutesObserver(capture(routeObserversSlot)) }

        routeObserversSlot.forEach {
            it.onRoutesChanged(RoutesUpdatedResult(routes, reason))
        }

        coVerify(exactly = 1) {
            currentIndicesProvider.clear()
        }
        coVerify(exactly = 0) { routeRefreshController.refresh(any()) }
    }

    @Test
    fun `don't interrupt reroute requests on a standalone route request`() {
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(rerouteController)
        every { directionsSession.requestRoutes(any(), any()) } returns 1L
        mapboxNavigation.requestRoutes(mockk(), mockk<NavigationRouterCallback>())

        verify(exactly = 0) { rerouteController.interrupt() }
    }

    @Test
    fun interrupt_reroute_process_when_new_reroute_controller_has_been_set() {
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(rerouteController)
        val newRerouteController: RerouteController = mockk(relaxUnitFun = true) {
            every { state } returns RerouteState.Idle
        }
        val observers = mutableListOf<OffRouteObserver>()
        verify { tripSession.registerOffRouteObserver(capture(observers)) }
        every { rerouteController.state } returns RerouteState.FetchingRoute

        observers.forEach {
            it.onOffRouteStateChanged(true)
        }
        mapboxNavigation.setRerouteController(newRerouteController)

        verify(exactly = 1) { rerouteController.reroute(any()) }
        verify(exactly = 1) { rerouteController.interrupt() }
        verify(exactly = 1) { newRerouteController.reroute(any()) }
        verifyOrder {
            rerouteController.reroute(any())
            rerouteController.interrupt()
            newRerouteController.reroute(any())
        }
    }

    @Test
    fun `road objects observer is registered in the trip session`() {
        createMapboxNavigation()
        val observer: RoadObjectsOnRouteObserver = mockk()

        mapboxNavigation.registerRoadObjectsOnRouteObserver(observer)

        verify(exactly = 1) { tripSession.registerRoadObjectsOnRouteObserver(observer) }
    }

    @Test
    fun `road objects observer is unregistered in the trip session`() {
        createMapboxNavigation()
        val observer: RoadObjectsOnRouteObserver = mockk()

        mapboxNavigation.unregisterRoadObjectsOnRouteObserver(observer)

        verify(exactly = 1) { tripSession.unregisterRoadObjectsOnRouteObserver(observer) }
    }

    @Test
    fun `resetTripSession should reset the navigator`() {
        createMapboxNavigation()
        mapboxNavigation.resetTripSession()

        verify { navigator.resetRideSession() }
    }

    @Test
    fun `verify tile config path`() {
        threadController.cancelAllUICoroutines()
        val slot = slot<TilesConfig>()
        every {
            NavigationComponentProvider.createNativeNavigator(
                any(),
                any(),
                capture(slot),
                any(),
                any(),
                any()
            )
        } returns navigator
        val options = navigationOptions.toBuilder()
            .routingTilesOptions(RoutingTilesOptions.Builder().build())
            .build()

        mapboxNavigation = MapboxNavigation(options, threadController)

        assertTrue(slot.captured.tilesPath.endsWith(RoutingTilesFiles.TILES_PATH_SUB_DIR))
    }

    @Test
    fun `verify tile config dataset`() {
        threadController.cancelAllUICoroutines()
        val slot = slot<TilesConfig>()
        every {
            NavigationComponentProvider.createNativeNavigator(
                any(),
                any(),
                capture(slot),
                any(),
                any(),
                any()
            )
        } returns navigator
        val options = navigationOptions.toBuilder()
            .routingTilesOptions(
                RoutingTilesOptions.Builder()
                    .tilesDataset("someUser.osm")
                    .tilesProfile("truck")
                    .build()
            )
            .build()

        mapboxNavigation = MapboxNavigation(options, threadController)

        assertEquals(slot.captured.endpointConfig!!.dataset, "someUser.osm/truck")
    }

    @Test
    fun `verify incidents options null when no params set`() {
        threadController.cancelAllUICoroutines()
        val slot = slot<NavigatorConfig>()
        every {
            NavigationComponentProvider.createNativeNavigator(
                any(),
                capture(slot),
                any(),
                any(),
                any(),
                any()
            )
        } returns navigator

        mapboxNavigation = MapboxNavigation(navigationOptions)

        assertNull(slot.captured.incidentsOptions)
    }

    @Test
    fun `verify incidents options non-null when graph set`() {
        threadController.cancelAllUICoroutines()
        val slot = slot<NavigatorConfig>()
        every {
            NavigationComponentProvider.createNativeNavigator(
                any(),
                capture(slot),
                any(),
                any(),
                any(),
                any()
            )
        } returns navigator
        val options = navigationOptions.toBuilder()
            .incidentsOptions(
                IncidentsOptions.Builder()
                    .graph("graph")
                    .build()
            )
            .build()

        mapboxNavigation = MapboxNavigation(options, threadController)

        assertEquals(slot.captured.incidentsOptions!!.graph, "graph")
        assertEquals(slot.captured.incidentsOptions!!.apiUrl, "")
    }

    @Test
    fun `verify incidents options non-null when apiUrl set`() {
        threadController.cancelAllUICoroutines()
        val slot = slot<NavigatorConfig>()
        every {
            NavigationComponentProvider.createNativeNavigator(
                any(),
                capture(slot),
                any(),
                any(),
                any(),
                any()
            )
        } returns navigator
        val options = navigationOptions.toBuilder()
            .incidentsOptions(
                IncidentsOptions.Builder()
                    .apiUrl("apiUrl")
                    .build()
            )
            .build()

        mapboxNavigation = MapboxNavigation(options, threadController)

        assertEquals(slot.captured.incidentsOptions!!.apiUrl, "apiUrl")
        assertEquals(slot.captured.incidentsOptions!!.graph, "")
    }

    @Test
    fun `setRoute pushes the route to the directions session`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        val route: NavigationRoute = mockk()
        val processedRoute: NavigationRoute = mockk()
        val routeOptions = createRouteOptions()
        every { route.routeOptions } returns routeOptions
        every { route.directionsRoute.geometry() } returns "geometry"
        every { route.directionsRoute.legs() } returns emptyList()

        val routes = listOf(route)
        val processedRoutes = listOf(processedRoute)
        val initialLegIndex = 2

        coEvery {
            tripSession.setRoutes(
                routes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, initialLegIndex)
            )
        } returns NativeSetRouteValue(processedRoutes, emptyList())
        mapboxNavigation.setNavigationRoutes(routes, initialLegIndex)

        verify(exactly = 1) {
            directionsSession.setRoutes(
                processedRoutes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, initialLegIndex)
            )
        }
    }

    @Test
    fun `setRoute does not push the invalid route to the directions session`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()
            val route: NavigationRoute = mockk(relaxed = true)
            val routeOptions = createRouteOptions()
            every { route.routeOptions } returns routeOptions
            every { route.directionsRoute.geometry() } returns "geometry"
            every { route.directionsRoute.legs() } returns emptyList()

            val routes = listOf(route)
            val initialLegIndex = 2

            coEvery {
                tripSession.setRoutes(
                    routes,
                    BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, initialLegIndex)
                )
            } returns NativeSetRouteError("some error")
            mapboxNavigation.setNavigationRoutes(routes, initialLegIndex)

            verify(exactly = 0) {
                directionsSession.setRoutes(any(), any())
            }
        }

    @Test
    fun `deprecated setRoutes pushes the route to the directions session`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()

            val route: DirectionsRoute = createDirectionsRoute(requestUuid = "test1")
            val processedRoute: NavigationRoute = mockk()
            val routes = listOf(route)
            val processedRoutes = listOf(processedRoute)
            val initialLegIndex = 2

            coEvery {
                tripSession.setRoutes(any(), any())
            } returns NativeSetRouteValue(processedRoutes, listOf(mockk()))
            mapboxNavigation.setRoutes(routes, initialLegIndex)

            verify(exactly = 1) {
                directionsSession.setRoutes(
                    processedRoutes,
                    match { it.legIndex == initialLegIndex }
                )
            }
        }

    @Test
    fun `deprecated setRoutes does not push the invalid route to the directions session`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()

            val routes = emptyList<DirectionsRoute>()
            val initialLegIndex = 2

            coEvery {
                tripSession.setRoutes(any(), any())
            } returns NativeSetRouteError("some error")
            mapboxNavigation.setRoutes(routes, initialLegIndex)

            verify(exactly = 0) {
                directionsSession.setRoutes(any(), any())
            }
        }

    @Test
    fun `requestRoutes pushes the request to the directions session`() {
        createMapboxNavigation()
        val options = mockk<RouteOptions>()
        val callback = mockk<NavigationRouterCallback>()
        every { directionsSession.requestRoutes(options, callback) } returns 1L

        mapboxNavigation.requestRoutes(options, callback)
        verify(exactly = 1) { directionsSession.requestRoutes(options, callback) }
    }

    @Test
    fun `requestRoutes passes back the request id`() {
        createMapboxNavigation()
        val expected = 1L
        val options = mockk<RouteOptions>()
        val callback = mockk<NavigationRouterCallback>()
        every { directionsSession.requestRoutes(options, callback) } returns expected

        val actual = mapboxNavigation.requestRoutes(options, callback)

        assertEquals(expected, actual)
    }

    @Test
    fun `requestRoutes doesn't pushes the route to the directions session automatically`() {
        createMapboxNavigation()
        val routes = listOf(mockk<NavigationRoute>())
        val options = mockk<RouteOptions>()
        val possibleInternalCallbackSlot = slot<NavigationRouterCallback>()
        val origin = mockk<RouterOrigin>()
        every { directionsSession.requestRoutes(options, any()) } returns 1L

        mapboxNavigation.requestRoutes(
            options,
            mockk<NavigationRouterCallback>(relaxUnitFun = true)
        )
        verify { directionsSession.requestRoutes(options, capture(possibleInternalCallbackSlot)) }
        possibleInternalCallbackSlot.captured.onRoutesReady(routes, origin)

        verify(exactly = 0) {
            directionsSession.setRoutes(routes, any())
        }
    }

    @Test
    fun `cancelRouteRequest pushes the data to directions session`() {
        createMapboxNavigation()
        mapboxNavigation.cancelRouteRequest(1L)

        verify(exactly = 1) { directionsSession.cancelRouteRequest(1L) }
    }

    @Test
    fun `directions session is shutdown onDestroy`() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { directionsSession.shutdown() }
    }

    @Test
    fun `register internalFallbackVersionsObserver`() {
        createMapboxNavigation()
        verify(exactly = 1) { tripSession.registerFallbackVersionsObserver(any()) }
    }

    @Test
    fun `unregisterAllFallbackVersionsObservers on destroy`() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()

        verify(exactly = 1) { tripSession.unregisterAllFallbackVersionsObservers() }
    }

    @Test
    fun `verify tile config tilesVersion and isFallback on init`() {
        threadController.cancelAllUICoroutines()
        val slot = slot<TilesConfig>()
        every {
            NavigationComponentProvider.createNativeNavigator(
                any(),
                any(),
                capture(slot),
                any(),
                any(),
                any()
            )
        } returns navigator
        val tilesVersion = "tilesVersion"
        val options = navigationOptions.toBuilder()
            .routingTilesOptions(
                RoutingTilesOptions.Builder()
                    .tilesVersion(tilesVersion)
                    .build()
            )
            .build()

        mapboxNavigation = MapboxNavigation(options, threadController)

        assertEquals(tilesVersion, slot.captured.endpointConfig?.version)
        assertFalse(slot.captured.endpointConfig?.isFallback!!)
    }

    @Test
    fun `verify tile config tilesVersion and isFallback on fallback`() {
        threadController.cancelAllUICoroutines()

        val fallbackObserverSlot = slot<FallbackVersionsObserver>()
        every {
            tripSession.registerFallbackVersionsObserver(capture(fallbackObserverSlot))
        } just Runs
        every { directionsSession.routes } returns emptyList()
        every { tripSession.getRouteProgress() } returns mockk()

        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)

        val tileConfigSlot = slot<TilesConfig>()
        every {
            navigator.recreate(
                any(),
                any(),
                capture(tileConfigSlot),
                any(),
                any(),
                any(),
            )
        } just Runs

        val tilesVersion = "tilesVersion"
        val latestTilesVersion = "latestTilesVersion"
        fallbackObserverSlot.captured.onFallbackVersionsFound(
            listOf(tilesVersion, latestTilesVersion)
        )

        assertEquals(latestTilesVersion, tileConfigSlot.captured.endpointConfig?.version)
        assertTrue(tileConfigSlot.captured.endpointConfig?.isFallback!!)
    }

    @Test
    fun `verify tile config tilesVersion and isFallback on return to latest tiles version`() {
        threadController.cancelAllUICoroutines()

        val fallbackObserverSlot = slot<FallbackVersionsObserver>()
        every {
            tripSession.registerFallbackVersionsObserver(capture(fallbackObserverSlot))
        } just Runs
        every { directionsSession.routes } returns emptyList()
        every { tripSession.getRouteProgress() } returns mockk()

        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)

        val tileConfigSlot = slot<TilesConfig>()
        every {
            navigator.recreate(
                any(),
                any(),
                capture(tileConfigSlot),
                any(),
                any(),
                any(),
            )
        } just Runs

        fallbackObserverSlot.captured.onCanReturnToLatest("")

        assertEquals("", tileConfigSlot.captured.endpointConfig?.version)
        assertFalse(tileConfigSlot.captured.endpointConfig?.isFallback!!)
    }

    @Test
    fun `verify route and routeProgress are set after navigator recreation`() = runBlocking {
        threadController.cancelAllUICoroutines()

        val fallbackObserverSlot = slot<FallbackVersionsObserver>()
        every {
            tripSession.registerFallbackVersionsObserver(capture(fallbackObserverSlot))
        } just Runs
        val primaryRoute: NavigationRoute = mockk()
        val alternativeRoute: NavigationRoute = mockk()
        val routes: List<NavigationRoute> = listOf(primaryRoute, alternativeRoute)
        val routeProgress: RouteProgress = mockk()
        val legProgress: RouteLegProgress = mockk()
        val index = 137
        every { directionsSession.routes } returns routes
        every { tripSession.getRouteProgress() } returns routeProgress
        every { routeProgress.currentLegProgress } returns legProgress
        every { legProgress.legIndex } returns index
        coEvery { navigator.setRoutes(any(), any(), any()) } answers {
            createSetRouteResult()
        }

        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)

        fallbackObserverSlot.captured.onFallbackVersionsFound(listOf("version"))

        coVerify {
            navigator.setRoutes(primaryRoute, index, listOf(alternativeRoute))
        }
    }

    @Test
    fun `verify that session state callbacks are always delivered to NavigationSession`() =
        runBlocking {
            mapboxNavigation = MapboxNavigation(navigationOptions, threadController)
            every { directionsSession.initialLegIndex } returns 0
            mapboxNavigation.startTripSession()
            mapboxNavigation.onDestroy()

            verifyOrder {
                tripSession.registerStateObserver(navigationSession)
                tripSession.start(
                    withTripService = true,
                    withReplayEnabled = false
                )
                tripSession.stop()
                tripSession.unregisterAllStateObservers()
            }
        }

    @Test(expected = IllegalStateException::class)
    fun `verify that only one instance of MapboxNavigation can be alive`() = runBlocking {
        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)
        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)
    }

    @Test
    fun `verify that MapboxNavigation instance can be recreated`() = runBlocking {
        val firstInstance = MapboxNavigation(navigationOptions)
        firstInstance.onDestroy()
        val secondInstance = MapboxNavigation(navigationOptions)

        assertNotNull(secondInstance)
        assertTrue(firstInstance.isDestroyed)

        secondInstance.onDestroy()
    }

    @Test(expected = IllegalStateException::class)
    fun `verify that the old instance is not accessible when a new one is created`() = runBlocking {
        val firstInstance = MapboxNavigation(navigationOptions)
        firstInstance.onDestroy()
        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)
        firstInstance.startTripSession()
    }

    @Test(expected = IllegalStateException::class)
    fun `verify that startTripSession is not called when destroyed`() = runBlocking {
        val localNavigationSession = NavigationSession()
        every { NavigationComponentProvider.createNavigationSession() } answers {
            localNavigationSession
        }

        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)
        mapboxNavigation.onDestroy()
        mapboxNavigation.startTripSession()
    }

    @Test(expected = IllegalStateException::class)
    fun `verify that stopTripSession is not called when destroyed`() = runBlocking {
        mapboxNavigation = MapboxNavigation(navigationOptions, threadController)
        mapboxNavigation.onDestroy()
        mapboxNavigation.stopTripSession()
    }

    @Test
    fun `verify that empty routes are not passed to the billing controller`() {
        createMapboxNavigation()
        mapboxNavigation.setNavigationRoutes(emptyList())

        verify(exactly = 0) { billingController.onExternalRouteSet(any()) }
    }

    @Test
    fun `external route is first provided to the billing controller before directions session`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()
            val routes = listOf(mockk<NavigationRoute>())

            mapboxNavigation.setNavigationRoutes(routes)

            verifyOrder {
                billingController.onExternalRouteSet(routes.first())
                directionsSession.setRoutes(
                    routes,
                    BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
                )
            }
        }

    @Test
    fun `adding or removing alternative routes creates alternative reason`() {
        createMapboxNavigation()
        val primaryRoute = createNavigationRoute()
        val alternativeRoute = createNavigationRoute()

        every { directionsSession.routes } returns listOf(primaryRoute)

        mapboxNavigation.setNavigationRoutes(listOf(primaryRoute, alternativeRoute))
        mapboxNavigation.setNavigationRoutes(listOf(primaryRoute))

        verifyOrder {
            directionsSession.setRoutes(any(), ofType(SetAlternativeRoutesInfo::class))
            directionsSession.setRoutes(any(), ofType(SetAlternativeRoutesInfo::class))
        }
    }

    @Test
    fun `verify that billing controller is notified of instance destruction`() {
        createMapboxNavigation()
        mapboxNavigation.onDestroy()
        verify(exactly = 1) {
            billingController.onDestroy()
        }
    }

    @Test
    fun `provider - check if the instance was destroyed outside of the providers scope`() {
        val instance = MapboxNavigationProvider.create(navigationOptions)

        instance.onDestroy()

        assertFalse(MapboxNavigationProvider.isCreated())
    }

    @Test
    fun `set routes are processed in the correct order`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()

        val longRoutes = listOf<NavigationRoute>(mockk())
        val shortRoutes = listOf<NavigationRoute>(mockk())
        coEvery { tripSession.setRoutes(longRoutes, any()) } coAnswers {
            delay(100L)
            NativeSetRouteValue(longRoutes, emptyList())
        }
        coEvery { tripSession.setRoutes(shortRoutes, any()) } coAnswers {
            delay(50L)
            NativeSetRouteValue(shortRoutes, emptyList())
        }

        pauseDispatcher {
            mapboxNavigation.setNavigationRoutes(longRoutes)
            mapboxNavigation.setNavigationRoutes(shortRoutes)
        }

        verifyOrder {
            directionsSession.setRoutes(
                longRoutes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
            )
            directionsSession.setRoutes(
                shortRoutes,
                BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
            )
        }
    }

    @Test
    fun `route refresh of previous route completes after new route is set`() =
        coroutineRule.runBlockingTest {
            every { NavigationComponentProvider.createDirectionsSession(any()) } answers {
                MapboxDirectionsSession(mockk(relaxed = true))
            }
            createMapboxNavigation()
            val first = listOf(createNavigationRoute(createDirectionsRoute(requestUuid = "test1")))
            val second = listOf(createNavigationRoute(createDirectionsRoute(requestUuid = "test2")))
            val refreshOrFirstRoute = CompletableDeferred<Unit>()
            coEvery { routeRefreshController.refresh(any()) } coAnswers {
                CompletableDeferred<Unit>().await() // never completes
                firstArg()
            }
            coEvery { routeRefreshController.refresh(first) } coAnswers {
                refreshOrFirstRoute.await()
                RefreshedRouteInfo(
                    listOf(createNavigationRoute(createDirectionsRoute(requestUuid = "test1.1"))),
                    CurrentIndicesFactory.createIndices(1, 2, 3)
                )
            }
            coEvery { tripSession.setRoutes(second, any()) } coAnswers {
                NativeSetRouteValue(second, emptyList())
            }

            val routesUpdates = mutableListOf<RoutesUpdatedResult>()
            mapboxNavigation.registerRoutesObserver {
                routesUpdates.add(it)
            }
            mapboxNavigation.setNavigationRoutes(first)
            mapboxNavigation.setNavigationRoutes(second)
            refreshOrFirstRoute.complete(Unit)

            assertEquals(
                listOf(first, second),
                routesUpdates.map { it.navigationRoutes }
            )
            assertEquals(
                listOf(RoutesExtra.ROUTES_UPDATE_REASON_NEW, RoutesExtra.ROUTES_UPDATE_REASON_NEW),
                routesUpdates.map { it.reason }
            )
        }

    @Test
    fun `set route - immediately stops the reroute controller`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(rerouteController)

        val shortRoutes = listOf<NavigationRoute>(mockk())
        coEvery { tripSession.setRoutes(shortRoutes, any()) } coAnswers {
            delay(50L)
            NativeSetRouteValue(shortRoutes, emptyList())
        }

        pauseDispatcher {
            mapboxNavigation.setNavigationRoutes(shortRoutes)
            verify(exactly = 1) { rerouteController.interrupt() }
        }
    }

    @Test
    fun `set route - correct order of actions, result applied to alternatives controller`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()
            val routes = listOf<NavigationRoute>(mockk())
            val processedRoutes = listOf<NavigationRoute>(mockk())
            val nativeAlternatives = listOf<RouteAlternative>(mockk())
            coEvery {
                tripSession.setRoutes(
                    routes,
                    BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
                )
            } returns NativeSetRouteValue(processedRoutes, nativeAlternatives)

            mapboxNavigation.setNavigationRoutes(routes)

            coVerifyOrder {
                tripSession.setRoutes(
                    routes,
                    BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
                )
                routeAlternativesController.processAlternativesMetadata(
                    processedRoutes,
                    nativeAlternatives
                )
                directionsSession.setRoutes(
                    processedRoutes,
                    BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
                )
            }
        }

    @Test
    fun `route refresh - empty native alternatives returned doesn't clear alternatives metadata`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()
            val primary: NavigationRoute = mockk(relaxed = true) {
                every { directionsRoute } returns mockk(relaxed = true)
            }
            val routes = listOf(primary)
            val currentIndices = CurrentIndicesFactory.createIndices(5, 12, 43)
            val routeObserversSlot = mutableListOf<RoutesObserver>()
            every { tripSession.getState() } returns TripSessionState.STARTED
            coEvery {
                currentIndicesProvider.getFilledIndicesOrWait()
            } returns currentIndices

            val refreshedRoutes = listOf(mockk<NavigationRoute>(relaxed = true))
            coEvery {
                tripSession.setRoutes(
                    refreshedRoutes,
                    SetRefreshedRoutesInfo(currentIndices)
                )
            } returns NativeSetRouteError("some error")
            coEvery {
                routeRefreshController.refresh(routes)
            } returns RefreshedRouteInfo(refreshedRoutes, currentIndices)

            verify { directionsSession.registerRoutesObserver(capture(routeObserversSlot)) }
            routeObserversSlot.forEach {
                it.onRoutesChanged(
                    RoutesUpdatedResult(routes, RoutesExtra.ROUTES_UPDATE_REASON_NEW)
                )
            }

            verify(exactly = 0) {
                routeAlternativesController.processAlternativesMetadata(any(), any())
            }
        }

    @Test
    fun `correct order of actions when trip session started before routes are processed`() =
        coroutineRule.runBlockingTest {
            every { directionsSession.initialLegIndex } returns 0
            every { tripSession.isRunningWithForegroundService() } returns true
            createMapboxNavigation()
            val routes = listOf<NavigationRoute>(mockk())
            coEvery {
                tripSession.setRoutes(
                    routes,
                    BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)
                )
            } coAnswers {
                delay(100)
                NativeSetRouteValue(routes, emptyList())
            }
            every { directionsSession.setRoutes(any(), any()) } answers {
                every { directionsSession.routes } returns firstArg()
            }

            pauseDispatcher {
                mapboxNavigation.setNavigationRoutes(routes)
                runCurrent()
                advanceTimeBy(50) // let trip session start processing
                mapboxNavigation.startTripSession() // start session before routes processed
            }
            val setRoutesInfo = BasicSetRoutesInfo(RoutesExtra.ROUTES_UPDATE_REASON_NEW, 0)

            coVerifyOrder {
                tripSession.setRoutes(routes, setRoutesInfo)
                directionsSession.setRoutes(routes, setRoutesInfo)
                tripSession.setRoutes(routes, setRoutesInfo)
            }

            val routesSlot = mutableListOf<List<NavigationRoute>>()
            verify(exactly = 1) {
                directionsSession.setRoutes(capture(routesSlot), setRoutesInfo)
            }
            assertEquals(1, routesSlot.size)
            assertEquals(routes, routesSlot.first())
        }

    @Test
    fun `stopping trip session does not clear the route`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        every { directionsSession.initialLegIndex } returns 0
        every { tripSession.isRunningWithForegroundService() } returns true
        val routes = listOf<NavigationRoute>(mockk())
        mapboxNavigation.startTripSession()
        mapboxNavigation.setNavigationRoutes(routes)
        mapboxNavigation.stopTripSession()

        verify(exactly = 1) {
            directionsSession.setRoutes(routes, any())
        }
        verify(exactly = 0) {
            directionsSession.setRoutes(emptyList(), any())
        }
    }

    @Test
    fun `refreshed route is set to trip session and directions session`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()
            val primary: NavigationRoute = mockk {
                every { directionsRoute } returns mockk()
            }
            val routes = listOf(primary)
            val reason = RoutesExtra.ROUTES_UPDATE_REASON_NEW
            val currentIndices = CurrentIndicesFactory.createIndices(5, 12, 43)
            val routeObserversSlot = mutableListOf<RoutesObserver>()
            every { tripSession.getState() } returns TripSessionState.STARTED
            verify { directionsSession.registerRoutesObserver(capture(routeObserversSlot)) }

            val refreshedRoutes = listOf(mockk<NavigationRoute>())
            coEvery {
                routeRefreshController.refresh(routes)
            } returns RefreshedRouteInfo(refreshedRoutes, currentIndices)
            routeObserversSlot.forEach {
                it.onRoutesChanged(RoutesUpdatedResult(routes, reason))
            }

            coVerify(exactly = 1) {
                tripSession.setRoutes(
                    refreshedRoutes,
                    SetRefreshedRoutesInfo(currentIndices)
                )
            }
            verify(exactly = 1) {
                directionsSession.setRoutes(
                    refreshedRoutes,
                    SetRefreshedRoutesInfo(currentIndices)
                )
            }
        }

    @Test
    fun `refreshed route is not set to directions session if it is invalid`() =
        coroutineRule.runBlockingTest {
            createMapboxNavigation()
            val primary: NavigationRoute = mockk(relaxed = true) {
                every { directionsRoute } returns mockk()
            }
            val routes = listOf(primary)
            val reason = RoutesExtra.ROUTES_UPDATE_REASON_NEW
            val usedIndicesSnapshot = CurrentIndicesFactory.createIndices(4, 13, 42)
            val routeObserversSlot = mutableListOf<RoutesObserver>()
            every { tripSession.getState() } returns TripSessionState.STARTED
            verify { directionsSession.registerRoutesObserver(capture(routeObserversSlot)) }

            val refreshedRoutes = listOf(mockk<NavigationRoute>(relaxed = true))
            coEvery {
                routeRefreshController.refresh(routes)
            } returns RefreshedRouteInfo(refreshedRoutes, usedIndicesSnapshot)
            coEvery {
                tripSession.setRoutes(any(), any())
            } returns NativeSetRouteError("some error")
            routeObserversSlot.forEach {
                it.onRoutesChanged(RoutesUpdatedResult(routes, reason))
            }

            coVerify(exactly = 1) {
                tripSession.setRoutes(
                    refreshedRoutes,
                    SetRefreshedRoutesInfo(usedIndicesSnapshot)
                )
            }
            verify(exactly = 0) {
                directionsSession.setRoutes(any(), any())
            }
        }

    @Test
    fun `set reroute controller`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        val oldRerouteController = mapboxNavigation.getRerouteController()
        mapboxNavigation.setRerouteController(rerouteController)
        assertFalse(mapboxNavigation.getRerouteController() === oldRerouteController)
    }

    @Test
    fun `set null reroute controller`() = coroutineRule.runBlockingTest {
        val oldController: RerouteController = mockk(relaxed = true)
        val newController: RerouteController? = null
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(oldController)

        mapboxNavigation.setRerouteController(newController)

        assertNull(mapboxNavigation.getRerouteController())
    }

    @Test
    fun `set navigation reroute controller`() = coroutineRule.runBlockingTest {
        val navigationRerouteController: NavigationRerouteController = mockk(relaxed = true)
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(navigationRerouteController)
        assertEquals(navigationRerouteController, mapboxNavigation.getRerouteController())
    }

    @Test
    fun `set null navigation reroute controller`() = coroutineRule.runBlockingTest {
        val oldController: NavigationRerouteController = mockk(relaxed = true)
        val newController: NavigationRerouteController? = null
        createMapboxNavigation()
        mapboxNavigation.setRerouteController(oldController)

        mapboxNavigation.setRerouteController(newController)

        assertNull(mapboxNavigation.getRerouteController())
    }

    @Test
    fun `when telemetry is enabled custom event is posted`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        every { TelemetryUtilsDelegate.getEventsCollectionState() } returns true
        every { MapboxNavigationTelemetry.postCustomEvent(any(), any(), any()) } just Runs
        every { MapboxNavigationTelemetry.destroy(any()) } just Runs

        mapboxNavigation.postCustomEvent("", NavigationCustomEventType.ANALYTICS, "1.0")

        verify(exactly = 1) { MapboxNavigationTelemetry.postCustomEvent(any(), any(), any()) }
    }

    @Test
    fun `when telemetry is disabled custom event is not posted`() = coroutineRule.runBlockingTest {
        createMapboxNavigation()
        every { TelemetryUtilsDelegate.getEventsCollectionState() } returns false
        every { MapboxNavigationTelemetry.postCustomEvent(any(), any(), any()) } just Runs
        every { MapboxNavigationTelemetry.destroy(any()) } just Runs

        mapboxNavigation.postCustomEvent("", NavigationCustomEventType.ANALYTICS, "1.0")

        verify(exactly = 0) { MapboxNavigationTelemetry.postCustomEvent(any(), any(), any()) }
    }

    @Test
    fun requestRoadGraphDataUpdate() {
        val callback = mockk<RoadGraphDataUpdateCallback>()
        createMapboxNavigation()
        mapboxNavigation.requestRoadGraphDataUpdate(callback)
        verify { CacheHandleWrapper.requestRoadGraphDataUpdate(cache, callback) }
    }

    @Test
    fun registerHistoryRecordingStateChangeObserver() {
        val observer = mockk<HistoryRecordingStateChangeObserver>(relaxed = true)
        createMapboxNavigation()

        mapboxNavigation.registerHistoryRecordingStateChangeObserver(observer)

        verify {
            historyRecordingStateHandler.registerStateChangeObserver(observer)
        }
    }

    @Test
    fun unregisterHistoryRecordingStateChangeObserver() {
        val observer = mockk<HistoryRecordingStateChangeObserver>(relaxed = true)
        createMapboxNavigation()

        mapboxNavigation.unregisterHistoryRecordingStateChangeObserver(observer)

        verify {
            historyRecordingStateHandler.unregisterStateChangeObserver(observer)
        }
    }

    @Test
    fun registerDeveloperMetadataObserver() {
        val observer = mockk<DeveloperMetadataObserver>(relaxed = true)
        createMapboxNavigation()

        mapboxNavigation.registerDeveloperMetadataObserver(observer)

        verify {
            developerMetadataAggregator.registerObserver(observer)
        }
    }

    @Test
    fun unregisterDeveloperMetadataObserver() {
        val observer = mockk<DeveloperMetadataObserver>(relaxed = true)
        createMapboxNavigation()

        mapboxNavigation.unregisterDeveloperMetadataObserver(observer)

        verify {
            developerMetadataAggregator.unregisterObserver(observer)
        }
    }

    @Test
    fun registerRouteRefreshStateObserver() {
        val observer = mockk<RouteRefreshStatesObserver>()
        createMapboxNavigation()

        mapboxNavigation.registerRouteRefreshStateObserver(observer)

        verify(exactly = 1) {
            routeRefreshController.registerRouteRefreshStateObserver(observer)
        }
    }

    @Test
    fun unregisterRouteRefreshStateObserver() {
        val observer = mockk<RouteRefreshStatesObserver>()
        createMapboxNavigation()

        mapboxNavigation.unregisterRouteRefreshStateObserver(observer)

        verify(exactly = 1) {
            routeRefreshController.unregisterRouteRefreshStateObserver(observer)
        }
    }

    @Test
    fun onDestroyUnregisterAllRouteRefreshStateObserver() {
        createMapboxNavigation()

        mapboxNavigation.onDestroy()

        verify(exactly = 1) {
            routeRefreshController.unregisterAllRouteRefreshStateObservers()
        }
    }
}
