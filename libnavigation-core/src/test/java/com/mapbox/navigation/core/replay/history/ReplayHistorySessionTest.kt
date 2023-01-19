package com.mapbox.navigation.core.replay.history

import android.content.Context
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.TripSessionResetCallback
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.replay.MapboxReplayer
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.testing.LoggingFrontendTestRule
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class ReplayHistorySessionTest {

    @get:Rule
    val loggerRule = LoggingFrontendTestRule()

    private val replayer: MapboxReplayer = mockk(relaxed = true)

    private val sut = ReplayHistorySession()

    @Test
    fun `onAttached with startReplayTripSession`() {
        val mapboxNavigation = mockMapboxNavigation()

        sut.onAttached(mapboxNavigation)

        verify { mapboxNavigation.startReplayTripSession() }
    }

    @Test
    fun `onAttached with call MapboxReplayer#play`() {
        val mapboxNavigation = mockMapboxNavigation()

        sut.onAttached(mapboxNavigation)

        verify { replayer.play() }
    }

    private fun mockMapboxNavigation(): MapboxNavigation {
        val context: Context = mockk(relaxed = true)
        val options: NavigationOptions = mockk {
            every { applicationContext } returns context
        }
        val routesObserver = slot<RoutesObserver>()
        val routeProgressObserver = slot<RouteProgressObserver>()
        return mockk(relaxed = true) {
            every { mapboxReplayer } returns replayer
            every { navigationOptions } returns options
            every { registerRoutesObserver(capture(routesObserver)) } just runs
            every { registerRouteProgressObserver(capture(routeProgressObserver)) } just runs
            every { resetTripSession(any()) } answers {
                firstArg<TripSessionResetCallback>().onTripSessionReset()
            }
        }
    }
}
