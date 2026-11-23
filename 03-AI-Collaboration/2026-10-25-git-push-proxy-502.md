# 问题：git push 到 GitHub 反复失败（误推远程 + 代理故障 + 陈旧 proxy）

- 日期：2026-10-25（误推远程）/ 2026-11-06（代理端口变化）/
  2026-11-23（陈旧 proxy 复核）
- 使用的 AI：当日协作 AI（process_logs 未逐字记录具体模型名）；
  2026-11-23 由 Hy4 依据日志回溯整理
- 技术栈 / 环境：Windows + Git Bash，GitHub 课程仓 `newrepo`，
  本地 CONNECT 型代理
- **说明**：以下「我的提问」为据当日 `process_logs/` 与 `AGENTS.md` 回溯整理的
  提问要点，**非逐字原文**；逐字原文未逐条留存，按真实性优先原则不编造。

## 背景与目标

课程仓需要每天 push 留痕。但多次遇到 `git push` 失败：一次推错远程，一次代理端口
变化连不上，一次 curl 能通而 git 不通。目标：分清三类根因，给出可复现修复与预防。

## 我的提问（据日志回溯，非逐字原文）

1. （10-25）「我 push 之后发现推到了别的仓库，课程主仓没同步，怎么办？」
   ——依据 `AGENTS.md` 9.5：归档提交后执行 `git push origin main`，误推到旧仓库
   `symmetrical-train`，课程主仓 `newrepo` 一度未同步。
2. （11-06）「平板上的修复三天都没进 GitHub，CI 一直是旧包，为什么？」
   ——依据 `process_logs/2026-11-06.md` 一·1：v1.9.2 修复因代理端口变化
   （58704→62374）连不上、推送失败，CI 一直没构建新包。
3. （11-23）「curl 访问 GitHub 是 200，但 git fetch / push 全卡在 127.0.0.1:55571」
   ——依据 `AGENTS.md` 第 6 节代理故障处理原则与当日排障记录。

## AI 回答要点

- ✅ **有效**：先分清三类问题，不要混为一谈——
  ① **推错远程**：`origin` 指向旧游戏仓，课程仓只认 `newrepo`。立即
  `git push newrepo main` 补推；长期预防执行 `git remote remove origin` 物理切断
  footgun（已执行，课程仓现仅 `newrepo` 一个远程）。
  ② **代理端口变化**：URL 专属代理写死后端口一变就连不上，更新到当前端口后推送成功。
  ③ **curl 通而 git 不通**：优先查仓库级 `.git/config` 的 URL 专属代理
  `http.https://github.com/.proxy`——它覆盖环境变量与命令行通用代理。
  用 `git config --list --show-origin` 定位，
  `git config --local --unset "http.https://github.com/.proxy"` 移除后恢复。
- ✅ **有效**：代理故障（CONNECT 502）时不要连续重试轰炸，提交先安全留在本地，
  待网络恢复后先 `git log newrepo/main..HEAD` 确认待推送清单再推。
- ❌ **无效 / 教训**：一开始把「推不上去」笼统归因于「梯子没开」，反复重试无效；
  正确做法是先区分远程配置 / 代理配置 / 端口三个层面再动手。

## 验证结果

- [x] 已解决：误推分支已在旧仓删除、默认分支切到「资源」，课程仓补推完成；
  代理端口更新后 11-06 推送成功；陈旧 proxy 移除后 `git fetch` / `push` 均正常。
- [ ] 部分解决 / 遗留问题：代理端口仍会随本地工具变化，换端口需同步更新；
  已通过「只保留 `newrepo` 一个远程 + 禁用 URL 专属代理」降低复发概率。

> 溯源：`AGENTS.md` 第 6 节、9.5；`process_logs/2026-11-06.md` 一·1；
> `GIT-GUIDE.md` 第 5 节。当前状态以 `AGENTS.md` 8.4 收口快照为准。
