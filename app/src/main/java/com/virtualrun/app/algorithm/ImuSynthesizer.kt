package com.virtualrun.app.algorithm

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 与 GPS 轨迹物理自洽的 10Hz IMU 合成器。
 *
 * 服务端不看你上报的计数，它可以直接从原始 IMU 反推：
 * 1. `az`（或去重力后的竖向分量）主频  ↔  由速度推出的步频；
 * 2. `GPS 位移 / 步数`                  ↔  人体有效步幅（0.5–1.8 m）；
 * 3. `q*` 推出的重力方向                ↔  低通后的加速度方向；
 * 4. `q*` 推出的前向轴方位              ↔  轨迹方位（差一个常量安装偏航）；
 * 5. `gyro` 的世界系 z 分量             ↔  轨迹方位变化率。
 *
 * 这个类的每一步都为满足上述约束而设计，而不是"事后加噪声"：
 *
 * - **步频与位移同源**：步频由速度经人体节律模型给出 `f(v) = 108 + 17v` spm，
 *   相位按 `∫f dt` 连续累积，步数就是同一个积分；步幅 `v/f` 因此与 GPS 位移严格一致，
 *   同时落在人体步幅区间内。
 * - **幅度随速度变化**：竖向加速度幅值随速度增长，服务端拟合"幅度-速度"关系也是人形。
 * - **姿态驱动全部三路数据**：加速度计、陀螺、四元数都由同一个旋转矩阵 `R` 派生，
 *   陀螺来自 `Rᵀ·dR/dt`，所以三路天然互相自洽，不会出现"q 说在转弯但 gyro 没动"。
 * - **重力可恢复**：加速度计输出 `Rᵀ(a_motion + g·ẑ)`，静止时模长就是 9.80665。
 * - **抗混叠**：10Hz 采样下 Nyquist 只有 5Hz，步态谐波按 3.2Hz 截止衰减，
 *   所以频谱形态与真实设备采样结果一致（真实设备前端同样会滤掉高次谐波）。
 * - **步态有真实变异性**：步频带一阶自回归抖动（步间隔 CV 约 2%），不是完美正弦。
 * - **静止不出步**：速度低于阈值时步频、幅度都归零，只留重力。
 *
 * 坐标约定（与 [ImuMotionSample] 的注释一致）：
 * - 世界系 ENU：x 东、y 北、z 天；
 * - 人体系：x 指向身体右方、y 指向前进方向、z 向上；
 * - `headingRad` 是罗盘航向（顺时针自北，即 `Location.bearing` 的弧度），
 *   内部取数学角 `ψ = -headingRad`，使前向 `= (-sin ψ, cos ψ) = (sin heading, cos heading)`。
 *
 * 时序：调用方按目标采样率（默认 10Hz）喂时间戳，内部用相邻时间戳之差推进相位，
 * 因此输出与定位共用一条时间轴；时间戳抖动由调用方施加（见 docs/imu-synthesis.md）。
 *
 * 本类纯 Kotlin、无 Android 依赖，可在 JVM 单元测试里直接跑。
 */
