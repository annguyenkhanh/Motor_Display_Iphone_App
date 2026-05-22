package com.motordisplay.model

data class RoutePoint(
    val lat: Double,
    val lon: Double
)

data class RouteInfo(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val instruction: String
)