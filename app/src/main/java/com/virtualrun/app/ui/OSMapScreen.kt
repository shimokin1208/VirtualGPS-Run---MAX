package com.virtualrun.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.RouteClassifier
import com.virtualrun.app.algorithm.RouteRules
import com.virtualrun.app.map.CHINA_MAP_MAX_ZOOM_LEVEL
import com.virtualrun.app.map.CHINA_MAP_MIN_ZOOM_LEVEL
import com.virtualrun.app.map.ChinaMapTileSource
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.map.GeoMath
import com.virtualrun.app.map.MapMode
import com.virtualrun.app.persist.RoutePointType
import com.virtualrun.app.service.MockLocationService
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

private val PlannedRouteColor = Color(0xFF1E88E5)
private val ActualTrailColor = Color(0xFFFF7043)
private val RunGreen = Color(0xFF2EAD65)
private val StopRed = Color(0xFFE04F5F)
private val StartGreen = Color(0xFF2EAD65)
private val ViaBlue = Color(0xFF1E88E5)
private val EndRed = Color(0xFFE04F5F)
private const val MIN_CURRENT_PACE_DISPLAY_SPEED_MPS = 0.6f

private enum class RunUiErrorAction {
    DISMISS,
    APP_SETTINGS,
    DEVELOPER_SETTINGS
}

