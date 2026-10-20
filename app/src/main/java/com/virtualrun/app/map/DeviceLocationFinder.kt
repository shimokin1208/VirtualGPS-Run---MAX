package com.virtualrun.app.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 设备真实定位的一次性查询，供「快速定位到我的位置」使用。
 *
 * 设计要点：
 * 1. 先看 所有 provider 的 lastKnownLocation，够新就直接用，避免等待；
 * 2. 不够新就同时向 GPS / NETWORK 各要一次单点更新，谁先回来用谁，最多等 [DEFAULT_TIMEOUT_MS]；
 * 3. 优先返回「非模拟」的定位（本应用运行中会把 GPS provider 变成 mock，
 *    这时退回最后一手可得的位置，至少还能定位到地图上可见的位置）；
 * 4. 反查地址（Geocoder）是可选增强，失败只返回 null，不影响定位本身。
 */
class DeviceLocationFinder(private val context: Context) {

    private val manager: LocationManager?
        get() = runCatching {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }.getOrNull()

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** 系统定位总开关（而不是单个 provider）是否打开。 */
    fun isLocationServiceEnabled(): Boolean {
        val lm = manager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }

    /**
     * 取一个尽量新的设备位置（WGS-84，与地图显示的 GCJ-02 不同，调用方负责换算）。
     * 无权限、无 provider、定位关闭或超时都会返回 null。
     */
    @SuppressLint("MissingPermission")
    suspend fun findCurrentLocation(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Location? {
        if (!hasLocationPermission()) return null
        val lm = manager ?: return null

        val cached = lastKnownFix(lm)
        if (cached != null && System.currentTimeMillis() - cached.time <= CACHED_FRESH_MS) {
            Log.d(TAG, "使用缓存的定位：${cached.provider}")
            return cached
        }

        val fresh = awaitFreshFix(lm, timeoutMs)
        return fresh ?: cached
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownFix(lm: LocationManager): Location? {
        val providers = runCatching { lm.allProviders }.getOrDefault(emptyList())
        val candidates = providers.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }
        if (candidates.isEmpty()) return null
        // 优先真实定位；如果全是本应用注入的模拟点，就退而求其次。
        val real = candidates.filterNot { it.looksMocked() }
        return (if (real.isNotEmpty()) real else candidates).maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFreshFix(lm: LocationManager, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }

                val looper = Looper.getMainLooper()
                val requested = mutableListOf<String>()
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    val ok = runCatching {
                        if (lm.isProviderEnabled(provider)) {
                            lm.requestLocationUpdates(provider, 0L, 0f, listener, looper)
                            true
                        } else {
                            false
                        }
                    }.getOrDefault(false)
                    if (ok) requested.add(provider)
                }

                if (requested.isEmpty()) {
                    if (continuation.isActive) continuation.resume(null)
                } else {
                    continuation.invokeOnCancellation {
                        requested.forEach { runCatching { lm.removeUpdates(listener) } }
                    }
                }
            }
        }

    /**
     * 反查地址。用来在地图上告诉用户「定位到哪儿了」。
     * 系统 Geocoder 在无网络 / 无地理编码服务时会失败，这里统一降级为 null。
     */
    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(location: Location): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val geocoder = Geocoder(context, Locale.CHINA)
            val address = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?: return@runCatching null
            describe(address)
        }.onFailure {
            Log.d(TAG, "反查地址失败：${it.message}")
        }.getOrNull()
    }

    private fun describe(address: Address): String? {
        val full = runCatching { address.getAddressLine(0) }.getOrNull()
        if (!full.isNullOrBlank()) return full
        return listOfNotNull(
            address.adminArea,
            address.locality,
            address.subLocality,
            address.thoroughfare,
            address.featureName
        ).distinct().joinToString("").ifBlank { null }
    }

    @Suppress("DEPRECATION")
    private fun Location.looksMocked(): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
        }.getOrDefault(false)

    companion object {
        private const val TAG = "DeviceLocationFinder"

        /** 单点定位最长等待时间。 */
        const val DEFAULT_TIMEOUT_MS = 6000L

        /** 缓存定位多久以内还算「当前」。 */
        private const val CACHED_FRESH_MS = 60_000L
    }
}
