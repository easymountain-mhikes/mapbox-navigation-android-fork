package com.mapbox.androidauto.navigation.speedlimit

import android.graphics.Rect
import android.location.Location
import androidx.annotation.VisibleForTesting
import com.mapbox.androidauto.MapboxCarContext
import com.mapbox.androidauto.MapboxCarOptions
import com.mapbox.androidauto.internal.RendererUtils
import com.mapbox.androidauto.internal.extensions.mapboxNavigationForward
import com.mapbox.androidauto.internal.logAndroidAuto
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.androidauto.MapboxCarMapObserver
import com.mapbox.maps.extension.androidauto.MapboxCarMapSurface
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.speed.model.SpeedLimitSign
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.roundToInt

/**
 * Create a speed limit sign. This class is demonstrating how to create a renderer.
 * To Create a new speed limit sign experience, try creating a new class.
 */
@OptIn(MapboxExperimental::class)
class CarSpeedLimitRenderer
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
    private val services: CarSpeedLimitServices,
    private val options: MapboxCarOptions,
) : MapboxCarMapObserver {

    /**
     * Public constructor and the internal constructor is for unit testing.
     */
    constructor(mapboxCarContext: MapboxCarContext) : this(
        CarSpeedLimitServices(),
        mapboxCarContext.options
    )

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var speedLimitWidget: SpeedLimitWidget? = null

    private val speedLimitState = MutableStateFlow(
        CarSpeedLimitState(
            isVisible = false,
            speedLimit = null,
            speed = null,
            signFormat = options.speedLimitOptions.value.forcedSignFormat,
            threshold = options.speedLimitOptions.value.warningThreshold
        )
    )

    private var distanceFormatterOptions: DistanceFormatterOptions? = null
    private val navigationObserver = mapboxNavigationForward(this::onAttached, this::onDetached)

    private val locationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            updateSpeed(locationMatcherResult)
        }

        override fun onNewRawLocation(rawLocation: Location) {
            // no op
        }
    }

    private lateinit var scope: CoroutineScope

    private fun onAttached(mapboxNavigation: MapboxNavigation) {
        distanceFormatterOptions = mapboxNavigation
            .navigationOptions.distanceFormatterOptions
        mapboxNavigation.registerLocationObserver(locationObserver)
    }

    private fun onDetached(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.unregisterLocationObserver(locationObserver)
        distanceFormatterOptions = null
    }

    private fun onSpeedLimitStateChange(state: CarSpeedLimitState) {
        logAndroidAuto("CarSpeedLimitRenderer onSpeedLimitStateChange $state")
        if (!state.isVisible) {
            speedLimitWidget?.updateBitmap(RendererUtils.EMPTY_BITMAP)
            return
        }

        if (state.speed != null) {
            speedLimitWidget?.update(
                state.speedLimit,
                state.speed,
                state.signFormat,
                state.threshold
            )
        } else {
            speedLimitWidget?.update(
                state.signFormat,
                state.threshold
            )
        }
    }

    private fun updateSpeed(locationMatcherResult: LocationMatcherResult) {
        logAndroidAuto("CarSpeedLimitRenderer updateSpeed $locationMatcherResult")
        val speedKmph =
            locationMatcherResult.enhancedLocation.speed / METERS_IN_KILOMETER * SECONDS_IN_HOUR
        val speedLimitOptions = options.speedLimitOptions.value
        val signFormat = speedLimitOptions.forcedSignFormat
            ?: locationMatcherResult.speedLimit?.speedLimitSign
        val threshold = speedLimitOptions.warningThreshold
        when (distanceFormatterOptions!!.unitType) {
            UnitType.IMPERIAL -> {
                val speedLimit =
                    locationMatcherResult.speedLimit?.speedKmph?.let { speedLimitKmph ->
                        5 * (speedLimitKmph / KILOMETERS_IN_MILE / 5).roundToInt()
                    }
                val speed = speedKmph / KILOMETERS_IN_MILE
                speedLimitState.value = speedLimitState.value.copy(
                    speedLimit = speedLimit,
                    speed = speed.roundToInt(),
                    signFormat = signFormat,
                    threshold = threshold
                )
            }
            UnitType.METRIC -> {
                val speedLimit = locationMatcherResult.speedLimit?.speedKmph
                speedLimitState.value = speedLimitState.value.copy(
                    speedLimit = speedLimit,
                    speed = speedKmph.roundToInt(),
                    signFormat = signFormat,
                    threshold = threshold
                )
            }
        }
    }

    override fun onAttached(mapboxCarMapSurface: MapboxCarMapSurface) {
        logAndroidAuto("CarSpeedLimitRenderer carMapSurface loaded")
        val signFormat = options.speedLimitOptions.value.forcedSignFormat
            ?: SpeedLimitSign.MUTCD
        val speedLimitWidget = services.speedLimitWidget(signFormat).also { speedLimitWidget = it }
        mapboxCarMapSurface.mapSurface.addWidget(speedLimitWidget)
        MapboxNavigationApp.registerObserver(navigationObserver)
        scope = MainScope()
        options.speedLimitOptions
            .onEach {
                speedLimitState.value = speedLimitState.value.copy(
                    speedLimit = null,
                    speed = null,
                    signFormat = options.speedLimitOptions.value.forcedSignFormat,
                    threshold = options.speedLimitOptions.value.warningThreshold
                )
            }
            .launchIn(scope)
        speedLimitState
            .onEach { onSpeedLimitStateChange(it) }
            .launchIn(scope)
    }

    override fun onDetached(mapboxCarMapSurface: MapboxCarMapSurface) {
        logAndroidAuto("CarSpeedLimitRenderer carMapSurface detached")
        MapboxNavigationApp.unregisterObserver(navigationObserver)
        speedLimitWidget?.let { mapboxCarMapSurface.mapSurface.removeWidget(it) }
        speedLimitWidget = null
        scope.cancel()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect, edgeInsets: EdgeInsets) {
        logAndroidAuto("CarSpeedLimitRenderer onVisibleAreaChanged $visibleArea $edgeInsets")
        speedLimitState.value = speedLimitState.value.copy(isVisible = edgeInsets.right == 0.0)
    }

    private companion object {
        private const val METERS_IN_KILOMETER = 1000.0
        private const val KILOMETERS_IN_MILE = 1.609
        private const val SECONDS_IN_HOUR = 3600
    }
}

private data class CarSpeedLimitState(
    val isVisible: Boolean,
    val speedLimit: Int?,
    val speed: Int?,
    val signFormat: SpeedLimitSign?,
    val threshold: Int
)
