package com.virtualrun.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.RouteClassifier
import com.virtualrun.app.algorithm.RouteRules
import com.virtualrun.app.map.CHINA_MAP_MAX_ZOOM_LEVEL
import com.virtualrun.app.map.CHINA_MAP_MIN_ZOOM_LEVEL
import com.virtualrun.app.map.ChinaMapTileSource
import com.virtualrun.app.map.CoordinateConverter
import com.virtualrun.app.map.DeviceLocationFinder
import com.virtualrun.app.map.GeoMath
import com.virtualrun.app.map.MapMode
import com.virtualrun.app.persist.RoutePointType
import com.virtualrun.app.service.MockLocationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
private val MutedText = Color(0xFF5E6166)
private val HintText = Color(0xFF939599)
private const val MIN_CURRENT_PACE_DISPLAY_SPEED_MPS = 0.6f

/** 点「定位」后地图拉到的缩放级别。 */
private const val LOCATE_ZOOM_LEVEL = 17.5

private enum class RunUiErrorAction {
    DISMISS,
    APP_SETTINGS,
    DEVELOPER_SETTINGS,
    LOCATION_SETTINGS
}

private data class RunUiError(
    val message: String,
    val action: RunUiErrorAction
)

/**
 * 主界面。
 *
 * 布局约定（核心是「别挡住地图中心」）：
 * 1. 顶部不再放常驻文字卡片，只留右上角地图工具按钮；
 * 2. 底部是两块独立面板——「选点」和「配速」，各自有最小化按钮，收起后只留一行；
 * 3. 开始 / 暂停 / 停止始终留在最底下，最小化面板不会把主操作藏掉；
 * 4. 拖动地图时自动最小化配速面板；选点面板保持不动，避免把「确认落点」收掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSMapScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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
    var lastCameraFollowAt by remember { mutableLongStateOf(0L) }
    var wasRunning by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<RunUiError?>(null) }
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // 选点、配速各自独立最小化。配速默认收起，避免一进地图就被滑块挡住中心。
    var selectionMinimized by remember { mutableStateOf(false) }
    var paceMinimized by remember { mutableStateOf(true) }
    var mapMenuOpen by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var myAddress by remember { mutableStateOf<String?>(null) }

    val locationFinder = remember(context) { DeviceLocationFinder(context) }

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

    /** 把屏幕中心坐标同步为预选点；force=false 时对微小位移去抖。 */
    fun pushDraftFromCenter(force: Boolean) {
        val mv = mapView ?: return
        if (mv.width == 0 || mv.height == 0) return
        val center = mv.mapCenter ?: return
        if (!GeoMath.isValidLatLng(center.latitude, center.longitude)) return
        val previous = lastDraftHolder[0]
        if (!force && previous != null && GeoMath.distanceMeters(
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

    fun syncDraftFromCenter() = pushDraftFromCenter(false)

    fun flyTo(latitude: Double, longitude: Double, zoom: Double) {
        mapView?.controller?.animateTo(GeoPoint(latitude, longitude), zoom, 800L)
    }

    fun zoomBy(zoomIn: Boolean) {
        val mv = mapView ?: return
        if (zoomIn) {
            if (mv.canZoomIn()) {
                mv.controller.zoomIn()
            } else {
                Toast.makeText(context, "已达到地图最大缩放级别", Toast.LENGTH_SHORT).show()
            }
        } else {
            mv.controller.zoomOut()
        }
    }

    /** 快速定位到设备当前位置：一次点击完成「取定位 → 换算 GCJ-02 → 飞过去 → 反查地址」。 */
    fun locateToMyPosition() {
        if (locating) return
        if (!locationFinder.hasLocationPermission()) {
            uiError = RunUiError("需要定位权限才能定位到当前位置", RunUiErrorAction.APP_SETTINGS)
            return
        }
        if (!locationFinder.isLocationServiceEnabled()) {
            uiError = RunUiError("系统定位服务未开启，打开后再试", RunUiErrorAction.LOCATION_SETTINGS)
            return
        }
        locating = true
        scope.launch {
            try {
                val location = runCatching { locationFinder.findCurrentLocation() }.getOrNull()
                if (location == null) {
                    uiError = RunUiError(
                        "暂时拿不到当前定位，请确认系统定位已开启后重试",
                        RunUiErrorAction.LOCATION_SETTINGS
                    )
                    return@launch
                }
                val gcj = CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
                flyTo(gcj.first, gcj.second, LOCATE_ZOOM_LEVEL)
                // 相机动画结束后把中心光标同步到新位置：选点模式下可直接「确认落点」。
                delay(850)
                pushDraftFromCenter(true)
                val address = locationFinder.reverseGeocode(location)
                myAddress = address
                val coords = GeoMath.formatLatLng(gcj.first, gcj.second, 5)
                Toast.makeText(
                    context,
                    if (address.isNullOrBlank()) "已定位到当前位置 $coords" else "已定位到：$address",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                locating = false
            }
        }
    }

    // 一次性提示
    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }

    // 进入选点时展开选点面板，并立刻刷新预选坐标。
    LaunchedEffect(isSelecting, isRunning, mapView) {
        if (isSelecting && !isRunning) {
            selectionMinimized = false
            pushDraftFromCenter(true)
        }
    }

    // 开始跑步后收起选点面板，把地图让出来。配速面板保持用户自己的开合状态。
    LaunchedEffect(isRunning) {
        if (isRunning) selectionMinimized = true
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
                            // 拖地图时收起配速滑块，避免挡住中心光标；选点确认行保持可见。
                            if (!paceMinimized) paceMinimized = true
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

        // 固定在屏幕中心的光标：只在选点模式下显示，高对比度，浅色/卫星底图都看得清。
        MapCenterCursor(
            visible = isSelecting && !isRunning,
            modifier = Modifier.fillMaxSize()
        )

        // 右上角只留地图工具，不再放常驻文字卡片。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 12.dp)
        ) {
            MapMenuButton(
                mapMode = mapMode,
                expanded = mapMenuOpen,
                locating = locating,
                onExpandedChange = { mapMenuOpen = it },
                onToggleMode = {
                    mapMenuOpen = false
                    mapMode = mapMode.next()
                    mapPreferences.edit().putString("mode", mapMode.name).apply()
                },
                onZoomIn = {
                    mapMenuOpen = false
                    zoomBy(true)
                },
                onZoomOut = {
                    mapMenuOpen = false
                    zoomBy(false)
                },
                onLocate = {
                    mapMenuOpen = false
                    locateToMyPosition()
                }
            )
        }

        // 底部：选点面板、配速面板各自可最小化；主按钮始终可见。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MinimizablePanel(
                title = dockTitle(
                    routeName = routeName,
                    pointCount = routePoints.size,
                    isRunning = isRunning,
                    isPaused = isPaused,
                    isLoop = isLoopMode,
                    lap = lapCount,
                    isSelecting = isSelecting
                ),
                minimized = selectionMinimized,
                minimizeLabel = "最小化选点面板",
                expandLabel = "展开选点面板",
                onToggle = { selectionMinimized = !selectionMinimized },
                headerActions = {
                    DockIconButton(
                        icon = Icons.Default.MyLocation,
                        description = "定位到我的位置",
                        busy = locating,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { locateToMyPosition() }
                    )
                    DockIconButton(
                        icon = Icons.Default.Place,
                        description = if (isSelecting) "已在地图中心选点" else "在地图中心开始选点",
                        tint = if (isSelecting && !isRunning) ViaBlue else MutedText,
                        onClick = {
                            selectionMinimized = false
                            if (!isSelecting) {
                                viewModel.beginSelection()
                                routePoints.lastOrNull()?.let { last ->
                                    flyTo(last.latitude, last.longitude, mapView?.zoomLevelDouble ?: 16.0)
                                }
                            }
                        }
                    )
                }
            ) {
                Text(
                    text = dockSubtitle(
                        pointCount = routePoints.size,
                        distanceMeters = viewModel.getCurrentRouteDistance(),
                        durationSeconds = viewModel.getCurrentRouteDuration(),
                        isLoop = isLoopMode,
                        address = myAddress,
                        isSelecting = isSelecting,
                        isRunning = isRunning
                    ),
                    fontSize = 11.sp,
                    color = MutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isSelecting && !isRunning) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "预选坐标（GCJ-02）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = draftPoint?.let {
                                    "GCJ-02 " + GeoMath.formatLatLng(it.latitude, it.longitude, 5)
                                } ?: "等待地图就绪…",
                                fontSize = 11.sp,
                                color = MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = {
                                if (draftPoint == null) {
                                    uiError = RunUiError("地图还没准备好，请稍后重试", RunUiErrorAction.DISMISS)
                                } else if (!viewModel.confirmDraftPoint()) {
                                    uiError = RunUiError("落点失败，请重试", RunUiErrorAction.DISMISS)
                                }
                            },
                            enabled = draftPoint != null,
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("确认落点", fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.cancelSelection() },
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("取消", fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        enabled = routePoints.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { Text("清除", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = {
                            if (!viewModel.closeLoop()) {
                                uiError = RunUiError(
                                    "闭环需要至少 3 个点，且当前未闭环",
                                    RunUiErrorAction.DISMISS
                                )
                            }
                        },
                        enabled = RouteRules.canCloseLoop(routePoints),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { Text("闭环", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { Text("改名", fontSize = 13.sp) }
                }
                if (routePoints.isEmpty()) {
                    TextButton(
                        onClick = {
                            if (!viewModel.restoreLastRoute()) {
                                uiError = RunUiError("没有可恢复的路线", RunUiErrorAction.DISMISS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("恢复上次路线", fontSize = 13.sp) }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(StartGreen, "起点")
                    Spacer(Modifier.width(10.dp))
                    LegendDot(ViaBlue, "途经")
                    Spacer(Modifier.width(10.dp))
                    LegendDot(EndRed, "终点")
                    Spacer(Modifier.weight(1f))
                    Text("共 ${routePoints.size} 点", fontSize = 11.sp, color = HintText)
                }
            }

            val paceTitle = buildString {
                append("目标配速 ${"%.1f".format(sliderPace)} 分/km")
                if (isRunning && animatedSpeed >= MIN_CURRENT_PACE_DISPLAY_SPEED_MPS) {
                    val currentPace = 1000f / (animatedSpeed.coerceAtLeast(0.1f) * 60f)
                    append(" · 当前 ${"%.1f".format(currentPace)}")
                }
            }
            MinimizablePanel(
                title = paceTitle,
                minimized = paceMinimized,
                minimizeLabel = "最小化配速面板",
                expandLabel = "展开配速面板",
                onToggle = { paceMinimized = !paceMinimized }
            ) {
                Slider(
                    value = sliderPace,
                    onValueChange = { sliderPace = it },
                    onValueChangeFinished = { viewModel.setBasePace(sliderPace, context) },
                    valueRange = 3f..15f,
                    steps = 11
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("3'00\" · 快", fontSize = 10.sp, color = HintText)
                    Text("15'00\" · 慢", fontSize = 10.sp, color = HintText)
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isRunning) {
                        LinearProgressIndicator(
                            progress = { animatedProgress.value },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = RunGreen,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (isRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isPaused) viewModel.resumeRunning(context) else viewModel.pauseRunning(context)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) RunGreen else Color(0xFF6F7782)
                                ),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (isPaused) "继续" else "暂停", fontSize = 17.sp)
                            }
                            Button(
                                onClick = { viewModel.stopRunning(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = StopRed),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("停止", fontSize = 17.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                if (routePoints.size < 2) {
                                    uiError = RunUiError(
                                        "请先在地图上至少确认 2 个点",
                                        RunUiErrorAction.DISMISS
                                    )
                                } else if (!MockLocationService.isMockEnabled(context)) {
                                    uiError = RunUiError(
                                        "请先在开发者选项里把本应用设为模拟位置应用",
                                        RunUiErrorAction.DEVELOPER_SETTINGS
                                    )
                                } else {
                                    viewModel.startRunning(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .semantics { contentDescription = "开始虚拟跑步" }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (routePoints.size < 2) "选好点位后开始" else "开始",
                                fontSize = 17.sp
                            )
                        }
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
            text = { Text("将删除全部 ${routePoints.size} 个点位。清空后可在「更多设置」里恢复。") },
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

                        RunUiErrorAction.LOCATION_SETTINGS -> try {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (_: Exception) {
                            Toast.makeText(context, "请手动打开系统定位设置", Toast.LENGTH_SHORT).show()
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

/**
 * 屏幕中央的预选光标：位置固定，只随地图移动而指向不同坐标。
 *
 * 画法保持 1.1.0 原样（72dp、半透明蓝 + 白色十字 + 蓝色中心点），
 * 当初"看不到光标"是底部面板叠得比屏幕中心还高、把它盖住了，不是绘制问题；
 * 现在面板默认收起，中心区域留空即可。容器用全屏 Box 居中，保证与地图中心严格对齐。
 */
@Composable
private fun MapCenterCursor(visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
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
}

/** 可最小化的底部面板：收起后只留标题行和「展开」。 */
@Composable
private fun MinimizablePanel(
    title: String,
    minimized: Boolean,
    minimizeLabel: String,
    expandLabel: String,
    onToggle: () -> Unit,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(
                start = 14.dp,
                end = 6.dp,
                top = 4.dp,
                bottom = if (minimized) 4.dp else 10.dp
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                headerActions()
                TextButton(
                    onClick = onToggle,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .semantics {
                            contentDescription = if (minimized) expandLabel else minimizeLabel
                        }
                ) {
                    Text(if (minimized) "展开" else "最小化", fontSize = 12.sp)
                }
            }
            AnimatedVisibility(
                visible = !minimized,
                enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 4 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 4 }
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    content()
                }
            }
        }
    }
}

/** 右上角地图工具菜单：卫星/普通、放大、缩小、定位到我的位置。 */
@Composable
private fun MapMenuButton(
    mapMode: MapMode,
    expanded: Boolean,
    locating: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggleMode: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onLocate: () -> Unit
) {
    Box {
        SmallFloatingActionButton(
            onClick = { onExpandedChange(true) },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.semantics { contentDescription = "地图工具菜单" }
        ) {
            Icon(Icons.Default.Layers, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text(if (mapMode == MapMode.ROAD) "切换到卫星地图" else "切换到普通地图") },
                onClick = onToggleMode,
                leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("放大") },
                onClick = onZoomIn,
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("缩小") },
                onClick = onZoomOut,
                leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text(if (locating) "正在定位…" else "定位到我的位置") },
                onClick = onLocate,
                enabled = !locating,
                leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = null) }
            )
        }
    }
}

/** 操作坞里的图标按钮，定位按钮在忙碌时显示进度圈。 */
@Composable
private fun DockIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
    busy: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier
            .size(42.dp)
            .semantics { contentDescription = description }
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(icon, contentDescription = null, tint = tint)
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
        Text(label, fontSize = 11.sp, color = MutedText)
    }
}

