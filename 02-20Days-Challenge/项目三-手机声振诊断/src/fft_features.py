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

加速度 CSV 按列名匹配 ax/ay/az，兼容多种布局：无表头时前 3 列为
ax,ay,az；含表头时按列名定位（如实验方案「含时间戳」``t,ax,ay,az``，或
采集 App 实际导出的 ``ax,ay,az,t_ns,battery_pct,temp_c``，多出的时间戳/
电量/温度列会被自动忽略）。运行 `python selftest.py` 可用合成样本一键
验证音频+加速度特征提取管线。
"""

import argparse
import csv
import glob
import os
import re
from typing import Dict, List, Optional

import numpy as np

RAW = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "raw_data"))
SR_AUDIO = 16000  # 音频采样率 Hz。
SR_ACCEL = 100.0  # 加速度计采样率 Hz。

# 12 维音频/加速度特征（顺序固定，train 与 predict 共用，并与 blindtest.py 对齐）。
FEATURES = [
    "audio_rms", "audio_centroid", "audio_dom_freq", "audio_rolloff", "audio_low_ratio",
    "acc_x_rms", "acc_y_rms", "acc_z_rms", "acc_mag_rms",
    "acc_x_dom", "acc_y_dom", "acc_z_dom",
]

SHORT = {
    "工况1-电风扇正常低速": "风扇正常",
    "工况2-电风扇进风遮挡": "风扇遮挡",
    "工况3-电风扇高速": "风扇高速",
    "工况4-吹风机冷风": "吹风冷风",
    "工况5-吹风机热风": "吹风热风",
}

MODEL_PATH = os.path.join(os.path.dirname(__file__), "rf_model.joblib")


def cond_of(path: str) -> str:
    """路径首段即工况目录名（兼容 / 与 \\）。"""
    return re.split(r"[\\/]", path)[0]


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
        csv_path: CSV 文件路径。按列名匹配 ax/ay/az，兼容多种布局：
            无表头时前 3 列为 ax,ay,az；含表头时按列名定位（如实验方案
            ``t,ax,ay,az``，或采集 App 实际导出 ``ax,ay,az,t_ns,
            battery_pct,temp_c``，多出的时间戳/电量/温度列自动忽略）。

    Returns:
        特征名字典（键如 acc_x_rms/acc_mag_rms）；读取失败或数据为空时
        返回 None。
    """
    try:
        with open(csv_path, "r", encoding="utf-8") as fh:
            rows = list(csv.reader(fh))
    except Exception as e:  # noqa: BLE001
        print(f"  [skip] 读加速度失败 {os.path.basename(csv_path)}: {e}")
        return None
    if not rows:
        return None
    # 首行含非数字单元格 -> 视为表头。
    header = None
    start = 0
    try:
        float(rows[0][0])
    except (ValueError, IndexError):
        header = rows[0]
        start = 1
    if header is not None:
        low = [h.strip().lower() for h in header]
        cols: List[int] = []
        for name in ("ax", "ay", "az"):
            if name in low:
                cols.append(low.index(name))
        if len(cols) != 3:
            # 未匹配到轴名：含时间戳取 1,2,3，否则取前 3 列。
            cols = [1, 2, 3] if len(header) >= 4 else [0, 1, 2]
    else:
        cols = [0, 1, 2]
    data: List[List[float]] = []
    for r in rows[start:]:
        try:
            data.append([float(r[c]) for c in cols])
        except (ValueError, IndexError):
            continue
    if not data:
        return None
    data_arr = np.array(data, dtype=np.float32)
    if data_arr.ndim == 1:
        data_arr = data_arr.reshape(-1, 3)
    if data_arr.shape[1] != 3:
        return None
    feats: Dict[str, float] = {}
    for i, ax in enumerate("xyz"):
        col = data_arr[:, i] - np.mean(data_arr[:, i])
        feats[f"acc_{ax}_rms"] = float(np.sqrt(np.mean(col**2)))
        spec = np.abs(np.fft.rfft(col))
        f = np.fft.rfftfreq(len(col), 1.0 / SR_ACCEL)
        feats[f"acc_{ax}_dom"] = float(f[int(np.argmax(spec))]) if spec.sum() > 0 else 0.0
    mag = np.sqrt(data_arr[:, 0] ** 2 + data_arr[:, 1] ** 2 + data_arr[:, 2] ** 2)
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


