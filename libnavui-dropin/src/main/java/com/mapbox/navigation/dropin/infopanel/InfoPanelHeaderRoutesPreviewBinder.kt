package com.mapbox.navigation.dropin.infopanel

import android.transition.Scene
import android.transition.TransitionManager
import android.view.ViewGroup
import com.mapbox.navigation.core.internal.extensions.navigationListOf
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.dropin.R
import com.mapbox.navigation.dropin.databinding.MapboxInfoPanelHeaderRoutePreviewLayoutBinding
import com.mapbox.navigation.dropin.internal.extensions.startNavigationButtonComponent
import com.mapbox.navigation.dropin.internal.extensions.tripProgressComponent
import com.mapbox.navigation.dropin.navigationview.NavigationViewContext
import com.mapbox.navigation.ui.base.lifecycle.UIBinder

internal class InfoPanelHeaderRoutesPreviewBinder(
    private val context: NavigationViewContext
) : UIBinder {

    override fun bind(viewGroup: ViewGroup): MapboxNavigationObserver {
        val scene = Scene.getSceneForLayout(
            viewGroup,
            R.layout.mapbox_info_panel_header_route_preview_layout,
            viewGroup.context
        )
        TransitionManager.go(scene)
        val binding = MapboxInfoPanelHeaderRoutePreviewLayoutBinding.bind(viewGroup)

        return context.run {
            navigationListOf(
                tripProgressComponent(binding.tripProgressLayout),
                startNavigationButtonComponent(binding.startNavigationContainer)
            )
        }
    }
}
