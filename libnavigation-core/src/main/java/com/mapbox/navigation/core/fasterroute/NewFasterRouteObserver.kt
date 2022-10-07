package com.mapbox.navigation.core.fasterroute

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute

@ExperimentalPreviewMapboxNavigationAPI
fun interface NewFasterRouteObserver {
    /***
     * Called every time a faster route is available.
     *
     * You can accept it using [FasterRoutes.acceptFasterRoute]
     * or decline it using [FasterRoutes.declineFasterRoute]
     */
    fun onNewFasterRouteFound(newFasterRoute: NewFasterRoute)
}

@ExperimentalPreviewMapboxNavigationAPI
data class NewFasterRoute(
    val fasterRoute: NavigationRoute,
    val fasterThanPrimaryRouteBy: Double,
    val alternativeId: Int
)
