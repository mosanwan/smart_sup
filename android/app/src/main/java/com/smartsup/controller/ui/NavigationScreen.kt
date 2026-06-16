package com.smartsup.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.GpsTrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NavigationScreen(
    state: ControlUiState,
    modifier: Modifier = Modifier,
    onSyncTrack: () -> Unit,
    onPlaybackIndexChange: (Int) -> Unit,
) {
    val gpsTrack = state.gpsTrack
    val liveLocation = state.telemetry.liveLatLng()
    val trackPoints = remember(gpsTrack.recentPoints) {
        gpsTrack.recentPoints.map { LatLng(it.latitude, it.longitude) }
    }
    val playbackPoint = gpsTrack.recentPoints.getOrNull(gpsTrack.playbackIndex)
    val mapTarget = liveLocation ?: playbackPoint?.let { LatLng(it.latitude, it.longitude) } ?: trackPoints.lastOrNull()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        if (BuildConfig.MAPTILER_API_KEY_CONFIGURED) {
            MapLibreTrackMap(
                liveLocation = liveLocation,
                trackPoints = trackPoints,
                playbackPoint = playbackPoint,
                mapTarget = mapTarget,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MapUnavailableLayer(modifier = Modifier.fillMaxSize())
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LocationStatusOverlay(
                fixText = state.telemetry.gpsFixText(),
                satelliteText = state.telemetry.statusFields["GPS_SAT"]?.let { "$it 颗星" } ?: "--",
                locationText = liveLocation?.formatLatLng() ?: "暂无坐标",
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = onSyncTrack,
                enabled = !gpsTrack.syncing,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(imageVector = Icons.Outlined.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (gpsTrack.syncing) "同步中" else "同步")
            }
        }

        TrackPlaybackOverlay(
            storedPointCount = gpsTrack.storedPointCount,
            esp32CacheText = gpsTrack.latestInfo?.let { "${it.count}/${it.capacity}" } ?: "--",
            syncMessage = gpsTrack.syncMessage,
            points = gpsTrack.recentPoints,
            playbackPoint = playbackPoint,
            playbackIndex = gpsTrack.playbackIndex,
            onPlaybackIndexChange = onPlaybackIndexChange,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }
}

@Composable
private fun MapUnavailableLayer(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 2.dp,
        ) {
            Text(
                text = "未配置 MAPTILER_API_KEY，地图暂不可用",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun LocationStatusOverlay(
    fixText: String,
    satelliteText: String,
    locationText: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("GPS $fixText", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "$satelliteText · $locationText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackPlaybackOverlay(
    storedPointCount: Int,
    esp32CacheText: String,
    syncMessage: String,
    points: List<GpsTrackPoint>,
    playbackPoint: GpsTrackPoint?,
    playbackIndex: Int,
    onPlaybackIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("轨迹回放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${storedPointCount} 点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val maxIndex = (points.size - 1).coerceAtLeast(0)
            val sliderEnd = if (maxIndex > 0) maxIndex.toFloat() else 1f
            Slider(
                value = playbackIndex.coerceIn(0, maxIndex).toFloat(),
                onValueChange = { onPlaybackIndexChange(it.toInt()) },
                valueRange = 0f..sliderEnd,
                enabled = points.size > 1,
                steps = 0,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CompactMetric("ESP32", esp32CacheText)
                CompactMetric("同步", syncMessage)
                CompactMetric("回放", if (points.isEmpty()) "--" else "${playbackIndex + 1}/${points.size}")
            }
            Text(
                playbackPoint?.let { "${it.localTimeText()} · ${it.formatLatLng()}" } ?: "暂无回放点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MapLibreTrackMap(
    liveLocation: LatLng?,
    trackPoints: List<LatLng>,
    playbackPoint: GpsTrackPoint?,
    mapTarget: LatLng?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pathColor = MaterialTheme.colorScheme.primary.toArgb()
    val styleUrl = remember(BuildConfig.MAPTILER_API_KEY) {
        "https://api.maptiler.com/maps/hybrid/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
        }
    }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(liveLocation, trackPoints, playbackPoint, mapTarget, pathColor, styleUrl) {
        mapView.getMapAsync { map ->
            renderTrackMap(
                map = map,
                styleUrl = styleUrl,
                liveLocation = liveLocation,
                trackPoints = trackPoints,
                playbackPoint = playbackPoint,
                mapTarget = mapTarget,
                pathColor = pathColor,
            )
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}

private fun renderTrackMap(
    map: MapLibreMap,
    styleUrl: String,
    liveLocation: LatLng?,
    trackPoints: List<LatLng>,
    playbackPoint: GpsTrackPoint?,
    mapTarget: LatLng?,
    pathColor: Int,
) {
    map.uiSettings.isCompassEnabled = true
    if (map.style == null) {
        map.setStyle(Style.Builder().fromUri(styleUrl)) {
            drawTrackAnnotations(
                map = map,
                liveLocation = liveLocation,
                trackPoints = trackPoints,
                playbackPoint = playbackPoint,
                mapTarget = mapTarget,
                pathColor = pathColor,
            )
        }
        return
    }
    drawTrackAnnotations(
        map = map,
        liveLocation = liveLocation,
        trackPoints = trackPoints,
        playbackPoint = playbackPoint,
        mapTarget = mapTarget,
        pathColor = pathColor,
    )
}

private fun drawTrackAnnotations(
    map: MapLibreMap,
    liveLocation: LatLng?,
    trackPoints: List<LatLng>,
    playbackPoint: GpsTrackPoint?,
    mapTarget: LatLng?,
    pathColor: Int,
) {
    map.clear()

    if (trackPoints.size >= 2) {
        map.addPolyline(
            PolylineOptions()
                .addAll(trackPoints)
                .color(pathColor)
                .width(7f),
        )
    }
    liveLocation?.let {
        map.addMarker(
            MarkerOptions()
                .position(it)
                .title("实时位置"),
        )
    }
    playbackPoint?.let {
        map.addMarker(
            MarkerOptions()
                .position(LatLng(it.latitude, it.longitude))
                .title("回放点")
                .snippet(it.localTimeText()),
        )
    }
    mapTarget?.let {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 16.0))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun com.smartsup.controller.model.Telemetry.liveLatLng(): LatLng? {
    if (statusFields["GPS_FIX"] != "1") {
        return null
    }
    val lat = statusFields["GPS_LAT"]?.toDoubleOrNull() ?: return null
    val lon = statusFields["GPS_LON"]?.toDoubleOrNull() ?: return null
    return LatLng(lat, lon)
}

private fun com.smartsup.controller.model.Telemetry.gpsFixText(): String {
    return when (statusFields["GPS_FIX"]) {
        "1" -> "已定位"
        "0" -> "未定位"
        else -> "--"
    }
}

private fun LatLng.formatLatLng(): String {
    return "%.6f, %.6f".format(latitude, longitude)
}

private fun GpsTrackPoint.formatLatLng(): String {
    return "%.6f, %.6f".format(latitude, longitude)
}

private fun GpsTrackPoint.localTimeText(): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(utcSeconds))
}
