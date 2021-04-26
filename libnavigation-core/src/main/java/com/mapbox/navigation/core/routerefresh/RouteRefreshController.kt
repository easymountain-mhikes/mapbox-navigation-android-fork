package com.mapbox.navigation.core.routerefresh

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.base.common.logger.Logger
import com.mapbox.base.common.logger.model.Message
import com.mapbox.base.common.logger.model.Tag
import com.mapbox.navigation.base.extensions.supportsRouteRefresh
import com.mapbox.navigation.base.route.RouteRefreshCallback
import com.mapbox.navigation.base.route.RouteRefreshError
import com.mapbox.navigation.base.route.RouteRefreshOptions
import com.mapbox.navigation.core.directions.session.DirectionsSession
import com.mapbox.navigation.core.internal.utils.isUuidValidForRefresh
import com.mapbox.navigation.core.trip.session.TripSession
import com.mapbox.navigation.utils.internal.MapboxTimer
import kotlinx.coroutines.Job

/**
 * This class is responsible for refreshing the current direction route's traffic.
 * This does not support alternative routes.
 *
 * If the route is successfully refreshed, this class will update the [TripSession.route]
 *
 * [start] and [stop] are attached to the application lifecycle. Observing routes that
 * can be refreshed are handled by this class. Calling [start] will restart the refresh timer.
 */
internal class RouteRefreshController(
    routeRefreshOptions: RouteRefreshOptions,
    private val directionsSession: DirectionsSession,
    private val tripSession: TripSession,
    private val logger: Logger
) {

    companion object {
        internal val TAG = Tag("MbxRouteRefreshController")
    }

    private val routerRefreshTimer = MapboxTimer().apply {
        restartAfterMillis = routeRefreshOptions.intervalMillis
    }

    private var requestId: Long? = null

    fun start(): Job {
        stop()
        return routerRefreshTimer.startTimer {
            val route = tripSession.route
                ?.takeIf { it.routeOptions().supportsRouteRefresh() }
                ?.takeIf { it.routeOptions().isUuidValidForRefresh() }
            if (route != null) {
                val legIndex = tripSession.getRouteProgress()?.currentLegProgress?.legIndex ?: 0
                requestId = directionsSession.requestRouteRefresh(
                    route,
                    legIndex,
                    routeRefreshCallback
                )
            } else {
                logger.w(
                    TAG,
                    Message(
                        """
                           The route is not qualified for route refresh feature.
                           See com.mapbox.navigation.base.extensions.supportsRouteRefresh
                           extension for details.
                        """.trimIndent()
                    )
                )
            }
        }
    }

    fun stop() {
        requestId?.let {
            directionsSession.cancelRouteRefreshRequest(it)
            requestId = null
        }
        routerRefreshTimer.stopJobs()
    }

    private val routeRefreshCallback = object : RouteRefreshCallback {

        override fun onRefresh(directionsRoute: DirectionsRoute) {
            logger.i(TAG, msg = Message("Successful route refresh"))
            val directionsSessionRoutes = directionsSession.routes.toMutableList()
            if (directionsSessionRoutes.isNotEmpty()) {
                directionsSessionRoutes[0] = directionsRoute
                directionsSession.routes = directionsSessionRoutes
            }
        }

        override fun onError(error: RouteRefreshError) {
            logger.e(
                TAG,
                msg = Message("Route refresh error: ${error.message}"),
                tr = error.throwable
            )
        }
    }
}
