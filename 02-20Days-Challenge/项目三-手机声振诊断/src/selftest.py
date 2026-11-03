"""项目三 · fft_features.py 自测（可重复运行，完全隔离、不污染真实采集目录）。

自测流程：
  1. 在系统临时目录下生成一对合成样本（3 列 / 4 列含时间戳两种加速度 CSV
     布局，音频均为 100Hz 基频 + 谐波）。
  2. 将 fft_features 的 RAW 指向该临时目录（隔离真实 raw_data），就地调用
     ``cmd_extract()`` 跑真实特征管线。
  3. 读取临时目录内的 features.csv，断言关键特征符合注入信号
     （audio_dom_freq≈100、acc_x_dom≈30）。
  4. 清理临时目录，真实 raw_data 与 features.csv 不受影响。

用法：
  python selftest.py
依赖：numpy scipy（同 requirements.txt）。
"""

import csv
import math
import os
import shutil
import struct
import sys
import tempfile
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
SR_AUDIO = 16000
SR_ACCEL = 100
DUR = 6  # 秒，自测够用。


def _audio_wav(path: str) -> None:
    """写一段 16kHz/16bit/单声道 WAV：100Hz 基频 + 200/300Hz 谐波 + 噪声。"""
    n = SR_AUDIO * DUR
    buf = bytearray()
    for i in range(n):
        t = i / SR_AUDIO
        v = (
            0.6 * math.sin(2 * math.pi * 100 * t)
            + 0.3 * math.sin(2 * math.pi * 200 * t)
            + 0.15 * math.sin(2 * math.pi * 300 * t)
            + 0.05 * math.sin(2 * math.pi * 1234.5 * t)
        )
        v = max(-1.0, min(1.0, v))
        buf += struct.pack("<h", int(v * 32767))
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR_AUDIO)
        w.writeframes(bytes(buf))


def _accel_csv(path: str, with_time: bool) -> None:
    """写加速度 CSV；with_time=True 时首列为时间戳（t,ax,ay,az）。"""
    n = SR_ACCEL * DUR
    seed = 20261102

    def rnd() -> float:
        nonlocal seed
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        return seed / 0x7FFFFFFF - 0.5

    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["t", "ax", "ay", "az"] if with_time else ["ax", "ay", "az"])
        for i in range(n):
            t = i / SR_ACCEL
            ax = 0.8 * math.sin(2 * math.pi * 30 * t) + 0.1 * rnd()
            ay = 0.2 * math.sin(2 * math.pi * 12 * t) + 0.1 * rnd()
            az = 0.15 * math.sin(2 * math.pi * 8 * t) + 0.1 * rnd()
            if with_time:
                w.writerow([f"{t:.3f}", f"{ax:.4f}", f"{ay:.4f}", f"{az:.4f}"])
            else:
                w.writerow([f"{ax:.4f}", f"{ay:.4f}", f"{az:.4f}"])


def main() -> int:
    """生成隔离样本、跑 extract、断言特征，返回进程退出码。"""
    import fft_features

    tmp = tempfile.mkdtemp(prefix="p3_selftest_")
    try:
        specs = [
            ("风扇_正常_01_20261102-1200", False),
            ("风扇_遮挡_02_20261102-1210", True),
        ]
        for name, with_time in specs:
            base = os.path.join(tmp, name)
            _audio_wav(base + ".wav")
            _accel_csv(base + ".csv", with_time)

        # 隔离：将 fft_features 的 RAW 指向临时目录，避免扫描到真实采集数据。
        fft_features.RAW = tmp
        fft_features.cmd_extract()

        feat = os.path.join(tmp, "features.csv")
        if not os.path.exists(feat):
            print("[FAIL] 未生成 features.csv")
            return 1
        with open(feat, "r", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        if len(rows) != 2:
            print(f"[FAIL] 期望 2 条特征，实际 {len(rows)} 条")
            return 1

        ok = True
        for r in rows:
            try:
                dom = float(r["audio_dom_freq"])
                axd = float(r["acc_x_dom"])
                ayd = float(r["acc_y_dom"])
                azd = float(r["acc_z_dom"])
            except (KeyError, ValueError, TypeError) as e:
                print(f"[FAIL] 特征缺失/解析失败: {e}")
                ok = False
                break
            # 音频主频应接近注入的 100Hz；加速度主频应接近 30/12/8Hz。
            if not (90 <= dom <= 110):
                print(f"[FAIL] audio_dom_freq={dom:.1f} 不在 [90,110]")
                ok = False
                break
            if not (25 <= axd <= 35 and 8 <= ayd <= 16 and 5 <= azd <= 12):
                print(f"[FAIL] 加速度主频异常 ax={axd} ay={ayd} az={azd}")
                ok = False
                break
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if ok:
        print("[PASS] fft_features.py 音频+加速度特征提取均正常（双布局 CSV，已隔离真实数据）。")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
