package com.mapbox.navigation.ui.voice.api

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.api.directions.v5.models.VoiceInstructions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteStepProgress
import com.mapbox.navigation.core.directions.session.RoutesExtra
import com.mapbox.navigation.core.directions.session.RoutesUpdatedResult
import com.mapbox.navigation.utils.internal.Time
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Before
import org.junit.Test

class VoiceInstructionsDownloadTriggerTest {

    private val observableTime = 100
    private val timePercentageToTriggerAfter = 0.5
    private val nextVoiceInstructionsProvider = mockk<NextVoiceInstructionsProvider>(relaxed = true)
    private val timeProvider = mockk<Time>(relaxed = true)
    private val observer = mockk<VoiceInstructionsDownloadTriggerObserver>(relaxed = true)
    private val stepDistance = 200.0
    private val stepDuration = 90.0
    private val legIndex = 1
    private val stepIndex = 2
    private val stepDistanceRemaining = 8.9f
    private val stepDurationRemaining = 4.5
    private val instructionsToDownload = listOf(
        VoiceInstructions.builder().announcement("ann1").build(),
        VoiceInstructions.builder().announcement("ann2").build()
    )
    private val currentTimeSeconds = 9988L
    private val sut = VoiceInstructionsDownloadTrigger(
        observableTime,
        timePercentageToTriggerAfter,
        nextVoiceInstructionsProvider,
        timeProvider,
    )

    @Before
    fun setUp() {
        sut.registerObserver(observer)
        every {
            nextVoiceInstructionsProvider.getNextVoiceInstructions(any())
        } returns instructionsToDownload
        every { timeProvider.seconds() } returns currentTimeSeconds
    }

