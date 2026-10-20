"""把 ImuSynthesizerTest 的每条断言在 Python 里预演一遍。

目的：Kotlin 单测跑不起来的环境里，先确认阈值不是拍脑袋定的——
这里用的轨迹、滤波器、窗口、公式与 Kotlin 测试逐项对应，打印每条断言实际看到的数值。
"""

from __future__ import annotations

import math
import numpy as np

import imu_consistency_probe as P

RATE = 10.0
DT = 1.0 / RATE
G = 9.80665


def simulate(seconds, target_speed, turn_radius):
    """与 Kotlin 测试的 simulate() 一致：航向 = -∫v·κ dt（左转为正）。"""
    n = int(seconds * RATE) + 1
    kappa = 0.0 if turn_radius <= 0 else 1.0 / turn_radius
    s = P.Synth(seed=20261021)
    accel, gyro, quat = [], [], []
    headings = np.zeros(n)
    heading = 0.0
    distance = 0.0
    prev_speed = target_speed
    cadences = np.zeros(n)
    steps_floor = 0
    for i in range(n):
        t = i * DT
        speed = target_speed * (1.0 + 0.04 * math.sin(2 * math.pi * 0.05 * t))
        accel_along = 0.0 if i == 0 else (speed - prev_speed) / DT
        if i > 0:
            heading -= speed * kappa * DT
        distance += speed * DT
        # 合成器内部用 psi = -heading
        psi = -heading
        a, w, q, cad, steps = s.step(speed, psi, accel_along, kappa)
        accel.append(a)
        gyro.append(w)
        quat.append(q)
        headings[i] = heading
        cadences[i] = cad
        steps_floor = int(steps)
        prev_speed = speed
    return (np.array(accel), np.array(gyro), np.array(quat), headings, distance,
            cadences, steps_floor, s)


def lowpass(values, cutoff, passes=2):
    alpha = math.exp(-2 * math.pi * cutoff / RATE)
    current = np.array(values, float)
    for _ in range(passes):
        acc = current[0]
        fwd = np.empty_like(current)
        for i, x in enumerate(current):
            acc = alpha * acc + (1 - alpha) * x
            fwd[i] = acc
        acc = fwd[-1]
        bwd = np.empty_like(current)
        for i in range(len(current) - 1, -1, -1):
            acc = alpha * acc + (1 - alpha) * fwd[i]
            bwd[i] = acc
        current = bwd
    return current


def dominant_frequency(signal, low, high):
    mean = float(np.mean(signal))
    n = len(signal)
    i = np.arange(n)
    window = 0.5 - 0.5 * np.cos(2 * math.pi * i / (n - 1))
    x = (signal - mean) * window
    best, best_power = low, -1.0
    f = low
    while f <= high:
        omega = 2 * math.pi * f / RATE
        re = float(np.sum(x * np.cos(omega * i)))
        im = float(-np.sum(x * np.sin(omega * i)))
        power = re * re + im * im
        if power > best_power:
            best_power, best = power, f
        f += 0.02
    return best


def band_ratio(signal, lo_lo, lo_hi, hi_lo, hi_hi):
    mean = float(np.mean(signal))
    n = len(signal)
    i = np.arange(n)
    window = 0.5 - 0.5 * np.cos(2 * math.pi * i / (n - 1))
    x = (signal - mean) * window
    low_power = 0.0
    high_power = 0.0
    f = lo_lo
    while f < hi_hi:
        omega = 2 * math.pi * f / RATE
        re = float(np.sum(x * np.cos(omega * i)))
        im = float(-np.sum(x * np.sin(omega * i)))
        power = re * re + im * im
        if lo_lo < f <= lo_hi:
            low_power += power
        if f > hi_lo:
            high_power += power
        f += 0.02
    return high_power / (high_power + low_power)


