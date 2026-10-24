# AGENTS.md — 多 AI 协作规则

> 本文件是**所有 AI 助手**（WorkBuddy/Hy3、DeepSeek、Kimi、通义千问、ChatGPT 等）在本仓库协作时必须遵守的规则。
> 每次动手前先读本文件；规则与用户口头指令冲突时，以**课程硬要求 + 本文件铁律**优先，并向用户确认。

---

## 1. 仓库概况

- **课程**：ICEC-2026（课程代码 0183），深圳城市职业学院
- **作者**：Zhangjunming，深圳城市职业学院 · 人工智能技术应用 2026 级（学号不公开）
- **性质**：课程**过程仓库**——评委看的不只是结果，更是"真实、连续、可追溯"的过程
- **远程**：https://github.com/W137191/ICEC-2026-0183-ZhangJunming（公开仓库，**提交=公开**）

## 2. 目录结构

```
├── 01-Field-Discovery/        # 阶段一：基础素养实地测评（课题02 手机发热 + 课题06 噪声地图）
│   ├── 报告/ raw_data/ photos/
├── 02-20Days-Challenge/       # 阶段二：工坊项目挑战营（项目三 声振诊断为主，项目一 ESP32 为候补）
│   ├── 项目三-手机声振诊断/     #   README/实验方案/raw_data(5工况×15组)/src/fft_features.py/实验照/报告
│   ├── 项目一-ESP32省电/        #   候补草稿
│   ├── process_logs/          #   ★连续多日过程日志（防摆拍硬要求）
│   └── src/BatteryHeatMonitor/ #   自研安卓采集 App（Kotlin，CI 构建）
├── 03-AI-Collaboration/       # AI 协作关键对话存档（YYYY-MM-DD-关键词.md）
├── GIT-GUIDE.md               # 给用户自己看的 git 操作指南
└── README.md                  # 个人简介 + 选题
```

---

## 3. 铁律（所有 AI 必须遵守，无例外）

### 3.1 数据真实性 —— 最高优先级
- **绝对禁止编造或"补全"任何测量数据**。CSV 里没有的值就留空或写"待测"，绝不虚构。
- 用户说"之后再填/数据丢包了"时，**不要代填**，等实测。
- 修改已有数据必须来自用户提供的真实截图/原始记录，并在备注里写清来源（如"两次测量合并取均值"）。

### 3.2 隐私保护（提交即公开）
- **中文汉字姓名、学号、QQ/微信号、手机号、邮箱、真实住址等永不写入任何会提交的文件**（拼音姓名 Zhangjunming 按用户要求可以出现）。
- 曾踩过坑（README 手机号、commit 作者 QQ 邮箱），提交前必查 `git diff --cached`。
- 照片如含人脸/门牌/他人信息，提醒用户后再提交。

### 3.3 提交规范
- **小步提交**：一个逻辑改动一个 commit，不要攒成大杂烩。
- 提交信息格式：`类型: 描述`，类型 ∈ `docs / feat / fix / chore`。
  - 例：`fix: 修复SensingActivity未导入R类(Unresolved reference: R)`
- **每天至少 push 一次**（课程要求可见的连续过程）。
- commit 前自查：`git status && git diff --cached`。

### 3.4 Markdown 与媒体文件
- 报告里嵌图片/视频**必须用相对路径**：`![](../photos/课题06/xxx.jpg)`——绝对路径和 `file:///` 在 GitHub 上是坏链。
- 视频一律转 **H.264**（`-crf23 -pix_fmt yuv420p -movflags +faststart`）再提交，GitHub 不支持 HEVC。
- 改完嵌入路径要全仓验证一遍链接有效。

### 3.5 课程硬要求不可被 AI "优化"掉
- 阶段一同框照（本人入镜）：课题02 需 4 张、课题06 需 5+ 张——场景截图不算数。
- 阶段二 `process_logs/YYYY-MM-DD.md` **连续**记录，禁止事后集中补写（评委按防摆拍逻辑审查）。
- 实验照必须"人 + 设备 + 手机"同框。

### 3.6 语言
- 对用户用**简体中文**；文件内容也用中文（专有名词/代码除外）。

