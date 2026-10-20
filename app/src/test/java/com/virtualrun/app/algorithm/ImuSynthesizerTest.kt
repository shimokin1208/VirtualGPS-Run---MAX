package com.virtualrun.app.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 锁住"合成 IMU 与 GPS 轨迹物理自洽"这条性质。
 *
 * 每一项断言对应的都是服务端可以完全独立复算的量：
 * 步频（az 主频）↔ 速度、位移/步数 ↔ 步幅、q* 推出的重力 ↔ 低通加速度、
 * q* 推出的前向方位 ↔ 轨迹方位、gyro 世界系 z ↔ 轨迹方位变化率。
 *
 * 数值下限来自 tools/imu_consistency_probe.py 的实测余量，改动模型时这些断言会先失败。
 */
class ImuSynthesizerTest {

    private val rateHz = 10.0
    private val step = 1.0 / rateHz
    private val gravity = ImuSynthesizer.GRAVITY

    private data class Run(
        val frames: List<ImuFrame>,
        val times: DoubleArray,
        val headings: DoubleArray,
        val distanceMeters: Double,
        val targetCadenceSpm: Double
    )

    /**
     * 生成一段轨迹并逐帧合成：速度在目标值附近 ±4% 慢变，
     * 曲率给定时按左转为正持续转弯。
     */
    private fun simulate(
        seconds: Double,
        targetSpeedMps: Double,
        turnRadiusMeters: Double
    ): Run {
        val synthesizer = ImuSynthesizer()
        val count = (seconds * rateHz).toInt() + 1
        val frames = ArrayList<ImuFrame>(count)
        val times = DoubleArray(count)
        val headings = DoubleArray(count)
        val curvature = if (turnRadiusMeters <= 0.0) 0.0 else 1.0 / turnRadiusMeters
        val baseNanos = 1_000_000_000L
        var heading = 0.0
        var distance = 0.0
        var previousSpeed = targetSpeedMps

        for (i in 0 until count) {
            val t = i * step
            val speed = targetSpeedMps * (1.0 + 0.04 * sin(2.0 * Math.PI * 0.05 * t))
            val acceleration = if (i == 0) 0.0 else (speed - previousSpeed) / step
            if (i > 0) {
                // 左转为正曲率，罗盘航向随之减小
                heading -= speed * curvature * step
            }
            distance += speed * step
            frames.add(
                synthesizer.nextFrame(
                    baseNanos + (t * 1_000_000_000.0).toLong(),
                    ImuMotionSample(
                        timeSeconds = t,
                        speedMps = speed,
                        headingRad = heading,
                        curvatureRadPerMeter = curvature,
                        longitudinalAccelMps2 = acceleration
                    )
                )
            )
            times[i] = t
            headings[i] = heading
            previousSpeed = speed
        }

        return Run(frames, times, headings, distance, synthesizer.cadenceSpmFor(targetSpeedMps))
    }

    /** 双向（零相位）一阶低通，用来复现服务端"先滤再比"的做法。 */
    private fun lowPass(values: DoubleArray, cutoffHz: Double, passes: Int = 2): DoubleArray {
        val alpha = exp(-2.0 * Math.PI * cutoffHz / rateHz)
        var current = values.copyOf()
        repeat(passes) {
            var acc = current[0]
            val forward = DoubleArray(current.size)
            for (i in current.indices) {
                acc = alpha * acc + (1.0 - alpha) * current[i]
                forward[i] = acc
            }
            acc = forward[forward.size - 1]
            val backward = DoubleArray(current.size)
            for (i in current.indices.reversed()) {
                acc = alpha * acc + (1.0 - alpha) * forward[i]
                backward[i] = acc
            }
            current = backward
        }
        return current
    }

