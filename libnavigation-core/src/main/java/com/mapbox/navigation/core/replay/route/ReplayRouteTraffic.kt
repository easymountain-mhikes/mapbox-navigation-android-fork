package com.mapbox.navigation.core.replay.route

import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.navigation.utils.internal.logW
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import kotlin.math.abs

internal class ReplayRouteTraffic {

    private val replayRouteSmoother = ReplayRouteSmoother()

    /**
     * Take a [RouteLeg] and convert the geometry into a list of points where all of
     * coordinates are unique. The distinct points are meant to clean the route of
     * any duplicates, making downstream algorithms simpler.
     */
    fun mapToDistinctRoutePoints(routeLeg: RouteLeg): List<Point> {
        val legSteps = routeLeg.steps() ?: return emptyList()
        return legSteps.map { it.geometry() ?: "" }
            .flatMap { geometry -> PolylineUtils.decode(geometry, 6) }
            .let { points -> replayRouteSmoother.distinctPoints(points) }
    }

    /**
     * Given a list of distinct points, and two equal size lists of distances and speed; find
     * the distinct points that correlate to the distances. The [ReplayRouteLocation.routeIndex]
     * will be the index of the [distinctRoutePoints]. The [ReplayRouteDriver] uses these indices
     * to interpolate the speed.
     */
    fun trafficLocations(
        distinctRoutePoints: List<Point>,
        distances: List<Double>,
        speeds: List<Double>
    ): List<ReplayRouteLocation> {
        return findTrafficLocations(distinctRoutePoints, distances, speeds)
    }

    private fun findTrafficLocations(
        points: List<Point>,
        distances: List<Double>,
        speeds: List<Double>
    ): List<ReplayRouteLocation> {
        val trafficLocations = mutableListOf<ReplayRouteLocation>()
        var segmentDistance = 0.0
        for ((annotationIndex, routeIndex) in (1..points.lastIndex).withIndex()) {
            segmentDistance += stepDistance(points, routeIndex)
            val trafficDistance = distances[annotationIndex]

            val trafficRouteDistance = abs(segmentDistance - trafficDistance)
            val isFarFromTrafficLocation = trafficRouteDistance > LOCATION_DISTANCE_THRESHOLD
            if (isFarFromTrafficLocation) {
                logW("The traffic distance is far from the route: $trafficRouteDistance")
            }

            val trafficSpeed = speeds[annotationIndex]
            val trafficLocation = ReplayRouteLocation(
                routeIndex = routeIndex,
                point = points[routeIndex]
            )
            trafficLocation.speedMps = trafficSpeed
            trafficLocation.distance = trafficDistance
            trafficLocations.add(trafficLocation)
            segmentDistance = 0.0
        }

        return trafficLocations
    }

    private fun stepDistance(points: List<Point>, index: Int): Double {
        val previousPoint = points[index - 1]
        val currentPoint = points[index]
        return TurfMeasurement.distance(previousPoint, currentPoint, TurfConstants.UNIT_METERS)
    }

    private companion object {
        private const val LOCATION_DISTANCE_THRESHOLD = 1.5
    }
}
