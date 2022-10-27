package com.mapbox.navigation.dropin

import com.mapbox.maps.MapView
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.dropin.actionbutton.ActionButtonDescription
import com.mapbox.navigation.dropin.actionbutton.ActionButtonsBinder
import com.mapbox.navigation.dropin.infopanel.InfoPanelBinder
import com.mapbox.navigation.dropin.map.MapViewBinder
import com.mapbox.navigation.ui.base.lifecycle.UIBinder

/**
 * A class that allows you to define [UIBinder] for various different views used by the
 * [NavigationView]. If not specified, [NavigationView] uses the default [UIBinder] defined for
 * each of these views.
 */
@ExperimentalPreviewMapboxNavigationAPI
class ViewBinderCustomization {

    /**
     * Customize the speed limit view by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var speedLimitBinder: UIBinder? = null

    /**
     * Customize the maneuvers view by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var maneuverBinder: UIBinder? = null

    /**
     * Customize the road name view by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var roadNameBinder: UIBinder? = null

    /**
     * Customize the Info Panel layout by providing your own [InfoPanelBinder].
     * Use [InfoPanelBinder.defaultBinder] to reset to default.
     */
    var infoPanelBinder: InfoPanelBinder? = null

    /**
     * Customize the Info Panel Trip Progress by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelTripProgressBinder: UIBinder? = null

    /**
     * Customize the Info Panel Point Of Interest Name text view by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelPoiNameBinder: UIBinder? = null

    /**
     * Customize the Info Panel Arrival text view by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelArrivalTextBinder: UIBinder? = null

    /**
     * Customize the Info Panel Header by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelHeaderBinder: UIBinder? = null

    /**
     * Customize the Info Panel Header for Free Drive state by providing your own [UIBinder]
     * when using default [infoPanelHeaderBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelHeaderFreeDriveBinder: UIBinder? = null

    /**
     * Customize the Info Panel Header for Destination Preview state by providing your own [UIBinder]
     * when using default [infoPanelHeaderBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelHeaderDestinationPreviewBinder: UIBinder? = null

    /**
     * Customize the Info Panel Header for Routes Preview state by providing your own [UIBinder]
     * when using default [infoPanelHeaderBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelHeaderRoutesPreviewBinder: UIBinder? = null

    /**
     * Customize the Info Panel Header for Active Guidance state by providing your own [UIBinder]
     * when using default [infoPanelHeaderBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelHeaderActiveGuidanceBinder: UIBinder? = null

    /**
     * Customize the Info Panel Header for Arrival state by providing your own [UIBinder]
     * when using default [infoPanelHeaderBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelHeaderArrivalBinder: UIBinder? = null

    /**
     * Customize the Info Panel Content by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelContentBinder: UIBinder? = null

    /**
     * Customize the Info Panel Route Preview Button by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelRoutePreviewButtonBinder: UIBinder? = null

    /**
     * Customize the Info Panel Start Navigation Button by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelStartNavigationButtonBinder: UIBinder? = null

    /**
     * Customize the Info Panel End Navigation Button by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var infoPanelEndNavigationButtonBinder: UIBinder? = null

    /**
     * Customize the Action Buttons by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to use default [ActionButtonsBinder] implementation.
     */
    var actionButtonsBinder: UIBinder? = null

    /**
     * Customize the Compass Action Button by providing your own [UIBinder]
     * when using [actionButtonsBinder] with [ActionButtonsBinder.defaultBinder] implementation.
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var actionCompassButtonBinder: UIBinder? = null

    /**
     * Customize the Camera Mode Action Button by providing your own [UIBinder]
     * when using [actionButtonsBinder] with [ActionButtonsBinder.defaultBinder] implementation.
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var actionCameraModeButtonBinder: UIBinder? = null

    /**
     * Customize the Toggle Audio Guidance Action Button by providing your own [UIBinder]
     * when using [actionButtonsBinder] with [ActionButtonsBinder.defaultBinder] implementation.
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var actionToggleAudioButtonBinder: UIBinder? = null

    /**
     * Customize the Recenter Camera Action Button by providing your own [UIBinder]
     * when using [actionButtonsBinder] with [ActionButtonsBinder.defaultBinder] implementation.
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var actionRecenterButtonBinder: UIBinder? = null

    /**
     * Customize the empty frame container on left side of the screen by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var leftFrameBinder: UIBinder? = null

    /**
     * Customize the empty frame container on right side of the screen by providing your own [UIBinder].
     * Use [UIBinder.USE_DEFAULT] to reset to default.
     */
    var rightFrameBinder: UIBinder? = null

    /**
     * Add custom Action Buttons to the [NavigationView].
     * Custom buttons can only be added to default [actionButtonsBinder] and can be placed either
     * before or after existing controls.
     * Setting this field to `emptyList()` will remove all custom buttons.
     */
    var customActionButtons: List<ActionButtonDescription>? = null

    /**
     * Inject your own [MapView] in the [NavigationView].
     * Use [MapViewBinder.defaultBinder] to reset to default.
     */
    var mapViewBinder: MapViewBinder? = null
}
