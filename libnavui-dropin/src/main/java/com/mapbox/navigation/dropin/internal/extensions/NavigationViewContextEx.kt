@file:JvmName("NavigationViewContextEx")

package com.mapbox.navigation.dropin.internal.extensions

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.Px
import androidx.constraintlayout.widget.Guideline
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.dropin.EmptyBinder
import com.mapbox.navigation.dropin.LeftFrameCoordinator
import com.mapbox.navigation.dropin.RightFrameCoordinator
import com.mapbox.navigation.dropin.actionbutton.ActionButtonsCoordinator
import com.mapbox.navigation.dropin.actionbutton.AudioGuidanceButtonBinder
import com.mapbox.navigation.dropin.actionbutton.CameraModeButtonBinder
import com.mapbox.navigation.dropin.actionbutton.CompassButtonBinder
import com.mapbox.navigation.dropin.actionbutton.RecenterButtonBinder
import com.mapbox.navigation.dropin.analytics.AnalyticsComponent
import com.mapbox.navigation.dropin.backpress.BackPressedComponent
import com.mapbox.navigation.dropin.databinding.MapboxNavigationViewLayoutBinding
import com.mapbox.navigation.dropin.infopanel.InfoPanelArrivalTextBinder
import com.mapbox.navigation.dropin.infopanel.InfoPanelCoordinator
import com.mapbox.navigation.dropin.infopanel.InfoPanelEndNavigationButtonBinder
import com.mapbox.navigation.dropin.infopanel.InfoPanelPoiNameBinder
import com.mapbox.navigation.dropin.infopanel.InfoPanelRoutePreviewButtonBinder
import com.mapbox.navigation.dropin.infopanel.InfoPanelStartNavigationButtonBinder
import com.mapbox.navigation.dropin.maneuver.ManeuverCoordinator
import com.mapbox.navigation.dropin.map.MapLayoutCoordinator
import com.mapbox.navigation.dropin.map.scalebar.ScalebarPlaceholderCoordinator
import com.mapbox.navigation.dropin.navigationview.NavigationViewContext
import com.mapbox.navigation.dropin.permission.LocationPermissionComponent
import com.mapbox.navigation.dropin.roadname.RoadNameCoordinator
import com.mapbox.navigation.dropin.speedlimit.SpeedLimitCoordinator
import com.mapbox.navigation.dropin.tripprogress.TripProgressBinder
import com.mapbox.navigation.dropin.tripsession.TripSessionComponent
import kotlinx.coroutines.flow.combine

internal fun NavigationViewContext.poiNameComponent(
    viewGroup: ViewGroup
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showPoiName,
        uiBinders.infoPanelPoiNameBinder
    ) { show, binder ->
        if (show) {
            binder ?: InfoPanelPoiNameBinder(this)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(viewGroup) }
}

internal fun NavigationViewContext.routePreviewButtonComponent(
    buttonContainer: ViewGroup
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showRoutePreviewButton,
        uiBinders.infoPanelRoutePreviewButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: InfoPanelRoutePreviewButtonBinder(this)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(buttonContainer) }
}

internal fun NavigationViewContext.startNavigationButtonComponent(
    buttonContainer: ViewGroup
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showStartNavigationButton,
        uiBinders.infoPanelStartNavigationButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: InfoPanelStartNavigationButtonBinder(this)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(buttonContainer) }
}

internal fun NavigationViewContext.endNavigationButtonComponent(
    endNavigationButtonLayout: ViewGroup
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showEndNavigationButton,
        uiBinders.infoPanelEndNavigationButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: InfoPanelEndNavigationButtonBinder(this)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(endNavigationButtonLayout) }
}

internal fun NavigationViewContext.arrivalTextComponent(
    viewGroup: ViewGroup
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showArrivalText,
        uiBinders.infoPanelArrivalTextBinder
    ) { show, binder ->
        if (show) {
            binder ?: InfoPanelArrivalTextBinder(this)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(viewGroup) }
}

