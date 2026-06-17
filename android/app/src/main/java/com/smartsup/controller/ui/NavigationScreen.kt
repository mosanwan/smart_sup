package com.smartsup.controller.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.smartsup.controller.model.GpsTrackSegment
import com.smartsup.controller.model.NavigationRoute
import com.smartsup.controller.model.NavigationRoutePoint
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.SymbolLayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PLAYBACK_POSITION_STEP = 50f / 350f
private const val PLAYBACK_CAMERA_EASE_MS = 80
private const val TRACK_ARROW_SPACING_POINTS = 12
private const val TRACK_ARROW_MIN_DISTANCE_METERS = 25.0
private const val TRACK_ARROW_ICON_SIZE_PX = 46
private const val PLAYBACK_ARROW_ICON_SIZE_PX = 58
private val TRACK_ARROW_COLOR = Color.rgb(0, 180, 255)
private val PLAYBACK_ARROW_COLOR = Color.rgb(220, 40, 40)

@Composable
fun NavigationScreen(
    state: ControlUiState,
    modifier: Modifier = Modifier,
    onSyncTrack: () -> Unit,
    onPlaybackIndexChange: (Int) -> Unit,
    onTrackSelected: (String) -> Unit,
    onTrackDeleted: (String) -> Unit,
    onAddRoute: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onClearSelectedRoute: () -> Unit,
    onEditRoute: (String) -> Unit,
    onAddRoutePoint: (Double, Double) -> Unit,
    onUndoRoutePoint: () -> Unit,
    onSaveRoute: () -> Unit,
    onCancelRouteEditing: () -> Unit,
    onExecuteRoute: (String) -> Unit,
    onDeleteRoute: (String) -> Unit,
    onIncreaseAutoGear: () -> Unit,
    onDecreaseAutoGear: () -> Unit,
    onStopAutoNavigation: () -> Unit,
) {
    val gpsTrack = state.gpsTrack
    val autoNavigation = state.autoNavigation
    var trackDialogVisible by rememberSaveable { mutableStateOf(false) }
    var routeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var playbackPlaying by rememberSaveable { mutableStateOf(false) }
    var playbackControlsVisible by rememberSaveable { mutableStateOf(false) }
    var playbackPosition by rememberSaveable { mutableStateOf(0f) }
    val liveLocation = state.telemetry.liveLatLng()
    val gpsSpeedText = state.telemetry.gpsSpeedText()
    val trackPoints = remember(gpsTrack.recentPoints) {
        gpsTrack.recentPoints.map { LatLng(it.latitude, it.longitude) }
    }
    val playbackPoint = gpsTrack.recentPoints.getOrNull(playbackPosition.toInt().coerceAtLeast(0))
    val playbackLocation = remember(gpsTrack.recentPoints, playbackPosition) {
        interpolateTrackLocation(gpsTrack.recentPoints, playbackPosition)
    }
    val playbackBearing = remember(gpsTrack.recentPoints, playbackPosition) {
        bearingAtTrackPosition(gpsTrack.recentPoints, playbackPosition)
    }
    val showingPlayback = playbackControlsVisible && gpsTrack.recentPoints.isNotEmpty()
    val selectedRoutePoints = autoNavigation.routePointsForMap()
    val selectedRoute = autoNavigation.selectedRouteId
        ?.let { routeId -> autoNavigation.routes.firstOrNull { it.id == routeId } }
    val routePoints = when {
        autoNavigation.editing -> autoNavigation.editingPoints.map { LatLng(it.latitude, it.longitude) }
        autoNavigation.executing -> selectedRoutePoints
        selectedRoute != null -> selectedRoutePoints
        else -> emptyList()
    }
    val targetRoutePoint = autoNavigation.executingRouteId
        ?.let { autoNavigation.routes.firstOrNull { route -> route.id == it } }
        ?.points
        ?.getOrNull(autoNavigation.targetPointIndex)
        ?.let { LatLng(it.latitude, it.longitude) }
    val cameraTarget = if (showingPlayback) {
        trackPoints.firstOrNull()
    } else if (autoNavigation.editing && routePoints.isNotEmpty()) {
        routePoints.last()
    } else if (autoNavigation.executing) {
        liveLocation ?: targetRoutePoint
    } else if (selectedRoute != null && routePoints.isNotEmpty()) {
        routePoints.first()
    } else {
        liveLocation
    }
    val cameraTargetKey = if (showingPlayback) {
        gpsTrack.selectedTrackId?.let { "track:$it" }
    } else if (autoNavigation.editing) {
        autoNavigation.editingRouteId?.let { "route-edit:$it:${autoNavigation.editingPoints.size}" }
    } else if (autoNavigation.executing) {
        autoNavigation.executingRouteId?.let { "route-exec:$it" }
    } else if (selectedRoute != null) {
        "route-selected:${selectedRoute.id}"
    } else {
        liveLocation?.let { "live:${it.latitude}:${it.longitude}" }
    }

    LaunchedEffect(gpsTrack.selectedTrackId, gpsTrack.recentPoints.size) {
        playbackPosition = gpsTrack.playbackIndex.toFloat()
    }

    LaunchedEffect(playbackPlaying, gpsTrack.selectedTrackId, gpsTrack.recentPoints.size) {
        if (!playbackPlaying || gpsTrack.recentPoints.size <= 1) {
            return@LaunchedEffect
        }
        val lastPosition = gpsTrack.recentPoints.lastIndex.toFloat()
        if (playbackPosition >= lastPosition) {
            playbackPlaying = false
            return@LaunchedEffect
        }
        while (playbackPlaying && playbackPosition < lastPosition) {
            delay(50)
            val nextPosition = (playbackPosition + PLAYBACK_POSITION_STEP).coerceAtMost(lastPosition)
            playbackPosition = nextPosition
            onPlaybackIndexChange(nextPosition.toInt())
            if (nextPosition >= lastPosition) {
                playbackPlaying = false
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        if (BuildConfig.MAPTILER_API_KEY_CONFIGURED) {
            MapLibreTrackMap(
                liveLocation = liveLocation,
                trackPoints = if (showingPlayback) trackPoints else emptyList(),
                playbackLocation = if (showingPlayback) playbackLocation else null,
                playbackBearing = if (showingPlayback) playbackBearing else null,
                routePoints = routePoints,
                routeEditing = autoNavigation.editing,
                routeTarget = targetRoutePoint,
                onRoutePointAdd = { point -> onAddRoutePoint(point.latitude, point.longitude) },
                followTarget = if (showingPlayback) playbackLocation else null,
                cameraTarget = cameraTarget,
                cameraTargetKey = cameraTargetKey,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MapUnavailableLayer(modifier = Modifier.fillMaxSize())
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GpsStatusChip(
                    text = "GPS ${state.telemetry.gpsFixText()}",
                    speedText = gpsSpeedText,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onSyncTrack,
                    enabled = !gpsTrack.syncing,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(if (gpsTrack.syncing) "同步中" else "同步")
                }
                TextButton(
                    onClick = { trackDialogVisible = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("轨迹")
                }
                TextButton(
                    onClick = { routeDialogVisible = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("路线")
                }
            }
        }

        if (showingPlayback) {
            MinimalTrackPlaybackControls(
                playing = playbackPlaying,
                pointCount = gpsTrack.recentPoints.size,
                playbackPosition = playbackPosition,
                onTogglePlaying = {
                    if (playbackPlaying) {
                        playbackPlaying = false
                    } else {
                        if (playbackPosition >= gpsTrack.recentPoints.lastIndex) {
                            playbackPosition = 0f
                            onPlaybackIndexChange(0)
                        }
                        playbackPlaying = true
                    }
                },
                onPlaybackPositionChange = {
                    playbackPlaying = false
                    playbackPosition = it
                    onPlaybackIndexChange(it.toInt())
                },
                onClose = {
                    playbackPlaying = false
                    playbackControlsVisible = false
                    playbackPosition = 0f
                    onPlaybackIndexChange(0)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        if (autoNavigation.editing) {
            RouteEditingControls(
                pointCount = autoNavigation.editingPoints.size,
                message = autoNavigation.message,
                onUndo = onUndoRoutePoint,
                onSave = onSaveRoute,
                onCancel = onCancelRouteEditing,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        } else if (autoNavigation.executing) {
            AutoNavigationControls(
                state = state,
                onDecreaseGear = onDecreaseAutoGear,
                onIncreaseGear = onIncreaseAutoGear,
                onStop = onStopAutoNavigation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        } else if (selectedRoute != null && !showingPlayback) {
            SelectedRouteControls(
                route = selectedRoute,
                message = autoNavigation.message,
                onClose = onClearSelectedRoute,
                onExecute = {
                    playbackControlsVisible = false
                    playbackPlaying = false
                    onExecuteRoute(selectedRoute.id)
                },
                onEdit = { onEditRoute(selectedRoute.id) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        if (trackDialogVisible) {
            TrackLibraryDialog(
                tracks = gpsTrack.tracks,
                selectedTrackId = gpsTrack.selectedTrackId,
                onDismiss = { trackDialogVisible = false },
                onPlayTrack = { trackId ->
                    onTrackSelected(trackId)
                    playbackPosition = 0f
                    onPlaybackIndexChange(0)
                    playbackControlsVisible = true
                    playbackPlaying = true
                    trackDialogVisible = false
                },
                onDeleteTrack = { trackId ->
                    playbackControlsVisible = false
                    playbackPlaying = false
                    onTrackDeleted(trackId)
                },
            )
        }

        if (routeDialogVisible) {
            RouteLibraryDialog(
                routes = autoNavigation.routes,
                selectedRouteId = autoNavigation.selectedRouteId,
                executingRouteId = autoNavigation.executingRouteId,
                message = autoNavigation.message,
                onDismiss = { routeDialogVisible = false },
                onAddRoute = {
                    onAddRoute()
                    routeDialogVisible = false
                },
                onSelectRoute = { routeId ->
                    playbackControlsVisible = false
                    playbackPlaying = false
                    onSelectRoute(routeId)
                    routeDialogVisible = false
                },
                onEditRoute = { routeId ->
                    onEditRoute(routeId)
                    routeDialogVisible = false
                },
                onDeleteRoute = onDeleteRoute,
            )
        }
    }
}

@Composable
private fun GpsStatusChip(
    text: String,
    speedText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (speedText != null) {
            Text(
                text = "当前速度 $speedText",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MinimalTrackPlaybackControls(
    playing: Boolean,
    pointCount: Int,
    playbackPosition: Float,
    onTogglePlaying: () -> Unit,
    onPlaybackPositionChange: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onTogglePlaying) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "暂停轨迹回放" else "播放轨迹回放",
                )
            }
            val maxIndex = (pointCount - 1).coerceAtLeast(0)
            val sliderEnd = if (maxIndex > 0) maxIndex.toFloat() else 1f
            Slider(
                value = playbackPosition.coerceIn(0f, maxIndex.toFloat()),
                onValueChange = { onPlaybackPositionChange(it) },
                valueRange = 0f..sliderEnd,
                enabled = pointCount > 1,
                steps = 0,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "退出轨迹回放")
            }
        }
    }
}

@Composable
private fun RouteEditingControls(
    pointCount: Int,
    message: String,
    onUndo: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("路线编辑 · $pointCount 点", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onUndo, enabled = pointCount > 0) {
                Icon(Icons.Outlined.Close, contentDescription = "撤销最后航点")
            }
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            OutlinedButton(onClick = onSave, enabled = pointCount >= 2) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun AutoNavigationControls(
    state: ControlUiState,
    onDecreaseGear: () -> Unit,
    onIncreaseGear: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoState = state.autoNavigation
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("自动驾驶", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        autoState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CompactMetric("速度", state.telemetry.gpsSpeedText() ?: "--")
                CompactMetric("目标", "${autoState.targetPointIndex + 1}")
                CompactMetric("距离", autoState.distanceToTargetMeters?.let { "${it.roundToInt()}m" } ?: "--")
                CompactMetric("误差", autoState.headingErrorDegrees?.let { "${it.roundToInt()}°" } ?: "--")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDecreaseGear) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "自动导航减档")
                }
                Text(
                    "档位 ${autoState.gearIndex + 1} · L ${autoState.leftOutputPercent}% / R ${autoState.rightOutputPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onIncreaseGear) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "自动导航加档")
                }
            }
        }
    }
}

@Composable
private fun SelectedRouteControls(
    route: NavigationRoute,
    message: String,
    onClose: () -> Unit,
    onExecute: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "退出路线模式")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(route.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${route.points.size} 点 · ${route.distanceText()} · $message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑路线")
            }
            OutlinedButton(onClick = onExecute, enabled = route.points.size >= 2) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("执行")
            }
        }
    }
}

@Composable
private fun RouteLibraryDialog(
    routes: List<NavigationRoute>,
    selectedRouteId: String?,
    executingRouteId: String?,
    message: String,
    onDismiss: () -> Unit,
    onAddRoute: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onEditRoute: (String) -> Unit,
    onDeleteRoute: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<NavigationRoute?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动导航路线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (routes.isEmpty()) {
                    Text("还没有保存路线。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(routes, key = { it.id }) { route ->
                            RouteListItem(
                                route = route,
                                selected = route.id == selectedRouteId,
                                executing = route.id == executingRouteId,
                                onSelect = { onSelectRoute(route.id) },
                                onEdit = { onEditRoute(route.id) },
                                onDelete = { pendingDelete = route },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onAddRoute) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )

    pendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除路线") },
            text = { Text("删除 ${route.name}？这只会删除手机本地路线。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRoute(route.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RouteListItem(
    route: NavigationRoute,
    selected: Boolean,
    executing: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected || executing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(route.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${route.points.size} 点 · ${route.distanceText()} · ${route.updatedTimeText()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (executing) {
                    Text("执行中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelect) {
                    Text(if (selected) "已选择" else "选择")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑路线")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除路线")
                }
            }
        }
    }
}

@Composable
private fun TrackLibraryDialog(
    tracks: List<GpsTrackSegment>,
    selectedTrackId: String?,
    onDismiss: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<GpsTrackSegment?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("轨迹回放") },
        text = {
            if (tracks.isEmpty()) {
                Text(
                    "手机本地还没有轨迹。连接 ESP32 后先同步轨迹缓存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tracks.asReversed(), key = { it.id }) { track ->
                        TrackListItem(
                            track = track,
                            selected = track.id == selectedTrackId,
                            onPlay = { onPlayTrack(track.id) },
                            onDelete = { pendingDelete = track },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )

    pendingDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除轨迹") },
            text = {
                Text("删除 ${track.longLabel()}？这只会删除手机本地轨迹点。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrack(track.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TrackListItem(
    track: GpsTrackSegment,
    selected: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.longLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${track.pointCount} 点 · ${track.durationText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "播放轨迹")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除轨迹")
            }
        }
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
private fun MapLibreTrackMap(
    liveLocation: LatLng?,
    trackPoints: List<LatLng>,
    playbackLocation: LatLng?,
    playbackBearing: Double?,
    routePoints: List<LatLng>,
    routeEditing: Boolean,
    routeTarget: LatLng?,
    onRoutePointAdd: (LatLng) -> Unit,
    followTarget: LatLng?,
    cameraTarget: LatLng?,
    cameraTargetKey: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pathColor = MaterialTheme.colorScheme.primary.toArgb()
    val routeColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val styleUrl = remember(BuildConfig.MAPTILER_API_KEY) {
        "https://api.maptiler.com/maps/hybrid/style.json?key=${BuildConfig.MAPTILER_API_KEY}&language=zh"
    }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
        }
    }
    val annotations = remember { TrackMapAnnotations() }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView, routeEditing, onRoutePointAdd) {
        var mapRef: MapLibreMap? = null
        val listener = MapLibreMap.OnMapClickListener { point ->
            onRoutePointAdd(point)
            true
        }
        if (routeEditing) {
            mapView.getMapAsync { map ->
                mapRef = map
                map.addOnMapClickListener(listener)
            }
        }
        onDispose {
            mapRef?.removeOnMapClickListener(listener)
        }
    }

    LaunchedEffect(liveLocation, trackPoints, playbackLocation, routePoints, routeTarget, pathColor, routeColor, styleUrl) {
        mapView.getMapAsync { map ->
            renderTrackMap(
                map = map,
                styleUrl = styleUrl,
                liveLocation = liveLocation,
                trackPoints = trackPoints,
                playbackLocation = playbackLocation,
                playbackBearing = playbackBearing,
                routePoints = routePoints,
                routeTarget = routeTarget,
                pathColor = pathColor,
                routeColor = routeColor,
                annotations = annotations,
                context = context,
            )
        }
    }

    LaunchedEffect(cameraTargetKey, cameraTarget, styleUrl) {
        if (cameraTargetKey == null || cameraTarget == null) {
            return@LaunchedEffect
        }
        mapView.getMapAsync { map ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(cameraTarget, 16.0))
        }
    }

    LaunchedEffect(followTarget) {
        followTarget ?: return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.easeCamera(CameraUpdateFactory.newLatLng(followTarget), PLAYBACK_CAMERA_EASE_MS, false)
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
    playbackLocation: LatLng?,
    playbackBearing: Double?,
    routePoints: List<LatLng>,
    routeTarget: LatLng?,
    pathColor: Int,
    routeColor: Int,
    annotations: TrackMapAnnotations,
    context: Context,
) {
    map.uiSettings.isCompassEnabled = true
    map.uiSettings.setAllGesturesEnabled(true)
    if (map.style == null) {
        map.setStyle(Style.Builder().fromUri(styleUrl)) {
            applyChineseMapLabels(it)
            drawTrackAnnotations(
                map = map,
                liveLocation = liveLocation,
                trackPoints = trackPoints,
                playbackLocation = playbackLocation,
                playbackBearing = playbackBearing,
                routePoints = routePoints,
                routeTarget = routeTarget,
                pathColor = pathColor,
                routeColor = routeColor,
                annotations = annotations,
                context = context,
            )
        }
        return
    }
    drawTrackAnnotations(
        map = map,
        liveLocation = liveLocation,
        trackPoints = trackPoints,
        playbackLocation = playbackLocation,
        playbackBearing = playbackBearing,
        routePoints = routePoints,
        routeTarget = routeTarget,
        pathColor = pathColor,
        routeColor = routeColor,
        annotations = annotations,
        context = context,
    )
}

private fun drawTrackAnnotations(
    map: MapLibreMap,
    liveLocation: LatLng?,
    trackPoints: List<LatLng>,
    playbackLocation: LatLng?,
    playbackBearing: Double?,
    routePoints: List<LatLng>,
    routeTarget: LatLng?,
    pathColor: Int,
    routeColor: Int,
    annotations: TrackMapAnnotations,
    context: Context,
) {
    val trackSignature = trackPoints.signature()
    if (trackPoints.size >= 2) {
        val polyline = annotations.trackPolyline
        if (polyline == null) {
            annotations.trackPolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(trackPoints)
                    .color(pathColor)
                    .width(7f),
            )
        } else if (annotations.trackSignature != trackSignature) {
            polyline.points = trackPoints
            polyline.color = pathColor
            polyline.width = 7f
        }
        if (annotations.trackSignature != trackSignature) {
            updateTrackDirectionArrows(
                map = map,
                trackPoints = trackPoints,
                annotations = annotations,
                context = context,
            )
            annotations.trackSignature = trackSignature
        }
    } else {
        annotations.trackPolyline?.let(map::removePolyline)
        annotations.trackPolyline = null
        clearTrackDirectionArrows(map, annotations)
        annotations.trackSignature = null
    }

    if (liveLocation != null) {
        val marker = annotations.liveMarker
        if (marker == null) {
            annotations.liveMarker = map.addMarker(
                MarkerOptions()
                    .position(liveLocation)
                    .title("实时位置"),
            )
        } else {
            marker.position = liveLocation
        }
    } else {
        annotations.liveMarker?.let(map::removeMarker)
        annotations.liveMarker = null
    }

    if (playbackLocation != null) {
        val marker = annotations.playbackMarker
        val bearingBucket = playbackBearing?.bearingBucket() ?: 0
        val icon = annotations.iconForBearing(
            context = context,
            bearingBucket = bearingBucket,
            sizePx = PLAYBACK_ARROW_ICON_SIZE_PX,
            color = PLAYBACK_ARROW_COLOR,
        )
        if (marker == null) {
            annotations.playbackMarker = map.addMarker(
                MarkerOptions()
                    .position(playbackLocation)
                    .icon(icon)
                    .title("回放点"),
            )
        } else {
            marker.position = playbackLocation
            if (annotations.playbackBearingBucket != bearingBucket) {
                marker.icon = icon
            }
        }
        annotations.playbackBearingBucket = bearingBucket
    } else {
        annotations.playbackMarker?.let(map::removeMarker)
        annotations.playbackMarker = null
        annotations.playbackBearingBucket = null
    }

    val routeSignature = routePoints.signature()
    if (routePoints.size >= 2) {
        val polyline = annotations.routePolyline
        if (polyline == null) {
            annotations.routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(routePoints)
                    .color(routeColor)
                    .width(6f),
            )
        } else if (annotations.routeSignature != routeSignature) {
            polyline.points = routePoints
            polyline.color = routeColor
            polyline.width = 6f
        }
    } else {
        annotations.routePolyline?.let(map::removePolyline)
        annotations.routePolyline = null
    }
    if (annotations.routeSignature != routeSignature) {
        clearRouteMarkers(map, annotations)
        routePoints.forEachIndexed { index, point ->
            annotations.routeMarkers += map.addMarker(
                MarkerOptions()
                    .position(point)
                    .title("航点 ${index + 1}"),
            )
        }
        annotations.routeSignature = routeSignature
    }

    if (routeTarget != null) {
        val marker = annotations.routeTargetMarker
        if (marker == null) {
            annotations.routeTargetMarker = map.addMarker(
                MarkerOptions()
                    .position(routeTarget)
                    .title("目标航点"),
            )
        } else {
            marker.position = routeTarget
        }
    } else {
        annotations.routeTargetMarker?.let(map::removeMarker)
        annotations.routeTargetMarker = null
    }
}

private class TrackMapAnnotations {
    var trackPolyline: Polyline? = null
    var routePolyline: Polyline? = null
    var liveMarker: Marker? = null
    var playbackMarker: Marker? = null
    var routeTargetMarker: Marker? = null
    var playbackBearingBucket: Int? = null
    var trackSignature: String? = null
    var routeSignature: String? = null
    val directionArrowMarkers: MutableList<Marker> = mutableListOf()
    val routeMarkers: MutableList<Marker> = mutableListOf()
    private val iconCache: MutableMap<String, Icon> = mutableMapOf()

    fun iconForBearing(context: Context, bearingBucket: Int, sizePx: Int, color: Int): Icon {
        val key = "$sizePx:$color:$bearingBucket"
        return iconCache.getOrPut(key) {
            IconFactory.getInstance(context).fromBitmap(createArrowBitmap(sizePx, color, bearingBucket.toFloat()))
        }
    }
}

private fun clearRouteMarkers(map: MapLibreMap, annotations: TrackMapAnnotations) {
    annotations.routeMarkers.forEach(map::removeMarker)
    annotations.routeMarkers.clear()
}

private fun updateTrackDirectionArrows(
    map: MapLibreMap,
    trackPoints: List<LatLng>,
    annotations: TrackMapAnnotations,
    context: Context,
) {
    clearTrackDirectionArrows(map, annotations)
    sampledDirectionArrows(trackPoints).forEach { arrow ->
        val icon = annotations.iconForBearing(
            context = context,
            bearingBucket = arrow.bearing.bearingBucket(),
            sizePx = TRACK_ARROW_ICON_SIZE_PX,
            color = TRACK_ARROW_COLOR,
        )
        annotations.directionArrowMarkers += map.addMarker(
            MarkerOptions()
                .position(arrow.position)
                .icon(icon)
                .title("轨迹方向"),
        )
    }
}

private fun clearTrackDirectionArrows(map: MapLibreMap, annotations: TrackMapAnnotations) {
    annotations.directionArrowMarkers.forEach(map::removeMarker)
    annotations.directionArrowMarkers.clear()
}

private data class DirectionArrow(
    val position: LatLng,
    val bearing: Double,
)

private fun applyChineseMapLabels(style: Style) {
    val chineseText = textField(
        Expression.coalesce(
            Expression.get("name:zh"),
            Expression.get("name_zh"),
            Expression.get("name"),
            Expression.get("name:en"),
        ),
    )
    style.layers.forEach { layer ->
        val symbolLayer = layer as? SymbolLayer ?: return@forEach
        if (!symbolLayer.textField.isNull) {
            symbolLayer.setProperties(chineseText)
        }
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

private fun com.smartsup.controller.model.Telemetry.gpsSpeedText(): String? {
    if (statusFields["GPS_FIX"] != "1") {
        return null
    }
    val speedKmh = statusFields["GPS_SPD_KMH"]?.toDoubleOrNull() ?: return "-- km/h"
    return String.format(Locale.US, "%.1f km/h", speedKmh.coerceAtLeast(0.0))
}

private fun interpolateTrackLocation(points: List<GpsTrackPoint>, position: Float): LatLng? {
    if (points.isEmpty()) {
        return null
    }
    if (points.size == 1) {
        return LatLng(points.first().latitude, points.first().longitude)
    }
    val startIndex = floor(position).toInt().coerceIn(0, points.lastIndex)
    val endIndex = (startIndex + 1).coerceAtMost(points.lastIndex)
    val fraction = (position - startIndex.toFloat()).coerceIn(0f, 1f)
    val start = points[startIndex]
    val end = points[endIndex]
    val lat = start.latitude + (end.latitude - start.latitude) * fraction
    val lon = start.longitude + (end.longitude - start.longitude) * fraction
    return LatLng(lat, lon)
}

private fun bearingAtTrackPosition(points: List<GpsTrackPoint>, position: Float): Double? {
    if (points.size < 2) {
        return null
    }
    val startIndex = floor(position).toInt().coerceIn(0, points.lastIndex - 1)
    val endIndex = (startIndex + 1).coerceAtMost(points.lastIndex)
    return bearingBetween(
        LatLng(points[startIndex].latitude, points[startIndex].longitude),
        LatLng(points[endIndex].latitude, points[endIndex].longitude),
    )
}

private fun sampledDirectionArrows(trackPoints: List<LatLng>): List<DirectionArrow> {
    if (trackPoints.size < 2) {
        return emptyList()
    }
    val arrows = mutableListOf<DirectionArrow>()
    var lastArrowPoint: LatLng? = null
    for (index in TRACK_ARROW_SPACING_POINTS until trackPoints.lastIndex step TRACK_ARROW_SPACING_POINTS) {
        val previous = trackPoints[index - 1]
        val current = trackPoints[index]
        if (lastArrowPoint != null && distanceMeters(lastArrowPoint, current) < TRACK_ARROW_MIN_DISTANCE_METERS) {
            continue
        }
        bearingBetween(previous, current)?.let { bearing ->
            arrows += DirectionArrow(position = current, bearing = bearing)
            lastArrowPoint = current
        }
    }
    if (arrows.isEmpty()) {
        val midpointIndex = trackPoints.size / 2
        bearingBetween(trackPoints[midpointIndex - 1], trackPoints[midpointIndex])?.let { bearing ->
            arrows += DirectionArrow(position = trackPoints[midpointIndex], bearing = bearing)
        }
    }
    return arrows
}

private fun bearingBetween(from: LatLng, to: LatLng): Double? {
    if (from.latitude == to.latitude && from.longitude == to.longitude) {
        return null
    }
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun distanceMeters(from: LatLng, to: LatLng): Double {
    val earthRadiusMeters = 6371000.0
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLat = Math.toRadians(to.latitude - from.latitude)
    val deltaLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
    return earthRadiusMeters * 2.0 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
}

private fun Double.bearingBucket(): Int {
    return ((this / 10.0).roundToInt() * 10).floorMod(360)
}

private fun Int.floorMod(modulus: Int): Int {
    return ((this % modulus) + modulus) % modulus
}

private fun List<LatLng>.signature(): String? {
    if (isEmpty()) {
        return null
    }
    val first = first()
    val last = last()
    return "${size}:${first.latitude}:${first.longitude}:${last.latitude}:${last.longitude}"
}

private fun createArrowBitmap(sizePx: Int, color: Int, bearingDegrees: Float): Bitmap {
    val base = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(base)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.08f
        strokeJoin = Paint.Join.ROUND
    }
    val center = sizePx / 2f
    val path = Path().apply {
        moveTo(center, sizePx * 0.10f)
        lineTo(sizePx * 0.78f, sizePx * 0.86f)
        lineTo(center, sizePx * 0.66f)
        lineTo(sizePx * 0.22f, sizePx * 0.86f)
        close()
    }
    canvas.drawPath(path, outlinePaint)
    canvas.drawPath(path, paint)

    val matrix = Matrix().apply {
        postRotate(bearingDegrees, center, center)
    }
    return Bitmap.createBitmap(base, 0, 0, sizePx, sizePx, matrix, true)
}

private fun com.smartsup.controller.model.AutoNavigationUiState.routePointsForMap(): List<LatLng> {
    val routeId = executingRouteId ?: selectedRouteId
    return routes.firstOrNull { it.id == routeId }
        ?.points
        ?.map { LatLng(it.latitude, it.longitude) }
        .orEmpty()
}

private fun NavigationRoute.distanceText(): String {
    val meters = points.zipWithNext().sumOf { (from, to) ->
        distanceMeters(
            LatLng(from.latitude, from.longitude),
            LatLng(to.latitude, to.longitude),
        )
    }
    return if (meters >= 1000.0) {
        String.format(Locale.US, "%.1fkm", meters / 1000.0)
    } else {
        "${meters.roundToInt()}m"
    }
}

private fun NavigationRoute.updatedTimeText(): String {
    return DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(updatedAtEpochSeconds))
}

private fun GpsTrackSegment.longLabel(): String {
    return timeRangeText()
}

private fun GpsTrackSegment.timeRangeText(): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(startUtcSeconds).atZone(zone)
    val end = Instant.ofEpochSecond(endUtcSeconds).atZone(zone)
    return if (start.toLocalDate() == end.toLocalDate()) {
        val startText = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(start)
        val endText = DateTimeFormatter.ofPattern("HH:mm").format(end)
        "$startText - $endText"
    } else {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        "${formatter.format(start)} - ${formatter.format(end)}"
    }
}

private fun GpsTrackSegment.durationText(): String {
    val seconds = (endUtcSeconds - startUtcSeconds).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分"
        else -> "${seconds}秒"
    }
}

private fun GpsTrackPoint.localTimeText(): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(utcSeconds))
}