def _make_rf(n_jobs=-1):
    """构造随机森林分类器（特征在外部做 z 标准化，与 blindtest.py 同口径）。"""
    from sklearn.ensemble import RandomForestClassifier
    return RandomForestClassifier(
        n_estimators=300, random_state=20261108,
        class_weight="balanced", n_jobs=n_jobs)


def _print_confusion(truths, preds, conds):
    """打印混淆矩阵（行=真实 / 列=预测）。"""
    print("        " + " ".join(f"{SHORT.get(c, c):>8}" for c in conds))
    for c in conds:
        cells = " ".join(
            f"{sum(1 for t, p in zip(truths, preds) if t == c and p == pc):>8}"
            for pc in conds)
        print(f"{SHORT.get(c, c):>8} " + cells)


def cmd_train() -> None:
    """用 features.csv 训练随机森林，输出分层盲测 + LOOCV 准确率、混淆矩阵，并保存模型。

    与 src/blindtest.py 保持同口径：排除 _spare、按工况分层抽样留 2 组（种子 20261108）、
    LOOCV 整体估计。特征在 train 内做 z 标准化（mu/sd 随模型保存），predict 直接喂原始特征即可。
    """
    feat = os.path.join(RAW, "features.csv")
    if not os.path.exists(feat):
        print("train 需要 features.csv。请先运行 `python fft_features.py extract`。")
        return
    rows = []  # (工况, {特征}, 路径)
    with open(feat, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            path = r["file"]
            if "_spare" in path:
                continue
            cond = cond_of(path)
            vals = {}
            ok = True
            for k in FEATURES:
                v = r.get(k, "")
                if v in ("", None):
                    ok = False
                    break
                try:
                    vals[k] = float(v)
                except ValueError:
                    ok = False
                    break
            if ok:
                rows.append((cond, vals, path))
    if not rows:
        print("train: features.csv 中无有效样本（需 12 维特征齐全且非 spare）。")
        return
    conds_list = [x[0] for x in rows]
    X = np.array([[x[1][k] for k in FEATURES] for x in rows], dtype=float)
    conds = sorted(set(conds_list))
    n = len(rows)
    mu, sd = X.mean(axis=0), X.std(axis=0) + 1e-9
    Xz = (X - mu) / sd
    print(f"train: 载入 {n} 组主值（已排除 _spare），工况：{[SHORT.get(c, c) for c in conds]}")

    def fit_pred(train_idx, test_idx):
        clf = _make_rf()
        clf.fit(Xz[train_idx], [conds_list[i] for i in train_idx])
        return clf.predict(Xz[test_idx])

    # ---- 分层盲测（每工况留 2 组，种子 20261108，与 blindtest 同口径）----
    rng = np.random.RandomState(20261108)
    holdout = set()
    for c in conds:
        idxs = [i for i in range(n) if conds_list[i] == c]
        holdout.update(rng.choice(idxs, size=2, replace=False).tolist())
    ho = sorted(holdout)
    preds_ho = fit_pred([i for i in range(n) if i not in holdout], ho)
    truths_ho = [conds_list[i] for i in ho]
    correct = sum(1 for t, p in zip(truths_ho, preds_ho) if t == p)
    print(f"\n分层盲测准确率：{correct}/{len(ho)} = {correct / len(ho):.3f}")
    print("  组        真实       预测       结果")
    for i, p in zip(ho, preds_ho):
        g = re.search(r"G(\d+)", rows[i][2])
        tag = g.group(0) if g else "?"
        print(f"  {SHORT.get(conds_list[i], conds_list[i]):8s} {tag:4s} -> "
              f"{SHORT.get(p, p):8s} {'✓' if p == conds_list[i] else '✗'}")
    print("  盲测混淆矩阵（行=真实 / 列=预测）：")
    _print_confusion(truths_ho, list(preds_ho), conds)

    # ---- LOOCV 整体鲁棒性 ----
    preds_loo = [fit_pred([j for j in range(n) if j != i], [i])[0] for i in range(n)]
    acc_loo = sum(1 for i in range(n) if preds_loo[i] == conds_list[i]) / n
    print(f"\nLOOCV 准确率：{acc_loo:.3f}")
    print("  LOOCV 混淆矩阵（行=真实 / 列=预测）：")
    _print_confusion(conds_list, preds_loo, conds)

    # ---- 抗噪鲁棒性（噪声注入仿真，与 blindtest 同口径：在 z 标准化特征上注入）----
    print(f"\n=== 抗噪鲁棒性扫描（噪声注入仿真，6 次实现平均）===")
    print("SNR(dB)   准确率   下降")
    base = None
    for snr in [20, 10, 5, 0, -5]:
        sigma = 10 ** (-snr / 20.0)
        r2 = np.random.RandomState(99 + snr)  # 每次 SNR 固定种子，内层逐次推进取不同噪声实现
        tot = 0
        for _ in range(6):
            Xn = Xz + r2.normal(0.0, sigma, size=Xz.shape)
            c = 0
            for i in range(n):
                tr = [j for j in range(n) if j != i]
                clf = _make_rf(n_jobs=1)
                clf.fit(Xn[tr], [conds_list[j] for j in tr])
                c += int(clf.predict(Xn[i].reshape(1, -1))[0] == conds_list[i])
            tot += c
        acc = tot / (6 * n)
        if base is None:
            base = acc
        print(f"{snr:7.0f}  {acc:7.3f}  {base - acc:+.3f}")

    # ---- 保存全量模型（供 predict 推理）----
    import joblib
    clf_full = _make_rf()
    clf_full.fit(Xz, conds_list)
    joblib.dump({"mu": mu, "sd": sd, "rf": clf_full}, MODEL_PATH)
    print(f"\n模型已保存 -> {MODEL_PATH}（predict 子命令直接喂原始特征，内部做 z 标准化）")


def cmd_predict(target: Optional[str] = None) -> None:
    """载入训练好的模型，对给定 WAV/CSV 预测工况。

    用法：python fft_features.py predict <path>
    path 为某个样本的 .wav 或 .csv；若同目录存在同名配对文件则一并提取特征。
    """
    if not target or not os.path.exists(target):
        print("predict 需要提供一个存在的 .wav 或 .csv 路径作为参数。")
        return
    if not os.path.exists(MODEL_PATH):
        print(f"未找到模型 {MODEL_PATH}，请先运行 `python fft_features.py train`。")
        return
    import joblib
    bundle = joblib.load(MODEL_PATH)
    mu, sd, clf = bundle["mu"], bundle["sd"], bundle["rf"]
    feats: Dict[str, float] = {}
    low = target.lower()
    if low.endswith(".wav"):
        a = audio_features(target)
        if a:
            feats.update(a)
        sib = os.path.splitext(target)[0] + ".csv"
        if os.path.exists(sib):
            b = accel_features(sib)
            if b:
                feats.update(b)
    elif low.endswith(".csv"):
        b = accel_features(target)
        if b:
            feats.update(b)
        sib = os.path.splitext(target)[0] + ".wav"
        if os.path.exists(sib):
            a = audio_features(sib)
            if a:
                feats.update(a)
    else:
        print("predict 仅支持 .wav 或 .csv 文件。")
        return
    missing = [k for k in FEATURES if k not in feats]
    if missing:
        print(f"该样本缺少特征（缺失 {missing}），无法预测。需音频+加速度成对文件。")
        return
    x = np.array([[feats[k] for k in FEATURES]], dtype=float)
    xz = (x - mu) / sd
    pred = clf.predict(xz)[0]
    proba = clf.predict_proba(xz)[0]
    classes = list(clf.classes_)
    conf = float(proba[classes.index(pred)])
    print(f"预测工况：{SHORT.get(pred, pred)}（置信度 {conf:.3f}）")
    print("  各工况概率：")
    for c, p in sorted(zip(classes, proba), key=lambda t: -t[1]):
        print(f"    {SHORT.get(c, c):8s} {p:.3f}")


def main() -> None:
    """解析命令行参数并分发到 extract/train/predict 子命令。"""
    ap = argparse.ArgumentParser(description="项目三 FFT 特征提取")
    ap.add_argument("cmd", choices=["extract", "train", "predict"])
    ap.add_argument("target", nargs="?", default=None,
                    help="predict 子命令需要的 WAV/CSV 路径")
    args = ap.parse_args()
    if args.cmd == "extract":
        cmd_extract()
    elif args.cmd == "train":
        cmd_train()
    else:
        cmd_predict(args.target)


if __name__ == "__main__":
    main()