private data class RunUiError(
    val message: String,
    val action: RunUiErrorAction
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSMapScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val routePoints by viewModel.routePoints.collectAsState()
    val routeName by viewModel.routeName.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val basePace by viewModel.basePace.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val lapCount by viewModel.lapCount.collectAsState()
    val isLoopMode by viewModel.isLoopMode.collectAsState()
    val currentSpeed by viewModel.speed.collectAsState()
    val draftPoint by viewModel.draftPoint.collectAsState()
    val isSelecting by viewModel.isSelecting.collectAsState()
    val notice by viewModel.notice.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    val mapPreferences = remember(context) { context.getSharedPreferences("map_display", Context.MODE_PRIVATE) }
    var mapMode by remember { mutableStateOf(MapMode.fromPreference(mapPreferences.getString("mode", null))) }
    var sliderPace by remember(basePace) { mutableFloatStateOf(basePace) }
    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    var routeMarkers by remember { mutableStateOf<FolderOverlay?>(null) }
    var actualTrail by remember { mutableStateOf<Polyline?>(null) }
    val rawTrailPoints = remember { mutableListOf<GeoPoint>() }
    var currentMarker by remember { mutableStateOf<Marker?>(null) }
    var markerAnimator by remember { mutableStateOf<ValueAnimator?>(null) }
    var pendingLocationListener by remember { mutableStateOf<LocationListener?>(null) }
    var lastCameraFollowAt by remember { mutableLongStateOf(0L) }
    var wasRunning by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<RunUiError?>(null) }
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var hasNetwork by remember { mutableStateOf(true) }
    var gpsEnabled by remember { mutableStateOf(true) }
    var mockAuthorized by remember { mutableStateOf(true) }

    // 光标位置去抖用的非状态容器，避免每一帧滚动都触发重组。
    val lastDraftHolder = remember { arrayOfNulls<LatLng>(1) }

    val animatedProgress = remember { Animatable(0f) }
    var animatedProgressLap by remember { mutableIntStateOf(0) }
    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed,
        animationSpec = tween(durationMillis = 180, easing = LinearEasing),
        label = "current speed"
    )
    val actionColor by animateColorAsState(
        targetValue = if (isRunning) StopRed else RunGreen,
        animationSpec = tween(250),
        label = "run action color"
    )
    val locationButtonOffset by animateDpAsState(
        targetValue = if (isSelecting || routePoints.size >= 2) (-300).dp else (-210).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "location button offset"
    )

    fun syncDraftFromCenter() {
        val mv = mapView ?: return
        if (mv.width == 0 || mv.height == 0) return
        val center = mv.mapCenter ?: return
        if (!GeoMath.isValidLatLng(center.latitude, center.longitude)) return
        val previous = lastDraftHolder[0]
        if (previous != null && GeoMath.distanceMeters(
                previous.latitude, previous.longitude,
                center.latitude, center.longitude
            ) < 0.15
        ) {
            return
        }
        val point = LatLng(center.latitude, center.longitude)
        lastDraftHolder[0] = point
        viewModel.setDraftPoint(point)
    }

    fun animateToDeviceLocation(location: Location) {
        val gcjLocation = CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
        mapView?.controller?.animateTo(GeoPoint(gcjLocation.first, gcjLocation.second), 18.0, 1000L)
    }

    // 一次性提示
    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }

    // 网络 / 系统定位状态巡检（用于状态栏与瓦片失败提示）
    LaunchedEffect(Unit) {
        while (true) {
            hasNetwork = isNetworkAvailable(context)
            gpsEnabled = runCatching {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            mockAuthorized = runCatching { MockLocationService.isMockEnabled(context) }.getOrDefault(false)
            delay(3000)
        }
    }

    LaunchedEffect(progress, lapCount, isRunning) {
        if (!isRunning) {
            animatedProgress.snapTo(0f)
        } else if (lapCount != animatedProgressLap || progress + 0.05f < animatedProgress.value) {
            animatedProgress.snapTo(progress)
        } else {
            animatedProgress.animateTo(progress, tween(850, easing = LinearEasing))
        }
        animatedProgressLap = lapCount
    }

    LaunchedEffect(isRunning, mapView) {
        if (isRunning && !wasRunning) {
            markerAnimator?.cancel()
            rawTrailPoints.clear()
            actualTrail?.let { mapView?.overlays?.remove(it) }
            actualTrail = null
            lastCameraFollowAt = 0L
        }
        wasRunning = isRunning
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val activeMap = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activeMap?.onResume()
                Lifecycle.Event.ON_PAUSE -> activeMap?.onPause()
                Lifecycle.Event.ON_DESTROY -> activeMap?.onDetach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            markerAnimator?.cancel()
        }
    }

    DisposableEffect(pendingLocationListener) {
        val listener = pendingLocationListener
        onDispose {
            if (listener != null) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    // 计划路线与点位标记
    LaunchedEffect(mapView, routePoints, isLoopMode, isRunning) {
        val mv = mapView ?: return@LaunchedEffect
        routePolyline?.let(mv.overlays::remove)
        routeMarkers?.let(mv.overlays::remove)
        routePolyline = null
        routeMarkers = null

        if (routePoints.size >= 2) {
            routePolyline = Polyline(mv).apply {
                routePoints.forEach { addPoint(GeoPoint(it.latitude, it.longitude)) }
                outlinePaint.apply {
                    color = PlannedRouteColor.toArgb()
                    strokeWidth = 6f
                }
            }.also(mv.overlays::add)
        }

        if (routePoints.isNotEmpty()) {
            val types = RouteClassifier.classify(routePoints)
            routeMarkers = FolderOverlay().also { folder ->
                routePoints.forEachIndexed { index, point ->
                    val type = types.getOrElse(index) { RoutePointType.VIA }
                    val marker = Marker(mv).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        icon = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(type.androidColor())
                            setStroke(if (type == RoutePointType.VIA) 2 else 3, android.graphics.Color.WHITE)
                            val targetDp = when (type) {
                                RoutePointType.START -> 30
                                RoutePointType.END -> 26
                                RoutePointType.VIA -> 18
                            }
                            val targetPx = (targetDp * context.resources.displayMetrics.density).roundToInt()
                            setSize(targetPx, targetPx)
                        }
                        setOnMarkerClickListener { _, _ ->
                            if (!isRunning) pendingDeleteIndex = index
                            true
                        }
                    }
                    folder.add(marker)
                }
                mv.overlays.add(folder)
            }
        }

        if (routePoints.isEmpty() && !isRunning) {
            actualTrail?.let(mv.overlays::remove)
            actualTrail = null
            rawTrailPoints.clear()
        }
        // 稳定层级：计划线 < 路线点 < 原始实跑线 < 当前定位点
        actualTrail?.let {
            mv.overlays.remove(it)
            mv.overlays.add(it)
        }
        currentMarker?.let {
            mv.overlays.remove(it)
            mv.overlays.add(it)
        }
        mv.invalidate()
    }

    LaunchedEffect(mapView, currentLocation) {
        val mv = mapView ?: return@LaunchedEffect
        val loc = currentLocation
        if (loc == null) {
            markerAnimator?.cancel()
            currentMarker?.let(mv.overlays::remove)
            currentMarker = null
            mv.invalidate()
            return@LaunchedEffect
        }

        val gcjLoc = CoordinateConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
        val target = GeoPoint(gcjLoc.first, gcjLoc.second)
        val previousTrailPoint = rawTrailPoints.lastOrNull()
        if (previousTrailPoint == null || previousTrailPoint.latitude != target.latitude || previousTrailPoint.longitude != target.longitude) {
            rawTrailPoints.add(target)
            if (rawTrailPoints.size > 3600) rawTrailPoints.removeAt(0)
        }

        val trail = actualTrail ?: Polyline(mv).apply {
            outlinePaint.apply {
                color = ActualTrailColor.toArgb()
                strokeWidth = 6f
            }
        }.also {
            actualTrail = it
            mv.overlays.add(it)
        }
        trail.setPoints(rawTrailPoints.toList())

        val marker = currentMarker ?: Marker(mv).apply {
            position = target
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#FF7043"))
                setStroke(5, android.graphics.Color.WHITE)
                setSize(44, 44)
            }
        }.also {
            currentMarker = it
            mv.overlays.add(it)
        }

        markerAnimator?.cancel()
        val start = GeoPoint(marker.position.latitude, marker.position.longitude)
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 880L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                marker.position = GeoPoint(
                    start.latitude + (target.latitude - start.latitude) * fraction,
                    start.longitude + (target.longitude - start.longitude) * fraction
                )
                mv.invalidate()
            }
            start()
        }

        val now = SystemClock.elapsedRealtime()
        if (isRunning && now - lastCameraFollowAt >= 2500L) {
            mv.controller.animateTo(target, mv.zoomLevelDouble, 900L)
            lastCameraFollowAt = now
        }
        mv.invalidate()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "路线地图。移动地图把中心光标对准目标位置，然后点确认落点"
                    onClick(label = "在中心光标处确认落点") {
                        if (!isRunning && isSelecting && viewModel.confirmDraftPoint()) true else false
                    }
                },
            factory = { ctx ->
                MapView(ctx).apply {
                    runCatching {
                        setTileSource(ChinaMapTileSource(mapMode))
                        setMinZoomLevel(CHINA_MAP_MIN_ZOOM_LEVEL.toDouble())
                        setMaxZoomLevel(CHINA_MAP_MAX_ZOOM_LEVEL.toDouble())
                    }.onFailure {
                        uiError = RunUiError("地图初始化失败，请稍后重试或切换到普通地图", RunUiErrorAction.DISMISS)
                    }
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(39.9042, 116.4074))
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            syncDraftFromCenter()
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            syncDraftFromCenter()
                            return false
                        }
                    })
                    mapView = this
                    post { syncDraftFromCenter() }
                }
            },
            update = { view ->
                if ((view.tileProvider.tileSource as? ChinaMapTileSource)?.mode != mapMode) {
                    // 只换底图：保留相机、路线覆盖物与运行状态
                    val zoom = view.zoomLevelDouble
                    val center = GeoPoint(view.mapCenter.latitude, view.mapCenter.longitude)
                    runCatching {
                        view.setTileSource(ChinaMapTileSource(mapMode))
                        view.setMinZoomLevel(CHINA_MAP_MIN_ZOOM_LEVEL.toDouble())
                        view.setMaxZoomLevel(CHINA_MAP_MAX_ZOOM_LEVEL.toDouble())
                    }.onFailure {
                        uiError = RunUiError("切换地图样式失败", RunUiErrorAction.DISMISS)
                    }
                    view.controller.setZoom(zoom)
                    view.controller.setCenter(center)
                    view.invalidate()
                }
            }
        )

        // 固定在屏幕中心的光标：只在选点模式下显示
        MapCenterCursor(
            visible = isSelecting && !isRunning,
            modifier = Modifier.align(Alignment.Center)
        )

        if (!hasNetwork) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    "当前无网络，地图瓦片可能无法加载（选点仍可使用已缓存区域）",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        AnimatedVisibility(
            visible = isRunning,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.DirectionsRun,
                        contentDescription = null,
                        tint = if (isPaused) Color.Gray else RunGreen
                    )
                    Text(
                        when {
                            isPaused -> "已暂停"
                            isLoopMode -> "跑步中 · 第 ${lapCount + 1} 圈"
                            else -> "跑步中"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        StatusStrip(
            modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp),
            routeCount = routePoints.size,
            isLoop = isLoopMode,
            mockAuthorized = mockAuthorized,
            gpsEnabled = gpsEnabled,
            currentLocation = currentLocation,
            isRunning = isRunning
        )

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    mapMode = mapMode.next()
                    mapPreferences.edit().putString("mode", mapMode.name).apply()
                },
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.semantics {
                    contentDescription = if (mapMode == MapMode.ROAD) "切换到卫星地图" else "切换到普通地图"
                }
            ) {
                Text(if (mapMode == MapMode.ROAD) "卫星" else "普通", modifier = Modifier.padding(horizontal = 8.dp))
            }
            SmallFloatingActionButton(
                onClick = {
                    mapView?.let { mv ->
                        if (mv.canZoomIn()) {
                            mv.controller.zoomIn()
                        } else {
                            Toast.makeText(context, "已达到地图最大缩放级别", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.86f)
            ) { Icon(Icons.Default.Add, "放大") }
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.zoomOut() },
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.86f)
            ) { Icon(Icons.Default.Remove, "缩小") }
        }

        FloatingActionButton(
            onClick = {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    uiError = RunUiError("请先授予位置权限", RunUiErrorAction.APP_SETTINGS)
                    return@FloatingActionButton
                }
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (location != null) {
                    pendingLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
                    pendingLocationListener = null
                    animateToDeviceLocation(location)
                } else {
                    pendingLocationListener?.let { runCatching { locationManager.removeUpdates(it) } }
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            animateToDeviceLocation(location)
                            runCatching { locationManager.removeUpdates(this) }
                            pendingLocationListener = null
                        }

                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    pendingLocationListener = listener
                    locationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            },
            modifier = Modifier.padding(16.dp).align(Alignment.BottomStart).offset(y = locationButtonOffset),
            containerColor = MaterialTheme.colorScheme.primary
        ) { Icon(Icons.Default.MyLocation, "定位") }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 选点面板：展示中心光标的预选经纬度，并提供确认 / 取消 / 重新选点
            if (isSelecting && !isRunning) {
                SelectionPanel(
                    draft = draftPoint,
                    nextIndex = routePoints.size,
                    onConfirm = {
                        if (draftPoint == null) {
                            uiError = RunUiError("地图还没准备好，请稍后重试", RunUiErrorAction.DISMISS)
                        } else if (!viewModel.confirmDraftPoint()) {
                            uiError = RunUiError("落点失败，请重试", RunUiErrorAction.DISMISS)
                        }
                    },
                    onCancel = { viewModel.cancelSelection() },
                    onReselect = {
                        viewModel.beginSelection()
                        if (routePoints.isNotEmpty()) {
                            val last = routePoints.last()
                            mapView?.controller?.animateTo(
                                GeoPoint(last.latitude, last.longitude), mapView?.zoomLevelDouble ?: 16.0, 600L
                            )
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            } else if (!isRunning) {
                ReselectBar(
                    routeEmpty = routePoints.isEmpty(),
                    onReselect = { viewModel.beginSelection() },
                    onRestore = {
                        if (!viewModel.restoreLastRoute()) {
                            uiError = RunUiError("没有可恢复的路线", RunUiErrorAction.DISMISS)
                        }
                    },
                    onRename = { showRenameDialog = true }
                )
                Spacer(Modifier.height(8.dp))
            }

            AnimatedVisibility(
                visible = routePoints.size >= 2,
                enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 3 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(220)) { it / 3 }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize(
                        spring(stiffness = Spring.StiffnessMediumLow)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(5.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(routeName, fontWeight = FontWeight.Bold)
                            AnimatedVisibility(visible = isLoopMode, enter = fadeIn(), exit = fadeOut()) {
                                Badge(containerColor = RunGreen) {
                                    Text(
                                        "闭环",
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val distanceKm = viewModel.getCurrentRouteDistance() / 1000
                            Text("距离: ${"%.2f".format(distanceKm)} km")
                            Text("预计: ${formatDuration(viewModel.getCurrentRouteDuration())}")
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LegendDot(StartGreen, "起点")
                            LegendDot(ViaBlue, "途经")
                            LegendDot(EndRed, "终点")
                            Text("共 ${routePoints.size} 点", fontSize = 11.sp, color = Color.Gray)
                        }
                        AnimatedVisibility(visible = isRunning, enter = fadeIn(tween(220)), exit = fadeOut(tween(150))) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { animatedProgress.value },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = RunGreen
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("进度: ${"%.1f".format(animatedProgress.value * 100)}%", fontSize = 12.sp)
                                    if (isLoopMode) Text("第 ${lapCount + 1} 圈", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize(
                    spring(stiffness = Spring.StiffnessMediumLow)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(5.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标配速: ${"%.1f".format(sliderPace)} 分/km", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (isRunning && animatedSpeed >= MIN_CURRENT_PACE_DISPLAY_SPEED_MPS) {
                            val currentPace = 1000f / (animatedSpeed.coerceAtLeast(0.1f) * 60f)
                            Text("当前: ${"%.1f".format(currentPace)} 分/km", fontSize = 14.sp, color = RunGreen)
                        }
                    }
                    Slider(
                        value = sliderPace,
                        onValueChange = { sliderPace = it },
                        onValueChangeFinished = { viewModel.setBasePace(sliderPace, context) },
                        valueRange = 3f..15f,
                        steps = 11
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("3'00\"", fontSize = 10.sp, color = Color.Gray)
                        Text("快", fontSize = 10.sp, color = Color.Gray)
                        Text("慢", fontSize = 10.sp, color = Color.Gray)
                        Text("15'00\"", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }

            if (isRunning) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (isPaused) viewModel.resumeRunning(context) else viewModel.pauseRunning(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) RunGreen else Color(0xFF6F7782)
                        ),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(
                            if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPaused) "继续" else "暂停", fontSize = 18.sp)
                    }
                    Button(
                        onClick = { viewModel.stopRunning(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = StopRed),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("停止", fontSize = 18.sp)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showClearConfirm = true },
                        enabled = routePoints.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6F7782)),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("清除", fontSize = 16.sp)
                    }

                    Button(
                        onClick = {
                            if (!viewModel.closeLoop()) {
                                uiError = RunUiError(
                                    "闭环需要至少 3 个点，且当前未闭环",
                                    RunUiErrorAction.DISMISS
                                )
                            }
                        },
                        enabled = RouteRules.canCloseLoop(routePoints),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.Loop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("闭环", fontSize = 16.sp)
                    }

                    Button(
                        onClick = {
                            if (routePoints.size < 2) {
                                uiError = RunUiError("请至少确认 2 个点", RunUiErrorAction.DISMISS)
                            } else if (!MockLocationService.isMockEnabled(context)) {
                                uiError = RunUiError(
                                    "请先在开发者选项中「选择模拟位置信息应用」为本应用",
                                    RunUiErrorAction.DEVELOPER_SETTINGS
                                )
                            } else {
                                viewModel.startRunning(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("开始", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    pendingDeleteIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { pendingDeleteIndex = null },
            title = { Text("删除点位") },
            text = { Text("确定删除第 ${index + 1} 个点吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removePointAt(index)
                    pendingDeleteIndex = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIndex = null }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空路线") },
            text = { Text("将删除全部 ${routePoints.size} 个点位。清空后可点「恢复」找回。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRoute()
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showRenameDialog) {
        var nameInput by remember { mutableStateOf(routeName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("路线名称") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRouteName(nameInput)
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    uiError?.let { error ->
        AlertDialog(
            onDismissRequest = { uiError = null },
            title = { Text("提示") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = {
                    uiError = null
                    when (error.action) {
                        RunUiErrorAction.DISMISS -> Unit
                        RunUiErrorAction.APP_SETTINGS -> try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开应用权限设置", Toast.LENGTH_SHORT).show()
                        }

                        RunUiErrorAction.DEVELOPER_SETTINGS -> try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(if (error.action == RunUiErrorAction.DISMISS) "知道了" else "去设置")
                }
            },
            dismissButton = if (error.action == RunUiErrorAction.DISMISS) null else {
                { TextButton(onClick = { uiError = null }) { Text("取消") } }
            }
        )
    }
}

/** 屏幕中央的预选光标：位置固定，只随地图移动而指向不同坐标。 */
@Composable
private fun MapCenterCursor(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120))
    ) {
        Canvas(modifier = Modifier.size(72.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val stroke = 2.dp.toPx()
            val radius = size.minDimension / 2f
            drawCircle(
                color = Color(0x221E88E5),
                radius = radius,
                center = center
            )
            drawCircle(
                color = Color(0x661E88E5),
                radius = radius,
                center = center,
                style = Stroke(stroke)
            )
            drawCircle(
                color = Color.White,
                radius = radius * 0.30f,
                center = center,
                style = Stroke(stroke * 2f)
            )
            drawCircle(color = Color(0xFF1E88E5), radius = radius * 0.14f, center = center)
            drawLine(
                Color.White,
                Offset(center.x, center.y - radius),
                Offset(center.x, center.y - radius * 0.42f),
                strokeWidth = stroke * 1.5f
            )
            drawLine(
                Color.White,
                Offset(center.x, center.y + radius * 0.42f),
                Offset(center.x, center.y + radius),
                strokeWidth = stroke * 1.5f
            )
            drawLine(
                Color.White,
                Offset(center.x - radius, center.y),
                Offset(center.x - radius * 0.42f, center.y),
                strokeWidth = stroke * 1.5f
            )
            drawLine(
                Color.White,
                Offset(center.x + radius * 0.42f, center.y),
                Offset(center.x + radius, center.y),
                strokeWidth = stroke * 1.5f
            )
        }
    }
}

/** 选点面板：显示预选经纬度与下一个点的类型，并提供确认 / 取消 / 重新选点。 */
@Composable
private fun SelectionPanel(
    draft: LatLng?,
    nextIndex: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onReselect: () -> Unit
) {
    val predictedType = when {
        nextIndex == 0 -> RoutePointType.START
        else -> RoutePointType.VIA
    }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(
            spring(stiffness = Spring.StiffnessMediumLow)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(5.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("地图中心选点", fontWeight = FontWeight.Bold)
                LegendDot(predictedType.composeColor(), typeLabel(predictedType))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "预选坐标（GCJ-02）: ${
                    draft?.let { GeoMath.formatLatLng(it.latitude, it.longitude) } ?: "等待地图就绪…"
                }",
                fontSize = 13.sp
            )
            Text(
                text = "第 ${nextIndex + 1} 个点",
                fontSize = 11.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, enabled = draft != null) { Text("确认落点") }
                OutlinedButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = onReselect) { Text("重新选点") }
            }
        }
    }
}

/** 非选点模式下的入口条：重新进入选点 / 恢复路线 / 改路线名。 */
@Composable
private fun ReselectBar(
    routeEmpty: Boolean,
    onReselect: () -> Unit,
    onRestore: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (routeEmpty) "还没有点位" else "选点已取消", fontSize = 13.sp, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReselect) { Text("重新选点") }
                if (routeEmpty) TextButton(onClick = onRestore) { Text("恢复") }
                TextButton(onClick = onRename) { Text("改名") }
            }
        }
    }
}

/** 状态栏：区分「地图路线」「模拟位置」「系统定位状态」三类信息。 */
@Composable
private fun StatusStrip(
    modifier: Modifier = Modifier,
    routeCount: Int,
    isLoop: Boolean,
    mockAuthorized: Boolean,
    gpsEnabled: Boolean,
    currentLocation: LatLng?,
    isRunning: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "系统定位: " + (if (mockAuthorized) "模拟位置已授权" else "未授权模拟位置") +
                    " · " + (if (gpsEnabled) "GPS 开" else "GPS 关"),
                fontSize = 10.sp,
                color = if (mockAuthorized && gpsEnabled) Color(0xFF2EAD65) else Color(0xFFB3261E)
            )
            Text(
                "模拟位置: " + if (currentLocation == null) {
                    "尚无"
                } else {
                    GeoMath.formatLatLng(currentLocation.latitude, currentLocation.longitude, 5)
                },
                fontSize = 10.sp,
                color = Color(0xFF5E6166)
            )
            Text(
                "地图路线: $routeCount 点" + if (isLoop) " · 闭环" else " · 单程",
                fontSize = 10.sp,
                color = Color(0xFF5E6166)
            )
            if (!isRunning) {
                Text(
                    "提示: 拖动地图对准，点「确认落点」加入路线",
                    fontSize = 10.sp,
                    color = Color(0xFF939599)
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(9.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF5E6166))
    }
}

private fun RoutePointType.composeColor(): Color = when (this) {
    RoutePointType.START -> StartGreen
    RoutePointType.VIA -> ViaBlue
    RoutePointType.END -> EndRed
}

private fun RoutePointType.androidColor(): Int = when (this) {
    RoutePointType.START -> 0xFF2EAD65.toInt()
    RoutePointType.VIA -> 0xFF1E88E5.toInt()
    RoutePointType.END -> 0xFFE04F5F.toInt()
}

private fun typeLabel(type: RoutePointType): String = when (type) {
    RoutePointType.START -> "起点"
    RoutePointType.VIA -> "途经点"
    RoutePointType.END -> "终点"
}

private fun isNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}
