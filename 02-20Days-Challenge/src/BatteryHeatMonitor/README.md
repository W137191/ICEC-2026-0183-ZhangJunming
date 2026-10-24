# 发热掉电监测 · 安卓 App（APK）

> 配套课程课题 **02 个人手机多工况发热与掉电实测**。 在手机上自动读取**电量%**与**电池温度℃**，
> 按设定的**工况**与**采样间隔**定时记录， 一键导出与 `raw_data` 模板列头完全一致的 **CSV**，直接用于课题02报告。

## 功能

- 实时显示：电量 %、机身温度 ℃、充放电状态
- 4 个工况（与课题02一致）：`待机` / `短视频` / `导航拍摄` / `高帧率游戏`
- 后台**前台服务**采样：熄屏或切到其它 App（刷视频、打游戏）时仍持续记录
- 默认 **每 5 分钟**采样一条（间隔在 `MonitorService.EXTRA_INTERVAL_MS` 可调）
- 本地 JSON 持久化，App 内实时折线图（电量% + 温度℃双线）
- **导出 CSV**：`序号,工况,时间min,电量%,机身温度℃,备注`
- **温度预留接口**：默认读系统电池温度；读不到或想用第三方读数时，可在界面填入「温度兜底(℃)」手动覆盖

## 编译生成 APK

> 需要电脑装 **Android Studio**（免费，[developer.android.com/studio](https://developer.android.com/studio)）。

1. 打开 Android Studio → `File → Open` → 选中本目录 `BatteryHeatMonitor`（含
   `settings.gradle` 的那层）
2. 首次打开会自动下载 Gradle 与依赖（需联网），请等待 `Sync` 完成
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. 编译完成后，APK 在：
`app/build/outputs/apk/debug/app-debug.apk`
5. 把 `app-debug.apk` 拷到手机安装即可（安卓允许「未知来源」安装）

> 若 Android Studio 提示 Gradle / AGP 版本不兼容，点右上角提示让它「自动升级」即可， 本项目未使用任何冷门依赖，
> 正常可一次编译通过。

## 真机使用流程（对齐课题02）

1. 手机装好 App，首次打开按系统提示**允许通知权限**（用于后台保活通知）
2. 选工况（如「短视频」），点 **开始监测**
3. 正常使用该工况 30 分钟；App 每 5 分钟自动采一条（共约 6 条）
4. 每个工况结束后点 **停止监测**，再切到下一个工况重新开始
5. 全部工况采完后点 **导出CSV** → 用微信/邮件/U盘把 `battery_heat_samples.csv` 取出
6. 把 CSV 内容**复制进** `01-Field-Discovery/raw_data/课题02-手机发热/发热掉电原始记录.csv`
（或直接替换其数据为实测值），配合 `报告/课题02-手机发热掉电.md` 完成分析

## 温度接口说明（预留接口）

机身温度**原生安卓可直接读** `BatteryManager.EXTRA_TEMPERATURE`（单位 0.1℃，已 ÷10）。 代码已抽象为可插拔接口
`monitor/TemperatureProvider`：

- `SystemBatteryTempProvider`：默认，读系统电池温度
- `ManualTempProvider(valueC)`：手动兜底（界面临时填值 / 后续接第三方测温 App 读数）

若某些机型读不到温度，或你想接 Root / 红外 / 第三方 App 的真实读数， 只需新增一个实现 `TemperatureProvider` 的类，并在
`MonitorService.onStartCommand` 里替换 `provider` 即可， **采样与导出逻辑无需改动**。

## 目录结构

```
BatteryHeatMonitor/
├── settings.gradle / build.gradle / gradle.properties   # 工程配置
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/batteryheatmonitor/
        │   ├── MainActivity.kt            # 主界面：读数/选工况/启停/导出
        │   ├── MonitorService.kt          # 前台采样服务（核心）
        │   ├── model/Sample.kt            # 采样数据模型
        │   ├── monitor/                   # 温度接口（预留接口）
        │   │   ├── TemperatureProvider.kt
        │   │   ├── SystemBatteryTempProvider.kt
        │   │   └── ManualTempProvider.kt
        │   ├── storage/SampleStore.kt     # 本地 JSON 持久化
        │   ├── util/CsvExporter.kt        # 导出 CSV（对齐课程模板）
        │   └── ui/LineChartView.kt        # 纯 Canvas 折线图（零依赖）
        └── res/                           # 布局 / 图标 / 字符串 / 通知渠道
```

## 注意事项

- 采样间隔默认 5 分钟，与课题02「每5分钟记录一次」一致；想改快/慢改 `MainActivity` 里 `EXTRA_INTERVAL_MS` 的
  `5*60*1000L` 即可
- 电池温度是**电池芯**温度，并非屏幕/CPU 表面温度；不同机型读数有差异，属正常
- 保活依赖前台服务 + 唤醒锁；个别国产 ROM 会强杀后台，若发现漏采，请把本 App 加入系统「自启动/电池优化白名单」
- 本 App 仅在本地记录与导出，**不上传任何数据**
