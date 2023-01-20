package com.mapbox.navigation.qa_test_app.view

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.transition.Fade
import androidx.transition.Scene
import androidx.transition.TransitionManager
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.qa_test_app.R
import com.mapbox.navigation.qa_test_app.databinding.DemoActivityLayoutBinding
import com.mapbox.navigation.qa_test_app.databinding.MyCustomViewLayoutBinding
import com.mapbox.navigation.ui.base.lifecycle.UIBinder
import com.mapbox.navigation.ui.base.lifecycle.UIComponent

class DemoActivity : AppCompatActivity() {

    private val viewBinding: DemoActivityLayoutBinding by lazy {
        DemoActivityLayoutBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        viewBinding.navigationView.api.routeReplayEnabled(true)

        viewBinding.navigationView.customizeViewBinders {
            infoPanelContentBinder = MyInfoPanelContentBinder()
        }
    }
}

class MyInfoPanelContentComponent(private val binding: MyCustomViewLayoutBinding) : UIComponent() {

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        super.onAttached(mapboxNavigation)

        mapboxNavigation.registerArrivalObserver(object: ArrivalObserver {
            override fun onWaypointArrival(routeProgress: RouteProgress) {}

            override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}

            override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
                binding.mainLayout.visibility = View.VISIBLE

            }
        })
    }
}

class MyInfoPanelContentBinder : UIBinder {
    override fun bind(viewGroup: ViewGroup): MapboxNavigationObserver {
        val scene = Scene.getSceneForLayout(
            viewGroup,
            R.layout.my_custom_view_layout,
            viewGroup.context
        )
        TransitionManager.go(scene, Fade())

        val binding = MyCustomViewLayoutBinding.bind(viewGroup)
        return MyInfoPanelContentComponent(binding)
    }
}
