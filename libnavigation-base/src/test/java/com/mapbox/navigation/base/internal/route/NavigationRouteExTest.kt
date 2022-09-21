package com.mapbox.navigation.base.internal.route

import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.Closure
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.Incident
import com.mapbox.api.directions.v5.models.LegAnnotation
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.testing.FileUtils
import com.mapbox.navigation.testing.factories.TestSDKRouteParser
import com.mapbox.navigation.testing.factories.createRouteLegAnnotation
import com.mapbox.navigator.RouterOrigin
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import junit.framework.Assert.assertEquals
import org.junit.Test
import java.net.URL

class NavigationRouteExTest {

    @Test
    fun `update Navigation route`() {
        val navigationRoute = provideNavigationRoute(addLeg = true, distance = 88.0)
        val updated = navigationRoute.updateDirectionsRouteOnly {
            assertEquals(88.0, distance())
            toBuilder().distance(73483.0).build()
        }
        assertEquals(73483.0, updated.directionsRoute.distance())
    }

    @Test
    fun `route refresh updates route durations`() {
        val sourceRoute = createNavigationRouteFromResource(
            "3-steps-route-directions-response.json",
            "3-steps-route-directions-request-url.txt"
        )

        val refreshedRoute = sourceRoute.refreshRoute(
            initialLegIndex = 0,
            currentLegGeometryIndex = 0,
            legAnnotations = listOf(
                createRouteLegAnnotation(
                    duration = listOf(
                        4.548,
                        4.555,
                        4.512,
                        3.841,
                        15.415,
                        1.507,
                        6.359
                    )
                )
            ),
            incidents = null,
            closures = null
        )

        assertEquals(
            40.736999999999995,
            refreshedRoute.directionsRoute.duration(),
            0.00001
        )
        val firstLeg = refreshedRoute.directionsRoute.legs()!!.first()!!
        assertEquals(
            40.736999999999995,
            firstLeg.duration() ?: -1.0,
            0.00001
        )
        val steps = firstLeg.steps()!!
        assertEquals(
            34.37799999999999,
            steps[0].duration(),
            0.00001
        )
        assertEquals(
            6.359,
            steps[1].duration(),
            0.00001
        )
        assertEquals(
            0.0,
            steps[2].duration(),
            0.00001
        )
    }

    @Test
    fun `route refresh without duration annotation doesn't affect durations`() {
        val sourceRoute = createNavigationRouteFromResource(
            "3-steps-route-directions-response.json",
            "3-steps-route-directions-request-url.txt"
        )
            .updateDirectionsRouteOnly {
                toBuilder()
                    .legs(
                        legs()?.map {
                            it.toBuilder()
                                .annotation(
                                    it.annotation()
                                        ?.toBuilder()
                                        ?.duration(null)
                                        ?.congestionNumeric(MutableList(7) { 1 })
                                        ?.build()
                                )
                                .build()
                        }
                    )
                    .routeOptions(
                        routeOptions()?.toBuilder()
                            ?.annotations(DirectionsCriteria.ANNOTATION_CONGESTION)
                            ?.build()
                    )
                    .build()
            }

        val refreshedRoute = sourceRoute.refreshRoute(
            initialLegIndex = 0,
            currentLegGeometryIndex = 0,
            legAnnotations = listOf(
                createRouteLegAnnotation(
                    congestionNumeric = MutableList(7) { 2 }
                )
            ),
            incidents = null,
            closures = null
        )

        // compare durations with original values from json file
        assertEquals(
            41.882,
            refreshedRoute.directionsRoute.duration(),
            0.00001
        )
        val firstLeg = refreshedRoute.directionsRoute.legs()!!.first()!!
        assertEquals(
            41.882,
            firstLeg.duration()!!,
            0.00001
        )
        val steps = firstLeg.steps()!!
        assertEquals(
            34.341,
            steps[0].duration(),
            0.00001
        )
        assertEquals(
            7.541,
            steps[1].duration(),
            0.00001
        )
        assertEquals(
            0.0,
            steps[2].duration(),
            0.00001
        )
    }

    @Test
    fun `route refresh refreshed durations on second leg`() {
        // uses polyline instead of polyline6
        val sourceRoute = createNavigationRouteFromResource(
            "6-steps-3-waypoints-directions-response.json",
            "6-steps-3-waypoints-directions-request-url.txt"
        )

        val refreshedRoute = sourceRoute.refreshRoute(
            initialLegIndex = 1,
            currentLegGeometryIndex = 0,
            legAnnotations = listOf(
                LegAnnotation.builder().build(),
                createRouteLegAnnotation(
                    duration = MutableList(4) { 1.0 }
                )
            ),
            incidents = null,
            closures = null
        )

        assertEquals(
            45.882,
            refreshedRoute.directionsRoute.duration(),
            0.00001
        )
        val secondLeg = refreshedRoute.directionsRoute.legs()!![1]
        assertEquals(
            4.0,
            secondLeg.duration()!!,
            0.00001
        )
        val steps = secondLeg.steps()!!
        assertEquals(
            1.0,
            steps[0].duration(),
            0.00001
        )
        assertEquals(
            3.0,
            steps[1].duration(),
            0.00001
        )
        assertEquals(
            0.0,
            steps[2].duration(),
            0.00001
        )
    }

