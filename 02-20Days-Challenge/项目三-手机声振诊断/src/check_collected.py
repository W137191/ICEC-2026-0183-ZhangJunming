"""项目三 · 采集进度校验（零依赖，仅标准库）。

扫描 raw_data/工况*/ 下的 WAV 与 CSV，按文件名（去扩展名）配对，输出：
  - 每个工况已采集「WAV+CSV 成对」组数 / 15
  - 全局进度 X / 75
  - 不匹配项（只有 WAV 缺 CSV，或只有 CSV 缺 WAV）
  - 命名/目录整洁提示（忽略 features.csv 与 _selftest）

用法：
  python src/check_collected.py
得分项①要求 5 工况 × 15 组 = 75 组，每组必须 WAV 与 CSV 同名成对。
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.abspath(os.path.join(HERE, "..", "raw_data"))

WORKLOADS = [
    "工况1-电风扇正常低速",
    "工况2-电风扇进风遮挡",
    "工况3-电风扇高速",
    "工况4-吹风机冷风",
    "工况5-吹风机热风",
]

TARGET_PER_WL = 15


def _list_stems(directory: str, ext: str) -> set:
    """返回目录下指定扩展名文件的「去扩展名」集合（忽略 features.csv / _selftest）。"""
    out = set()
    if not os.path.isdir(directory):
        return out
    for name in os.listdir(directory):
        if name.lower().endswith(ext):
            if name.lower() == "features.csv":
                continue
            if name.startswith("_"):
                continue
            out.add(os.path.splitext(name)[0])
    return out


def main() -> int:
    """扫描并报告采集进度，返回是否存在不匹配项。"""
    total = 0
    has_issue = False
    print("=" * 64)
    print("项目三 · 采集进度校验（要求 5×15 = 75 组，每组 WAV+CSV 成对）")
    print("=" * 64)
    for wl in WORKLOADS:
        d = os.path.join(RAW, wl)
        wavs = _list_stems(d, ".wav")
        csvs = _list_stems(d, ".csv")
        pairs = wavs & csvs
        wav_only = sorted(wavs - csvs)
        csv_only = sorted(csvs - wavs)
        n = len(pairs)
        total += n
        if n == TARGET_PER_WL and not wav_only and not csv_only:
            flag = "OK"
        elif wav_only or csv_only:
            flag = "!!"  # 存在缺配对的文件
        else:
            flag = "~ "  # 未采满但当前已采的均成对
        print(f"[{flag}] {wl}: {n}/{TARGET_PER_WL}")
        if wav_only:
            has_issue = True
            print(f"      仅 WAV 缺 CSV: {wav_only}")
        if csv_only:
            has_issue = True
            print(f"      仅 CSV 缺 WAV: {csv_only}")
    print("-" * 64)
    print(f"全局进度: {total}/75")
    if total == 75 and not has_issue:
        print("✅ 75 组已全部成对采集完成，可进入 extract 与盲测环节。")
    else:
        remain = 75 - total
        print(f"⏳ 还差 {remain} 组；存在不匹配请先补齐同名 WAV/CSV 再提交。")
    print("=" * 64)
    return 1 if has_issue else 0


if __name__ == "__main__":
    sys.exit(main())
