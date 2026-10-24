"""项目三 · 手机声振诊断 —— FFT 时域/频域特征提取与基线分类.

从采集 App 导出的 WAV（麦克风 16kHz）与 CSV（加速度计 ~100Hz）中提取
时域/频域特征，汇总为 features.csv，供后续训练工况分类器。

依赖：numpy scipy soundfile pandas scikit-learn（见 requirements.txt）。

用法：
  python fft_features.py extract   # 扫描 raw_data 下所有 WAV/CSV，输出 features.csv
  python fft_features.py train     # 用 features.csv + 采集记录索引.csv 训练基线分类器

说明：当前仓库暂无原始数据，extract 在 raw_data 下找不到文件时会给出
指引，不会报错。采集 App 确定后（麦克风录音 + 加速度计记录），把同名
.wav/.csv 成对放入 raw_data/工况*/ 即可直接跑。
"""

import argparse
import csv
import glob
import os
from typing import Dict, List, Optional

import numpy as np

RAW = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "raw_data"))
SR_AUDIO = 16000  # 音频采样率 Hz。
SR_ACCEL = 100.0  # 加速度计采样率 Hz。


def audio_features(wav_path: str) -> Optional[Dict[str, float]]:
    """提取单个 WAV 的音频时域/频域特征。

    Args:
        wav_path: WAV 文件路径（16kHz/16bit/单声道，由采集 App 生成）。

    Returns:
        特征名字典（键如 audio_rms/audio_centroid）；读取失败或信号为
        空时返回 None。
    """
    try:
        # scipy 仅在 extract 音频时才需要：懒加载以保证无 scipy 环境也能
        # 跑加速度特征与训练流程。
        from scipy.io import wavfile

        sr, sig = wavfile.read(wav_path)
    except Exception as e:
        print(f"  [skip] 读音频失败 {os.path.basename(wav_path)}: {e}")
        return None
    if sig.ndim > 1:
        sig = sig[:, 0]
    sig = sig.astype(np.float32)
    if sig.size == 0:
        return None
    if sig.max() > 1.0:  # 16-bit PCM -> 归一化。
        sig = sig / 32768.0
    # 时域。
    rms = float(np.sqrt(np.mean(sig**2)))
    # 频域。
    n = len(sig)
    win = np.hanning(n)
    spectrum = np.abs(np.fft.rfft(sig * win))
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    power = spectrum**2
    total = power.sum() + 1e-12
    centroid = float((freqs * power).sum() / total)
    dom_freq = float(freqs[int(np.argmax(spectrum))])
    cum = np.cumsum(power)
    rolloff = float(freqs[min(np.searchsorted(cum, 0.85 * total), len(freqs) - 1)])
    low_band = float(power[freqs < 500].sum() / total)  # 低频能量占比。
    return {
        "audio_rms": rms,
        "audio_centroid": centroid,
        "audio_dom_freq": dom_freq,
        "audio_rolloff": rolloff,
        "audio_low_ratio": low_band,
    }


def accel_features(csv_path: str) -> Optional[Dict[str, float]]:
    """提取单个加速度 CSV 的三轴时域/频域特征。

    Args:
        csv_path: CSV 文件路径（前 3 列 ax,ay,az，由采集 App 生成）。

    Returns:
        特征名字典（键如 acc_x_rms/acc_mag_rms）；读取失败或数据为空时
        返回 None。
    """
    try:
        # 兼容带/不带表头：自动检测。
        data = np.loadtxt(csv_path, delimiter=",", usecols=(0, 1, 2))
    except Exception as e:
        print(f"  [skip] 读加速度失败 {os.path.basename(csv_path)}: {e}")
        return None
    if data.size == 0:
        return None
    if data.ndim == 1:
        data = data.reshape(-1, 3)
    feats: Dict[str, float] = {}
    for i, ax in enumerate("xyz"):
        col = data[:, i] - np.mean(data[:, i])
        feats[f"acc_{ax}_rms"] = float(np.sqrt(np.mean(col**2)))
        spec = np.abs(np.fft.rfft(col))
        f = np.fft.rfftfreq(len(col), 1.0 / SR_ACCEL)
        feats[f"acc_{ax}_dom"] = float(f[int(np.argmax(spec))]) if spec.sum() > 0 else 0.0
    mag = np.sqrt(data[:, 0] ** 2 + data[:, 1] ** 2 + data[:, 2] ** 2)
    feats["acc_mag_rms"] = float(np.sqrt(np.mean(mag**2)))
    return feats


def cmd_extract() -> None:
    """扫描 raw_data 下所有 WAV/CSV，输出 features.csv。"""
    rows: List[Dict[str, object]] = []
    pattern = os.path.join(RAW, "**", "*.wav")
    for wav in sorted(glob.glob(pattern, recursive=True)):
        base = os.path.splitext(wav)[0]
        csvp = base + ".csv"
        rec: Dict[str, object] = {"file": os.path.relpath(wav, RAW)}
        af = audio_features(wav)
        if af:
            rec.update(af)
        if os.path.exists(csvp):
            bf = accel_features(csvp)
            if bf:
                rec.update(bf)
        rows.append(rec)
    if not rows:
        print("未找到原始数据。请先把同名 .wav/.csv 成对放入 raw_data/工况*/ 下，再运行 extract。")
        return
    keys = sorted({k for r in rows for k in r})
    out = os.path.join(RAW, "features.csv")
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print(f"已写出 {len(rows)} 条特征 -> {out}")


def cmd_train() -> None:
    """用 features.csv + 采集记录索引.csv 训练基线分类器。

    TODO: 读取两表，按 file 关联标签，训练随机森林，输出交叉验证/
    盲测准确率。此段在拿到真实数据后补全
    （sklearn.ensemble.RandomForestClassifier）。
    """
    idx = os.path.join(RAW, "采集记录索引.csv")
    feat = os.path.join(RAW, "features.csv")
    if not (os.path.exists(idx) and os.path.exists(feat)):
        print("train 需要 features.csv 与 采集记录索引.csv（含工况标签）。请先 extract 并补全索引。")
        return
    print("train: 等待真实数据 + 采集记录索引.csv 提供工况标签后，由 AI 辅助补全分类与盲测逻辑。")


def main() -> None:
    """解析命令行参数并分发到 extract/train 子命令。"""
    ap = argparse.ArgumentParser(description="项目三 FFT 特征提取")
    ap.add_argument("cmd", choices=["extract", "train"])
    args = ap.parse_args()
    (cmd_extract if args.cmd == "extract" else cmd_train)()


if __name__ == "__main__":
    main()