    @Test
    fun `extension NavigationRoute refreshRoute`() {
        listOf(
            TestData(
                "update to null items",
                provideNavigationRoute(addLeg = false),
                RefreshLegItemsWrapper(0, listOf(null), listOf(null), null, null),
                LegItemsResult(
                    listOf(null),
                    listOf(null),
                    listOf(null),
                    0,
                )
            ),
            TestData(
                "update to null items multi-leg route",
                provideNavigationRoute(addLeg = true),
                RefreshLegItemsWrapper(
                    0, listOf(null, null), listOf(null, null), null, null
                ),
                LegItemsResult(
                    listOf(null, null),
                    listOf(null, null),
                    listOf(null, null),
                    0
                )
            ),
            TestData(
                "update to null items multi-leg route starting with second leg",
                provideNavigationRoute(addLeg = true),
                RefreshLegItemsWrapper(
                    1,
                    listOf(null, null),
                    listOf(null, null),
                    null,
                    null,
                ),
                LegItemsResult(
                    listOf(provideDefaultLegAnnotation(), null),
                    listOf(provideDefaultIncidents(), null),
                    listOf(provideDefaultClosures(), null),
                    0
                ),
            ),

            run {
                val newLegAnnotations = createRouteLegAnnotation()
                val newIncidents = listOf(createIncident(0, 1))
                val newClosures = listOf(createClosure(10, 15))
                return@run TestData(
                    "update items route",
                    provideNavigationRoute(addLeg = false),
                    RefreshLegItemsWrapper(
                        0,
                        listOf(newLegAnnotations),
                        listOf(newIncidents),
                        listOf(newClosures),
                        null,
                    ),
                    LegItemsResult(
                        listOf(newLegAnnotations),
                        listOf(newIncidents),
                        listOf(newClosures),
                        0,
                    )
                )
            },
            run {
                val newLegAnnotations = createRouteLegAnnotation()
                val newLegAnnotations2 = createRouteLegAnnotation()
                val newIncidents = listOf(createIncident(0, 1), createIncident(10, 15))
                val newIncidents2 = listOf(createIncident(0, 1), createIncident(5, 7))
                val newClosures = listOf(createClosure(0, 3), createClosure(6, 7))
                val newClosures2 = listOf(createClosure(4, 7), createClosure(14, 17))
                TestData(
                    "update items multi-leg route",
                    provideNavigationRoute(addLeg = true),
                    RefreshLegItemsWrapper(
                        0,
                        listOf(newLegAnnotations, newLegAnnotations2),
                        listOf(newIncidents, newIncidents2),
                        listOf(newClosures, newClosures2),
                        null,
                    ),
                    LegItemsResult(
                        listOf(newLegAnnotations, newLegAnnotations2),
                        listOf(newIncidents, newIncidents2),
                        listOf(newClosures, newClosures2),
                        0,
                    )
                )
            },
            run {
                val newLegAnnotations = createRouteLegAnnotation()
                val newLegAnnotations2 = createRouteLegAnnotation()
                val newInputIncidents = listOf(createIncident(2, 4))
                val newOutputIncidents = listOf(createIncident(4, 6))
                val newInputIncidents2 = listOf(createIncident(6, 9))
                val newOutputIncidents2 = listOf(createIncident(6, 9))
                val newInputClosures = listOf(createClosure(3, 4))
                val newOutputClosures = listOf(createClosure(5, 6))
                val newInputClosures2 = listOf(createClosure(1, 2))
                val newOutputClosures2 = listOf(createClosure(1, 2))
                TestData(
                    "update items multi-leg route, geometryIndex is 2",
                    provideNavigationRoute(addLeg = true),
                    RefreshLegItemsWrapper(
                        0,
                        listOf(newLegAnnotations, newLegAnnotations2),
                        listOf(newInputIncidents, newInputIncidents2),
                        listOf(newInputClosures, newInputClosures2),
                        2,
                    ),
                    LegItemsResult(
                        listOf(newLegAnnotations, newLegAnnotations2),
                        listOf(newOutputIncidents, newOutputIncidents2),
                        listOf(newOutputClosures, newOutputClosures2),
                        2,
                    )
                )
            },
            run {
                val newLegAnnotations = createRouteLegAnnotation()
                val newLegAnnotations2 = createRouteLegAnnotation()
                val newIncidents = listOf(createIncident(10, 12))
                val newIncidents2 = listOf(createIncident(40, 50))
                val newClosures = listOf(createClosure(13, 17))
                val newClosures2 = listOf(createClosure(2, 6))
                TestData(
                    "update items multi-leg route starting with second leg",
                    provideNavigationRoute(addLeg = true),
                    RefreshLegItemsWrapper(
                        1,
                        listOf(newLegAnnotations, newLegAnnotations2),
                        listOf(newIncidents, newIncidents2),
                        listOf(newClosures, newClosures2),
                        null,
                    ),
                    LegItemsResult(
                        listOf(provideDefaultLegAnnotation(), newLegAnnotations2),
                        listOf(provideDefaultIncidents(), newIncidents2),
                        listOf(provideDefaultClosures(), newClosures2),
                        0
                    )
                )
            },
            run {
                val newLegAnnotations = createRouteLegAnnotation()
                val newLegAnnotations2 = createRouteLegAnnotation()
                val newIncidents = listOf(createIncident(10, 12))
                val newInputIncidents2 = listOf(createIncident(40, 50))
                val newOutputIncidents2 = listOf(createIncident(44, 54))
                val newClosures = listOf(createClosure(13, 17))
                val newInputClosures2 = listOf(createClosure(2, 6))
                val newOutputClosures2 = listOf(createClosure(6, 10))
                TestData(
                    "update items multi-leg route starting with second leg, geometryIndex = 4",
                    provideNavigationRoute(addLeg = true),
                    RefreshLegItemsWrapper(
                        1,
                        listOf(newLegAnnotations, newLegAnnotations2),
                        listOf(newIncidents, newInputIncidents2),
                        listOf(newClosures, newInputClosures2),
                        4,
                    ),
                    LegItemsResult(
                        listOf(provideDefaultLegAnnotation(), newLegAnnotations2),
                        listOf(provideDefaultIncidents(), newOutputIncidents2),
                        listOf(provideDefaultClosures(), newOutputClosures2),
                        4,
                    )
                )
            },
        ).forEach { (description, navRoute, refreshItems, result) ->
            mockkObject(AnnotationsRefresher) {
                every {
                    AnnotationsRefresher.getRefreshedAnnotations(any(), any(), any())
                } returnsMany
                    (result.newLegAnnotation?.drop(refreshItems.startWithIndex) ?: emptyList())
                val updatedNavRoute = try {
                    navRoute.refreshRoute(
                        refreshItems.startWithIndex,
                        refreshItems.legGeometryIndex,
                        refreshItems.legAnnotation,
                        refreshItems.incidents,
                        refreshItems.closures,
                    )
                } catch (t: Throwable) {
                    throw Throwable("unhandled exception in $description", t)
                }

                assertEquals(
                    description,
                    result.newLegAnnotation,
                    updatedNavRoute.directionsRoute
                        .legs()
                        ?.map { it.annotation() },
                )
                assertEquals(
                    description,
                    result.newIncidents,
                    updatedNavRoute.directionsRoute
                        .legs()
                        ?.map { it.incidents() },
                )
                assertEquals(
                    description,
                    result.newClosures,
                    updatedNavRoute.directionsRoute
                        .legs()
                        ?.map { it.closures() },
                )

                val capturedOldAnnotations = mutableListOf<LegAnnotation?>()
                val capturedNewAnnotations = mutableListOf<LegAnnotation?>()
                val capturedLegGeometryIndices = mutableListOf<Int>()
                verify {
                    AnnotationsRefresher.getRefreshedAnnotations(
                        captureNullable(capturedOldAnnotations),
                        captureNullable(capturedNewAnnotations),
                        capture(capturedLegGeometryIndices)
                    )
                }
                assertEquals(
                    description,
                    navRoute.directionsRoute.legs()
                        ?.drop(refreshItems.startWithIndex)
                        ?.map { it.annotation() },
                    capturedOldAnnotations
                )
                assertEquals(
                    description,
                    refreshItems.legAnnotation?.drop(refreshItems.startWithIndex),
                    capturedNewAnnotations
                )
                assertEquals(
                    description,
                    listOf(result.expectedLegGeometryIndex) +
                        List(capturedLegGeometryIndices.size - 1) { 0 },
                    capturedLegGeometryIndices
                )
            }
        }
    }