class ImuSynthesizer(
    private val config: ImuConfig = ImuConfig()
) {

    private val random = Random(config.seed)
    private val mountRotation: DoubleArray = matMul(
        matMul(rotationZ(Math.toRadians(config.mountYawDeg)), rotationY(Math.toRadians(config.mountPitchDeg))),
        rotationX(Math.toRadians(config.mountRollDeg))
    )

    private var phase = 0.0
    private var cadenceJitter = 0.0
    private var stepAccumulator = 0.0
    private var traveledMeters = 0.0
    private var lastTimestampNanos: Long? = null
    private var previousRotation: DoubleArray? = null
    private var previousQuaternion = doubleArrayOf(0.0, 0.0, 0.0, 1.0)

    /** 累计步数（与 [ImuFrame.stepCount] 一致，便于外部对账）。 */
    val stepCount: Long get() = floor(stepAccumulator).toLong()

    /** 累计里程（米），用于自检 `里程 / 步数 = 步幅`。 */
    val traveledMetersTotal: Double get() = traveledMeters

    /** 当前步幅（米）；还没产生步数时返回 0。 */
    fun currentStrideMeters(): Double {
        val steps = stepAccumulator
        return if (steps <= 0.0) 0.0 else traveledMeters / steps
    }

    /** 重新开始一段路程（例如服务重启、切路线）。 */
    fun reset() {
        phase = 0.0
        cadenceJitter = 0.0
        stepAccumulator = 0.0
        traveledMeters = 0.0
        lastTimestampNanos = null
        previousRotation = null
        previousQuaternion = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    }

    /**
     * 合成一帧。调用频率应等于目标传感器的输出频率（默认 10Hz）。
     *
     * @param timestampNanos 与定位同源的时间戳（`SystemClock.elapsedRealtimeNanos()` 量纲）
     * @param sample 该时刻的轨迹运动状态
     */
    fun nextFrame(timestampNanos: Long, sample: ImuMotionSample): ImuFrame {
        val previousNanos = lastTimestampNanos
        lastTimestampNanos = timestampNanos
        val dtSeconds = if (previousNanos == null) {
            1.0 / config.nominalRateHz
        } else {
            ((timestampNanos - previousNanos) / 1_000_000_000.0)
                .coerceIn(config.minStepSeconds, config.maxStepSeconds)
        }

        val speed = max(0.0, sample.speedMps)

        // ---- 步频：人体节律模型 + 一阶自回归抖动（真实步间隔 CV 约 2%） ----
        val modelCadenceHz = cadenceHzFor(speed)
        cadenceJitter = (cadenceJitter * config.cadenceJitterDecay + gaussian() * config.cadenceJitterSigma)
            .coerceIn(-config.cadenceJitterLimit, config.cadenceJitterLimit)
        val cadenceHz = if (modelCadenceHz > 0.0) modelCadenceHz * (1.0 + cadenceJitter) else 0.0

        phase += TWO_PI * cadenceHz * dtSeconds
        stepAccumulator += cadenceHz * dtSeconds
        traveledMeters += speed * dtSeconds

        // ---- 世界系运动加速度（东、北、天） ----
        val amplitude = verticalAmplitudeFor(speed)
        val aUp = verticalHarmonics(cadenceHz, amplitude)

        val psi = -sample.headingRad
        val forwardEast = -sin(psi)
        val forwardNorth = cos(psi)
        val rightEast = cos(psi)
        val rightNorth = sin(psi)

        val aLateral = config.lateralRatio * amplitude * sin(phase + 2.2)
        val aLongitudinal = sample.longitudinalAccelMps2 +
            config.longitudinalRatio * amplitude * sin(2.0 * phase + 0.4)
        val aCentripetal = speed * speed * sample.curvatureRadPerMeter

        val motionEast = aLongitudinal * forwardEast + aLateral * rightEast - aCentripetal * rightEast
        val motionNorth = aLongitudinal * forwardNorth + aLateral * rightNorth - aCentripetal * rightNorth
        val motionUp = aUp

        // ---- 姿态：航向 × 步态摆动 × 固定安装角 ----
        val gaitScale = if (cadenceHz > 0.0) (speed / 2.5).coerceIn(0.6, 1.6) else 0.0
        val roll = Math.toRadians(config.swayRollDeg) * gaitScale * sin(phase + 0.3)
        val pitch = Math.toRadians(config.swayPitchDeg) * gaitScale * sin(phase + Math.PI / 2 + 0.5)
        val rotation = matMul(
            rotationZ(psi),
            matMul(rotationY(pitch), matMul(rotationX(roll), mountRotation))
        )

        // ---- 加速度计：比力 = Rᵀ(a_motion + g·ẑ) ----
        val specificForce = doubleArrayOf(
            motionEast + gaussian() * config.accelNoiseSigma,
            motionNorth + gaussian() * config.accelNoiseSigma,
            motionUp + GRAVITY + gaussian() * config.accelNoiseSigma
        )
        val accelBody = matVec(transpose(rotation), specificForce)

        // ---- 陀螺：ω_body = vee(Rᵀ·dR/dt)，与四元数严格同源 ----
        val previous = previousRotation
        val gyroBody = if (previous == null) {
            doubleArrayOf(
                gaussian() * config.gyroNoiseSigma,
                gaussian() * config.gyroNoiseSigma,
                gaussian() * config.gyroNoiseSigma
            )
        } else {
            val delta = DoubleArray(9) { (rotation[it] - previous[it]) / dtSeconds }
            val skew = matMul(transpose(rotation), delta)
            doubleArrayOf(
                skew[7] + gaussian() * config.gyroNoiseSigma,
                skew[2] + gaussian() * config.gyroNoiseSigma,
                skew[3] + gaussian() * config.gyroNoiseSigma
            )
        }
        previousRotation = rotation

        // ---- 旋转矢量（四元数），保持符号连续以便服务端做差分/插值 ----
        val quaternion = quaternionFromMatrix(rotation)
        if (dot(quaternion, previousQuaternion) < 0.0) {
            for (i in 0..3) quaternion[i] = -quaternion[i]
        }
        previousQuaternion = quaternion

        return ImuFrame(
            timestampNanos = timestampNanos,
            accelX = accelBody[0].toFloat(),
            accelY = accelBody[1].toFloat(),
            accelZ = accelBody[2].toFloat(),
            gyroX = gyroBody[0].toFloat(),
            gyroY = gyroBody[1].toFloat(),
            gyroZ = gyroBody[2].toFloat(),
            quatX = quaternion[0].toFloat(),
            quatY = quaternion[1].toFloat(),
            quatZ = quaternion[2].toFloat(),
            quatW = quaternion[3].toFloat(),
            cadenceSpm = cadenceHz * 60.0,
            stepCount = stepCount,
            strideMeters = currentStrideMeters()
        )
    }

    /** 由速度推出的目标步频（步/分钟）；低于步行阈值时为 0（静止不出步）。 */
    fun cadenceSpmFor(speedMps: Double): Double = cadenceHzFor(speedMps) * 60.0

    /** 由速度推出的步幅（米/步）；速度过低时返回 0。 */
    fun strideMetersFor(speedMps: Double): Double {
        val cadenceHz = cadenceHzFor(speedMps)
        return if (cadenceHz <= 0.0) 0.0 else speedMps / cadenceHz
    }

    private fun cadenceHzFor(speedMps: Double): Double {
        if (speedMps <= 0.0) return 0.0
        val baseSpm = (config.cadenceOffsetSpm + config.cadencePerMps * speedMps)
            .coerceIn(config.cadenceMinSpm, config.cadenceMaxSpm)
        if (speedMps <= config.walkThresholdMps) return 0.0
        if (speedMps < config.fullCadenceMps) {
            val ramp = (speedMps - config.walkThresholdMps) /
                (config.fullCadenceMps - config.walkThresholdMps)
            return baseSpm * ramp / 60.0
        }
        return baseSpm / 60.0
    }

    private fun verticalAmplitudeFor(speedMps: Double): Double {
        if (cadenceHzFor(speedMps) <= 0.0) return 0.0
        return (config.verticalAccelBase + config.verticalAccelPerMps * speedMps)
            .coerceIn(0.0, config.verticalAccelMaxMps2)
    }

    /**
     * 竖向加速度的谐波叠加。高次谐波按设备前端抗混叠特性衰减：
     * 10Hz 采样下 Nyquist 只有 5Hz，若把 2f 以上的能量原样塞进去，
     * 频谱会呈现"欠采样"特征，反而是破绽。
     */
    private fun verticalHarmonics(cadenceHz: Double, amplitude: Double): Double {
        if (cadenceHz <= 0.0 || amplitude <= 0.0) return 0.0
        return antialiasGain(1.0 * cadenceHz) * amplitude * sin(phase) +
            antialiasGain(2.0 * cadenceHz) * (0.25 * amplitude) * sin(2.0 * phase + 0.9) +
            antialiasGain(3.0 * cadenceHz) * (0.08 * amplitude) * sin(3.0 * phase + 1.7) +
            antialiasGain(4.0 * cadenceHz) * (0.03 * amplitude) * sin(4.0 * phase + 2.4)
    }

    private fun antialiasGain(frequencyHz: Double): Double {
        if (frequencyHz <= 0.0) return 1.0
        val ratio = frequencyHz / config.antialiasCutoffHz
        return 1.0 / (1.0 + ratio * ratio * ratio * ratio * ratio * ratio * ratio * ratio)
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(TWO_PI * u2)
    }

    companion object {
        const val GRAVITY = 9.80665
        private const val TWO_PI = 6.283185307179586

        private fun dot(a: DoubleArray, b: DoubleArray): Double =
            a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
    }
}

