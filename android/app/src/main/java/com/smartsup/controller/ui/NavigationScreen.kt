package com.smartsup.controller.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.rememberScrollState
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
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.GpsTrackPoint
import com.smartsup.controller.model.GpsTrackSegment
import com.smartsup.controller.model.GpsTrackUiState
import com.smartsup.controller.model.NavigationGpsSource
import com.smartsup.controller.model.NavigationRoute
import com.smartsup.controller.model.NavigationRoutePoint
import com.smartsup.controller.model.PhoneGpsState
import com.smartsup.controller.model.YB_IMU_HEADING_MODE_YBY_INVERTED
import com.smartsup.controller.model.ybImuHeadingDegrees
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PLAYBACK_TICK_MS = 50L
private const val PLAYBACK_BEARING_LOOKAROUND_POINTS = 4f
private const val PLAYBACK_CAMERA_EASE_MS = 80
private const val TRACK_SMOOTHING_WINDOW = 5
private const val TRACK_ARROW_FAR_ZOOM_MAX = 10.0
private const val TRACK_ARROW_MID_ZOOM_MAX = 13.0
private const val TRACK_ARROW_NEAR_ZOOM_MAX = 15.0
private const val PLAYBACK_ARROW_ICON_SIZE_PX = 58
private const val LIVE_HEADING_ARROW_ICON_SIZE_PX = 64
private const val TRACK_LINE_DISPLAY_LENGTH_METERS = 3_000.0
private val TRACK_ARROW_COLOR = Color.rgb(0, 180, 255)
private val PLAYBACK_ARROW_COLOR = Color.rgb(220, 40, 40)
private val LIVE_HEADING_ARROW_COLOR = Color.rgb(40, 120, 255)
private val PLAYBACK_SPEED_OPTIONS = listOf(1, 5, 15, 30, 60, 120)

