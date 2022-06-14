package com.mapbox.navigation.dropin.component.marker

import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.dropin.util.HapticFeedback
import com.mapbox.navigation.ui.app.internal.Store
import com.mapbox.navigation.ui.app.internal.destination.Destination
import com.mapbox.navigation.ui.app.internal.destination.DestinationAction
import com.mapbox.navigation.ui.app.internal.routefetch.RoutesAction
import com.mapbox.navigation.ui.base.lifecycle.UIComponent
import com.mapbox.navigation.utils.internal.logW
import com.mapbox.navigation.utils.internal.toPoint

@ExperimentalPreviewMapboxNavigationAPI
internal class RoutePreviewLongPressMapComponent(
    private val store: Store,
    private val mapView: MapView,
) : UIComponent() {

    private var hapticFeedback: HapticFeedback? = null

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        super.onAttached(mapboxNavigation)
        hapticFeedback =
            HapticFeedback.create(mapboxNavigation.navigationOptions.applicationContext)
        mapView.gestures.addOnMapLongClickListener(longClickListener)
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        super.onDetached(mapboxNavigation)
        mapView.gestures.removeOnMapLongClickListener(longClickListener)
        hapticFeedback = null
    }

    private val longClickListener = OnMapLongClickListener { point ->
        val location = store.state.value.location?.enhancedLocation
        location?.toPoint()?.also { lastPoint ->
            store.dispatch(DestinationAction.SetDestination(Destination(point)))
            store.dispatch(RoutesAction.FetchPoints(listOf(lastPoint, point)))
            hapticFeedback?.tick()
        } ?: logW(TAG, "Current location is unknown so map long press does nothing")
        false
    }

    private companion object {
        private val TAG = this::class.java.simpleName
    }
}
