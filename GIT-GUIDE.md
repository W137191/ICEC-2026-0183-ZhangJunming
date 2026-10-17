# Git 极简指南（零基础 · Windows）

> 配套：ICEC-2026 课程 Step 0
> 目标：30 分钟走完「建仓库 → 首次提交 → 推送远程」，之后每天只用 4 条命令。
> 学法：本指南 + AI 兜底。任何报错整段复制给 DeepSeek / Kimi / Qwen / ChatGPT，让它解释原因并给命令；关键排障对话按规范存档到 `03-AI-Collaboration/`，这就是课程要的过程数据。

---

## 一、先建立直觉：Git 管的是什么

Git 管理「文件的修改历史」。记住 4 个位置就够了：

| 位置 | 是什么 | 对应命令 |
| --- | --- | --- |
| 工作区 | 你眼前文件夹里改来改去的文件 | （直接编辑） |
| 暂存区 | 准备纳入下一次提交的清单 | `git add` |
| 本地仓库 | 本机的完整历史（`.git` 文件夹） | `git commit` |
| 远程仓库 | GitHub / Gitee 上的云端备份 | `git push` / `git pull` |

数据流向：**工作区 →（add）→ 暂存区 →（commit）→ 本地仓库 →（push）→ 远程仓库**

## 二、安装 Git（只需一次）

1. 打开 https://git-scm.com/download/win ，下载 64-bit Git for Windows Setup
2. 双击安装，**一路 Next**（选项全部用默认值）
3. 开始菜单打开 **Git Bash**，输入 `git --version`，出现 `git version 2.x.x` 即成功

## 三、首次配置（只需一次）

```bash
git config --global user.name "你的名字拼音"
git config --global user.email "你的常用邮箱"
```

## 四、在 GitHub 创建公开仓库

1. 登录 github.com → 右上角 **+** → **New repository**
2. Repository name 填：`ICEC-2026-0183-ZhangJunming`（与本地文件夹同名）
3. 可见性选 **Public**（课程要求公开）
4. **不要**勾选 Add a README file（本地已有内容，勾了会产生冲突）
5. Create repository → 复制仓库地址：`https://github.com/W137191/ICEC-2026-0183-ZhangJunming.git`

> 国内访问 GitHub 不稳定时，用 Gitee 平替：gitee.com → 右上角 **+** → 新建仓库 → 同名 → 公开 → 创建。后续命令里的地址换成 Gitee 的即可，其他操作完全一样。

## 五、关联远程并首次推送（一次性）

本仓库已经在本地初始化好了，你只需要：

```bash
cd /d/zjm/ICEC-2026-0183-ZhangJunming        # 进入仓库（Git Bash 路径写法）
git remote add origin https://github.com/W137191/ICEC-2026-0183-ZhangJunming.git
git push -u origin main
```

- `-u` 只加一次：记住「本地 main ↔ 远程 main」的对应关系，之后推送直接 `git push`
- 中途弹出 GitHub 登录窗，按提示授权；若要求密码，见第七节 token

> 以后在别的电脑上拿这个仓库，用 `git clone <仓库地址>`，不需要再 `git init`。

## 六、每日工作流：只有 4 条命令

```bash
git status                              # 1. 看看当前有哪些改动
git add .                               # 2. 全部改动放入暂存区（也可 git add 某个文件）
git commit -m "docs: 完成调研报告初稿"  # 3. 提交到本地仓库，消息写清楚改了什么
git push                                # 4. 推送到 GitHub
```

查看历史：`git log --oneline`（一行一次提交：哈希 + 消息）

**提交信息写法**（课程看过程提交，信息清晰很重要）：

| 前缀 | 用途 | 示例 |
| --- | --- | --- |
| `docs:` | 文档 / 报告 / 日志 | `docs: 补充调研照片说明` |
| `feat:` | 新功能 / 新原型 | `feat: 完成首页布局` |
| `fix:` | 修 Bug | `fix: 修复表单提交报错` |
| `chore:` | 结构 / 配置等杂项 | `chore: 更新 .gitignore` |

## 七、常见报错自救表

| 报错 / 现象 | 原因 | 解决 |
| --- | --- | --- |
| `! [rejected] ... failed to push` | 远程有你没有的提交（常见于在网页端改过文件） | 先 `git pull --rebase origin main`，再 `git push` |
| `Support for password authentication was removed` / 403 | GitHub 2021 年起不支持账号密码推送 | 用 Personal Access Token 代替密码：GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token → 勾选 `repo` → 复制；push 时用户名填 GitHub 用户名，密码处粘贴 token |
| push 卡住 / 超时 | 网络不通畅 | 换 Gitee 仓库，或检查代理 |
| `fatal: not a git repository` | 当前目录不是仓库 | 先 `cd` 进仓库文件夹再执行命令 |
| 误提交了密码 / token | 敏感信息进了历史 | 立即删除并修改密码；`git rm --cached 文件` 移出跟踪后重新提交；必要时让 AI 协助重写历史 |

## 八、把 AI 变成你的 Git 助教

1. **报错整段复制**：不要截图描述，把 Git Bash 里的英文报错全文粘贴给 AI
2. **说清上下文**：目标 + 环境 + 已尝试，例如：「Windows + Git Bash，想把本地仓库推到新建的 GitHub 仓库，执行 git push 后报错如下……」
3. **要「命令 + 解释」**：让 AI 给可复制的命令并逐条解释，看不懂就追问
4. **验证后归档**：关键排障对话按 `03-AI-Collaboration/README.md` 的模板存档

## 九、命令速查（可打印）

```bash
git status                  # 查看当前状态
git add <文件> / git add .  # 暂存改动
git commit -m "消息"        # 提交到本地仓库
git push                    # 推送到远程
git pull                    # 拉取远程更新
git log --oneline           # 简洁历史
git clone <地址>            # 克隆远程仓库
git diff                    # 查看未暂存的改动
```

---

*本指南随课程进度更新。改进建议直接改本文件再 push，一次 `docs:` 提交就是一次过程记录。*