/** 合成一帧所需的轨迹运动状态（来自 [TrajectoryInterpolator] 的 PositionResult）。 */
data class ImuMotionSample(
    /** 与 [ImuFrame.timestampNanos] 同一条时间轴上的秒数。 */
    val timeSeconds: Double,
    /** 当前速度（m/s）。 */
    val speedMps: Double,
    /** 罗盘航向（顺时针自北，`Location.bearing` 的弧度）。 */
    val headingRad: Double,
    /** 路径曲率（1/m），左正右负；直线为 0。曲率 × v² 就是向心加速度。 */
    val curvatureRadPerMeter: Double = 0.0,
    /** 沿前进方向的加速度（m/s²），即 dv/dt。 */
    val longitudinalAccelMps2: Double = 0.0
)

/** 一帧合成 IMU。字段顺序对应 Android 的 `TYPE_ACCELEROMETER` / `TYPE_GYROSCOPE` / `TYPE_ROTATION_VECTOR`。 */
data class ImuFrame(
    val timestampNanos: Long,
    /** 加速度计，人体系，m/s²，含重力（静止时模长 ≈ 9.80665）。 */
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    /** 陀螺，人体系，rad/s。 */
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    /** 旋转矢量 / 四元数，世界系 ENU ← 人体系，单位长。 */
    val quatX: Float,
    val quatY: Float,
    val quatZ: Float,
    val quatW: Float,
    /** 瞬时步频（步/分钟）。 */
    val cadenceSpm: Double,
    /** 累计步数。 */
    val stepCount: Long,
    /** 当前平均步幅（米/步），恒等于 `累计里程 / 步数`。 */
    val strideMeters: Double
)

