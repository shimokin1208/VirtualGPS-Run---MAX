# 与 GPS 轨迹物理自洽的 10Hz IMU 合成

本文说明 `algorithm/ImuSynthesizer.kt` 的模型、参数、可被服务端独立复算的自洽性指标，
以及"真正生效"还欠什么。

## 1. 为什么要合成，而不是"补噪声"

第三层上传原始 IMU 序列后，服务端不依赖客户端自陈的计数，可以直接重算：

| 服务端可独立计算的量 | 用到的数据 | 合成时必须满足 |
| --- | --- | --- |
| 步频 `f_step` | `az`（竖向加速度）主频 / 峰谷计数 | 与 `GPS 速度` 经人体节律关系一致 |
| 有效步幅 | `GPS 位移 / 步数` | 落在人体区间 0.5–1.8 m，且与步频-速度自洽 |
| 重力方向 | `q*` → `Rᵀ(0,0,g)` vs 低通加速度 | 两者必须同向（静止时模长 = 9.80665） |
| 前向方位 | `q*` → 前向轴方位 vs 轨迹方位 | 只允许差一个**常量**安装偏航 |
| 转向 | `gyro` 世界系 z 分量 vs 方位变化率 | 斜率 = −1（符号见 §3.4） |
| 步态真实性 | `az` 频谱形态、步间隔变异 | 不能是完美正弦，也不能有欠采样特征 |

所以判据不是"像不像传感器数据"，而是**三路数据与轨迹之间能不能互相推导出来**。

## 2. 模型

### 2.1 步频与步幅（同一套积分）

```
f_spm(v) = clamp(108 + 17·v, 100, 196)        # 步/分钟，v 单位 m/s
f(v) ≤ 0.35 m/s → 0                           # 静止不出步
0.35 < v < 0.9 m/s → 线性爬升                 # 起步不跳变
θ(t) = 2π∫f dt                                # 相位连续累积（不按帧重置）
步数 = ∫f dt                                  # 与相位同一个积分
步幅 = v / f                                  # 因此 = 位移/步数，恒等成立
```

`f(v)` 给出的人体参数：4'00"/km(4.17 m/s) → 179 spm / 1.40 m；6'00"/km(2.78) → 155 spm / 1.08 m；
9'00"/km(1.85) → 139 spm / 0.80 m。全部落在文献区间内，且随速度单调不减。

**关键点**：步数、相位、位移都来自同一个 `v(t)`，所以"服务端拿 GPS 位移除以步数反推步幅"
和"用步频推步幅"得到同一个数——这不是巧合，是构造上的恒等关系。

### 2.2 加速度计（含重力，世界系先算再转到体体系）

```
a_motion_world = a_长向 · forward + a_侧向 · right + a_向心 · left + (0,0,a_竖向)
a_竖向(t)   = Σ_{k=1..4} G_抗混叠(k·f) · A_k(v) · sin(kθ + φ_k)
              A_1 = 0.9 + 0.9v (≤6), A_2 = 0.25A_1, A_3 = 0.08A_1, A_4 = 0.03A_1
a_侧向      = 0.35·A_1·sin(θ+2.2)
a_长向      = dv/dt + 0.22·A_1·sin(2θ+0.4)
a_向心      = v²·κ
accel_body = Rᵀ·(a_motion_world + (0,0,9.80665))
```

`G_抗混叠(x) = 1/(1+(x/3.2)⁸)`：10 Hz 采样的 Nyquist 只有 5 Hz，把 2f 以上的能量原样塞进去
会形成"欠采样"频谱特征，反而是破绽；真实设备的前端抗混叠滤波器同样会压掉这些谐波。

### 2.3 姿态与四元数（三路数据的唯一来源）

```
R = Rz(ψ) · Ry(pitch_sway) · Rx(roll_sway) · M_安装
ψ = −heading（罗盘航向顺时针自北，取负号后 前向 = (−sinψ, cosψ) = (sin h, cos h)）
roll_sway  = 2.2° · s(v) · sin(θ+0.3)
pitch_sway = 3.0° · s(v) · sin(θ+π/2+0.5),  s(v) = clamp(v/2.5, 0.6, 1.6)
M_安装 = Rz(−35°) · Ry(18°) · Rx(−8°)        # 固定的口袋/手持姿态
```

- **加速度计**用 `Rᵀ` 投影 ⇒ 重力方向与四元数天然一致；
- **陀螺**用 `ω_body = vee(Rᵀ·dR/dt)` ⇒ 与四元数的变化率是同一条曲线，不会出现"q 说在转弯但 gyro 没动"；
- **四元数**由同一个 `R` 转出来，单位长、符号连续（服务端做插值/差分时不会看到跳变）。