    /** 在 [lowHz, highHz] 上扫频找主频（Hann 窗），等价于服务端做的 FFT 峰值。 */
    private fun dominantFrequencyHz(signal: DoubleArray, lowHz: Double, highHz: Double): Double {
        val n = signal.size
        val mean = signal.average()
        var best = lowHz
        var bestPower = -1.0
        var frequency = lowHz
        while (frequency <= highHz) {
            val omega = 2.0 * Math.PI * frequency / rateHz
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until n) {
                val window = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1))
                val x = (signal[i] - mean) * window
                real += x * cos(omega * i)
                imaginary -= x * sin(omega * i)
            }
            val power = real * real + imaginary * imaginary
            if (power > bestPower) {
                bestPower = power
                best = frequency
            }
            frequency += 0.02
        }
        return best
    }

    private fun unitNormError(frame: ImuFrame): Double = abs(
        sqrt(
            frame.quatX.toDouble() * frame.quatX +
                frame.quatY.toDouble() * frame.quatY +
                frame.quatZ.toDouble() * frame.quatZ +
                frame.quatW.toDouble() * frame.quatW
        ) - 1.0
    )

    private fun forwardBearingRad(frame: ImuFrame): Double = ImuMath.bearingRadOfAxis(
        frame.quatX.toDouble(), frame.quatY.toDouble(), frame.quatZ.toDouble(), frame.quatW.toDouble()
    )

    @Test
    fun steadyRunStaysPhysicallyConsistentWithTrack() {
        val run = simulate(seconds = 60.0, targetSpeedMps = 2.78, turnRadiusMeters = 600.0)
        val frames = run.frames
        assertEquals(
            "帧数应与采样率一致",
            ((60.0 * rateHz).toInt() + 1).toLong(),
            frames.size.toLong()
        )

        // 时间戳必须单调递增（服务端会按时间对齐两路数据）
        for (i in 1 until frames.size) {
            assertTrue("时间戳必须单调递增: $i", frames[i].timestampNanos > frames[i - 1].timestampNanos)
        }

        // 1) 重力可恢复：低通后的加速度模长 = g
        val ax = lowPass(DoubleArray(frames.size) { frames[it].accelX.toDouble() }, 0.4)
        val ay = lowPass(DoubleArray(frames.size) { frames[it].accelY.toDouble() }, 0.4)
        val az = lowPass(DoubleArray(frames.size) { frames[it].accelZ.toDouble() }, 0.4)
        var magnitudeSum = 0.0
        var magnitudeCount = 0
        for (i in 30 until frames.size) {
            magnitudeSum += sqrt(ax[i] * ax[i] + ay[i] * ay[i] + az[i] * az[i])
            magnitudeCount++
        }
        val measuredGravity = magnitudeSum / magnitudeCount
        assertTrue(
            "低通后的加速度模长应约等于 g，实测 $measuredGravity",
            abs(measuredGravity - gravity) < 0.1
        )

        // 2) 四元数必须是单位四元数
        var worstNorm = 0.0
        for (frame in frames) worstNorm = maxOf(worstNorm, unitNormError(frame))
        assertTrue("四元数 |q|-1 应接近 0，实际 $worstNorm", worstNorm < 1e-5)

        // 3) q* 推出的前向方位 = 轨迹方位 + 常量安装偏航（残差即模型误差）
        var offsetSum = 0.0
        var offsetCount = 0
        for (i in 30 until frames.size) {
            offsetSum += ImuMath.angleDelta(forwardBearingRad(frames[i]), run.headings[i])
            offsetCount++
        }
        val offset = offsetSum / offsetCount
        var worstBearingResidual = 0.0
        for (i in 30 until frames.size) {
            val residual = ImuMath.angleDelta(forwardBearingRad(frames[i]), run.headings[i]) - offset
            worstBearingResidual = maxOf(worstBearingResidual, abs(residual))
        }
        assertTrue(
            "前向方位残差应 < 2.5°（实测 ${Math.toDegrees(worstBearingResidual)}°）",
            Math.toDegrees(worstBearingResidual) < 2.5
        )

        // 4) az 主频 = 上报步频（服务端 FFT 反推 vs 客户端计数必须自洽）
        val window = frames.subList(150, 150 + 256)
        val vertical = DoubleArray(window.size) {
            val f = window[it]
            sqrt(
                (f.accelX.toDouble() * f.accelX + f.accelY.toDouble() * f.accelY +
                    f.accelZ.toDouble() * f.accelZ)
            ) - gravity
        }
        val measuredCadence = dominantFrequencyHz(vertical, 1.0, 4.6) * 60.0
        val reportedCadence = window.map { it.cadenceSpm }.average()
        val cadenceError = abs(measuredCadence - reportedCadence) / reportedCadence
        assertTrue(
            "az 主频($measuredCadence spm)应等于上报步频($reportedCadence spm)，误差 ${cadenceError * 100}%",
            cadenceError < 0.06
        )

        // 5) 位移 / 步数 = 有效步幅，且落在人体区间
        val last = frames.last()
        val stride = run.distanceMeters / last.stepCount
        assertTrue("步幅应落在人体区间，实际 $stride m", stride in 0.5..1.8)
        val modelStride = 2.78 / (reportedCadence / 60.0)
        assertTrue(
            "步幅($stride)应与模型步幅($modelStride)一致",
            abs(stride - modelStride) / modelStride < 0.03
        )
        assertEquals(
            "帧内步幅字段应等于 里程/步数",
            stride,
            last.strideMeters,
            0.02
        )

        // 6) 10Hz 采样下不应出现混叠特征：3.2Hz 以上能量占比要低
        val spectrum = DoubleArray(vertical.size) { vertical[it] }
        var lowBand = 0.0
        var highBand = 0.0
        val n = spectrum.size
        val mean = spectrum.average()
        var frequency = 0.2
        while (frequency < 5.0) {
            val omega = 2.0 * Math.PI * frequency / rateHz
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until n) {
                val x = (spectrum[i] - mean) * (0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1)))
                real += x * cos(omega * i)
                imaginary -= x * sin(omega * i)
            }
            val power = real * real + imaginary * imaginary
            if (frequency > 1.0 && frequency <= 3.2) lowBand += power
            if (frequency > 3.2) highBand += power
            frequency += 0.02
        }
        val highRatio = highBand / (highBand + lowBand)
        assertTrue("3.2Hz 以上能量占比应 < 10%，实际 ${highRatio * 100}%", highRatio < 0.10)

        // 7) 步态必须有变异（完美正弦本身就是破绽）
        val cadences = frames.filter { it.cadenceSpm > 1.0 }.map { it.cadenceSpm }
        val meanCadence = cadences.average()
        val variance = cadences.sumOf { (it - meanCadence) * (it - meanCadence) } / cadences.size
        val coefficient = sqrt(variance) / meanCadence
        assertTrue(
            "步频变异系数应在 0.3%~10%，实际 ${coefficient * 100}%",
            coefficient in 0.003..0.10
        )
    }

    @Test
    fun turnProducesConsistentYawRate() {
        val run = simulate(seconds = 40.0, targetSpeedMps = 2.78, turnRadiusMeters = 18.0)
        val frames = run.frames

        // 角度增量用差分算：q* 世界系 z 角速度 与 航向变化率 符号相反、量级相同
        val yawRates = DoubleArray(frames.size)
        for (i in 1 until frames.size) {
            val q = frames[i]
            val world = ImuMath.toWorld(
                q.quatX.toDouble(), q.quatY.toDouble(), q.quatZ.toDouble(), q.quatW.toDouble(),
                q.gyroX.toDouble(), q.gyroY.toDouble(), q.gyroZ.toDouble()
            )
            yawRates[i] = world[2]
        }
        val bearingRates = DoubleArray(frames.size)
        for (i in 1 until frames.size) {
            bearingRates[i] = ImuMath.angleDelta(run.headings[i], run.headings[i - 1]) / step
        }

        val from = 40
        var num = 0.0
        var den = 0.0
        for (i in from until frames.size) {
            num += yawRates[i] * bearingRates[i]
            den += bearingRates[i] * bearingRates[i]
        }
        val slope = num / den
        assertTrue(
            "转弯时 gyro 世界系 z 应等于 -航向变化率，实测斜率 $slope",
            abs(slope + 1.0) < 0.3
        )

        // 陀螺量级要像人：不是零，也不能是转速表
        val meanGyro = frames.map {
            sqrt(
                (it.gyroX.toDouble() * it.gyroX + it.gyroY.toDouble() * it.gyroY +
                    it.gyroZ.toDouble() * it.gyroZ)
            )
        }.average()
        assertTrue("陀螺均值应在人体运动范围（0.05~5 rad/s），实际 $meanGyro", meanGyro in 0.05..5.0)
    }

    @Test
    fun standingStillProducesNoSteps() {
        val synthesizer = ImuSynthesizer()
        val base = 5_000_000_000L
        var steps = 0L
        var lastMagnitude = 0.0
        for (i in 0 until 100) {
            val frame = synthesizer.nextFrame(
                base + (i * 100_000_000L),
                ImuMotionSample(
                    timeSeconds = i * 0.1,
                    speedMps = 0.0,
                    headingRad = 0.0,
                    curvatureRadPerMeter = 0.0,
                    longitudinalAccelMps2 = 0.0
                )
            )
            assertEquals("静止时不应有步频", 0.0, frame.cadenceSpm, 1e-9)
            steps = frame.stepCount
            lastMagnitude = sqrt(
                (frame.accelX.toDouble() * frame.accelX + frame.accelY.toDouble() * frame.accelY +
                    frame.accelZ.toDouble() * frame.accelZ)
            )
        }
        assertEquals("静止时步数必须为 0", 0L, steps)
        assertTrue("静止时加速度模长应 ≈ g，实际 $lastMagnitude", abs(lastMagnitude - gravity) < 0.2)
    }

    @Test
    fun cadenceAndStrideModelCoversHumanRange() {
        val synthesizer = ImuSynthesizer()
        // 4'00"/km ~ 9'00"/km 的配速区间
        var speed = 1.85
        while (speed <= 4.2) {
            val cadence = synthesizer.cadenceSpmFor(speed)
            val stride = synthesizer.strideMetersFor(speed)
            assertTrue("速度 $speed m/s 的步频应在 100~200 spm，实际 $cadence", cadence in 100.0..200.0)
            assertTrue("速度 $speed m/s 的步幅应在 0.5~1.8 m，实际 $stride", stride in 0.5..1.8)
            speed += 0.25
        }
        // 步频随速度单调不减（服务端会做这个单调性检查）
        var previous = 0.0
        speed = 1.85
        while (speed <= 4.2) {
            val cadence = synthesizer.cadenceSpmFor(speed)
            assertTrue("步频应随速度单调不减", cadence >= previous - 1e-9)
            previous = cadence
            speed += 0.25
        }
        // 静止 / 极低速不出步
        assertEquals(0.0, synthesizer.cadenceSpmFor(0.0), 1e-9)
        assertEquals(0.0, synthesizer.cadenceSpmFor(0.2), 1e-9)
        assertEquals(0.0, synthesizer.strideMetersFor(0.0), 1e-9)
    }

    @Test
    fun attitudeHelpersAgreeWithAccelerometer() {
        val run = simulate(seconds = 20.0, targetSpeedMps = 2.78, turnRadiusMeters = 0.0)
        val frames = run.frames
        // 与真实服务端一致的口径：q* 推出的重力和加速度计读数都要先低通再比，
        // 瞬时比会差出步态摆动幅度（滚转 2.2° + 俯仰 3.0°），那是运动分量不是不一致
        val ax = lowPass(DoubleArray(frames.size) { frames[it].accelX.toDouble() }, 0.3)
        val ay = lowPass(DoubleArray(frames.size) { frames[it].accelY.toDouble() }, 0.3)
        val az = lowPass(DoubleArray(frames.size) { frames[it].accelZ.toDouble() }, 0.3)
        val predicted = DoubleArray(frames.size * 3)
        for (i in frames.indices) {
            val gravityBody = ImuMath.gravityInBody(
                frames[i].quatX.toDouble(),
                frames[i].quatY.toDouble(),
                frames[i].quatZ.toDouble(),
                frames[i].quatW.toDouble()
            )
            predicted[i * 3] = gravityBody[0]
            predicted[i * 3 + 1] = gravityBody[1]
            predicted[i * 3 + 2] = gravityBody[2]
        }
        val px = lowPass(DoubleArray(frames.size) { predicted[it * 3] }, 0.3)
        val py = lowPass(DoubleArray(frames.size) { predicted[it * 3 + 1] }, 0.3)
        val pz = lowPass(DoubleArray(frames.size) { predicted[it * 3 + 2] }, 0.3)

        var worstAngle = 0.0
        for (i in 30 until frames.size step 5) {
            val dotProduct = px[i] * ax[i] + py[i] * ay[i] + pz[i] * az[i]
            val magnitudes = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i]) *
                sqrt(ax[i] * ax[i] + ay[i] * ay[i] + az[i] * az[i])
            val cosine = (dotProduct / magnitudes).coerceIn(-1.0, 1.0)
            worstAngle = maxOf(worstAngle, Math.toDegrees(kotlin.math.acos(cosine)))
        }
        assertTrue("q* 重力与加速度计方向夹角应 < 2°，实测 $worstAngle°", worstAngle < 2.0)
    }

    @Test
    fun quaternionStaysContinuousAcrossFrames() {
        val run = simulate(seconds = 30.0, targetSpeedMps = 3.33, turnRadiusMeters = 25.0)
        // 服务端常做 q 的插值/差分，符号翻转会被当成异常
        for (i in 1 until run.frames.size) {
            val a = run.frames[i - 1]
            val b = run.frames[i]
            val dotProduct = a.quatX.toDouble() * b.quatX + a.quatY.toDouble() * b.quatY +
                a.quatZ.toDouble() * b.quatZ + a.quatW.toDouble() * b.quatW
            assertTrue("相邻帧四元数不应符号翻转（dot=$dotProduct）", dotProduct >= -1e-3)
        }
    }
}
