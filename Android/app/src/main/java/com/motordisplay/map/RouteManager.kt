package com.motordisplay.map

import com.motordisplay.model.RouteInfo
import com.motordisplay.model.RoutePoint
import com.motordisplay.util.PolylineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RouteManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun getRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): RouteInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                    "$originLon,$originLat;$destLon,$destLat" +
                    "?overview=full&geometries=polyline&steps=true"

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return@withContext null

            val routes = root.getJSONArray("routes")
            if (routes.length() == 0) return@withContext null

            val route = routes.getJSONObject(0)
            val geometry = route.getString("geometry")
            val distance = route.getDouble("distance")
            val duration = route.getDouble("duration")

            var instruction = "Đi thẳng"
            val legs = route.optJSONArray("legs")
            if (legs != null && legs.length() > 0) {
                val steps = legs.getJSONObject(0).optJSONArray("steps")
                if (steps != null && steps.length() > 0) {
                    val maneuver = steps.getJSONObject(0).optJSONObject("maneuver")
                    val type = maneuver?.optString("type") ?: ""
                    instruction = when (type) {
                        "turn" -> "Rẽ"
                        "new name" -> "Đi tiếp"
                        "depart" -> "Xuất phát"
                        "arrive" -> "Đến nơi"
                        "fork" -> "Rẽ nhánh"
                        "roundabout" -> "Vòng xoay"
                        else -> "Đi thẳng"
                    }
                }
            }

            val decoded = PolylineUtils.decode(geometry)
            val points = decoded.map { RoutePoint(it.first, it.second) }

            RouteInfo(
                points = simplify(points, 120),
                distanceMeters = distance,
                durationSeconds = duration,
                instruction = instruction
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun simplify(points: List<RoutePoint>, maxPoints: Int): List<RoutePoint> {
        if (points.size <= maxPoints) return points
        val step = points.size.toDouble() / maxPoints.toDouble()
        val out = mutableListOf<RoutePoint>()
        var i = 0.0
        while (i < points.size) {
            out.add(points[i.toInt()])
            i += step
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }
}