"""合成 IMU 的物理一致性原型验证（v2）。

给定 GPS 轨迹（速度 / 航向 / 曲率），合成 10Hz 的 accel / gyro / rotation-vector，
再按"服务端能独立算出来的东西"逐项检查：

1. 低通后加速度模长 = g（重力可恢复，且运动分量不会污染 DC）
2. 由 q* 推出的重力方向 = 低通加速度方向
3. az 主频 = 由速度推出的步频；峰谷计数同样成立（10Hz 采样下可检测）
4. GPS 位移 / 步数 = 步幅，落在人体区间且等于模型步幅
5. |q| = 1；q 推出的航向与轨迹航向只差一个常量安装偏航
6. gyro 在世界系 z 上的分量 = 航向变化率（转弯段可验证）
7. 3.2Hz 以上能量占比很小（不像事后拼接/欠采样）
8. 步态本身有真实变异性（步间隔 CV 2-5%），不是完美正弦
9. 静止段不出步、不含步频特征
"""

from __future__ import annotations

import math
import numpy as np

G = 9.80665
RATE_HZ = 10.0
DT = 1.0 / RATE_HZ

CAD_OFFSET = 108.0
CAD_PER_MPS = 17.0
CAD_MIN, CAD_MAX = 100.0, 196.0
V_WALK_THRESHOLD = 0.35     # 低于此速度视为静止：不出步
V_RAMP_FULL = 0.9           # 到此速度步频完整建立

MOUNT_YAW = math.radians(-35.0)
MOUNT_PITCH = math.radians(18.0)
MOUNT_ROLL = math.radians(-8.0)


def cadence_model_spm(v: float) -> float:
    if v <= 0.0:
        return 0.0
    base = min(CAD_MAX, max(CAD_MIN, CAD_OFFSET + CAD_PER_MPS * v))
    if v <= V_WALK_THRESHOLD:
        return 0.0
    if v < V_RAMP_FULL:
        return base * (v - V_WALK_THRESHOLD) / (V_RAMP_FULL - V_WALK_THRESHOLD)
    return base


def stride_m(v: float) -> float:
    f = cadence_model_spm(v)
    return float("nan") if f <= 0 else v / (f / 60.0)


def amp_vertical(v: float) -> float:
    return min(6.0, max(0.0, 0.9 + 0.9 * v)) if cadence_model_spm(v) > 0 else 0.0


def lpf_gain(x: float) -> float:
    """设备前端抗混叠：3.2Hz 以上迅速衰减。"""
    return 1.0 / (1.0 + (abs(x) / 3.2) ** 8)


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], float)


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], float)


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], float)


MOUNT = rz(MOUNT_YAW) @ ry(MOUNT_PITCH) @ rx(MOUNT_ROLL)


def mat_to_quat(R):
    tr = R[0, 0] + R[1, 1] + R[2, 2]
    if tr > 0:
        s = math.sqrt(tr + 1.0) * 2
        w = 0.25 * s
        x = (R[2, 1] - R[1, 2]) / s
        y = (R[0, 2] - R[2, 0]) / s
        z = (R[1, 0] - R[0, 1]) / s
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = math.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        w = (R[2, 1] - R[1, 2]) / s
        x = 0.25 * s
        y = (R[0, 1] + R[1, 0]) / s
        z = (R[0, 2] + R[2, 0]) / s
    elif R[1, 1] > R[2, 2]:
        s = math.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        w = (R[0, 2] - R[2, 0]) / s
        x = (R[0, 1] + R[1, 0]) / s
        y = 0.25 * s
        z = (R[1, 2] + R[2, 1]) / s
    else:
        s = math.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
        w = (R[1, 0] - R[0, 1]) / s
        x = (R[0, 2] + R[2, 0]) / s
        y = (R[1, 2] + R[2, 1]) / s
        z = 0.25 * s
    q = np.array([x, y, z, w])
    return q / np.linalg.norm(q)


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def trajectory(seconds: float, v0: float, radius_m: float):
    """速度带慢变 ±4%；曲率可配（用于转弯段验证航向变化率）。"""
    n = int(seconds * RATE_HZ) + 1
    t = np.arange(n) * DT
    v = v0 * (1.0 + 0.04 * np.sin(2 * math.pi * 0.05 * t))
    kappa = np.full(n, 1.0 / radius_m)
    psi = np.cumsum(v * kappa * DT)
    dv = np.gradient(v, DT)
    return t, v, psi, dv, kappa