    private fun provideNavigationRoute(
        annotations: LegAnnotation? = provideDefaultLegAnnotation(),
        incidents: List<Incident>? = provideDefaultIncidents(),
        closures: List<Closure>? = provideDefaultClosures(),
        addLeg: Boolean,
        distance: Double = 10.0,
    ): NavigationRoute {
        val twoPointGeometry = PolylineUtils.encode(
            listOf(
                Point.fromLngLat(1.2, 3.4),
                Point.fromLngLat(3.3, 6.7)
            ),
            5
        )
        val validStep = mockk<LegStep>(relaxed = true) {
            every { geometry() } returns twoPointGeometry
        }
        return NavigationRoute(
            DirectionsResponse.builder()
                .routes(
                    listOf(
                        DirectionsRoute.builder()
                            .duration(10.0)
                            .distance(distance)
                            .legs(
                                mutableListOf(
                                    RouteLeg.builder()
                                        .annotation(annotations)
                                        .incidents(incidents)
                                        .closures(closures)
                                        .steps(List(2) { validStep })
                                        .build()
                                ).apply {
                                    if (addLeg) {
                                        add(
                                            RouteLeg.builder()
                                                .annotation(annotations)
                                                .incidents(incidents)
                                                .closures(closures)
                                                .steps(List(2) { validStep })
                                                .build()
                                        )
                                    }
                                }
                            )
                            .geometry(
                                PolylineUtils.encode(
                                    listOf(
                                        Point.fromLngLat(11.22, 33.44),
                                        Point.fromLngLat(23.34, 34.45)
                                    ),
                                    5
                                )
                            )
                            .build()
                    )
                )
                .code("Ok")
                .build(),
            0,
            mockk {
                every { geometries() } returns DirectionsCriteria.GEOMETRY_POLYLINE
            },
            mockk {
                every { routeInfo } returns mockk(relaxed = true)
                every { routeId } returns ""
                every { routerOrigin } returns RouterOrigin.ONLINE
            }
        )
    }