/** 合成参数：默认值即经过一致性验证的一组参数（见 docs/imu-synthesis.md）。 */
data class ImuConfig(
    /** 步频模型的截距（步/分钟）。 */
    val cadenceOffsetSpm: Double = 108.0,
    /** 步频模型随速度的斜率（步/分钟 每 m/s）。 */
    val cadencePerMps: Double = 17.0,
    val cadenceMinSpm: Double = 100.0,
    val cadenceMaxSpm: Double = 196.0,
    /** 低于此速度视为静止：不出步、无步态加速度。 */
    val walkThresholdMps: Double = 0.35,
    /** 到此速度步频完整建立（之间线性爬升，避免起步瞬间步频跳变）。 */
    val fullCadenceMps: Double = 0.9,
    /** 竖向加速度幅值的基线（m/s²）。 */
    val verticalAccelBase: Double = 0.9,
    /** 竖向加速度幅值随速度的增量。 */
    val verticalAccelPerMps: Double = 0.9,
    val verticalAccelMaxMps2: Double = 6.0,
    /** 抗混叠截止频率（Hz）；10Hz 采样必须远低于 5Hz。 */
    val antialiasCutoffHz: Double = 3.2,
    /** 侧向摆动幅度占竖向幅度的比例。 */
    val lateralRatio: Double = 0.35,
    /** 前后向摆动幅度占竖向幅度的比例。 */
    val longitudinalRatio: Double = 0.22,
    /** 步态滚转摆动幅度（度）。 */
    val swayRollDeg: Double = 2.2,
    /** 步态俯仰摆动幅度（度）。 */
    val swayPitchDeg: Double = 3.0,
    /** 固定安装偏航（度）：手机相对人体前向的朝向，服务端只能看到常量偏移。 */
    val mountYawDeg: Double = -35.0,
    val mountPitchDeg: Double = 18.0,
    val mountRollDeg: Double = -8.0,
    /** 步频抖动的一阶自回归强度与上限（产生 2% 量级的步间隔变异）。 */
    val cadenceJitterSigma: Double = 0.004,
    val cadenceJitterDecay: Double = 0.98,
    val cadenceJitterLimit: Double = 0.08,
    val accelNoiseSigma: Double = 0.012,
    val gyroNoiseSigma: Double = 0.004,
    /** 首次调用时用于推进相位的时间步长。 */
    val nominalRateHz: Double = 10.0,
    /** 相邻帧时间差的限幅，避免卡顿/暂停后相位跳变。 */
    val minStepSeconds: Double = 0.004,
    val maxStepSeconds: Double = 0.25,
    val seed: Long = 20261021L
)

/** 四元数/旋转矩阵工具，便于在测试或服务端口径下复算一致性。 */
object ImuMath {