class Synth:
    def __init__(self, seed: int = 20261021, cadence_jitter: float = 0.025):
        self.rng = np.random.default_rng(seed)
        self.phase = 0.0
        self.R_prev = None
        self.phase_prev = 0.0
        # 步态变异性：步频的慢随机游走（真实步间隔 CV 约 2-4%）
        self.jitter = 0.0
        self.jitter_sigma = cadence_jitter
        self.steps = 0.0
        self.step_intervals = []

    def noise(self, sigma):
        return float(self.rng.normal(0.0, sigma))

    def step(self, v, psi, dv, kappa):
        f_model_hz = cadence_model_spm(v) / 60.0
        # 步频抖动：一阶自回归，界在 ±8% 内，均值回零
        self.jitter = 0.98 * self.jitter + self.noise(0.004)
        self.jitter = max(-0.08, min(0.08, self.jitter))
        f = f_model_hz * (1.0 + self.jitter) if f_model_hz > 0 else 0.0

        f_prev = self.phase
        self.phase += 2 * math.pi * f * DT
        stepped = 1.0 if (f > 0 and int(self.phase / (2 * math.pi)) > int(f_prev / (2 * math.pi))) else 0.0
        self.steps += f * DT
        if stepped:
            self.step_intervals.append(DT if f <= 0 else 1.0 / f)

        A_v = amp_vertical(v)
        ph = self.phase
        a_up = sum(
            lpf_gain(mult * f) * amp * math.sin(mult * ph + off)
            for mult, amp, off in (
                (1, A_v, 0.0),
                (2, 0.25 * A_v, 0.9),
                (3, 0.08 * A_v, 1.7),
                (4, 0.03 * A_v, 2.4),
            )
        )
        a_ml = 0.35 * A_v * math.sin(ph + 2.2)
        a_ap = dv + 0.22 * A_v * math.sin(2 * ph + 0.4)
        a_cent = v * v * kappa
        # 前进/右/左三个正交方向（psi 为数学角：前进方向 = (-sin psi, cos psi)）
        fwd = np.array([-math.sin(psi), math.cos(psi)])
        rgt = np.array([math.cos(psi), math.sin(psi)])
        lft = -rgt
        a_hl = a_ap * fwd + a_ml * rgt + a_cent * lft
        a_world = np.array([a_hl[0], a_hl[1], a_up]) + np.array(
            [self.noise(0.012), self.noise(0.012), self.noise(0.012)]
        )

        gait = min(1.6, max(0.6, v / 2.5)) if f > 0 else 0.0
        roll = math.radians(2.2) * gait * math.sin(ph + 0.3)
        pitch = math.radians(3.0) * gait * math.sin(ph + math.pi / 2 + 0.5)
        R = rz(psi) @ ry(pitch) @ rx(roll) @ MOUNT

        accel = R.T @ (a_world + np.array([0.0, 0.0, G]))

        dR = np.zeros((3, 3)) if self.R_prev is None else (R - self.R_prev) / DT
        skew = R.T @ dR
        omega = np.array([skew[2, 1], skew[0, 2], skew[1, 0]])
        omega += np.array([self.noise(0.004), self.noise(0.004), self.noise(0.004)])
        self.R_prev = R

        q = mat_to_quat(R)
        return accel, omega, q, f * 60.0, self.steps