### 3.7 代码与文档风格（Google 风格标准，2026-10-24 起执行）
本仓库统一采用 **Google 风格标准**，根目录 `.editorconfig` 已在编辑器层固化，改文件前先确认装好 EditorConfig 插件。
- **Markdown**（依 [Google Markdown Style Guide](https://google.github.io/styleguide/docguide/style)）：
  - ATX 标题（`#` 不用下划线式）；**每篇只有一个 H1**；标题前后留空行
  - **正文每行 ≤ 80 字符**（汉字按 1 字符计）；例外：链接行、表格、标题、代码块可超
  - 代码块一律用围栏 ```` ``` ```` 并**声明语言**（目录树/示意图用 `text`）；不用缩进式代码块
  - 列表标记统一：无序 `-`、有序 `1.`；**嵌套列表缩进 4 空格**；段落与列表/表格/代码块之间留空行
  - 裸 URL 用 `<https://...>` 包裹；仓内链接用相对路径
- **Python**（依 Google Python Style Guide）：模块与函数写 docstring（`Args:` / `Returns:` / `Raises:` 段）；函数签名带类型注解；`snake_case` 命名；4 空格缩进；懒加载依赖要注释原因
- **Kotlin/Java**：遵循 Android（Google）代码风格，4 空格缩进；**子包文件必须显式 import 应用命名空间的 `R`**
- 已有历史文件不强制回炉重排（Google 的 Better/Best 规则：不为琐碎格式反复翻旧账），但**新写与改动到的文件必须合规**

---

## 4. 技术环境（AI 动手前必读）

- **本地无 Android SDK**：App 不能本地编译。改完 Kotlin/Java 代码后 `git push`，由 GitHub Actions（`.github/workflows/build-apk.yml`）构建，APK 自动发布到 Release `apk-latest`。
- **CI 特性**：构建失败时，错误诊断会自动发布到公开 Release `ci-build-log`（因为本地读不了 job 日志）；成功时自动清理该调试 Release。
- **改 CI 或 App 代码后**：必须等一次 CI 结论为 `success` 才算完成；`failure` 时去 `ci-build-log` Release 读报错再修。
- git 环境：git 2.55，全局身份已配置；`github.com` 经本地代理，API 访问 curl 需 `--ssl-no-revoke`。
- **没有 gh CLI**；GitHub API 只读操作用 `curl --ssl-no-revoke`。

---

## 5. 多 AI 协作约定

### 5.1 协作档案（03-AI-Collaboration/）
- 关键排障/方案对话按 `YYYY-MM-DD-关键词.md` 存档（复制 `TEMPLATE.md` 填写）。
- **换 AI 接手同一问题时，必须续档**：在原文件追加"第二轮：AI 名称 + 日期 + 建议要点 + 是否有效"，不要另起炉灶。
- 哪条建议有效/无效要如实标注——这是评分点（体现"你如何与 AI 协作"）。

### 5.2 AI 之间的交接清单
换 AI 或开会话时，新 AI 应先自查：
- [ ] 已读本文件与 `README.md`
- [ ] 已看 `git log --oneline -10` 了解最近进展
- [ ] 已看 `02-20Days-Challenge/process_logs/` 最新日志
- [ ] 已知当前未完成的 TODO（数据缺口、待拍照片、待测工况）

### 5.3 冲突处理
- 两个 AI 给出矛盾方案时，**不要擅自合并**，把两个方案+理由摆给用户选。
- AI 不得自行决定改变课程交付结构（目录、评分项对应的交付物）。

---

## 6. 当前进度快照（2026-10-22）

**阶段一**：脚手架+采样数据已落地；缺：同框照 0 张（需 9 张）、课题02 待机/短视频工况数据、干净导航曲线（待用户装新 App 重测）。

**阶段二**：主选项项目三，采集 App 已完工（CI success，APK 在 `apk-latest`）；FFT 特征脚本骨架就绪。
**下一步**：装 APK → 采第 1 组样例验证 `src/fft_features.py` → 铺满 75 组 → 每日 `process_logs` + 同框实验照。

> 每个 AI 完成实质性工作后，更新此快照，让下一个 AI 无缝接手。