private fun dockTitle(
    routeName: String,
    pointCount: Int,
    isRunning: Boolean,
    isPaused: Boolean,
    isLoop: Boolean,
    lap: Int,
    isSelecting: Boolean
): String = when {
    isRunning && isPaused -> "已暂停 · $pointCount 点"
    isRunning && isLoop -> "跑步中 · 第 ${lap + 1} 圈"
    isRunning -> "跑步中 · $pointCount 点"
    isSelecting -> "地图中心选点 · 第 ${pointCount + 1} 个点"
    else -> routeName
}

private fun dockSubtitle(
    pointCount: Int,
    distanceMeters: Float,
    durationSeconds: Long,
    isLoop: Boolean,
    address: String?,
    isSelecting: Boolean,
    isRunning: Boolean
): String {
    val loopSuffix = if (isLoop) " · 闭环" else " · 单程"
    val routeSummary = "%.2f km".format(distanceMeters / 1000f) +
        " · 预计 ${formatDuration(durationSeconds)}" + loopSuffix
    return when {
        !isRunning && !address.isNullOrBlank() && pointCount < 2 -> "当前地址: $address"
        pointCount >= 2 -> routeSummary
        isSelecting -> "拖动地图把中心光标对准目标位置"
        else -> "点「选点」在地图中心添加起点"
    }
}

private fun RoutePointType.androidColor(): Int = when (this) {
    RoutePointType.START -> 0xFF2EAD65.toInt()
    RoutePointType.VIA -> 0xFF1E88E5.toInt()
    RoutePointType.END -> 0xFFE04F5F.toInt()
}

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}
