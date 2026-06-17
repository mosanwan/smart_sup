package com.smartsup.controller.control

import android.content.Context
import com.smartsup.controller.model.NavigationRoute
import com.smartsup.controller.model.NavigationRoutePoint
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class AutoNavigationRouteStore(context: Context) {
    private val routeDir = File(context.filesDir, "navigation_routes")
    private val routeFile = File(routeDir, "routes.json")

    init {
        routeDir.mkdirs()
        if (!routeFile.exists()) {
            writeRoutes(emptyList())
        }
    }

    fun readRoutes(): List<NavigationRoute> {
        if (!routeFile.exists()) {
            return emptyList()
        }
        return runCatching {
            val root = JSONObject(routeFile.readText())
            val routes = root.optJSONArray("routes") ?: JSONArray()
            buildList {
                for (index in 0 until routes.length()) {
                    parseRoute(routes.optJSONObject(index))?.let(::add)
                }
            }.sortedByDescending { it.updatedAtEpochSeconds }
        }.getOrDefault(emptyList())
    }

    fun upsertRoute(route: NavigationRoute) {
        val routes = readRoutes()
            .filterNot { it.id == route.id }
            .plus(route)
            .sortedByDescending { it.updatedAtEpochSeconds }
        writeRoutes(routes)
    }

    fun deleteRoute(routeId: String): Boolean {
        val routes = readRoutes()
        val remaining = routes.filterNot { it.id == routeId }
        if (remaining.size == routes.size) {
            return false
        }
        writeRoutes(remaining)
        return true
    }

    private fun writeRoutes(routes: List<NavigationRoute>) {
        routeDir.mkdirs()
        val root = JSONObject()
        val routeArray = JSONArray()
        routes.forEach { route ->
            val points = JSONArray()
            route.points.forEach { point ->
                points.put(
                    JSONObject()
                        .put("lat", point.latitude)
                        .put("lon", point.longitude),
                )
            }
            routeArray.put(
                JSONObject()
                    .put("id", route.id)
                    .put("name", route.name)
                    .put("created_at", route.createdAtEpochSeconds)
                    .put("updated_at", route.updatedAtEpochSeconds)
                    .put("points", points),
            )
        }
        root.put("routes", routeArray)
        routeFile.writeText(root.toString(2))
    }

    private fun parseRoute(json: JSONObject?): NavigationRoute? {
        json ?: return null
        val pointsJson = json.optJSONArray("points") ?: JSONArray()
        val points = buildList {
            for (index in 0 until pointsJson.length()) {
                val point = pointsJson.optJSONObject(index) ?: continue
                add(
                    NavigationRoutePoint(
                        latitude = point.optDouble("lat"),
                        longitude = point.optDouble("lon"),
                    ),
                )
            }
        }
        return NavigationRoute(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: return null,
            name = json.optString("name").takeIf { it.isNotBlank() } ?: "未命名路线",
            createdAtEpochSeconds = json.optLong("created_at"),
            updatedAtEpochSeconds = json.optLong("updated_at"),
            points = points,
        )
    }
}