    private fun provideDefaultLegAnnotation(): LegAnnotation = LegAnnotation.builder()
        .congestion(listOf("congestion1, congestion2"))
        .distance(listOf(0.0, 0.1))
        .build()

    private fun provideDefaultIncidents(): List<Incident> = listOf(
        Incident.builder()
            .id("0")
            .description("description1")
            .build(),
        Incident.builder()
            .id("2")
            .description("description2")
            .build(),
    )

    private fun provideDefaultClosures(): List<Closure> = listOf(
        Closure.builder()
            .geometryIndexStart(0)
            .geometryIndexEnd(5)
            .build(),
        Closure.builder()
            .geometryIndexStart(10)
            .geometryIndexEnd(12)
            .build(),
    )

    /**
     * Wrapper of test case
     *
     * @param testDescription short description of what is tested
     * @param navigationRoute initial navigation route that will be refreshed
     * @param refreshLegItemsWrapper refreshed data, which should be apply to the route
     * @param legResult result items of refreshed route
     */
    private data class TestData(
        val testDescription: String,
        val navigationRoute: NavigationRoute,
        val refreshLegItemsWrapper: RefreshLegItemsWrapper,
        val legResult: LegItemsResult,
    )

    /**
     * Items wrapper that needs to refresh route
     */
    private data class RefreshLegItemsWrapper(
        val startWithIndex: Int,
        val legAnnotation: List<LegAnnotation?>?,
        val incidents: List<List<Incident>?>?,
        val closures: List<List<Closure>?>?,
        val legGeometryIndex: Int?,
    )

    /**
     * Expecting result items after route is refreshed
     */
    private data class LegItemsResult(
        val newLegAnnotation: List<LegAnnotation?>?,
        val newIncidents: List<List<Incident>?>?,
        val newClosures: List<List<Closure>?>?,
        val expectedLegGeometryIndex: Int,
    )
}

private fun createNavigationRouteFromResource(
    responseFileName: String,
    requestFileName: String
) = createNavigationRoutes(
    DirectionsResponse.fromJson(
        FileUtils.loadJsonFixture(responseFileName)
    ),
    RouteOptions.fromUrl(
        URL(
            FileUtils.loadJsonFixture(requestFileName)
        )
    ),
    TestSDKRouteParser(),
    com.mapbox.navigation.base.route.RouterOrigin.Offboard
).first()

private fun createIncident(startIndex: Int?, endIndex: Int?): Incident =
    Incident.builder()
        .id("id")
        .geometryIndexStart(startIndex)
        .geometryIndexEnd(endIndex)
        .build()

private fun createClosure(startIndex: Int?, endIndex: Int?): Closure =
    Closure.builder()
        .apply {
            startIndex?.let { geometryIndexStart(it) }
            endIndex?.let { geometryIndexEnd(it) }
        }
        .build()