@Composable
fun NavigationScreen(
    state: ControlUiState,
    navigationGpsSource: NavigationGpsSource,
    usePhoneHeading: Boolean,
    phoneHeadingOffsetDegrees: Float,
    ybImuHeadingOffsetDegrees: Float,
    ybImuHeadingMode: Int,
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
    onUpdateRoutePoint: (Int, Double, Double) -> Unit,
    onDeleteRoutePoint: (Int) -> Unit,
    onMoveRoutePoint: (Int, Int) -> Unit,
    onUndoRoutePoint: () -> Unit,
    onSaveRoute: () -> Unit,
    onCancelRouteEditing: () -> Unit,
    onExecuteRoute: (String) -> Unit,
    onDeleteRoute: (String) -> Unit,
    onRenameRoute: (String, String) -> Unit,
    onIncreaseAutoGear: () -> Unit,
    onDecreaseAutoGear: () -> Unit,
    onStopAutoNavigation: () -> Unit,
    onStartTrackLineLock: () -> Unit,
    onStopTrackLineLock: () -> Unit,
    onStartStationKeeping: () -> Unit,
    onStopStationKeeping: () -> Unit,
) {
    val gpsTrack = state.gpsTrack
    val autoNavigation = state.autoNavigation
    var trackDialogVisible by rememberSaveable { mutableStateOf(false) }
    var routeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var pendingExecuteRoute by remember { mutableStateOf<NavigationRoute?>(null) }
    var playbackPlaying by rememberSaveable { mutableStateOf(false) }
    var playbackControlsVisible by rememberSaveable { mutableStateOf(false) }
    var playbackPosition by rememberSaveable { mutableStateOf(0f) }
    var playbackSpeedMultiplier by rememberSaveable { mutableStateOf(30) }
    var previousSyncing by remember { mutableStateOf(gpsTrack.syncing) }
    var syncNotice by remember { mutableStateOf<String?>(null) }
    var selectedRoutePointIndex by rememberSaveable(autoNavigation.editingRouteId) { mutableStateOf<Int?>(null) }
    var movingRoutePointIndex by rememberSaveable(autoNavigation.editingRouteId) { mutableStateOf<Int?>(null) }
    val liveLocation = state.liveLatLng(navigationGpsSource)
    val liveHeadingDegrees = state.navigationHeadingDegrees(
        usePhoneHeading,
        phoneHeadingOffsetDegrees,
        ybImuHeadingOffsetDegrees,
        ybImuHeadingMode,
    )
    val liveHeadingSourceText = if (usePhoneHeading) "船头" else "IMU"
    val gpsStatusText = state.gpsFixText(navigationGpsSource)
    val gpsSpeedText = state.gpsSpeedText(navigationGpsSource)
    val gpsLocated = state.hasNavigationGpsLocation(navigationGpsSource)
    val rawTrackPoints = remember(gpsTrack.recentPoints) {
        gpsTrack.recentPoints.map { LatLng(it.latitude, it.longitude) }
    }
    val trackPoints = remember(rawTrackPoints) {
        smoothTrackPoints(rawTrackPoints)
    }
    val lastKnownGpsLocation = rawTrackPoints.lastOrNull()
    val lastKnownGpsKey = gpsTrack.recentPoints.lastOrNull()?.let { point ->
        "last-gps:${point.sessionId}:${point.sequence}:${point.latitudeE7}:${point.longitudeE7}"
    }
    val playbackLocation = remember(gpsTrack.recentPoints, playbackPosition) {
        smoothedTrackLocation(gpsTrack.recentPoints, playbackPosition)
    }
    val playbackBearing = remember(gpsTrack.recentPoints, playbackPosition) {
        smoothedBearingAtTrackPosition(gpsTrack.recentPoints, playbackPosition)
    }
    val playbackTimeText = remember(gpsTrack.recentPoints, playbackPosition) {
        formatPlaybackTimeText(gpsTrack.recentPoints, playbackPosition)
    }
    val playbackTravelSpeedText = remember(gpsTrack.recentPoints, playbackPosition) {
        formatPlaybackTravelSpeedText(gpsTrack.recentPoints, playbackPosition)
    }
    val showingPlayback = playbackControlsVisible && gpsTrack.recentPoints.isNotEmpty()
    val selectedRoutePoints = autoNavigation.routePointsForMap()
    val trackLinePoints = autoNavigation.trackLinePointsForMap()
    val selectedRoute = autoNavigation.selectedRouteId
        ?.let { routeId -> autoNavigation.routes.firstOrNull { it.id == routeId } }
    val routePoints = when {
        autoNavigation.editing -> autoNavigation.editingPoints.map { LatLng(it.latitude, it.longitude) }
        autoNavigation.trackLineLockEnabled -> trackLinePoints
        autoNavigation.executing -> selectedRoutePoints
        selectedRoute != null -> selectedRoutePoints
        else -> emptyList()
    }
    val targetRoutePoint = autoNavigation.executingRouteId
        ?.let { autoNavigation.routes.firstOrNull { route -> route.id == it } }
        ?.points
        ?.getOrNull(autoNavigation.targetPointIndex)
        ?.let { LatLng(it.latitude, it.longitude) }
    val stationKeepingTarget = autoNavigation.stationKeepingTarget
        ?.let { LatLng(it.latitude, it.longitude) }
    val activeTargetPoint = stationKeepingTarget ?: targetRoutePoint
    LaunchedEffect(autoNavigation.editingPoints.size) {
        val lastIndex = autoNavigation.editingPoints.lastIndex
        selectedRoutePointIndex = selectedRoutePointIndex?.takeIf { it in 0..lastIndex }
        movingRoutePointIndex = movingRoutePointIndex?.takeIf { it in 0..lastIndex }
    }

    val cameraTarget = if (showingPlayback) {
        trackPoints.firstOrNull()
    } else if (autoNavigation.stationKeepingEnabled) {
        liveLocation ?: stationKeepingTarget
    } else if (autoNavigation.trackLineLockEnabled) {
        liveLocation ?: routePoints.firstOrNull()
    } else if (autoNavigation.executing) {
        liveLocation ?: activeTargetPoint
    } else if (selectedRoute != null && routePoints.isNotEmpty()) {
        routePoints.first()
    } else {
        liveLocation ?: lastKnownGpsLocation
    }
    val cameraTargetKey = if (showingPlayback) {
        gpsTrack.selectedTrackId?.let { "track:$it" }
    } else if (autoNavigation.editing) {
        null
    } else if (autoNavigation.trackLineLockEnabled) {
        autoNavigation.trackLineOrigin?.let { "track-line:${it.latitude}:${it.longitude}:${autoNavigation.trackLineBearingDegrees}" }
    } else if (autoNavigation.stationKeepingEnabled) {
        autoNavigation.stationKeepingTarget?.let { "station:${it.latitude}:${it.longitude}:${autoNavigation.stationKeepingTargetHeadingDegrees}" }
    } else if (autoNavigation.executing) {
        autoNavigation.executingRouteId?.let { "route-exec:$it" }
    } else if (selectedRoute != null) {
        "route-selected:${selectedRoute.id}"
    } else {
        liveLocation?.let { "live:${it.latitude}:${it.longitude}" } ?: lastKnownGpsKey
    }

    LaunchedEffect(gpsTrack.selectedTrackId, gpsTrack.recentPoints.size) {
        playbackPosition = gpsTrack.playbackIndex.toFloat()
    }

    LaunchedEffect(playbackPlaying, playbackSpeedMultiplier, gpsTrack.selectedTrackId, gpsTrack.recentPoints.size) {
        if (!playbackPlaying || gpsTrack.recentPoints.size <= 1) {
            return@LaunchedEffect
        }
        val lastPosition = gpsTrack.recentPoints.lastIndex.toFloat()
        if (playbackPosition >= lastPosition) {
            playbackPlaying = false
            return@LaunchedEffect
        }
        while (playbackPlaying && playbackPosition < lastPosition) {
            delay(PLAYBACK_TICK_MS)
            val step = playbackSpeedMultiplier * PLAYBACK_TICK_MS / 1000f
            val nextPosition = (playbackPosition + step).coerceAtMost(lastPosition)
            playbackPosition = nextPosition
            onPlaybackIndexChange(nextPosition.toInt())
            if (nextPosition >= lastPosition) {
                playbackPlaying = false
            }
        }
    }

    LaunchedEffect(gpsTrack.syncing, gpsTrack.syncMessage) {
        if (gpsTrack.syncing) {
            syncNotice = null
        } else if (previousSyncing) {
            syncNotice = gpsTrack.syncMessage.ifBlank { "轨迹同步完成" }
        }
        previousSyncing = gpsTrack.syncing
    }

    LaunchedEffect(syncNotice) {
        if (syncNotice != null) {
            delay(4200)
            syncNotice = null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        if (BuildConfig.MAPTILER_API_KEY_CONFIGURED) {
            MapLibreTrackMap(
                liveLocation = liveLocation,
                liveHeadingDegrees = liveHeadingDegrees,
                liveHeadingSourceText = liveHeadingSourceText,
                trackPoints = if (showingPlayback) trackPoints else emptyList(),
                playbackLocation = if (showingPlayback) playbackLocation else null,
                playbackBearing = if (showingPlayback) playbackBearing else null,
                routePoints = routePoints,
                routeEditing = autoNavigation.editing,
                selectedRoutePointIndex = selectedRoutePointIndex,
                routeTarget = activeTargetPoint,
                onRoutePointAdd = { point ->
                    val movingIndex = movingRoutePointIndex
                    if (movingIndex != null && movingIndex in autoNavigation.editingPoints.indices) {
                        onUpdateRoutePoint(movingIndex, point.latitude, point.longitude)
                        selectedRoutePointIndex = movingIndex
                        movingRoutePointIndex = null
                    } else {
                        onAddRoutePoint(point.latitude, point.longitude)
                        selectedRoutePointIndex = autoNavigation.editingPoints.size
                    }
                },
                onRoutePointSelected = { index ->
                    selectedRoutePointIndex = index
                    movingRoutePointIndex = null
                },
                followTarget = if (showingPlayback) playbackLocation else null,
                cameraTarget = cameraTarget,
                cameraTargetKey = cameraTargetKey,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MapUnavailableLayer(modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavigationTopPanel(
                gpsLocated = gpsLocated,
                gpsText = "${navigationGpsSource.label()} $gpsStatusText",
                speedText = gpsSpeedText,
            )
            if (gpsTrack.syncing) {
                TrackSyncProgressPanel(gpsTrack = gpsTrack)
            } else {
                syncNotice?.let { notice ->
                    TrackSyncNotice(message = notice)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showingPlayback) {
                MinimalTrackPlaybackControls(
                    playing = playbackPlaying,
                    pointCount = gpsTrack.recentPoints.size,
                    playbackPosition = playbackPosition,
                    playbackTimeText = playbackTimeText,
                    playbackTravelSpeedText = playbackTravelSpeedText,
                    playbackSpeedMultiplier = playbackSpeedMultiplier,
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
                    onPlaybackSpeedChange = { playbackSpeedMultiplier = it },
                    onClose = {
                        playbackPlaying = false
                        playbackControlsVisible = false
                        playbackPosition = 0f
                        onPlaybackIndexChange(0)
                    },
                )
            } else if (autoNavigation.editing) {
                RouteEditingControls(
                    pointCount = autoNavigation.editingPoints.size,
                    selectedPointIndex = selectedRoutePointIndex?.takeIf { it in autoNavigation.editingPoints.indices },
                    movingPointIndex = movingRoutePointIndex?.takeIf { it in autoNavigation.editingPoints.indices },
                    message = autoNavigation.message,
                    onMoveSelected = { index, delta ->
                        onMoveRoutePoint(index, delta)
                        selectedRoutePointIndex = (index + delta).coerceIn(0, autoNavigation.editingPoints.lastIndex)
                        movingRoutePointIndex = null
                    },
                    onStartMoving = { index -> movingRoutePointIndex = index },
                    onDeleteSelected = { index ->
                        onDeleteRoutePoint(index)
                        selectedRoutePointIndex = null
                        movingRoutePointIndex = null
                    },
                    onUndo = onUndoRoutePoint,
                    onSave = onSaveRoute,
                    onCancel = onCancelRouteEditing,
                )
            } else if (autoNavigation.stationKeepingEnabled) {
                StationKeepingControls(
                    state = state,
                    onDecreaseGear = onDecreaseAutoGear,
                    onIncreaseGear = onIncreaseAutoGear,
                    onStop = onStopStationKeeping,
                )
            } else if (autoNavigation.trackLineLockEnabled) {
                TrackLineLockControls(
                    state = state,
                    onDecreaseGear = onDecreaseAutoGear,
                    onIncreaseGear = onIncreaseAutoGear,
                    onStop = onStopTrackLineLock,
                )
            } else if (autoNavigation.executing) {
                AutoNavigationControls(
                    state = state,
                    onDecreaseGear = onDecreaseAutoGear,
                    onIncreaseGear = onIncreaseAutoGear,
                    onStop = onStopAutoNavigation,
                )
            } else if (selectedRoute != null) {
                SelectedRouteControls(
                    route = selectedRoute,
                    message = autoNavigation.message,
                    onClose = onClearSelectedRoute,
                    onExecute = {
                        playbackControlsVisible = false
                        playbackPlaying = false
                        pendingExecuteRoute = selectedRoute
                    },
                    onEdit = { onEditRoute(selectedRoute.id) },
                    onRename = { onRenameRoute(selectedRoute.id, it) },
                )
            }
            NavigationActionBar(
                syncing = gpsTrack.syncing,
                stationKeepingEnabled = autoNavigation.stationKeepingEnabled,
                stationKeepingEnabledForClick = !gpsTrack.syncing &&
                    !autoNavigation.editing &&
                    !autoNavigation.executing &&
                    !autoNavigation.trackLineLockEnabled,
                onSyncTrack = onSyncTrack,
                onToggleStationKeeping = {
                    playbackControlsVisible = false
                    playbackPlaying = false
                    if (autoNavigation.stationKeepingEnabled) {
                        onStopStationKeeping()
                    } else {
                        onStartStationKeeping()
                    }
                },
                onOpenTracks = { trackDialogVisible = true },
                onOpenRoutes = { routeDialogVisible = true },
            )
        }

        pendingExecuteRoute?.let { route ->
            StartAutoNavigationDialog(
                route = route,
                state = state,
                navigationGpsSource = navigationGpsSource,
                usePhoneHeading = usePhoneHeading,
                phoneHeadingOffsetDegrees = phoneHeadingOffsetDegrees,
                ybImuHeadingOffsetDegrees = ybImuHeadingOffsetDegrees,
                ybImuHeadingMode = ybImuHeadingMode,
                onDismiss = { pendingExecuteRoute = null },
                onConfirm = {
                    pendingExecuteRoute = null
                    onExecuteRoute(route.id)
                },
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
                onExecuteRoute = { route ->
                    playbackControlsVisible = false
                    playbackPlaying = false
                    pendingExecuteRoute = route
                    routeDialogVisible = false
                },
                onEditRoute = { routeId ->
                    onEditRoute(routeId)
                    routeDialogVisible = false
                },
                onDeleteRoute = onDeleteRoute,
                onRenameRoute = onRenameRoute,
            )
        }
    }
}

@Composable
private fun NavigationTopPanel(
    gpsLocated: Boolean,
    gpsText: String,
    speedText: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val gpsColor = if (gpsLocated) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Icon(
                imageVector = if (gpsLocated) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = gpsText,
                tint = gpsColor,
                modifier = Modifier.size(22.dp),
            )
            SpeedBadge(speedText = speedText)
        }
    }
}

@Composable
private fun SpeedBadge(speedText: String?) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                speedText.speedBadgeText(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NavigationActionBar(
    syncing: Boolean,
    stationKeepingEnabled: Boolean,
    stationKeepingEnabledForClick: Boolean,
    onSyncTrack: () -> Unit,
    onToggleStationKeeping: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onSyncTrack,
                enabled = !syncing,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (syncing) "同步中" else "同步", maxLines = 1)
            }
            TextButton(
                onClick = onToggleStationKeeping,
                enabled = stationKeepingEnabledForClick,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (stationKeepingEnabled) "停点" else "定点", maxLines = 1)
            }
            TextButton(
                onClick = onOpenTracks,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
                Text("轨迹", maxLines = 1)
            }
            TextButton(
                onClick = onOpenRoutes,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
                Text("路线", maxLines = 1)
            }
        }
    }
}

