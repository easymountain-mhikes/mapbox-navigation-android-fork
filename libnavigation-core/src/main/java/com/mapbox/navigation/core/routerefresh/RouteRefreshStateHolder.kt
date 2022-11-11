package com.mapbox.navigation.core.routerefresh

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import java.util.concurrent.CopyOnWriteArraySet

@ExperimentalPreviewMapboxNavigationAPI
internal class RouteRefreshStateHolder : RouteRefreshProgressObserver {

    private val observers = CopyOnWriteArraySet<RouteRefreshStatesObserver>()

    private var state: RouteRefreshStateResult? = null

    override fun onStarted() {
        onNewState(RouteRefreshExtra.REFRESH_STATE_STARTED)
    }

    override fun onSuccess() {
        onNewState(RouteRefreshExtra.REFRESH_STATE_FINISHED_SUCCESS)
    }

    override fun onFailure(message: String?) {
        onNewState(RouteRefreshExtra.REFRESH_STATE_FINISHED_FAILED, message)
    }

    override fun onCancel() {
        onNewState(RouteRefreshExtra.REFRESH_STATE_CANCELED)
    }

    fun reset() {
        onNewState(null)
    }

    fun registerRouteRefreshStateObserver(observer: RouteRefreshStatesObserver) {
        observers.add(observer)
        state?.let { observer.onNewState(it) }
    }

    fun unregisterRouteRefreshStateObserver(
        observer: RouteRefreshStatesObserver
    ) {
        observers.remove(observer)
    }

    fun unregisterAllRouteRefreshStateObservers() {
        observers.clear()
    }

    private fun onNewState(
        @RouteRefreshExtra.RouteRefreshState state: String?,
        message: String? = null
    ) {
        val oldState = this.state?.state
        if (oldState != state && RouteRefreshStateChanger.canChange(from = oldState, to = state)) {
            val newState = state?.let { RouteRefreshStateResult(it, message) }
            this.state = newState
            if (newState != null) {
                observers.forEach { it.onNewState(newState) }
            }
        }
    }
}