internal fun NavigationViewContext.tripProgressComponent(
    tripProgressLayout: ViewGroup
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showTripProgress,
        uiBinders.infoPanelTripProgressBinder
    ) { show, binder ->
        if (show) {
            binder ?: TripProgressBinder(this)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(tripProgressLayout) }
}

internal fun NavigationViewContext.compassButtonComponent(
    buttonContainer: ViewGroup,
    @Px verticalSpacing: Int
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showCompassActionButton,
        uiBinders.actionCompassButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: CompassButtonBinder(this, verticalSpacing)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(buttonContainer) }
}

internal fun NavigationViewContext.cameraModeButtonComponent(
    buttonContainer: ViewGroup,
    @Px verticalSpacing: Int
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showCameraModeActionButton,
        uiBinders.actionCameraModeButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: CameraModeButtonBinder(this, verticalSpacing)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(buttonContainer) }
}

internal fun NavigationViewContext.audioGuidanceButtonComponent(
    buttonContainer: ViewGroup,
    @Px verticalSpacing: Int
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showToggleAudioActionButton,
        uiBinders.actionToggleAudioButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: AudioGuidanceButtonBinder(this, verticalSpacing)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(buttonContainer) }
}

internal fun NavigationViewContext.recenterButtonComponent(
    buttonContainer: ViewGroup,
    @Px verticalSpacing: Int
): MapboxNavigationObserver {
    val binderFlow = combine(
        options.showRecenterActionButton,
        uiBinders.actionRecenterButtonBinder
    ) { show, binder ->
        if (show) {
            binder ?: RecenterButtonBinder(this, verticalSpacing)
        } else {
            EmptyBinder()
        }
    }
    return reloadOnChange(binderFlow) { it.bind(buttonContainer) }
}

internal fun NavigationViewContext.analyticsComponent() =
    AnalyticsComponent()

internal fun NavigationViewContext.locationPermissionComponent(activity: ComponentActivity) =
    LocationPermissionComponent(activity, store)

internal fun NavigationViewContext.tripSessionComponent() =
    TripSessionComponent(lifecycleOwner.lifecycle, store)

internal fun NavigationViewContext.backPressedComponent(activity: ComponentActivity) =
    BackPressedComponent(activity.onBackPressedDispatcher, store, lifecycleOwner)

internal fun NavigationViewContext.mapLayoutCoordinator(
    binding: MapboxNavigationViewLayoutBinding
) = MapLayoutCoordinator(this, binding)

internal fun NavigationViewContext.scalebarPlaceholderCoordinator(scalebarLayout: ViewGroup) =
    ScalebarPlaceholderCoordinator(this, scalebarLayout)

internal fun NavigationViewContext.maneuverCoordinator(guidanceLayout: ViewGroup) =
    ManeuverCoordinator(this, guidanceLayout)

internal fun NavigationViewContext.infoPanelCoordinator(
    infoPanelLayout: ViewGroup,
    guidelineBottom: Guideline
) = InfoPanelCoordinator(this, infoPanelLayout, guidelineBottom)

internal fun NavigationViewContext.actionButtonsCoordinator(actionListLayout: ViewGroup) =
    ActionButtonsCoordinator(this, actionListLayout)

internal fun NavigationViewContext.speedLimitCoordinator(speedLimitLayout: ViewGroup) =
    SpeedLimitCoordinator(this, speedLimitLayout)

internal fun NavigationViewContext.roadNameCoordinator(roadNameLayout: ViewGroup) =
    RoadNameCoordinator(this, roadNameLayout)

internal fun NavigationViewContext.leftFrameCoordinator(emptyLeftContainer: ViewGroup) =
    LeftFrameCoordinator(this, emptyLeftContainer)

internal fun NavigationViewContext.rightFrameCoordinator(emptyRightContainer: ViewGroup) =
    RightFrameCoordinator(this, emptyRightContainer)