@Composable
private fun TrackSyncProgressPanel(
    gpsTrack: GpsTrackUiState,
    modifier: Modifier = Modifier,
) {
    val progress = gpsTrack.syncProgressUi()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "轨迹同步",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    progress.percentText ?: "查询中",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                progress.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.fraction != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                progress.detailText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackSyncNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    val warning = message.isTrackSyncWarning()
    val containerColor = if (warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (warning) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (warning) "同步未完成" else "同步完成",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class TrackSyncProgressUi(
    val fraction: Float?,
    val percentText: String?,
    val message: String,
    val detailText: String,
)

private fun GpsTrackUiState.syncProgressUi(): TrackSyncProgressUi {
    val start = syncStartSequence
    val target = syncTargetSequence
    val current = syncCurrentSequence
    if (start != null && target != null && current != null) {
        val total = (target - start + 1).coerceAtLeast(1)
        val done = (current - start + 1).coerceIn(0, total)
        val fraction = done.toFloat() / total.toFloat()
        val sequenceText = if (current >= start) {
            "当前 seq $current"
        } else {
            "等待首个轨迹点"
        }
        return TrackSyncProgressUi(
            fraction = fraction,
            percentText = "${(fraction * 100).roundToInt()}%",
            message = syncMessage,
            detailText = "$done/$total 点 · $sequenceText · 本地 ${storedPointCount} 点",
        )
    }

    val info = latestInfo
    return TrackSyncProgressUi(
        fraction = null,
        percentText = null,
        message = syncMessage,
        detailText = if (info != null) {
            "主控缓存 ${info.count}/${info.capacity} 点 · 本地 ${storedPointCount} 点"
        } else {
            "等待主控返回轨迹缓存信息 · 本地 ${storedPointCount} 点"
        },
    )
}

private fun String.isTrackSyncWarning(): Boolean {
    val warningWords = listOf("错误", "失败", "无法", "未连接", "暂不")
    return warningWords.any { contains(it) }
}

@Composable
private fun MinimalTrackPlaybackControls(
    playing: Boolean,
    pointCount: Int,
    playbackPosition: Float,
    playbackTimeText: String,
    playbackTravelSpeedText: String,
    playbackSpeedMultiplier: Int,
    onTogglePlaying: () -> Unit,
    onPlaybackPositionChange: (Float) -> Unit,
    onPlaybackSpeedChange: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackInfoText(label = "时间", value = playbackTimeText)
                PlaybackInfoText(label = "航速", value = playbackTravelSpeedText)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "回放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PLAYBACK_SPEED_OPTIONS.forEach { speed ->
                    TextButton(
                        onClick = { onPlaybackSpeedChange(speed) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${speed}x",
                            fontWeight = if (speed == playbackSpeedMultiplier) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (speed == playbackSpeedMultiplier) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackInfoText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$label $value",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun RouteEditingControls(
    pointCount: Int,
    selectedPointIndex: Int?,
    movingPointIndex: Int?,
    message: String,
    onMoveSelected: (Int, Int) -> Unit,
    onStartMoving: (Int) -> Unit,
    onDeleteSelected: (Int) -> Unit,
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
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("路线编辑 · $pointCount 点", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (movingPointIndex != null) {
                            "点击地图设置航点 ${movingPointIndex + 1} 的新位置"
                        } else {
                            message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            selectedPointIndex?.let { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "航点 ${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = { onStartMoving(index) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(if (movingPointIndex == index) "等待点击" else "移动")
                    }
                    TextButton(
                        onClick = { onMoveSelected(index, -1) },
                        enabled = index > 0,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("前移")
                    }
                    TextButton(
                        onClick = { onMoveSelected(index, 1) },
                        enabled = index < pointCount - 1,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("后移")
                    }
                    TextButton(
                        onClick = { onDeleteSelected(index) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CompactMetric("速度", state.telemetry.gpsSpeedText() ?: "-- km/h")
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
private fun TrackLineLockControls(
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("航迹线锁定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CompactMetric("速度", state.telemetry.gpsSpeedText() ?: "-- km/h")
                CompactMetric("偏离", autoState.trackLineCrossTrackErrorMeters?.crossTrackText() ?: "--")
                CompactMetric("线向", autoState.trackLineBearingDegrees?.let { "${it.roundToInt()}°" } ?: "--")
                CompactMetric("目标", autoState.trackLineTargetHeadingDegrees?.let { "${it.roundToInt()}°" } ?: "--")
                CompactMetric("误差", autoState.headingErrorDegrees?.let { "${it.roundToInt()}°" } ?: "--")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDecreaseGear) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "航迹线锁定减档")
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
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "航迹线锁定加档")
                }
            }
        }
    }
}

@Composable
private fun StationKeepingControls(
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("定点保持", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CompactMetric("速度", state.telemetry.gpsSpeedText() ?: "-- km/h")
                CompactMetric("距离", autoState.distanceToTargetMeters?.let { "${it.roundToInt()}m" } ?: "--")
                CompactMetric("前后", autoState.stationKeepingForwardErrorMeters?.signedMetersText() ?: "--")
                CompactMetric("左右", autoState.stationKeepingLateralErrorMeters?.signedMetersText() ?: "--")
                CompactMetric("目标航向", autoState.stationKeepingTargetHeadingDegrees?.let { "${it.roundToInt()}°" } ?: "--")
                CompactMetric("误差", autoState.headingErrorDegrees?.let { "${it.roundToInt()}°" } ?: "--")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDecreaseGear) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "定点保持减档")
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
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "定点保持加档")
                }
            }
            Text(
                "日志 ${state.stationKeepingLogSampleCount} 条 · ${state.stationKeepingLogPath.ifBlank { "--" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RequirementLine(label: String, value: String, ready: Boolean) {
    val color = if (ready) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (ready) FontWeight.Normal else FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun StartAutoNavigationDialog(
    route: NavigationRoute,
    state: ControlUiState,
    navigationGpsSource: NavigationGpsSource,
    usePhoneHeading: Boolean,
    phoneHeadingOffsetDegrees: Float,
    ybImuHeadingOffsetDegrees: Float,
    ybImuHeadingMode: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val gpsLocated = state.hasNavigationGpsLocation(navigationGpsSource)
    val gpsReady = state.hasNavigationGpsFix(navigationGpsSource)
    val satelliteCount = state.navigationGpsSatelliteCount(navigationGpsSource)
    val gpsStateText = when {
        gpsReady -> "已定位"
        gpsLocated -> state.navigationGpsAccuracyText(navigationGpsSource) ?: "精度不足"
        else -> "未定位"
    }
    val headingSourceText = if (usePhoneHeading) "手机校准船头" else "IMU"
    val headingReady = state.navigationHeadingDegrees(
        usePhoneHeading,
        phoneHeadingOffsetDegrees,
        ybImuHeadingOffsetDegrees,
        ybImuHeadingMode,
    ) != null
    val connectionReady = state.connectionState == ConnectionState.Connected
    val armedReady = state.armed
    val satelliteReady = satelliteCount >= 4
    val routeReady = route.points.size >= 2
    val ready = connectionReady &&
        armedReady &&
        gpsReady &&
        satelliteReady &&
        headingReady &&
        routeReady
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启动自动驾驶") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${route.name} · ${route.points.size} 点 · ${route.distanceText()}")
                Text("启动后 App 会按路线持续计算左右推进，并每 100ms 发送控制心跳。")
                Text("请确认：主控已连接并手动解锁，${navigationGpsSource.label()} 已定位，航向源有效，人员随时可接管。")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RequirementLine("连接", connectionText(state.connectionState), connectionReady)
                    RequirementLine("解锁", if (armedReady) "是" else "否", armedReady)
                    RequirementLine(navigationGpsSource.label(), gpsStateText, gpsReady)
                    RequirementLine(if (navigationGpsSource == NavigationGpsSource.Phone) "定位等效" else "卫星", satelliteCount.toString(), satelliteReady)
                    RequirementLine("航向", "$headingSourceText ${if (headingReady) "可用" else "不可用"}", headingReady)
                    RequirementLine("路线", "${route.points.size} 点", routeReady)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = ready,
            ) {
                Text("确认启动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SelectedRouteControls(
    route: NavigationRoute,
    message: String,
    onClose: () -> Unit,
    onExecute: () -> Unit,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameDialogVisible by remember { mutableStateOf(false) }
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
            TextButton(onClick = { renameDialogVisible = true }) {
                Text("改名")
            }
            OutlinedButton(onClick = onExecute, enabled = route.points.size >= 2) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("执行")
            }
        }
    }
    if (renameDialogVisible) {
        RenameRouteDialog(
            route = route,
            onDismiss = { renameDialogVisible = false },
            onConfirm = { name ->
                renameDialogVisible = false
                onRename(name)
            },
        )
    }
}

