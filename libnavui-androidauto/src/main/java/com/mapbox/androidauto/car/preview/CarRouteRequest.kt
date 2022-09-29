package com.mapbox.androidauto.car.preview

import androidx.annotation.UiThread
import com.mapbox.androidauto.car.location.CarLocationProvider
import com.mapbox.androidauto.car.search.PlaceRecord
import com.mapbox.androidauto.internal.logAndroidAuto
import com.mapbox.androidauto.internal.logAndroidAutoFailure
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver

/**
 * This is a view interface. Each callback function represents a view that will be
 * shown for the situations.
 */
interface CarRouteRequestCallback {
    fun onRoutesReady(placeRecord: PlaceRecord, routes: List<NavigationRoute>)
    fun onUnknownCurrentLocation()
    fun onDestinationLocationUnknown()
    fun onNoRoutesFound()
}

/**
 * Service class that requests routes for the preview screen.
 */
class CarRouteRequest(
    private val routeOptionsInterceptor: CarRouteOptionsInterceptor,
) : MapboxNavigationObserver {
    private var currentRequestId: Long? = null
    private var mapboxNavigation: MapboxNavigation? = null

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        this.mapboxNavigation = mapboxNavigation
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        cancelRequest()
        this.mapboxNavigation = null
    }

    /**
     * When a search result was selected, request a route.
     */
    @UiThread
    fun request(placeRecord: PlaceRecord, callback: CarRouteRequestCallback) {
        val mapboxNavigation = this.mapboxNavigation
        if (mapboxNavigation == null) {
            callback.onNoRoutesFound()
            return
        }
        cancelRequest()

        val location = CarLocationProvider.getInstance().lastLocation
        if (location == null) {
            logAndroidAutoFailure("CarRouteRequest.onUnknownCurrentLocation")
            callback.onUnknownCurrentLocation()
            return
        }
        val origin = Point.fromLngLat(location.longitude, location.latitude)

        when (placeRecord.coordinate) {
            null -> {
                logAndroidAutoFailure("CarRouteRequest.onDestinationLocationUnknown")
                callback.onDestinationLocationUnknown()
            }
            else -> {
                currentRequestId = mapboxNavigation.requestRoutes(
                    mapboxNavigation.carRouteOptions(origin, placeRecord.coordinate),
                    carCallbackTransformer(placeRecord, callback)
                )
            }
        }
    }

    @UiThread
    fun cancelRequest() {
        currentRequestId?.let { mapboxNavigation?.cancelRouteRequest(it) }
    }

    /**
     * Default [RouteOptions] for the car.
     */
    private fun MapboxNavigation.carRouteOptions(
        origin: Point,
        destination: Point
    ) = RouteOptions.builder()
        .applyDefaultNavigationOptions()
        .language(navigationOptions.distanceFormatterOptions.locale.language)
        .voiceUnits(
            when (navigationOptions.distanceFormatterOptions.unitType) {
                UnitType.IMPERIAL -> DirectionsCriteria.IMPERIAL
                UnitType.METRIC -> DirectionsCriteria.METRIC
            },
        )
        .alternatives(true)
        .profile(DirectionsCriteria.PROFILE_DRIVING_TRAFFIC)
        .coordinatesList(listOf(origin, destination))
        .layersList(listOf(getZLevel(), null))
        .metadata(true)
        .let { routeOptionsInterceptor.intercept(it) }
        .build()

    /**
     * This creates a callback that transforms
     * [RouterCallback] into [CarRouteRequestCallback]
     */
    private fun carCallbackTransformer(
        searchResult: PlaceRecord,
        callback: CarRouteRequestCallback
    ): NavigationRouterCallback {
        return object : NavigationRouterCallback {
            override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: RouterOrigin) {
                currentRequestId = null

                logAndroidAuto("onRoutesReady ${routes.size}")
                callback.onRoutesReady(searchResult, routes)
            }

            override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                currentRequestId = null

                logAndroidAutoFailure("onCanceled $routeOptions")
                callback.onNoRoutesFound()
            }

            override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                currentRequestId = null

                logAndroidAutoFailure("onRoutesRequestFailure $routeOptions $reasons")
                callback.onNoRoutesFound()
            }
        }
    }
}