### 2.4 变异与噪声（避免"过于完美"）

| 项 | 处理 | 目标特征 |
| --- | --- | --- |
| 步频抖动 | 一阶自回归 `j = 0.98j + N(0,0.004)`，限幅 ±8% | 步间隔 CV ≈ 2%（真实 2–4%） |
| 加速度噪声 | `σ = 0.012 m/s²` | 噪声底接近手机 MEMS |
| 陀螺噪声 | `σ = 0.004 rad/s` | 同上 |
| 速度慢变 | 由轨迹器提供的 `±4%` | 步频随之缓变，非定值 |

## 3. 自洽性实测（脚本可复现）

`python tools/imu_consistency_probe.py`（模型层面）与
`python tools/imu_test_emulation.py`（逐条预演 Kotlin 单测断言）给出：

| 检查 | 实测 | 判据 |
| --- | --- | --- |
| 低通后加速度模长 vs g | 偏差 0.02–0.05 m/s² | < 0.1 |
| `az` 主频 vs 上报步频 | 误差 0.03–0.63% | < 6% |
| GPS 位移/步数 = 步幅 | 1.079 / 1.232 / 1.091 / 0.808 m | ∈ 0.5–1.8，且与模型差 < 3% |
| `|q|−1` | 1.1e-16 | < 1e-5 |
| 前向方位 vs 轨迹方位 | 残差 0.42–1.26° | < 2.5° |
| `q*` 重力 vs 加速度计方向（两侧同低通） | 0.57° | < 2° |
| gyro 世界系 z 斜率（R=18 m 弯道） | −0.952 | +1 的偏差 < 0.3 |
| 3.2 Hz 以上能量占比 | 0.02–2.6% | < 10% |
| 步频变异系数 | 1.8–2.4% | 0.3–10% |
| 静止段 | 0 步、模长 9.805 | 必须 0 |

弯道里"低通加速度方向 vs 重力"会多出 `atan(v²κ/g)` 的偏角（R=18 m、2.78 m/s 时约 2.5°），
这是**真实物理**（向心加速度就在那里），服务端同样要扣掉它，不是破绽。

### 3.4 符号约定（重要）

世界系 ENU（x 东、y 北、z 天），人体系（x 右、y 前、z 上），`ψ = −heading`。
因此 `q*` 推出的**前向轴方位** = 轨迹方位 + 常量；而若服务端直接对 `R` 取
`atan2(R10, R00)` 这类"ZYX 欧拉角 yaw"，符号会与罗盘航向相反。
只要是"用 q 求前向轴方位再比"，方向（手性）就一定一致——这是硬约束：转向方向错了，
任何常量偏航都吸收不掉。

## 4. 10Hz 采样的三个坑

1. **Nyquist 只有 5 Hz**：步频 2.6 Hz 时第 2 谐波 5.2 Hz 已经越界。必须把谐波按模拟前端
   抗混叠曲线衰减（本实现 3.2 Hz 截止、8 阶），否则频谱会呈现典型的混叠镜像。
2. **采样率不能恒定 10.000 Hz**：真实 `SENSOR_DELAY_*` 有 1–5 ms 抖动。调用方应在
   `100ms ± jitter` 上发时间戳（时间戳仍单调），否则"零抖动"本身是特征。
3. **丢帧**：真实设备会偶发丢帧。可按万分之一二的概率丢掉一帧（并保持时间戳连续），
   比"一帧不丢"更可信。

## 5. 怎么真正喂给目标应用

**普通应用做不到**：Android 的传感器数据由 `SystemSensorManager` 在目标进程内分发，
没有第三方 API 可以"写入别人的传感器"。所以合成器只是必要条件，注入层需要：

- **root + LSPosed/Xposed**：hook 目标进程的
  `android.hardware.SystemSensorManager$SensorEventQueue#dispatchSensorEvent(int handle, float[] values, int accuracy, long timestamp)`，
  对加速度计 / 陀螺 / 旋转矢量的 handle 直接改 `values[]` 与 `timestamp`；
- 或 **VirtualXposed / 双开框架**：在虚拟环境里替换 HAL / SensorService；
- 或 **自编译 ROM / 内核虚拟传感器**：写一个 virtual sensor HAL，把数据通道接出来。

骨架（放到独立 LSPosed 模块里，不要塞进本 App，注释里标注了未验证部分）：