    /** 由单位四元数得到世界系 ← 人体系的旋转矩阵（行主序 3×3）。 */
    fun rotationMatrix(qx: Double, qy: Double, qz: Double, qw: Double): DoubleArray {
        val xx = qx * qx
        val yy = qy * qy
        val zz = qz * qz
        val xy = qx * qy
        val xz = qx * qz
        val yz = qy * qz
        val wx = qw * qx
        val wy = qw * qy
        val wz = qw * qz
        return doubleArrayOf(
            1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy),
            2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx),
            2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy)
        )
    }

    /** 把人体系向量转到世界系（ENU）。 */
    fun toWorld(qx: Double, qy: Double, qz: Double, qw: Double, vx: Double, vy: Double, vz: Double): DoubleArray {
        val r = rotationMatrix(qx, qy, qz, qw)
        return doubleArrayOf(
            r[0] * vx + r[1] * vy + r[2] * vz,
            r[3] * vx + r[4] * vy + r[5] * vz,
            r[6] * vx + r[7] * vy + r[8] * vz
        )
    }

    /** 由四元数推出的"该体轴在重力方向上的投影"，即静止时加速度计应读到的值。 */
    fun gravityInBody(qx: Double, qy: Double, qz: Double, qw: Double, gravity: Double = 9.80665): DoubleArray {
        val r = rotationMatrix(qx, qy, qz, qw)
        // Rᵀ·(0, 0, g)
        return doubleArrayOf(
            r[6] * gravity,
            r[7] * gravity,
            r[8] * gravity
        )
    }

    /** 由四元数推出某个体轴（默认 +Y = 前进方向）的罗盘方位（顺时针自北，弧度）。 */
    fun bearingRadOfAxis(
        qx: Double,
        qy: Double,
        qz: Double,
        qw: Double,
        axisX: Double = 0.0,
        axisY: Double = 1.0,
        axisZ: Double = 0.0
    ): Double {
        val world = toWorld(qx, qy, qz, qw, axisX, axisY, axisZ)
        return atan2(world[0], world[1])
    }

    /** 两角度之差，归一化到 (-π, π]。 */
    fun angleDelta(a: Double, b: Double): Double {
        var d = a - b
        while (d > Math.PI) d -= 2.0 * Math.PI
        while (d <= -Math.PI) d += 2.0 * Math.PI
        return d
    }

    /** 四元数夹角（弧度）。 */
    fun quaternionAngle(a: DoubleArray, b: DoubleArray): Double {
        val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceIn(0.0, 1.0)
        return 2.0 * kotlin.math.acos(d)
    }
}

// ---------------------------------------------------------------------------
// 3×3 矩阵与四元数内部实现（只在本文件内使用，保持纯 Kotlin、可 JVM 单测）
// ---------------------------------------------------------------------------

private fun rotationZ(angle: Double): DoubleArray {
    val c = cos(angle)
    val s = sin(angle)
    return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
}

private fun rotationY(angle: Double): DoubleArray {
    val c = cos(angle)
    val s = sin(angle)
    return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
}

private fun rotationX(angle: Double): DoubleArray {
    val c = cos(angle)
    val s = sin(angle)
    return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
}

private fun matMul(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            out[row * 3 + col] = a[row * 3] * b[col] +
                a[row * 3 + 1] * b[3 + col] +
                a[row * 3 + 2] * b[6 + col]
        }
    }
    return out
}

private fun transpose(m: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            out[col * 3 + row] = m[row * 3 + col]
        }
    }
    return out
}

private fun matVec(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
    m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
    m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
    m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
)

/** 旋转矩阵 → 单位四元数 (x, y, z, w)。 */
private fun quaternionFromMatrix(m: DoubleArray): DoubleArray {
    val trace = m[0] + m[4] + m[8]
    val q: DoubleArray = if (trace > 0.0) {
        val s = sqrt(trace + 1.0) * 2.0
        doubleArrayOf((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25 * s)
    } else if (m[0] > m[4] && m[0] > m[8]) {
        val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2.0
        doubleArrayOf(0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
    } else if (m[4] > m[8]) {
        val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2.0
        doubleArrayOf((m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
    } else {
        val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2.0
        doubleArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s, (m[3] - m[1]) / s)
    }
    val norm = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
    if (norm > 0.0) {
        for (i in 0..3) q[i] /= norm
    }
    return q
}
