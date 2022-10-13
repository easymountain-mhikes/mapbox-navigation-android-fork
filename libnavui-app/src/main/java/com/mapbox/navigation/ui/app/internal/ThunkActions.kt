package com.mapbox.navigation.ui.app.internal

import com.mapbox.geojson.Point
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesPreview
import com.mapbox.navigation.ui.app.internal.destination.Destination
import com.mapbox.navigation.ui.app.internal.destination.DestinationAction
import com.mapbox.navigation.ui.app.internal.extension.ThunkAction
import com.mapbox.navigation.ui.app.internal.extension.dispatch
import com.mapbox.navigation.ui.app.internal.navigation.NavigationState
import com.mapbox.navigation.ui.app.internal.navigation.NavigationStateAction
import com.mapbox.navigation.ui.app.internal.routefetch.RouteOptionsProvider
import com.mapbox.navigation.ui.app.internal.routefetch.RoutePreviewAction
import com.mapbox.navigation.ui.app.internal.routefetch.RoutesAction
import com.mapbox.navigation.utils.internal.ifNonNull
import com.mapbox.navigation.utils.internal.toPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * End Navigation ThunkAction creator.
 */
fun endNavigation() = ThunkAction { store ->
    store.setRoutes(emptyList())
    store.setPreviewRoutes(emptyList())
    store.dispatch(DestinationAction.SetDestination(null))
    store.dispatch(NavigationStateAction.Update(NavigationState.FreeDrive))
}

/**
 * Show Destination Preview ThunkAction creator.
 */
fun showDestinationPreview(point: Point) = ThunkAction { store ->
    store.setDestination(point)
    store.dispatch(NavigationStateAction.Update(NavigationState.DestinationPreview))
}

/**
 * Show Route Preview ThunkAction creator.
 */
fun showRoutePreview(point: Point, routes: List<NavigationRoute>) = ThunkAction { store ->
    store.setDestination(point)
    store.setPreviewRoutes(routes)
    store.dispatch(NavigationStateAction.Update(NavigationState.RoutePreview))
}

/**
 * Start Active Navigation ThunkAction creator.
 */
fun startActiveNavigation(routes: List<NavigationRoute>) = ThunkAction { store ->
    store.setRoutes(routes)
    store.dispatch(NavigationStateAction.Update(NavigationState.ActiveNavigation))
}

/**
 * Start Active Navigation ThunkAction creator.
 */
fun startActiveNavigation(point: Point, routes: List<NavigationRoute>) = ThunkAction { store ->
    store.setDestination(point)
    store.setPreviewRoutes(routes)
    store.setRoutes(routes)
    store.dispatch(NavigationStateAction.Update(NavigationState.ActiveNavigation))
}

/**
 * Start Arrival ThunkAction creator.
 */
fun startArrival(point: Point, routes: List<NavigationRoute>) = ThunkAction { store ->
    store.setDestination(point)
    store.setPreviewRoutes(routes)
    store.setRoutes(routes)
    store.dispatch(NavigationStateAction.Update(NavigationState.Arrival))
}

/**
 * Fetch Route and Show Route Preview ThunkAction creator.
 */
fun CoroutineScope.fetchRouteAndShowRoutePreview(
    routeOptionsProvider: RouteOptionsProvider,
    mapboxNavigation: MapboxNavigation,
) = fetchRouteAndContinue(routeOptionsProvider, mapboxNavigation) { store ->
    store.dispatch(NavigationStateAction.Update(NavigationState.RoutePreview))
}

/**
 * Fetch Route and Start Active Navigation ThunkAction creator.
 */
fun CoroutineScope.fetchRouteAndStartActiveNavigation(
    routeOptionsProvider: RouteOptionsProvider,
    mapboxNavigation: MapboxNavigation,
) = fetchRouteAndContinue(routeOptionsProvider, mapboxNavigation) { store ->
    val navigationRoutes = mapboxNavigation.getRoutePreview().navigationRoutes
    if (navigationRoutes.isNotEmpty()) {
        store.dispatch(startActiveNavigation(navigationRoutes))
    }
}

// TODO: simplify after :libnavui-app module is merged with :libnavui-dropin
private fun CoroutineScope.fetchRouteAndContinue(
    routeOptionsProvider: RouteOptionsProvider,
    mapboxNavigation: MapboxNavigation,
    continuation: (Store) -> Unit,
) = ThunkAction { store ->
    launch {
        if (fetchRouteIfNeeded(store, routeOptionsProvider, mapboxNavigation)) {
            continuation(store)
        }
    }
}

/**
 * Dispatch FetchPoints action and wait for RoutePreviewState.Ready.
 * Method returns immediately if already in RoutePreviewState.Ready or RoutePreviewState.Fetching, or if
 * required location or destination data is missing.
 *
 * @return `true` once in RoutePreviewState.Ready state, otherwise `false`
 */
private suspend fun fetchRouteIfNeeded(
    store: Store,
    routeOptionsProvider: RouteOptionsProvider,
    mapboxNavigation: MapboxNavigation,
): Boolean {
    val routesPreview = mapboxNavigation.getRoutePreview()
    val storeState = store.state.value
    if (routesPreview.navigationRoutes.isNotEmpty()) return true
    if (routesPreview.state == RoutesPreview.FETCHING) return false

    return ifNonNull(
        storeState.location?.enhancedLocation?.toPoint(),
        storeState.destination?.point
    ) { lastPoint, destination ->
        val options = routeOptionsProvider.getOptions(mapboxNavigation, lastPoint, destination)
        store.dispatch(RoutePreviewAction.FetchOptions(options))
        store.waitWhileFetching()
        mapboxNavigation.getRoutePreview().navigationRoutes.isNotEmpty()
    } ?: false
}

private suspend fun Store.waitWhileFetching() {
    select { it.routePreviewState }.takeWhile { it is RoutePreviewState.Fetching }.collect()
}

private fun Store.setDestination(point: Point) {
    dispatch(DestinationAction.SetDestination(Destination(point)))
}

private fun Store.setPreviewRoutes(routes: List<NavigationRoute>) {
    dispatch(RoutePreviewAction.Ready(routes))
}

private fun Store.setRoutes(routes: List<NavigationRoute>) {
    dispatch(RoutesAction.SetRoutes(routes))
}