@Composable
private fun RenameRouteDialog(
    route: NavigationRoute,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(route.id) { mutableStateOf(route.name) }
    val normalizedName = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("路线改名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("路线名") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedName) },
                enabled = normalizedName.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
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
    onExecuteRoute: (NavigationRoute) -> Unit,
    onEditRoute: (String) -> Unit,
    onDeleteRoute: (String) -> Unit,
    onRenameRoute: (String, String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<NavigationRoute?>(null) }
    var pendingRename by remember { mutableStateOf<NavigationRoute?>(null) }

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
                                onExecute = { onExecuteRoute(route) },
                                onEdit = { onEditRoute(route.id) },
                                onRename = { pendingRename = route },
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

    pendingRename?.let { route ->
        RenameRouteDialog(
            route = route,
            onDismiss = { pendingRename = null },
            onConfirm = { name ->
                onRenameRoute(route.id, name)
                pendingRename = null
            },
        )
    }

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
    onExecute: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onSelect) {
                    Text(if (selected) "已选择" else "选择")
                }
                TextButton(onClick = onRename) {
                    Text("改名")
                }
                IconButton(
                    onClick = onExecute,
                    enabled = route.points.size >= 2 && !executing,
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "执行路线")
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
                    "手机本地还没有轨迹。连接主控后先同步轨迹缓存。",
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
    liveHeadingDegrees: Float?,
    liveHeadingSourceText: String,
    trackPoints: List<LatLng>,
    playbackLocation: LatLng?,
    playbackBearing: Double?,
    routePoints: List<LatLng>,
    routeEditing: Boolean,
    selectedRoutePointIndex: Int?,
    routeTarget: LatLng?,
    onRoutePointAdd: (LatLng) -> Unit,
    onRoutePointSelected: (Int) -> Unit,
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

    DisposableEffect(mapView, routeEditing, onRoutePointAdd, onRoutePointSelected) {
        var mapRef: MapLibreMap? = null
        val listener = MapLibreMap.OnMapClickListener { point ->
            onRoutePointAdd(point)
            true
        }
        val markerListener = MapLibreMap.OnMarkerClickListener { marker ->
            val routeMarkerIndex = annotations.routeMarkerIndex(marker)
            if (routeEditing && routeMarkerIndex != null) {
                onRoutePointSelected(routeMarkerIndex)
                true
            } else {
                false
            }
        }
        if (routeEditing) {
            mapView.getMapAsync { map ->
                mapRef = map
                map.addOnMapClickListener(listener)
                map.setOnMarkerClickListener(markerListener)
            }
        }
        onDispose {
            mapRef?.removeOnMapClickListener(listener)
            mapRef?.setOnMarkerClickListener(null)
        }
    }

    LaunchedEffect(liveLocation, liveHeadingDegrees, liveHeadingSourceText, trackPoints, playbackLocation, routePoints, selectedRoutePointIndex, routeTarget, pathColor, routeColor, styleUrl) {
        mapView.getMapAsync { map ->
            renderTrackMap(
                map = map,
                styleUrl = styleUrl,
                liveLocation = liveLocation,
                liveHeadingDegrees = liveHeadingDegrees,
                liveHeadingSourceText = liveHeadingSourceText,
                trackPoints = trackPoints,
                playbackLocation = playbackLocation,
                playbackBearing = playbackBearing,
                routePoints = routePoints,
                selectedRoutePointIndex = selectedRoutePointIndex,
                routeTarget = routeTarget,
                pathColor = pathColor,
                routeColor = routeColor,
                annotations = annotations,
                context = context,
            )
        }
    }

    DisposableEffect(mapView, liveLocation, liveHeadingDegrees, liveHeadingSourceText, trackPoints, playbackLocation, routePoints, selectedRoutePointIndex, routeTarget, pathColor, routeColor, styleUrl) {
        var mapRef: MapLibreMap? = null
        val listener = MapLibreMap.OnCameraIdleListener {
            mapRef?.let { map ->
                renderTrackMap(
                    map = map,
                    styleUrl = styleUrl,
                    liveLocation = liveLocation,
                    liveHeadingDegrees = liveHeadingDegrees,
                    liveHeadingSourceText = liveHeadingSourceText,
                    trackPoints = trackPoints,
                    playbackLocation = playbackLocation,
                    playbackBearing = playbackBearing,
                    routePoints = routePoints,
                    selectedRoutePointIndex = selectedRoutePointIndex,
                    routeTarget = routeTarget,
                    pathColor = pathColor,
                    routeColor = routeColor,
                    annotations = annotations,
                    context = context,
                )
            }
        }
        mapView.getMapAsync { map ->
            mapRef = map
            map.addOnCameraIdleListener(listener)
        }
        onDispose {
            mapRef?.removeOnCameraIdleListener(listener)
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
    liveHeadingDegrees: Float?,
    liveHeadingSourceText: String,
    trackPoints: List<LatLng>,
    playbackLocation: LatLng?,
    playbackBearing: Double?,
    routePoints: List<LatLng>,
    selectedRoutePointIndex: Int?,
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
                liveHeadingDegrees = liveHeadingDegrees,
                liveHeadingSourceText = liveHeadingSourceText,
                trackPoints = trackPoints,
                playbackLocation = playbackLocation,
                playbackBearing = playbackBearing,
                routePoints = routePoints,
                selectedRoutePointIndex = selectedRoutePointIndex,
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
        liveHeadingDegrees = liveHeadingDegrees,
        liveHeadingSourceText = liveHeadingSourceText,
        trackPoints = trackPoints,
        playbackLocation = playbackLocation,
        playbackBearing = playbackBearing,
        routePoints = routePoints,
        selectedRoutePointIndex = selectedRoutePointIndex,
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
    liveHeadingDegrees: Float?,
    liveHeadingSourceText: String,
    trackPoints: List<LatLng>,
    playbackLocation: LatLng?,
    playbackBearing: Double?,
    routePoints: List<LatLng>,
    selectedRoutePointIndex: Int?,
    routeTarget: LatLng?,
    pathColor: Int,
    routeColor: Int,
    annotations: TrackMapAnnotations,
    context: Context,
) {
    val arrowConfig = trackArrowRenderConfig(map.cameraPosition.zoom)
    val mapBearing = map.cameraPosition.bearing
    val mapBearingBucket = mapBearing.bearingBucket()
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
        val arrowSignature = "$trackSignature:${arrowConfig.signature}:map=$mapBearingBucket"
        if (annotations.directionArrowSignature != arrowSignature) {
            updateTrackDirectionArrows(
                map = map,
                trackPoints = trackPoints,
                config = arrowConfig,
                annotations = annotations,
                context = context,
                mapBearing = mapBearing,
            )
            annotations.directionArrowSignature = arrowSignature
        }
        annotations.trackSignature = trackSignature
    } else {
        annotations.trackPolyline?.let(map::removePolyline)
        annotations.trackPolyline = null
        clearTrackDirectionArrows(map, annotations)
        annotations.trackSignature = null
        annotations.directionArrowSignature = null
    }

    if (liveLocation != null) {
        val bearingBucket = liveHeadingDegrees?.toDouble()?.screenBearingBucket(mapBearing)
        val title = if (liveHeadingDegrees != null) {
            "实时位置 · $liveHeadingSourceText ${liveHeadingDegrees.roundToInt()}°"
        } else {
            "实时位置"
        }
        val marker = annotations.liveMarker
        if (marker == null || annotations.liveHeadingBearingBucket != bearingBucket) {
            marker?.let(map::removeMarker)
            val options = MarkerOptions()
                .position(liveLocation)
                .title(title)
            if (bearingBucket != null) {
                options.icon(
                    annotations.iconForBearing(
                        context = context,
                        bearingBucket = bearingBucket,
                        sizePx = LIVE_HEADING_ARROW_ICON_SIZE_PX,
                        color = LIVE_HEADING_ARROW_COLOR,
                    ),
                )
            }
            annotations.liveMarker = map.addMarker(
                options,
            )
            annotations.liveHeadingBearingBucket = bearingBucket
        } else {
            marker.position = liveLocation
            marker.title = title
        }
    } else {
        annotations.liveMarker?.let(map::removeMarker)
        annotations.liveMarker = null
        annotations.liveHeadingBearingBucket = null
    }

    if (playbackLocation != null) {
        val marker = annotations.playbackMarker
        val bearingBucket = playbackBearing?.screenBearingBucket(mapBearing) ?: 0
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
    val routeMarkerSignature = "$routeSignature:selected=$selectedRoutePointIndex"
    if (annotations.routeMarkerSignature != routeMarkerSignature) {
        clearRouteMarkers(map, annotations)
        routePoints.forEachIndexed { index, point ->
            val selected = index == selectedRoutePointIndex
            val marker = map.addMarker(
                MarkerOptions()
                    .position(point)
                    .title("航点 ${index + 1}")
                    .icon(
                        annotations.routePointIcon(
                            context = context,
                            index = index,
                            selected = selected,
                            color = routeColor,
                        ),
                    ),
            )
            annotations.routeMarkers += marker
            annotations.routeMarkerIndexes[marker] = index
        }
        annotations.routeMarkerSignature = routeMarkerSignature
    }
    annotations.routeSignature = routeSignature

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
    var liveHeadingBearingBucket: Int? = null
    var playbackBearingBucket: Int? = null
    var trackSignature: String? = null
    var directionArrowSignature: String? = null
    var routeSignature: String? = null
    var routeMarkerSignature: String? = null
    val directionArrowMarkers: MutableList<Marker> = mutableListOf()
    val routeMarkers: MutableList<Marker> = mutableListOf()
    val routeMarkerIndexes: MutableMap<Marker, Int> = mutableMapOf()
    private val iconCache: MutableMap<String, Icon> = mutableMapOf()

    fun iconForBearing(context: Context, bearingBucket: Int, sizePx: Int, color: Int): Icon {
        val key = "$sizePx:$color:$bearingBucket"
        return iconCache.getOrPut(key) {
            IconFactory.getInstance(context).fromBitmap(createArrowBitmap(sizePx, color, bearingBucket.toFloat()))
        }
    }

    fun routeMarkerIndex(marker: Marker): Int? {
        return routeMarkerIndexes[marker]
    }

    fun routePointIcon(context: Context, index: Int, selected: Boolean, color: Int): Icon {
        val key = "route-point:$index:$selected:$color"
        return iconCache.getOrPut(key) {
            IconFactory.getInstance(context).fromBitmap(createRoutePointBitmap(index = index, selected = selected, color = color))
        }
    }
}

private fun clearRouteMarkers(map: MapLibreMap, annotations: TrackMapAnnotations) {
    annotations.routeMarkers.forEach(map::removeMarker)
    annotations.routeMarkers.clear()
    annotations.routeMarkerIndexes.clear()
    annotations.routeMarkerSignature = null
}

private fun updateTrackDirectionArrows(
    map: MapLibreMap,
    trackPoints: List<LatLng>,
    config: TrackArrowRenderConfig,
    annotations: TrackMapAnnotations,
    context: Context,
    mapBearing: Double,
) {
    clearTrackDirectionArrows(map, annotations)
    if (config.maxCount <= 0) {
        return
    }
    sampledDirectionArrows(trackPoints, config).forEach { arrow ->
        val icon = annotations.iconForBearing(
            context = context,
            bearingBucket = arrow.bearing.screenBearingBucket(mapBearing),
            sizePx = config.iconSizePx,
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

private data class TrackArrowRenderConfig(
    val zoomBucket: Int,
    val iconSizePx: Int,
    val minSpacingMeters: Double,
    val maxCount: Int,
) {
    val signature: String
        get() = "$zoomBucket:$iconSizePx:$minSpacingMeters:$maxCount"
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

private fun ControlUiState.liveLatLng(source: NavigationGpsSource): LatLng? {
    return when (source) {
        NavigationGpsSource.Esp32 -> telemetry.liveLatLng()
        NavigationGpsSource.Phone -> phoneGps.liveLatLng()
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

private fun PhoneGpsState.liveLatLng(): LatLng? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return LatLng(lat, lon)
}

private fun ControlUiState.gpsFixText(source: NavigationGpsSource): String {
    return when (source) {
        NavigationGpsSource.Esp32 -> telemetry.gpsFixText()
        NavigationGpsSource.Phone -> phoneGps.gpsFixText()
    }
}

private fun com.smartsup.controller.model.Telemetry.gpsFixText(): String {
    return when (statusFields["GPS_FIX"]) {
        "1" -> "已定位"
        "0" -> "未定位"
        else -> "--"
    }
}

private fun PhoneGpsState.gpsFixText(): String {
    if (!hasLocation) {
        return "未定位"
    }
    val accuracy = accuracyMeters
    return if (accuracy != null) {
        "已定位 · 精度 ${accuracy.roundToInt()}m"
    } else {
        "已定位"
    }
}

private fun ControlUiState.gpsSpeedText(source: NavigationGpsSource): String? {
    return when (source) {
        NavigationGpsSource.Esp32 -> telemetry.gpsSpeedText()
        NavigationGpsSource.Phone -> phoneGps.gpsSpeedText()
    }
}

private fun com.smartsup.controller.model.Telemetry.gpsSpeedText(): String? {
    if (statusFields["GPS_FIX"] != "1") {
        return null
    }
    val speedKmh = statusFields["GPS_SPD_KMH"]?.toDoubleOrNull()
        ?: statusFields["SPD_KMH"]?.toDoubleOrNull()
        ?: return "-- km/h"
    return String.format(Locale.US, "%.1f km/h", speedKmh.coerceAtLeast(0.0))
}

private fun PhoneGpsState.gpsSpeedText(): String? {
    if (!hasLocation) {
        return null
    }
    val speed = speedKmh ?: return "-- km/h"
    return String.format(Locale.US, "%.1f km/h", speed.coerceAtLeast(0.0))
}

private fun String?.speedBadgeText(): String {
    val raw = this ?: return "--"
    val value = raw
        .replace("km/h", "")
        .trim()
    return value.ifBlank { "--" }
}

private fun ControlUiState.hasNavigationGpsFix(source: NavigationGpsSource): Boolean {
    return when (source) {
        NavigationGpsSource.Esp32 -> telemetry.statusFields["GPS_FIX"] == "1"
        NavigationGpsSource.Phone -> phoneGps.hasLocation &&
            (phoneGps.accuracyMeters == null || phoneGps.accuracyMeters <= 25f)
    }
}

private fun ControlUiState.hasNavigationGpsLocation(source: NavigationGpsSource): Boolean {
    return when (source) {
        NavigationGpsSource.Esp32 -> telemetry.statusFields["GPS_FIX"] == "1"
        NavigationGpsSource.Phone -> phoneGps.hasLocation
    }
}

private fun ControlUiState.navigationGpsSatelliteCount(source: NavigationGpsSource): Int {
    return when (source) {
        NavigationGpsSource.Esp32 -> telemetry.statusFields["GPS_SAT"]?.toIntOrNull() ?: 0
        NavigationGpsSource.Phone -> if (hasNavigationGpsLocation(source)) 4 else 0
    }
}

private fun ControlUiState.navigationGpsAccuracyText(source: NavigationGpsSource): String? {
    return when (source) {
        NavigationGpsSource.Esp32 -> null
        NavigationGpsSource.Phone -> {
            val accuracy = phoneGps.accuracyMeters
            if (accuracy != null) {
                "精度不足 ${accuracy.roundToInt()}m"
            } else {
                "精度不足"
            }
        }
    }
}

private fun NavigationGpsSource.label(): String {
    return when (this) {
        NavigationGpsSource.Esp32 -> "ESP32 GPS"
        NavigationGpsSource.Phone -> "手机 GPS"
    }
}

private fun ControlUiState.navigationHeadingDegrees(
    usePhoneHeading: Boolean,
    phoneHeadingOffsetDegrees: Float,
    ybImuHeadingOffsetDegrees: Float,
    ybImuHeadingMode: Int,
): Float? {
    return if (usePhoneHeading) {
        phoneHeadingDegrees
            ?.takeIf { phoneHeadingAvailable }
            ?.let { normalizeCompassDegrees(it) }
    } else {
        telemetry
            .takeIf { it.ybImuAvailable == true }
            ?.let {
                ybImuHeadingDegrees(
                    telemetry = it,
                    offsetDegrees = magneticDeclinationDegrees ?: 0f,
                    modeId = YB_IMU_HEADING_MODE_YBY_INVERTED,
                )
            }
    }
}

private fun normalizeCompassDegrees(degrees: Float): Float {
    val normalized = degrees % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

private fun connectionText(connectionState: ConnectionState): String {
    return when (connectionState) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Connecting -> "连接中"
        ConnectionState.Connected -> "已连接"
    }
}

private fun formatPlaybackTimeText(points: List<GpsTrackPoint>, position: Float): String {
    if (points.isEmpty()) {
        return "--"
    }
    val startIndex = floor(position).toInt().coerceIn(0, points.lastIndex)
    val endIndex = (startIndex + 1).coerceAtMost(points.lastIndex)
    val fraction = (position - startIndex.toFloat()).coerceIn(0f, 1f)
    val startTime = points[startIndex].utcSeconds
    val endTime = points[endIndex].utcSeconds
    val interpolatedTime = startTime + ((endTime - startTime) * fraction).roundToInt()
    return DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(interpolatedTime))
}

private fun formatPlaybackTravelSpeedText(points: List<GpsTrackPoint>, position: Float): String {
    if (points.size < 2) {
        return "-- km/h"
    }
    val fromIndex = floor(position - PLAYBACK_BEARING_LOOKAROUND_POINTS)
        .toInt()
        .coerceAtLeast(0)
    val toIndex = ceil(position + PLAYBACK_BEARING_LOOKAROUND_POINTS)
        .toInt()
        .coerceAtMost(points.lastIndex)
    if (toIndex <= fromIndex) {
        return "-- km/h"
    }
    val elapsedSeconds = (points[toIndex].utcSeconds - points[fromIndex].utcSeconds).coerceAtLeast(1)
    val from = smoothedTrackLocation(points, fromIndex.toFloat()) ?: return "-- km/h"
    val to = smoothedTrackLocation(points, toIndex.toFloat()) ?: return "-- km/h"
    val speedKmh = distanceMeters(from, to) / elapsedSeconds * 3.6
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

private fun smoothedTrackLocation(points: List<GpsTrackPoint>, position: Float): LatLng? {
    if (points.isEmpty()) {
        return null
    }
    if (points.size < TRACK_SMOOTHING_WINDOW) {
        return interpolateTrackLocation(points, position)
    }
    val halfWindow = TRACK_SMOOTHING_WINDOW / 2f
    val start = floor(position - halfWindow).toInt().coerceAtLeast(0)
    val end = ceil(position + halfWindow).toInt().coerceAtMost(points.lastIndex)
    var lat = 0.0
    var lon = 0.0
    var weightTotal = 0.0
    for (index in start..end) {
        val distance = kotlin.math.abs(index - position)
        val weight = (halfWindow + 1f - distance).coerceAtLeast(0.25f).toDouble()
        lat += points[index].latitude * weight
        lon += points[index].longitude * weight
        weightTotal += weight
    }
    return LatLng(lat / weightTotal, lon / weightTotal)
}

private fun smoothedBearingAtTrackPosition(points: List<GpsTrackPoint>, position: Float): Double? {
    if (points.size < 2) {
        return null
    }
    val fromPosition = (position - PLAYBACK_BEARING_LOOKAROUND_POINTS).coerceAtLeast(0f)
    val toPosition = (position + PLAYBACK_BEARING_LOOKAROUND_POINTS).coerceAtMost(points.lastIndex.toFloat())
    if (toPosition <= fromPosition) {
        return null
    }
    val from = smoothedTrackLocation(points, fromPosition) ?: return null
    val to = smoothedTrackLocation(points, toPosition) ?: return null
    if (distanceMeters(from, to) < 1.0) {
        return null
    }
    return bearingBetween(from, to)
}

private fun smoothTrackPoints(points: List<LatLng>): List<LatLng> {
    if (points.size < TRACK_SMOOTHING_WINDOW) {
        return points
    }
    val smoothed = points.mapIndexed { index, point ->
        if (index == 0 || index == points.lastIndex) {
            point
        } else {
            val start = (index - TRACK_SMOOTHING_WINDOW / 2).coerceAtLeast(0)
            val end = (index + TRACK_SMOOTHING_WINDOW / 2).coerceAtMost(points.lastIndex)
            var lat = 0.0
            var lon = 0.0
            var weightTotal = 0.0
            for (neighborIndex in start..end) {
                val distance = kotlin.math.abs(neighborIndex - index)
                val weight = when (distance) {
                    0 -> 3.0
                    1 -> 2.0
                    else -> 1.0
                }
                lat += points[neighborIndex].latitude * weight
                lon += points[neighborIndex].longitude * weight
                weightTotal += weight
            }
            LatLng(lat / weightTotal, lon / weightTotal)
        }
    }
    return chaikinSmooth(smoothed)
}

private fun chaikinSmooth(points: List<LatLng>): List<LatLng> {
    if (points.size < 3) {
        return points
    }
    val result = ArrayList<LatLng>(points.size * 2)
    result += points.first()
    for (index in 0 until points.lastIndex) {
        val current = points[index]
        val next = points[index + 1]
        result += LatLng(
            current.latitude * 0.75 + next.latitude * 0.25,
            current.longitude * 0.75 + next.longitude * 0.25,
        )
        result += LatLng(
            current.latitude * 0.25 + next.latitude * 0.75,
            current.longitude * 0.25 + next.longitude * 0.75,
        )
    }
    result += points.last()
    return result
}

private fun trackArrowRenderConfig(zoom: Double): TrackArrowRenderConfig {
    val zoomBucket = floor(zoom).toInt()
    return when {
        zoom < TRACK_ARROW_FAR_ZOOM_MAX -> TrackArrowRenderConfig(
            zoomBucket = zoomBucket,
            iconSizePx = 18,
            minSpacingMeters = 900.0,
            maxCount = 4,
        )
        zoom < TRACK_ARROW_MID_ZOOM_MAX -> TrackArrowRenderConfig(
            zoomBucket = zoomBucket,
            iconSizePx = 22,
            minSpacingMeters = 420.0,
            maxCount = 7,
        )
        zoom < TRACK_ARROW_NEAR_ZOOM_MAX -> TrackArrowRenderConfig(
            zoomBucket = zoomBucket,
            iconSizePx = 28,
            minSpacingMeters = 150.0,
            maxCount = 14,
        )
        else -> TrackArrowRenderConfig(
            zoomBucket = zoomBucket,
            iconSizePx = 34,
            minSpacingMeters = 55.0,
            maxCount = 28,
        )
    }
}

private fun sampledDirectionArrows(
    trackPoints: List<LatLng>,
    config: TrackArrowRenderConfig,
): List<DirectionArrow> {
    if (trackPoints.size < 2) {
        return emptyList()
    }
    var totalDistanceMeters = 0.0
    for (index in 1 until trackPoints.size) {
        totalDistanceMeters += distanceMeters(trackPoints[index - 1], trackPoints[index])
    }
    if (totalDistanceMeters <= 0.0) {
        return emptyList()
    }

    val arrows = mutableListOf<DirectionArrow>()
    val spacingMeters = max(config.minSpacingMeters, totalDistanceMeters / (config.maxCount + 1))
    var nextArrowAtMeters = spacingMeters
    var walkedMeters = 0.0
    for (index in 1 until trackPoints.size) {
        val previous = trackPoints[index - 1]
        val current = trackPoints[index]
        val segmentMeters = distanceMeters(previous, current)
        if (segmentMeters <= 0.0) {
            continue
        }
        if (walkedMeters + segmentMeters >= nextArrowAtMeters) {
            val fraction = ((nextArrowAtMeters - walkedMeters) / segmentMeters).coerceIn(0.0, 1.0)
            val position = LatLng(
                previous.latitude + (current.latitude - previous.latitude) * fraction,
                previous.longitude + (current.longitude - previous.longitude) * fraction,
            )
            bearingBetween(previous, current)?.let { bearing ->
                arrows += DirectionArrow(position = position, bearing = bearing)
            }
            if (arrows.size >= config.maxCount) {
                break
            }
            nextArrowAtMeters += spacingMeters
        }
        walkedMeters += segmentMeters
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

private fun Double.screenBearingBucket(mapBearingDegrees: Double): Int {
    return (this - mapBearingDegrees).bearingBucket()
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

private fun createRoutePointBitmap(index: Int, selected: Boolean, color: Int): Bitmap {
    val sizePx = if (selected) 76 else 66
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centerX = sizePx / 2f
    val circleCenterY = sizePx * 0.40f
    val radius = sizePx * 0.28f
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (selected) color else Color.WHITE
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (selected) Color.WHITE else color
        style = Paint.Style.STROKE
        strokeWidth = if (selected) sizePx * 0.07f else sizePx * 0.06f
    }
    val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (selected) color else Color.WHITE
        style = Paint.Style.FILL
    }
    val pointerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (selected) Color.WHITE else color
        style = Paint.Style.STROKE
        strokeWidth = strokePaint.strokeWidth
        strokeJoin = Paint.Join.ROUND
    }
    val pointer = Path().apply {
        moveTo(centerX - radius * 0.55f, circleCenterY + radius * 0.62f)
        lineTo(centerX, sizePx * 0.92f)
        lineTo(centerX + radius * 0.55f, circleCenterY + radius * 0.62f)
        close()
    }
    canvas.drawPath(pointer, pointerPaint)
    canvas.drawPath(pointer, pointerStrokePaint)
    canvas.drawCircle(centerX, circleCenterY, radius, fillPaint)
    canvas.drawCircle(centerX, circleCenterY, radius, strokePaint)

    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (selected) Color.WHITE else color
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.30f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val label = (index + 1).toString()
    val textBaseline = circleCenterY - (numberPaint.descent() + numberPaint.ascent()) / 2f
    canvas.drawText(label, centerX, textBaseline, numberPaint)
    return bitmap
}

private fun com.smartsup.controller.model.AutoNavigationUiState.routePointsForMap(): List<LatLng> {
    val routeId = executingRouteId ?: selectedRouteId
    return routes.firstOrNull { it.id == routeId }
        ?.points
        ?.map { LatLng(it.latitude, it.longitude) }
        .orEmpty()
}

private fun com.smartsup.controller.model.AutoNavigationUiState.trackLinePointsForMap(): List<LatLng> {
    val origin = trackLineOrigin ?: return emptyList()
    val bearing = trackLineBearingDegrees ?: return emptyList()
    val start = LatLng(origin.latitude, origin.longitude)
    val end = projectPoint(start, bearing.toDouble(), TRACK_LINE_DISPLAY_LENGTH_METERS)
    return listOf(start, end)
}

private fun projectPoint(from: LatLng, bearingDegrees: Double, distanceMeters: Double): LatLng {
    val earthRadiusMeters = 6_371_000.0
    val angularDistance = distanceMeters / earthRadiusMeters
    val bearingRadians = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(from.latitude)
    val lon1 = Math.toRadians(from.longitude)
    val lat2 = kotlin.math.asin(
        sin(lat1) * cos(angularDistance) +
            cos(lat1) * sin(angularDistance) * cos(bearingRadians),
    )
    val lon2 = lon1 + atan2(
        sin(bearingRadians) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )
    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

private fun Double.crossTrackText(): String {
    val direction = when {
        this > 0.2 -> "右"
        this < -0.2 -> "左"
        else -> "中"
    }
    return if (direction == "中") {
        "${abs(this).roundToInt()}m"
    } else {
        "${abs(this).roundToInt()}m$direction"
    }
}

private fun Double.signedMetersText(): String {
    return when {
        this > 0.2 -> "+${abs(this).roundToInt()}m"
        this < -0.2 -> "-${abs(this).roundToInt()}m"
        else -> "0m"
    }
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
