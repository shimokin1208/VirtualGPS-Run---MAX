package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.MainActivity
import com.virtualrun.app.R
import com.virtualrun.app.model.Route
import java.util.Locale

/**
 * 虚拟定位 Mock Location Service
 *
 * 核心功能：
 * 1. 同时模拟 GPS、NETWORK、系统 FUSED provider 和 Google Play FLP
 * 2. 模拟真实动作 (TrajectoryInterpolator 提供)
 * 3. 支持后台运行和实时参数更新
 */
@SuppressLint("InlinedApi")
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mock_location_service_channel"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L

        const val ACTION_START_MOCK = "com.virtualrun.action.START_MOCK"
        const val ACTION_STOP_MOCK = "com.virtualrun.action.STOP_MOCK"
        const val ACTION_PAUSE_MOCK = "com.virtualrun.action.PAUSE_MOCK"
        const val ACTION_RESUME_MOCK = "com.virtualrun.action.RESUME_MOCK"
        const val ACTION_UPDATE_PARAMS = "com.virtualrun.action.UPDATE_PARAMS"

        const val EXTRA_PACE = "extra_pace"
        const val EXTRA_ROUTE_POINTS = "extra_route_points"
        const val EXTRA_IS_LOOP = "extra_is_loop"

        const val BROADCAST_ACTION_STATE_UPDATE = "com.virtualrun.broadcast.STATE_UPDATE"
        const val BROADCAST_EXTRA_LAT = "extra_lat"
        const val BROADCAST_EXTRA_LNG = "extra_lng"
        const val BROADCAST_EXTRA_PROGRESS = "extra_progress"
        const val BROADCAST_EXTRA_LAP = "extra_lap"
        const val BROADCAST_EXTRA_SPEED = "extra_speed"
        const val BROADCAST_EXTRA_COMPLETED = "extra_completed"
        const val BROADCAST_EXTRA_RUNNING = "extra_running"
        const val BROADCAST_EXTRA_HAS_LOCATION = "extra_has_location"
        const val BROADCAST_EXTRA_PAUSED = "extra_paused"

        fun isMockEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.addTestProvider(
                        "test_check",
                        false, false, false, false, false, false, false,
                        ProviderProperties.POWER_USAGE_LOW,
                        ProviderProperties.ACCURACY_FINE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(
                        "test_check",
                        false, false, false, false, false, false, false,
                        // PowerManager.POWER_USAGE_LOW
                        1,
                        // Criteria.ACCURACY_FINE
                        1
                    )
                }
                lm.removeTestProvider("test_check")
                true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                // 已经存在同名测试 provider（或不支持移除）时，说明当前进程已拿到模拟定位能力。
                true
            }
        }
    }

    private lateinit var pump: MockLocationPump
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var session = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var currentPace = 6.0f
    private var currentRoutePoints: ArrayList<LatLng>? = null
    private var currentIsLoop = false
    private var lastResult: com.virtualrun.app.algorithm.PositionResult? = null
    private var terminalStateBroadcast = false
    private var paused = false
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        pump = MockLocationPump(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "Mock Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MOCK -> {
                Log.d(TAG, "Action: START_MOCK")
                if (pump.isActive) {
                    // 防重复启动：已有会话在出点时忽略新的一次 START。
                    Log.w(TAG, "忽略重复的 START_MOCK：已有定位会话在运行")
                    return START_NOT_STICKY
                }
                currentPace = intent.getFloatExtra(EXTRA_PACE, 6.0f)
                currentIsLoop = intent.getBooleanExtra(EXTRA_IS_LOOP, false)
                currentRoutePoints = intent.getParcelableArrayListExtra(EXTRA_ROUTE_POINTS)

                val points = currentRoutePoints
                if (points != null && points.size >= 2) {
                    val route = Route.fromLatLngPoints(points)
                    val activeSession = ++session
                    terminalStateBroadcast = false
                    startForeground(NOTIFICATION_ID, buildNotification())
                    pump.start(route, currentPace, currentIsLoop) { result ->
                        mainHandler.post {
                            if (session == activeSession && !terminalStateBroadcast) {
                                lastResult = result
                                broadcastUpdate(result)
                                if (SystemClock.elapsedRealtime() - lastNotificationAt >= 1000L) {
                                    lastNotificationAt = SystemClock.elapsedRealtime()
                                    updateNotification(result)
                                }
                                if (result.isCompleted) stopMockImmediately()
                            }
                        }
                    }
                } else {
                    broadcastTerminalState()
                    stopSelf()
                }
            }
            ACTION_UPDATE_PARAMS -> {
                val pace = intent.getFloatExtra(EXTRA_PACE, -1.0f)
                Log.d(TAG, "收到 ACTION_UPDATE_PARAMS, pace=$pace")
                if (pace > 0) {
                    currentPace = pace
                    pump.updatePace(pace)
                    updateNotification(lastResult)
                } else {
                    Log.w(TAG, "收到无效的配速值: $pace")
                }
            }
            ACTION_PAUSE_MOCK -> {
                Log.d(TAG, "Action: PAUSE_MOCK")
                if (!pump.isActive) return START_NOT_STICKY
                paused = true
                pump.pause()
                // 暂停不会再产生新样本，需要主动广播一次状态，UI 才能立刻反映出「已暂停」。
                broadcastCurrentState()
            }
            ACTION_RESUME_MOCK -> {
                Log.d(TAG, "Action: RESUME_MOCK")
                if (!pump.isActive) return START_NOT_STICKY
                paused = false
                pump.resume()
            }
            ACTION_STOP_MOCK -> {
                Log.d(TAG, "Action: STOP_MOCK")
                stopMockImmediately()
            }
        }
        // 未持久化运动状态时不自动重放起跑 Intent，避免进程恢复后瞬移回路线起点。
        return START_NOT_STICKY
    }

    private fun stopMockImmediately() {
        ++session
        paused = false
        pump.stop()
        lastResult = null
        broadcastTerminalState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 在没有新样本的情况下，把当前状态补播一次。 */
    private fun broadcastCurrentState() {
        val result = lastResult
        if (result != null) {
            broadcastUpdate(result)
        } else {
            broadcastTerminalState()
        }
    }

    private fun broadcastUpdate(result: com.virtualrun.app.algorithm.PositionResult) {
        val running = !result.isCompleted
        val intent = Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, true)
            putExtra(BROADCAST_EXTRA_LAT, result.latitude)
            putExtra(BROADCAST_EXTRA_LNG, result.longitude)
            putExtra(BROADCAST_EXTRA_PROGRESS, result.progress)
            putExtra(BROADCAST_EXTRA_LAP, result.lapCount)
            putExtra(BROADCAST_EXTRA_SPEED, result.speed)
            putExtra(BROADCAST_EXTRA_COMPLETED, result.isCompleted)
            putExtra(BROADCAST_EXTRA_RUNNING, running)
            putExtra(BROADCAST_EXTRA_PAUSED, paused)
            putExtra(EXTRA_PACE, currentPace)
            putExtra(EXTRA_IS_LOOP, currentIsLoop)
            putParcelableArrayListExtra(EXTRA_ROUTE_POINTS, currentRoutePoints)
        }
        if (result.isCompleted) terminalStateBroadcast = true
        sendBroadcast(intent)
    }

    private fun broadcastTerminalState() {
        if (terminalStateBroadcast) return
        terminalStateBroadcast = true
        paused = false
        sendBroadcast(Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, false)
            putExtra(BROADCAST_EXTRA_COMPLETED, true)
            putExtra(BROADCAST_EXTRA_RUNNING, false)
            putExtra(BROADCAST_EXTRA_PAUSED, false)
            putExtra(BROADCAST_EXTRA_SPEED, 0f)
        })
    }

    override fun onDestroy() {
        ++session
        if (!terminalStateBroadcast) broadcastTerminalState()
        pump.close()
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mock-location").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虚拟跑步服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val result = lastResult
        val statusText = if (result == null) {
            "正在准备虚拟定位..."
        } else {
            val progressPercent = (result.progress * 100).coerceIn(0f, 100f)
            val lapText = if (currentIsLoop) " · 第${result.lapCount + 1}圈" else ""
            String.format(Locale.getDefault(), "%.1f 分/km · %.0f%%%s", currentPace, progressPercent, lapText)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟跑步进行中")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(result: com.virtualrun.app.algorithm.PositionResult?) {
        lastResult = result
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