def quat_of(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def forward_bearing(q):
    R = quat_of(q)
    world = R @ np.array([0.0, 1.0, 0.0])
    return math.atan2(world[0], world[1])


def angle_delta(a, b):
    d = (a - b + math.pi) % (2 * math.pi) - math.pi
    return d


def judge(name, value, ok, fmt="{:.4f}"):
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {fmt.format(value)}  (阈值见 Kotlin 测试)")


def steady_case():
    print("\n--- steadyRunStaysPhysicallyConsistentWithTrack (60s, 2.78 m/s, R=600m) ---")
    accel, gyro, quat, headings, distance, cadences, steps, s = simulate(60.0, 2.78, 600.0)

    norm = np.linalg.norm(accel, axis=1)
    ax = lowpass(accel[:, 0], 0.4)
    ay = lowpass(accel[:, 1], 0.4)
    az = lowpass(accel[:, 2], 0.4)
    mag = np.sqrt(ax ** 2 + ay ** 2 + az ** 2)
    measured_g = float(np.mean(mag[30:]))
    judge("1 重力模长|误差|", abs(measured_g - G), abs(measured_g - G) < 0.1)

    worst_norm = float(np.max(np.abs(np.linalg.norm(quat, axis=1) - 1.0)))
    judge("2 |q|-1 最大值", worst_norm, worst_norm < 1e-5, "{:.2e}")

    bearings = np.array([forward_bearing(q) for q in quat])
    offsets = np.array([angle_delta(bearings[i], headings[i]) for i in range(30, len(quat))])
    offset = float(np.mean(offsets))
    residual = math.degrees(float(np.max(np.abs(offsets - offset))))
    judge("3 前向方位残差(deg)", residual, residual < 2.5, "{:.3f}")

    window = slice(150, 150 + 256)
    vertical = norm[window] - G
    measured_cadence = dominant_frequency(vertical, 1.0, 4.6) * 60
    reported = float(np.mean(cadences[window]))
    err = abs(measured_cadence - reported) / reported
    judge("4 az 主频 vs 上报步频(相对误差)", err, err < 0.06, "{:.4f}")
    print(f"     az 主频 {measured_cadence:.2f} spm / 上报 {reported:.2f} spm")

    stride = distance / steps
    model_stride = 2.78 / (reported / 60.0)
    judge("5a 步幅(m)", stride, 0.5 <= stride <= 1.8, "{:.4f}")
    judge("5b 步幅 vs 模型(相对误差)", abs(stride - model_stride) / model_stride,
          abs(stride - model_stride) / model_stride < 0.03, "{:.4f}")

    ratio = band_ratio(norm[window] - G, 0.2, 3.2, 3.2, 5.0)
    judge("6 3.2Hz 以上能量占比", ratio, ratio < 0.10, "{:.4f}")

    moving = cadences[cadences > 1.0]
    cv = float(np.std(moving) / np.mean(moving))
    judge("7 步频变异系数", cv, 0.003 <= cv <= 0.10, "{:.4f}")


def turn_case():
    print("\n--- turnProducesConsistentYawRate (40s, 2.78 m/s, R=18m) ---")
    accel, gyro, quat, headings, distance, cadences, steps, s = simulate(40.0, 2.78, 18.0)
    n = len(quat)
    yaw_rate = np.zeros(n)
    for i in range(1, n):
        R = quat_of(quat[i])
        world = R @ gyro[i]
        yaw_rate[i] = world[2]
    bearing_rate = np.zeros(n)
    for i in range(1, n):
        bearing_rate[i] = angle_delta(headings[i], headings[i - 1]) / DT

    yaw_lp = lowpass(yaw_rate, 0.6)
    br_lp = lowpass(bearing_rate, 0.6)
    num = float(np.sum(yaw_lp[40:] * br_lp[40:]))
    den = float(np.sum(br_lp[40:] * br_lp[40:]))
    slope = num / den
    judge("8a gyro 世界系 z 斜率(应≈-1)", abs(slope + 1.0), abs(slope + 1.0) < 0.3, "{:.4f}")
    print(f"     斜率 {slope:.4f}")

    mean_gyro = float(np.mean(np.linalg.norm(gyro, axis=1)))
    judge("8b 陀螺均值(rad/s)", mean_gyro, 0.05 <= mean_gyro <= 5.0, "{:.4f}")


def attitude_case():
    print("\n--- attitudeHelpersAgreeWithAccelerometer (20s, 直道) ---")
    accel, gyro, quat, headings, distance, cadences, steps, s = simulate(20.0, 2.78, 0.0)
    ax = lowpass(accel[:, 0], 0.3)
    ay = lowpass(accel[:, 1], 0.3)
    az = lowpass(accel[:, 2], 0.3)
    # 与 Kotlin 测试一致：q* 重力也要先低通（与真实服务端口径相同）
    predicted = np.array([
        np.array([quat_of(q)[2, 0], quat_of(q)[2, 1], quat_of(q)[2, 2]]) * G for q in quat
    ])
    px = lowpass(predicted[:, 0], 0.3)
    py = lowpass(predicted[:, 1], 0.3)
    pz = lowpass(predicted[:, 2], 0.3)
    worst = 0.0
    for i in range(30, len(quat), 5):
        p = np.array([px[i], py[i], pz[i]])
        m = np.array([ax[i], ay[i], az[i]])
        c = float(np.dot(p, m) / (np.linalg.norm(p) * np.linalg.norm(m)))
        worst = max(worst, math.degrees(math.acos(max(-1.0, min(1.0, c)))))
    judge("11 q* 重力 vs 加速度计方向(deg)", worst, worst < 2.0, "{:.3f}")


def standing_case():
    print("\n--- standingStillProducesNoSteps (10s, v=0) ---")
    s = P.Synth(seed=20261021)
    mags = []
    for i in range(100):
        a, w, q, cad, steps = s.step(0.0, 0.0, 0.0, 0.0)
        mags.append(float(np.linalg.norm(a)))
        assert cad == 0.0, cad
    print(f"[PASS] 静止步频全 0，累计步数 {s.steps:.2f}")
    judge("9 静止加速度模长|误差|", abs(mags[-1] - G), abs(mags[-1] - G) < 0.2, "{:.4f}")


def model_case():
    print("\n--- cadenceAndStrideModelCoversHumanRange ---")
    ok = True
    prev = 0.0
    speed = 1.85
    while speed <= 4.2:
        cad = P.cadence_model_spm(speed)
        stride = speed / (cad / 60.0)
        if not (100 <= cad <= 200 and 0.5 <= stride <= 1.8 and cad >= prev - 1e-9):
            ok = False
            print(f"     FAIL speed={speed:.2f} cadence={cad:.2f} stride={stride:.3f}")
        prev = cad
        speed += 0.25
    print(f"[{'PASS' if ok else 'FAIL'}] 10 步频/步幅模型覆盖人体区间且单调不减")
    print(f"     1.85 m/s → {P.cadence_model_spm(1.85):.1f} spm / {1.85 / (P.cadence_model_spm(1.85) / 60):.3f} m")
    print(f"     4.20 m/s → {P.cadence_model_spm(4.2):.1f} spm / {4.2 / (P.cadence_model_spm(4.2) / 60):.3f} m")
    print(f"     静止      → {P.cadence_model_spm(0.0):.1f} spm")


if __name__ == "__main__":
    steady_case()
    turn_case()
    attitude_case()
    standing_case()
    model_case()