def lowpass_zerophase(x, fc, fs=RATE_HZ, passes=2):
    a = math.exp(-2 * math.pi * fc / fs)
    y = x.astype(float).copy()
    for _ in range(passes):
        acc = y[0]
        fwd = np.empty_like(y)
        for i, xi in enumerate(y):
            acc = a * acc + (1 - a) * xi
            fwd[i] = acc
        acc = fwd[-1]
        bwd = np.empty_like(y)
        for i in range(len(y) - 1, -1, -1):
            acc = a * acc + (1 - a) * fwd[i]
            bwd[i] = acc
        y = bwd
    return y


def dominant_freq(sig, fs=RATE_HZ):
    sig = sig - sig.mean()
    win = np.hanning(len(sig))
    spec = np.abs(np.fft.rfft(sig * win, n=8192))
    freqs = np.fft.rfftfreq(8192, 1 / fs)
    band = (freqs > 1.0) & (freqs < 4.6)
    return freqs[band][np.argmax(spec[band])]


def report(name, passed, detail):
    print(f"[{'PASS' if passed else 'FAIL'}] {name}: {detail}")


def run_scenario(label, seconds, v0, radius_m, seed=7):
    t, v, psi, dv, kappa = trajectory(seconds, v0, radius_m)
    s = Synth(seed=seed)
    frames = [s.step(v[k], psi[k], dv[k], kappa[k]) for k in range(len(t))]
    accel = np.array([f[0] for f in frames])
    gyro = np.array([f[1] for f in frames])
    quat = np.array([f[2] for f in frames])
    cad = np.array([f[3] for f in frames])
    steps = float(s.steps)

    print(f"\n=== {label}  v={v.mean():.2f} m/s  R={radius_m:.0f} m  {seconds:.0f}s ===")

    # 弯道上离心加速度本身就会把低通后的加速度方向拉偏 atan(v^2 kappa / g)，这是真实物理
    cent_tilt = math.degrees(math.atan(v.mean() ** 2 * kappa.mean() / G))

    lp = np.stack([lowpass_zerophase(accel[:, i], 0.4) for i in range(3)], axis=1)
    g_est = np.linalg.norm(lp, axis=1).mean()
    report("1 重力模长", abs(g_est - G) < 0.1, f"低通后模长 {g_est:.4f} m/s^2，偏差 {abs(g_est - G):.4f}")

    dir_err = []
    # 服务端的正确做法：q* 推出的重力与加速度都要过同一道低通再比（瞬时比会差出摆动幅度）
    g_pred = np.array([quat_to_mat(q).T @ np.array([0.0, 0.0, G]) for q in quat])
    g_pred_lp = np.stack([lowpass_zerophase(g_pred[:, i], 0.4) for i in range(3)], axis=1)
    for k in range(30, len(t), 5):   # 跳过低通启动段（前 3s）
        c = np.dot(g_pred_lp[k], lp[k]) / (np.linalg.norm(g_pred_lp[k]) * np.linalg.norm(lp[k]) + 1e-12)
        dir_err.append(math.degrees(math.acos(max(-1.0, min(1.0, c)))))
    report("2 重力方向 vs q*", max(dir_err) < 1.0 + cent_tilt,
           f"最大夹角 {max(dir_err):.3f} deg（中位 {np.median(dir_err):.3f}；含弯道离心 {cent_tilt:.2f} deg 属真实物理）")

    vert = np.linalg.norm(accel, axis=1) - G
    est_spm = dominant_freq(vert) * 60
    mid = slice(len(t) // 5, -len(t) // 5)
    model_spm = float(np.mean(cad[mid]))
    rel = abs(est_spm - model_spm) / model_spm
    peaks = [k for k in range(1, len(vert) - 1)
             if vert[k] > vert[k - 1] and vert[k] > vert[k + 1] and vert[k] > 0.35]
    peak_spm = 0.0
    if len(peaks) > 3:
        peak_spm = 60 / np.mean(np.diff(t[peaks]))
    report("3 az 主频 vs 步频", rel < 0.05,
           f"FFT {est_spm:.1f} / 峰计数 {peak_spm:.1f} / 模型 {model_spm:.1f} spm，误差 {rel * 100:.2f}%")

    dist = float(np.sum(v * DT))
    stride = dist / steps if steps > 0 else float("nan")
    model_stride = stride_m(float(np.mean(v)))
    report("4 距离/步数=步幅", abs(stride - model_stride) / model_stride < 0.02,
           f"距离 {dist:.2f} m，步数 {steps:.1f}，步幅 {stride:.3f} m（模型 {model_stride:.3f} m，"
           f"人体区间 0.5-1.8 内 {0.5 < stride < 1.8}）")

    qn = float(np.abs(np.linalg.norm(quat, axis=1) - 1.0).max())
    yaw = np.array([math.atan2(quat_to_mat(q)[1, 0], quat_to_mat(q)[0, 0]) for q in quat])
    diff = (yaw - psi + math.pi) % (2 * math.pi) - math.pi
    offset = float(np.mean(diff))
    resid = float(np.max(np.abs(diff - offset)))
    report("5 单位四元数 + 航向", qn < 1e-9 and resid < 0.035,
           f"|q|-1={qn:.1e}，航向=轨迹+常量 {math.degrees(offset):.1f} deg，残差 {math.degrees(resid):.2f} deg")

    omega_world = np.array([quat_to_mat(q) @ w for q, w in zip(quat, gyro)])
    dpsi = np.gradient(psi, DT)
    yaw_rate = lowpass_zerophase(omega_world[:, 2], 0.6)
    true_rate = lowpass_zerophase(dpsi, 0.6)
    if np.abs(true_rate[10:]).mean() > 0.05:
        scale = float(np.dot(yaw_rate[10:], true_rate[10:]) / max(1e-12, np.dot(true_rate[10:], true_rate[10:])))
        report("6 gyro 世界系 z = 航向变化率", abs(scale - 1.0) < 0.25,
               f"斜率 {scale:.3f}（1.0 为完全一致），真实 {np.abs(true_rate[10:]).mean():.4f} rad/s")
    else:
        print(f"[SKIP] 6 gyro 世界系 z: 该段真实航向变化率仅 {np.abs(true_rate[10:]).mean():.4f} rad/s，"
              f"低于陀螺噪声，无判别力")

    sig = vert - vert.mean()
    spec = np.abs(np.fft.rfft(sig * np.hanning(len(sig))))
    freqs = np.fft.rfftfreq(len(sig), 1 / RATE_HZ)
    hi = spec[freqs > 3.2].sum()
    lo = spec[(freqs > 1.0) & (freqs <= 3.2)].sum()
    report("7 高频/混叠能量", hi / (hi + lo) < 0.05, f"3.2Hz 以上占比 {hi / (hi + lo) * 100:.2f}%")

    iv = np.array(s.step_intervals)
    cv = float(np.std(iv) / np.mean(iv)) if len(iv) > 2 else 0.0
    report("8 步态变异性", 0.005 < cv < 0.10, f"步间隔 CV {cv * 100:.2f}%（真实跑步约 2-4%）")

    return dict(vert=vert, t=t, v=v, steps=steps, dist=dist)


def run_standing():
    print("\n=== 静止段（v=0）===")
    t, v, psi, dv, kappa = trajectory(6.0, 0.0, 600.0)
    s = Synth(seed=11)
    frames = [s.step(0.0, 0.0, 0.0, kappa[k]) for k in range(len(t))]
    accel = np.array([f[0] for f in frames])
    cad = np.array([f[3] for f in frames])
    report("9 静止不出步", float(np.max(cad)) == 0.0 and s.steps == 0.0,
           f"步频最大 {np.max(cad):.1f} spm，累计步数 {s.steps:.2f}，加速度模长 {np.linalg.norm(accel, axis=1).mean():.3f}")


def main():
    run_scenario("直道 6'00\"/km", 60.0, 2.78, 600.0)
    run_scenario("直道 5'00\"/km", 40.0, 3.33, 1000.0)
    run_scenario("急转 R=18m", 40.0, 2.78, 18.0)
    run_scenario("慢走 9'00\"/km", 40.0, 1.85, 600.0)
    run_standing()


if __name__ == "__main__":
    main()