    @Test
    fun `onRoutesChanged should trigger download for reason NEW`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        val expectedData = RouteProgressData(
            route.directionsRoute,
            0,
            0,
            stepDuration,
            stepDistance
        )
        verify { nextVoiceInstructionsProvider.getNextVoiceInstructions(expectedData) }
        verify { observer.trigger(instructionsToDownload) }
    }

    @Test
    fun `onRoutesChanged should trigger download for reason REROUTE`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_REROUTE)
        )

        val expectedData = RouteProgressData(
            route.directionsRoute,
            0,
            0,
            stepDuration,
            stepDistance
        )
        verify { nextVoiceInstructionsProvider.getNextVoiceInstructions(expectedData) }
        verify { observer.trigger(instructionsToDownload) }
    }

    @Test
    fun `onRoutesChanged should not trigger download for reason REFRESH`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_REFRESH)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download for reason ALTERNATIVE`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_ALTERNATIVE)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download for reason CLEANUP`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should trigger download even if timeout did not pass`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )
        clearMocks(observer, answers = false)

        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        verify(exactly = 1) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged download should remember last download time`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )
        clearMocks(observer, answers = false)

        sut.onRouteProgressChanged(validRouteProgress())
        verify(exactly = 0) { observer.trigger(any()) }

        every { timeProvider.seconds() } returns currentTimeSeconds + 51
        sut.onRouteProgressChanged(validRouteProgress())
        verify(exactly = 1) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not remember last download time when not triggered`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_CLEAN_UP)
        )
        clearMocks(observer, answers = false)

        sut.onRouteProgressChanged(validRouteProgress())
        verify(exactly = 1) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download if steps are null`() {
        val route = validRoute(listOf(validLeg().toBuilder().steps(null).build()))
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download if steps are empty`() {
        val route = validRoute(listOf(validLeg().toBuilder().steps(emptyList()).build()))
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download if legs are null`() {
        val route = validRoute(null)
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download if legs are empty`() {
        val route = validRoute(emptyList())
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRoutesChanged should not trigger download if routes are empty`() {
        sut.onRoutesChanged(
            routesUpdatedResult(emptyList(), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRouteProgressChanged should not trigger if timeout did not pass`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )
        clearMocks(observer, answers = false)
        every { timeProvider.seconds() } returns currentTimeSeconds + 49

        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRouteProgressChanged should trigger if timeout passed`() {
        val route = validRoute()
        sut.onRoutesChanged(
            routesUpdatedResult(listOf(route), RoutesExtra.ROUTES_UPDATE_REASON_NEW)
        )
        clearMocks(observer, answers = false)
        every { timeProvider.seconds() } returns currentTimeSeconds + 51

        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 1) { observer.trigger(any()) }
    }

    @Test
    fun `onRouteProgressChanged should remember last download time if triggered`() {
        sut.onRouteProgressChanged(validRouteProgress())
        clearMocks(observer, answers = false)

        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 0) { observer.trigger(any()) }

        every { timeProvider.seconds() } returns currentTimeSeconds + 51

        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 1) { observer.trigger(any()) }
    }

    @Test
    fun `onRouteProgressChanged should not remember last download time if not triggered`() {
        sut.onRouteProgressChanged(validRouteProgress(null))
        clearMocks(observer, answers = false)

        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 1) { observer.trigger(any()) }
    }

    @Test
    fun `onRouteProgressChanged should trigger download`() {
        val routeProgress = validRouteProgress()

        sut.onRouteProgressChanged(routeProgress)

        val expectedData = RouteProgressData(
            routeProgress.route,
            legIndex,
            stepIndex,
            stepDurationRemaining,
            stepDistanceRemaining.toDouble()
        )
        verify { nextVoiceInstructionsProvider.getNextVoiceInstructions(expectedData) }
        verify(exactly = 1) { observer.trigger(instructionsToDownload) }
    }

    @Test
    fun `onRouteProgressChanged should not trigger download if leg progress is null`() {
        val routeProgress = validRouteProgress(null)

        sut.onRouteProgressChanged(routeProgress)

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `onRouteProgressChanged should not trigger download if step progress is null`() {
        val routeProgress = validRouteProgress(validLegProgress(null))

        sut.onRouteProgressChanged(routeProgress)

        verify(exactly = 0) { observer.trigger(any()) }
    }

    @Test
    fun `observers notification`() {
        every { timeProvider.seconds() } returnsMany List(100) { currentTimeSeconds + 51 * it }
        val secondObserver = mockk<VoiceInstructionsDownloadTriggerObserver>(relaxed = true)

        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 1) { observer.trigger(any()) }
        verify(exactly = 0) { secondObserver.trigger(any()) }
        clearMocks(observer, answers = false)

        sut.registerObserver(secondObserver)
        sut.onRouteProgressChanged(validRouteProgress())

        verifyOrder {
            observer.trigger(any())
            secondObserver.trigger(any())
        }

        clearMocks(observer, secondObserver, answers = false)
        sut.unregisterObserver(observer)
        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 1) { secondObserver.trigger(any()) }
        verify(exactly = 0) { observer.trigger(any()) }

        clearMocks(observer, secondObserver, answers = false)
        sut.unregisterObserver(secondObserver)
        sut.onRouteProgressChanged(validRouteProgress())

        verify(exactly = 0) {
            observer.trigger(any())
            secondObserver.trigger(any())
        }
    }

    private fun routesUpdatedResult(
        routes: List<NavigationRoute>,
        @RoutesExtra.RoutesUpdateReason mockReason: String
    ): RoutesUpdatedResult = mockk {
        every { navigationRoutes } returns routes
        every { reason } returns mockReason
    }

    private fun validRoute(legs: List<RouteLeg>? = listOf(validLeg())): NavigationRoute {
        return mockk(relaxed = true) {
            every { directionsRoute } returns validDirectionsRoute(legs)
        }
    }

    private fun validDirectionsRoute(legs: List<RouteLeg>? = listOf(validLeg())): DirectionsRoute =
        DirectionsRoute.builder()
            .legs(legs)
            .duration(1.2)
            .distance(3.4)
            .build()

    private fun validLeg(): RouteLeg = RouteLeg.builder()
        .distance(1.2)
        .duration(3.4)
        .steps(listOf(validStep()))
        .build()

    private fun validStep(): LegStep = LegStep.builder()
        .distance(stepDistance)
        .duration(stepDuration)
        .mode("mode")
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(1.1, 2.2)).build())
        .weight(1.0)
        .build()

    private fun validRouteProgress(
        legProgress: RouteLegProgress? = validLegProgress()
    ): RouteProgress = mockk {
        every { route } returns validDirectionsRoute()
        every { currentLegProgress } returns legProgress
    }

    private fun validLegProgress(
        stepProgress: RouteStepProgress? = validStepProgress()
    ): RouteLegProgress = mockk {
        every { legIndex } returns this@VoiceInstructionsDownloadTriggerTest.legIndex
        every { currentStepProgress } returns stepProgress
    }

    private fun validStepProgress(): RouteStepProgress = mockk {
        every { stepIndex } returns this@VoiceInstructionsDownloadTriggerTest.stepIndex
        every { distanceRemaining } returns stepDistanceRemaining
        every { durationRemaining } returns stepDurationRemaining
    }
}
