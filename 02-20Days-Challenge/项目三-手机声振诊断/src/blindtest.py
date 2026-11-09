"""项目三 · 盲测准确率 + 抗噪分析（零依赖，仅用 numpy）.

从 features.csv 关联工况标签，对 75 组主值做：
  1) 分层抽样盲测（每工况留 2 组 = 10 组盲测集，种子固定可复现）；
  2) 留一交叉验证（LOOCV）作为整体鲁棒性估计；
  3) 各工况音频/加速度特征均值±标准差，用于抗噪与易混淆分析。

所有数值均来自真实采集特征，不编造。用法：
  python blindtest.py
"""

import csv
import os
import re
from collections import defaultdict

import numpy as np

RAW = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "raw_data"))
FEAT_CSV = os.path.join(RAW, "features.csv")

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


def cond_of(path: str) -> str:
    """路径首段即工况目录名（兼容 / 与 \\）。"""
    return re.split(r"[\\/]", path)[0]


def load() -> list:
    rows = []
    with open(FEAT_CSV, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            path = row["file"]
            if "_spare" in path:  # 排除备档，仅用 75 主值
                continue
            vals = {}
            ok = True
            for k in FEATURES:
                v = row.get(k, "")
                if v in ("", None):
                    ok = False
                    break
                try:
                    vals[k] = float(v)
                except ValueError:
                    ok = False
                    break
            if ok:
                rows.append((cond_of(path), path, vals))
    return rows


def centroids(train_idx, Xz, conds_list):
    cents = defaultdict(list)
    for ci in train_idx:
        cents[conds_list[ci]].append(Xz[ci])
    return {c: np.mean(v, axis=0) for c, v in cents.items()}


def predict(x, cents):
    d = {c: np.linalg.norm(x - cent) for c, cent in cents.items()}
    return min(d, key=d.get)


def antinoise_sweep(conds_list, Xz, conds, snr_db_list, n_realiz=30, seed=20261108):
    """在真实特征（已 z 标准化）上注入受控高斯噪声，做 LOOCV 鲁棒性扫描。

    信号各维 std=1，注入噪声 N(0, σ)，SNR(dB) = -10·log10(σ²)。
    每个 SNR 用 n_realiz 次独立噪声实现取平均，避免因单次随机波动失真。
    这是「噪声注入仿真」而非真实场测噪声数据，报告须如实标注。
    """
    rng = np.random.RandomState(seed)
    print(f"\n=== 抗噪鲁棒性扫描（噪声注入仿真，{n_realiz} 次实现平均）===")
    print("SNR(dB)   准确率   下降")
    base = None
    for snr in snr_db_list:
        sigma = 10 ** (-snr / 20.0)
        tot = 0
        for _ in range(n_realiz):
            noise = rng.normal(0.0, sigma, size=Xz.shape)
            Xn = Xz + noise
            c = 0
            for i in range(len(conds_list)):
                train = [j for j in range(len(conds_list)) if j != i]
                cents_i = centroids(train, Xn, conds_list)
                c += int(predict(Xn[i], cents_i) == conds_list[i])
            tot += c
        acc = tot / (n_realiz * len(conds_list))
        if base is None:
            base = acc
        drop = base - acc
        print(f"{snr:7.0f}  {acc:7.3f}  {drop:+.3f}")


def main() -> None:
    rows = load()
    print(f"已载入 {len(rows)} 组主值（已排除 _spare）")
    conds_list = [r[0] for r in rows]
    paths_list = [r[1] for r in rows]
    conds = sorted(set(conds_list))
    print("工况：", [SHORT.get(c, c) for c in conds])

    X = np.array([[r[2][k] for k in FEATURES] for r in rows], dtype=float)
    mu, sd = X.mean(axis=0), X.std(axis=0) + 1e-9
    Xz = (X - mu) / sd

    # ---- 各工况特征均值±标准差 ----
    print("\n=== 各工况特征 均值±标准差 ===")
    for c in conds:
        arr = X[[i for i in range(len(rows)) if conds_list[i] == c]]
        print(f"[{SHORT.get(c, c)}] n={len(arr)}")
        for i, k in enumerate(FEATURES):
            print(f"  {k:16s} {arr[:, i].mean():9.4f} ± {arr[:, i].std():9.4f}")

    # ---- 分层抽样盲测（每工况留 2 组，种子固定）----
    rng = np.random.RandomState(20261108)
    holdout = set()
    for c in conds:
        idxs = [i for i in range(len(rows)) if conds_list[i] == c]
        holdout.update(rng.choice(idxs, size=2, replace=False).tolist())
    train_idx = [i for i in range(len(rows)) if i not in holdout]
    cents = centroids(train_idx, Xz, conds_list)
    hcm = defaultdict(lambda: defaultdict(int))
    htest = []
    correct = 0
    for ti in holdout:
        pred = predict(Xz[ti], cents)
        truth = conds_list[ti]
        hcm[truth][pred] += 1
        ok = pred == truth
        correct += int(ok)
        htest.append((paths_list[ti], SHORT.get(truth, truth), SHORT.get(pred, pred), ok))
    print(f"\n=== 盲测集（每工况留 2 组，共 {len(holdout)} 组）===")
    print(f"盲测准确率：{correct}/{len(holdout)} = {correct / len(holdout):.3f}")
    for p, t, pred, ok in sorted(htest, key=lambda x: x[1]):
        g = re.search(r"G(\d+)", p)
        print(f"  {t:8s} {g.group(0) if g else '?':4s} 真实={t:8s} 预测={pred:8s} {'✓' if ok else '✗'}")
    print("盲测混淆矩阵（行=真实 / 列=预测）：")
    print("        " + " ".join(f"{SHORT.get(c, c):>8}" for c in conds))
    for c in conds:
        print(f"{SHORT.get(c, c):>8} " + " ".join(f"{hcm[c][pc]:>8}" for pc in conds))

    # ---- LOOCV 整体鲁棒性 ----
    lcm = defaultdict(lambda: defaultdict(int))
    lc = 0
    for i in range(len(rows)):
        train = [j for j in range(len(rows)) if j != i]
        cents_i = centroids(train, Xz, conds_list)
        pred = predict(Xz[i], cents_i)
        truth = conds_list[i]
        lcm[truth][pred] += 1
        lc += int(pred == truth)
    print(f"\n=== 留一交叉验证（LOOCV，{len(rows)} 组）===")
    print(f"LOOCV 准确率：{lc}/{len(rows)} = {lc / len(rows):.3f}")
    print("LOOCV 混淆矩阵（行=真实 / 列=预测）：")
    print("        " + " ".join(f"{SHORT.get(c, c):>8}" for c in conds))
    for c in conds:
        print(f"{SHORT.get(c, c):>8} " + " ".join(f"{lcm[c][pc]:>8}" for pc in conds))

    # ---- 抗噪鲁棒性（噪声注入仿真）----
    antinoise_sweep(conds_list, Xz, conds, snr_db_list=[20, 10, 5, 0, -5])


if __name__ == "__main__":
    main()
