package com.virtualrun.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.algorithm.RouteRules
import com.virtualrun.app.algorithm.TrajectoryUtils
import com.virtualrun.app.map.GeoMath
import com.virtualrun.app.persist.RouteStore
import com.virtualrun.app.persist.SavedRoute
import com.virtualrun.app.persist.SavedRouteFactory
import com.virtualrun.app.service.MockLocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 路线编辑与运行状态的唯一数据源。
 *
 * 设计要点：
 * 1. 选点由地图中心光标驱动：先进入「预选」，再由用户点「确认」才落点；
 * 2. 不再存在任何按距离阈值自动闭环的行为，闭环只能由用户主动触发；
 * 3. 任何一次路线变更都会立即持久化（复用 SharedPreferences，不引入新依赖）；
 * 4. 运行类操作都有幂等保护，避免重复启动定位任务。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RouteStore(application)

    // ---------- 路线 ----------
    private val _routePoints = MutableStateFlow<List<LatLng>>(emptyList())
    val routePoints: StateFlow<List<LatLng>> = _routePoints.asStateFlow()

    private val _routeName = MutableStateFlow(RouteStore.DEFAULT_ROUTE_NAME)
    val routeName: StateFlow<String> = _routeName.asStateFlow()

    private val _isLoopMode = MutableStateFlow(false)
    val isLoopMode: StateFlow<Boolean> = _isLoopMode.asStateFlow()

    // ---------- 中心光标 / 预选点 ----------
    private val _draftPoint = MutableStateFlow<LatLng?>(null)

    /** 地图中心光标当前对应的预选坐标，尚未写入路线。 */
    val draftPoint: StateFlow<LatLng?> = _draftPoint.asStateFlow()

    private val _isSelecting = MutableStateFlow(true)

    /** 是否处于选点模式；关闭时光标隐藏且确认按钮不可用。 */
    val isSelecting: StateFlow<Boolean> = _isSelecting.asStateFlow()

    // ---------- 运行状态 ----------
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _basePace = MutableStateFlow(6.0f)
    val basePace: StateFlow<Float> = _basePace.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _lapCount = MutableStateFlow(0)
    val lapCount: StateFlow<Int> = _lapCount.asStateFlow()

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    // ---------- 提示 ----------
    private val _notice = MutableStateFlow<String?>(null)

    /** 一次性提示（已恢复上次路线 / 已清空等），消费后调用 [consumeNotice]。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    /** 清空后用于「恢复」的最近一份快照。 */
    private var undoSnapshot: SavedRoute? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MockLocationService.BROADCAST_ACTION_STATE_UPDATE) return

            val hasLocation = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_HAS_LOCATION, true)
            val prog = intent.getFloatExtra(MockLocationService.BROADCAST_EXTRA_PROGRESS, 0f)
            val lap = intent.getIntExtra(MockLocationService.BROADCAST_EXTRA_LAP, 0)
            val spd = intent.getFloatExtra(MockLocationService.BROADCAST_EXTRA_SPEED, 0f)
            val completed = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_COMPLETED, false)
            val running = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_RUNNING, !completed)

            if (hasLocation) {
                val lat = intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(MockLocationService.BROADCAST_EXTRA_LNG, 0.0)
                _currentLocation.value = LatLng(lat, lng)
                _progress.value = prog
                _lapCount.value = lap
                _speed.value = spd
            }

            _isPaused.value = intent.getBooleanExtra(MockLocationService.BROADCAST_EXTRA_PAUSED, false)

            val restoredPoints = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(MockLocationService.EXTRA_ROUTE_POINTS, LatLng::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(MockLocationService.EXTRA_ROUTE_POINTS)
            }
            restoredPoints?.let { if (it.isNotEmpty()) _routePoints.value = it }

            if (intent.hasExtra(MockLocationService.EXTRA_PACE)) {
                _basePace.value = intent.getFloatExtra(MockLocationService.EXTRA_PACE, _basePace.value)
            }
            if (intent.hasExtra(MockLocationService.EXTRA_IS_LOOP)) {
                _isLoopMode.value = intent.getBooleanExtra(MockLocationService.EXTRA_IS_LOOP, false)
            }

            _isRunning.value = running

            if (completed) {
                _speed.value = 0f
                _isRunning.value = false
                _isPaused.value = false
            }
        }
    }

    init {
        restoreSavedRoute()
    }

    // ---------- 生命周期 ----------

    fun registerReceiver(context: Context) {
        val filter = IntentFilter(MockLocationService.BROADCAST_ACTION_STATE_UPDATE)
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver(context: Context) {
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
    }

    // ---------- 持久化 ----------

    private fun snapshot(): SavedRoute = SavedRouteFactory.create(
        name = _routeName.value,
        points = _routePoints.value,
        isLoop = _isLoopMode.value,
        nowMillis = System.currentTimeMillis()
    )

    private fun persist() {
        val route = snapshot()
        if (!store.save(route)) {
            Log.w(TAG, "路线保存失败：${route.points.size} 个点")
        }
    }

    /** 启动时恢复上次保存的路线。 */
    private fun restoreSavedRoute() {
        val saved = store.load() ?: return
        val points = SavedRouteFactory.toLatLngList(saved)
        if (points.isEmpty()) return
        _routePoints.value = points
        _routeName.value = saved.name.ifBlank { RouteStore.DEFAULT_ROUTE_NAME }
        _isLoopMode.value = saved.isLoop && points.size >= RouteRules.MIN_POINTS_FOR_LOOP
        _notice.value = "已恢复上次路线：${points.size} 个点"
    }

    /** 手动从本地存档恢复（清空之后想找回时使用）。 */
    fun restoreLastRoute(): Boolean {
        val saved = undoSnapshot ?: store.load() ?: return false
        val points = SavedRouteFactory.toLatLngList(saved)
        if (points.isEmpty()) return false
        applyRoute(points, saved.isLoop, saved.name)
        undoSnapshot = null
        _notice.value = "已恢复路线：${points.size} 个点"
        return true
    }

    /** 从本地存档重新读取（用于「恢复保存内容」）。 */
    fun reloadFromStore(): Boolean {
        val saved = store.load() ?: return false
        val points = SavedRouteFactory.toLatLngList(saved)
        if (points.isEmpty()) return false
        applyRoute(points, saved.isLoop, saved.name)
        _notice.value = "已重新载入保存的路线"
        return true
    }

    private fun applyRoute(points: List<LatLng>, loop: Boolean, name: String = _routeName.value) {
        _routePoints.value = points
        _routeName.value = name.ifBlank { RouteStore.DEFAULT_ROUTE_NAME }
        _isLoopMode.value = loop && points.size >= RouteRules.MIN_POINTS_FOR_LOOP
        _lapCount.value = 0
        _progress.value = 0f
        persist()
    }

    // ---------- 选点 ----------

    /** 地图滚动 / 缩放后更新预选坐标（未确认，不写入路线）。 */
    fun setDraftPoint(point: LatLng?) {
        if (_isRunning.value) return
        _draftPoint.value = point
    }

    /** 重新进入选点模式（「重新选点」）。 */
    fun beginSelection() {
        if (_isRunning.value) return
        _isSelecting.value = true
        _draftPoint.value = null
    }

    /** 退出选点模式并丢弃预选点（「取消」）。 */
    fun cancelSelection() {
        _isSelecting.value = false
        _draftPoint.value = null
    }

    /** 把当前预选点写入路线（「确认」）。 */
    fun confirmDraftPoint(): Boolean {
        if (_isRunning.value || !_isSelecting.value) return false
        val point = _draftPoint.value ?: return false
        // 追加点位；若此前处于闭环状态，先去掉末尾与起点重合的重复点，闭环随之失效。
        applyRoute(RouteRules.append(editableBase(_routePoints.value), point), loop = false)
        return true
    }

    /** 直接追加一个坐标（无光标时的兼容入口）。 */
    fun addRoutePoint(latLng: LatLng) {
        if (_isRunning.value) return
        applyRoute(RouteRules.append(editableBase(_routePoints.value), latLng), loop = false)
    }

    /**
     * 编辑前的基线：已闭环的路线末尾会有一个与起点重合的点，
     * 直接追加会让这个重复点变成「途经点」，因此编辑时先剔除它。
     */
    private fun editableBase(points: List<LatLng>): List<LatLng> =
        if (points.size > 2 && RouteRules.isClosed(points)) points.dropLast(1) else points

    /** 删除指定下标的点位。 */
    fun removePointAt(index: Int): Boolean {
        if (_isRunning.value) return false
        val current = _routePoints.value
        if (index !in current.indices) return false
        val updated = RouteRules.removeAt(current, index)
        applyRoute(updated, loop = false)
        return true
    }

    /**
     * 拖动结束后把路径点落到新坐标。
     * 只在松手时调用，拖动过程中的线由地图自己重画，避免每个像素都重建标记。
     * 原本是闭环的路线，移动后如果首尾仍重合，就继续保持闭环。
     */
    fun movePoint(index: Int, latitude: Double, longitude: Double): Boolean {
        if (_isRunning.value) return false
        if (!GeoMath.isValidLatLng(latitude, longitude)) return false
        val current = _routePoints.value
        val updated = RouteRules.movePoint(current, index, LatLng(latitude, longitude))
        if (updated == current) return false
        applyRoute(updated, loop = _isLoopMode.value && RouteRules.isClosed(updated))
        return true
    }

    /**
     * 用户主动闭环：把起点接到末尾。
     * 这是唯一的闭环入口，不存在按距离自动闭环的路径。
     */
    fun closeLoop(): Boolean {
        if (_isRunning.value) return false
        val current = _routePoints.value
        val updated = RouteRules.closeLoop(current)
        if (updated.size == current.size) return false
        applyRoute(updated, loop = true)
        _notice.value = "已闭合路线，可在运行中循环"
        return true
    }

    fun clearRoute() {
        if (_isRunning.value) return
        undoSnapshot = snapshot()
        _routePoints.value = emptyList()
        _isLoopMode.value = false
        _progress.value = 0f
        _lapCount.value = 0
        _currentLocation.value = null
        _speed.value = 0f
        _draftPoint.value = null
        store.clear()
        _notice.value = "路线已清空，可点「恢复」撤回"
    }

    fun setRouteName(name: String) {
        _routeName.value = name.trim().ifEmpty { RouteStore.DEFAULT_ROUTE_NAME }
        persist()
    }

    // ---------- 运行 ----------

    fun setBasePace(pace: Float, context: Context? = null) {
        _basePace.value = pace
        Log.d(TAG, "配速调整为: $pace 分/km")
        if (_isRunning.value && context != null) {
            try {
                val intent = Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_UPDATE_PARAMS
                    putExtra(MockLocationService.EXTRA_PACE, pace)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "下发配速失败", e)
            }
        }
    }

    fun startRunning(context: Context): Boolean {
        if (_isRunning.value) {
            // 幂等保护：避免重复下发 START，导致重复创建定位任务。
            Log.w(TAG, "忽略重复的开始请求：已在运行中")
            return false
        }
        if (_routePoints.value.size < 2) return false

        return try {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_START_MOCK
                putExtra(MockLocationService.EXTRA_PACE, _basePace.value)
                putExtra(MockLocationService.EXTRA_IS_LOOP, _isLoopMode.value)
                putParcelableArrayListExtra(
                    MockLocationService.EXTRA_ROUTE_POINTS,
                    ArrayList(_routePoints.value)
                )
            }
            context.startForegroundService(intent)
            _progress.value = 0f
            _currentLocation.value = null
            _speed.value = 0f
            _lapCount.value = 0
            _isPaused.value = false
            _isRunning.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            _isRunning.value = false
            _notice.value = "启动失败：${e.message ?: "无法启动前台服务"}"
            false
        }
    }

    fun pauseRunning(context: Context): Boolean {
        if (!_isRunning.value || _isPaused.value) return false
        _isPaused.value = true
        try {
            context.startService(
                Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_PAUSE_MOCK
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "暂停失败", e)
            _isPaused.value = false
            return false
        }
        return true
    }

    fun resumeRunning(context: Context): Boolean {
        if (!_isRunning.value || !_isPaused.value) return false
        _isPaused.value = false
        try {
            context.startService(
                Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_RESUME_MOCK
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败", e)
            _isPaused.value = true
            return false
        }
        return true
    }

    fun stopRunning(context: Context) {
        if (!_isRunning.value) return
        _isRunning.value = false
        _isPaused.value = false
        _speed.value = 0f
        try {
            context.startService(
                Intent(context, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_STOP_MOCK
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "停止失败，尝试直接结束服务", e)
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }

    // ---------- 派生数据 ----------

    fun getCurrentRouteDistance(): Float =
        TrajectoryUtils.calculateTotalDistance(_routePoints.value)

    fun getCurrentRouteDuration(): Long {
        val distance = getCurrentRouteDistance()
        return TrajectoryUtils.calculateDuration(distance, _basePace.value)
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
