# 03 · AI 协作记录 AI Collaboration

存放与 AI 协作的**关键对话**，重点是「提问 → 排障 → 解决」的完整链路。 可用 AI：DeepSeek、Kimi、通义千问（Qwen）、
ChatGPT 等。

## 命名规范

`YYYY-MM-DD-问题关键词.md`（关键词用英文短横线连接）

示例：
- `2026-10-15-git-push-rejected.md`
- `2026-10-18-arduino-upload-error.md`

## 记录模板

复制 `TEMPLATE.md` 后填写：

```markdown
# 问题：<一句话说清>

- 日期：
- 使用的 AI：
- 技术栈 / 环境：

## 背景与目标

## 我的提问（原文粘贴）

## AI 回答要点（摘录关键步骤，注明哪条建议有效）

## 验证结果

- [ ] 已解决
- [ ] 部分解决 / 遗留问题：
```

## 收录标准

- 排障过程（报错 → 定位 → 修复）优先
- 让 AI review 代码 / 生成方案的关键对话
- 不必收录闲聊；收录能体现「你如何与 AI 协作解决问题」的记录
- 换 AI 接手同一问题时**续档**而非另起炉灶；有效 / 无效建议如实标注（评分点）

## 已归档记录

| 文件 | 排障主题 | 结论 |
| --- | --- | --- |
| `2026-10-25-git-push-proxy-502.md` | git push 失败：误推远程 + 代理故障 + 陈旧 proxy | 已解决 |
| `2026-11-06-ci-build-fail.md` | GitHub Actions 构建失败，靠公开 `ci-build-log` 定位 | 已解决 |
| `2026-11-09-rf-zero-dependency-correction.md` | 「零依赖 / TODO」误述更正与 RandomForest 实装 | 已解决 |

> 三份均按 `TEMPLATE.md` 编制。「我的提问」为据当日 `process_logs/` 与 `AGENTS.md`
> **回溯整理的要点，非逐字原文**（原文未逐条留存，不编造）；每条 AI 建议标注
> ✅ 有效 / ❌ 无效，验证结果链接真实 commit 与 Release。当前状态见 `../AGENTS.md` 8.4。