```kotlin
// LSPosed 模块入口（需 AndroidManifest 里 xposed 元数据；未在真机验证）
class ImuHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "TARGET_PACKAGE") return

        // handle -> sensor type，注册时或首次读取时缓存
        val handleToType = HashMap<Int, Int>()

        XposedHelpers.findAndHookMethod(
            "android.hardware.SystemSensorManager\$SensorEventQueue",
            lpparam.classLoader,
            "dispatchSensorEvent",
            Int::class.javaPrimitiveType, FloatArray::class.java,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val handle = param.args[0] as Int
                    val values = param.args[1] as FloatArray
                    val timestamp = param.args[3] as Long
                    val type = handleToType.getOrPut(handle) { resolveSensorType(handle) }
                    val frame = TrajectoryFeeder.frameAt(timestamp) ?: return
                    when (type) {
                        Sensor.TYPE_ACCELEROMETER ->
                            values.fill3(frame.accelX, frame.accelY, frame.accelZ)
                        Sensor.TYPE_GYROSCOPE ->
                            values.fill3(frame.gyroX, frame.gyroY, frame.gyroZ)
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            // Android 旋转矢量是 (x, y, z, w*可选)，前三个分量 + 可选 heading 精度
                            values[0] = frame.quatX
                            values[1] = frame.quatY
                            values[2] = frame.quatZ
                            values[3] = ((1f + frame.quatW) / 2f).coerceIn(0f, 1f)
                        }
                    }
                }
            }
        )
    }
}
```

`TrajectoryFeeder.frameAt(timestamp)` 就是"按目标应用的时间戳查/插值出 `ImuFrame`"，
数据源应当是 `ImuSynthesizer` 在**同一条时间轴**上预先生成好的帧序列
（因为 `timestamp` 是目标应用侧的时钟，喂入侧必须按时间戳对齐，不能按序号对齐）。

## 6. 仍然可能被识破的地方（自洽不等于隐身）

1. **个体基线**：服务端若持有该账号历史记录，会把本次的"步频-速度-步幅"关系与历史比对。
   恒定参数（`ImuConfig` 固定）会形成可聚类的个体画像；建议按账号/场次做小幅随机化
   （`cadenceOffsetSpm`、`verticalAccelBase`、`mountYawDeg` 都参与）。
2. **安装姿态漂移**：手机在口袋里会转向、换手、掏出来看。`M_安装` 是常量，长时间恒定
   与"手机绝对静止地绑在腰上"等价。真实做法是给 `mountYawDeg` 加缓慢随机游走
   （例如每 2–5 分钟 ±10°），并在"掏出看屏幕"时切换姿态。
3. **其它通道**：气压计、磁力计、光线/距离传感器、WiFi/BLE 扫描、屏幕状态、通话状态。
   目标应用若采集这些，仅 IMU + GPS 自洽还不够。
4. **量化与饱和**：真实传感器有固定量程（±2g/±16g）与量化步长，极端配速下会出现饱和；
   合成器输出的是连续值。
5. **两路轨迹（第三层"双份轨迹"）**：如果第二份轨迹来自别的来源（例如另一台设备/另一通道），
   两份之间的统计差异可能比 IMU 更容易露出问题。

## 7. 代码位置与验证

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/virtualrun/app/algorithm/ImuSynthesizer.kt` | 合成器（纯 Kotlin，无 Android 依赖） |
| `app/src/test/java/com/virtualrun/app/algorithm/ImuSynthesizerTest.kt` | 锁住 §3 全部自洽性指标的 JVM 单测 |
| `tools/imu_consistency_probe.py` | 模型层面的多场景一致性探针 |
| `tools/imu_test_emulation.py` | 逐条预演 Kotlin 单测断言实际会看到的数值 |

```bash
./gradlew :app:testDebugUnitTest --tests "com.virtualrun.app.algorithm.ImuSynthesizerTest"
python tools/imu_consistency_probe.py     # 需要 numpy
python tools/imu_test_emulation.py
```

接入 `MockLocationService` 的推荐方式（每 100 ms 一帧）：

```kotlin
val synthesizer = ImuSynthesizer()
// v / bearing / κ 来自轨迹器：κ 可用相邻两段 bearing 差除以行进距离
val frame = synthesizer.nextFrame(
    SystemClock.elapsedRealtimeNanos(),
    ImuMotionSample(
        timeSeconds = elapsedSeconds,
        speedMps = result.speed.toDouble(),
        headingRad = Math.toRadians(result.bearing.toDouble()),
        curvatureRadPerMeter = kappa,
        longitudinalAccelMps2 = (speed - previousSpeed) / 0.1
    )
)
```

> 注意：`ImuSynthesizer` 默认按相邻时间戳之差推进相位，因此**必须**用与目标时间戳同源的时钟，
> 且喂入顺序单调递增；暂停/继续时用 `reset()` 或让时间戳自然跳变（内部会对 dt 限幅）。
