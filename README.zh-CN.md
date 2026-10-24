# lagless-ssh

**网络卡顿时，也能顺畅地使用 SSH。** Lagless 让你先在本地流畅输入和修改命令，再由 SSH 在后台传输；它既能在现有终端中运行，也提供图形客户端。服务器无需安装任何东西。

[English](README.md) · [下载当前桌面预发布版](https://github.com/a1473838623/lagless-ssh/releases/tag/v0.2.0-rc.8.13) · [完整演示图库](docs/gallery.md) · [更新记录](CHANGELOG.md) · [Apache-2.0 协议](LICENSE)

## 为什么需要 Lagless？

假设你在拥挤的 Wi-Fi、跨地区网络或不稳定的连接上操作服务器。普通 SSH 的输入由远端回显；网络一慢，敲下的字迟迟不出现，光标、退格和修改命令都跟不上手。

Lagless 把**本地输入和网络等待分开**。命令先在你的电脑上显示和编辑，移动光标、改错字、翻历史都不用等远端。按下回车后，完整命令才通过 SSH 发出去；补全候选也在后台查询，不会挡住继续输入。这就是它的异步传输思路：网络慢可能让远端结果晚一点回来，但不该让你每敲一个字都卡一下。

远端命令的执行和输出仍然需要可用的网络。`vim` 等需要逐键交互的程序会直接接收按键，响应速度也会受到链路延迟影响。

![Lagless 桌面工作区示例](docs/media/local-editor.png)

*演示图由真实界面组件生成；主机 `example.invalid` 与输出是示例数据，没有连接真实 SSH。*

## 主要亮点

- **网络慢，输入仍跟手。** 命令在本地编辑，按回车后再整行发送；补全在后台查询，不挡住继续输入。
- **支持全屏、实时刷新的交互程序。** Claude Code、Codex CLI、`vim` 等程序持续重绘时也能直接操作。Claude Code 的输入行可在本地编辑；桌面本地编辑框可把草稿直接发送给正在运行的 Codex CLI 等程序，无需退出回到 shell。终端视图支持持续重绘和滚动查看。
- **终端版和桌面版共用核心。** 只想用现有终端，就安装独立终端版；需要多连接、文件管理和日志，再使用桌面版。两者都不要求在服务器安装 Lagless。

### 本地编辑演示

![在本地编辑框中输入命令](docs/media/local-editing.gif)

### 工作区演示

![终端、本地编辑器、输出镜像和 AI 助手视图](docs/media/workspace-tour.gif)

如果 Lagless 帮你在高延迟网络下更顺畅地操作 SSH，欢迎[给项目点 Star](https://github.com/a1473838623/lagless-ssh)，也欢迎提交可复现的问题或改进建议。

[宣传封面与中英文分享文案](docs/launch-kit.md)

## 独立 core 与终端版

低延迟核心保留为独立 `core` 模块。只使用命令行时，安装 `lagless-terminal-<版本>-<平台>-<架构>.zip` 即可，在 Windows Terminal、系统终端等现有终端中直接运行 `lagless user@host`，无需安装图形客户端。

只构建终端版：`mvn -pl cli -am verify`，生成 `cli/target/lagless-cli-standalone.jar`。终端包及其精简运行时都不需要图形模块；更新时也只更新终端版本。独立核心库为 `core/target/lagless-core.jar`。详见 [core 与终端版说明](docs/core.md)。

## 桌面客户端（0.2 预发布版）

```powershell
lagless.cmd gui
```

Linux/macOS 用 `./lagless gui`。桌面端复用原有低延迟终端内核，提供下拉菜单与按需连接库、并发标签与多窗口、底部/右侧/独立本地命令编辑、自动换行输出镜像、前台历史、合并本地操作的日志、带加载状态和完整文件操作的双栏 SFTP 与进度队列、磁盘终端历史与滚动、交互程序按键透传、自动重连、后台 SSH 跳板、插件、按需 AI/翻译、危险命令审阅、账号互导、加密备份恢复、更新与上一版本回滚。AI 需自行配置服务。

纯客户端无法在 SSH/TCP 完全中断后保住远程 PTY；可选复用服务器已有 tmux，无需安装新软件。跳板能向目标服务器隐藏来源 IP，但不能对所有网络节点匿名。完整功能范围、使用方法和限制见 [桌面客户端说明](docs/desktop.md)，扩展接口见 [插件开发](docs/plugins.md)。

源码构建为 `mvn package`，桌面产物为 `desktop/target/lagless-desktop-standalone.jar`；完整运行时打包脚本默认分别生成终端包和图形包，也可用 `-Edition terminal` 只构建终端包。仓库 CI 配置为三平台官方修复版 JDK 25 构建，源码最低目标仍为 Java 21。当前本机已验证的平台与未验收部分见桌面说明。

桌面也支持真正的 **GraalVM Native Image 原生包**：解压 `lagless-desktop-native-<版本>-windows-x64.zip` 后双击 `Lagless.exe`，不需要 Java。rc.8.13 使用 Oracle 官方已修复 CVE-2026-91403 的 GraalVM 25.0.5 安全更新线构建原生 EXE（官方归档 SHA-256 固定校验），终端和 JVM 桌面包使用 Oracle JDK 25.0.5.0.2；详见 [本次发布说明](docs/releases/0.2.0-rc.8.13.md)。构建入口和限制见 [原生桌面版](docs/native-image.md)。

## 终端核心如何工作

普通 SSH 的输入由服务器回显，链路差的时候光标跟不上手。Lagless 在 shell 提示符出现时接管键盘：

- 方向键、Home/End、Ctrl-A/E、Ctrl-W 等都在本地完成，任意位置插入删除，零延迟。
- 上下键、Ctrl-R 翻的是本地历史。连接时会把远程 `.bash_history` / `.zsh_history` 末尾 500 条并进来。
- Tab 补全走同一条 SSH 连接上的第二个通道。按 Tab 不阻塞：候选已缓存就立即补全，没缓存就后台去取，再按一次 Tab 就有了。当前目录的列表在每次出现提示符时预取。
- 回车后整行一次发出，远程 shell 的回显会被识别并吞掉，屏幕上不会出现重复的命令行。
- 执行的命令一旦输出、进入 vim/less/htop（备用屏）、等待密码、`read` 交互，都自动处于透传模式，按键原样发送，和普通 ssh 完全一致。命令结束、提示符再次出现时又自动回到本地编辑。
- Claude Code 的输入框（空的 `❯ ` 提示符）和 shell 提示符一样在本地编辑：打字、退格、左右键、Home/End、Ctrl-A/E/U/K/W 全在本地完成，不发给远程，也不和远程同步，网络再差也不会闪、不会拖；回车时输入框立刻清空、可以接着打下一句，这行字随即发出，等远程显示出这行字后再发回车。丢包、网络卡住时也一样：屏幕上的输入照常立即显示，只有发送会等网络恢复。按 Tab、上下键、Esc 等本地处理不了的键时，先把已打的字发过去再发这个键。Claude Code 弹出询问（例如权限确认）时按键直接给它，提示符回来后本地这一行也回来。`--no-local-line` 或配置 `local-line = false` 关闭。
- 在其他全屏程序里（vim 插入模式、nano、tmux 里的 shell）打字也不用等回显：按键先在本地光标处画出来，远程回显到了正好落在同一格上。每轮先让一个按键走完一次真实往返，确认程序确实是在光标处回显，之后的按键才本地先画；猜错的格子会按屏幕模型还原。回车、方向键、Esc、Ctrl 组合键、粘贴都会重新开始确认。本地打出的字就是屏幕上的准绳：按键被当作对当前行的编辑（在光标处插入、把后面的字推右；退格删掉光标前的字、把后面的拉左），远程回显陆续到达时只核对"远程已经处理到第几个键"，不会把还没回显的字挤掉或拖动。远程正在重画界面时（光标隐藏期间）先把输出攒住，重画完成（最多等 50 ms）再与本地字符合成一次整体刷新，只改有差异的格子。退格删除的是自己打过的字时同样本地立即生效。`--no-predict` 或配置 `predict = false` 关闭。
- 鼠标默认留给终端选中复制：Claude Code、vim、htop 等程序请求的鼠标跟踪会被忽略，在 cmd / Windows Terminal 里照常拖选、复制。想让程序接管鼠标（滚轮、点击），用 `--app-mouse` 或配置 `mouse = app`。
- 命令执行期间抢先输入的字符会被远程 shell 缓冲住。只要透传期间敲过字符，发送整行之前就一定先发一个 Ctrl-U 清空远程缓冲，所以绝不会出现两段拼在一起。其中在提示符后面回显出来的那部分会被放回本地编辑行，你看得见也删得掉；没有在提示符上出现过的（被运行中的程序消费掉，或者 shell 没把它重画出来）则直接丢弃，不会偷偷接到下一条命令前面。一句话：屏幕上看得到的就留着，看不到的就是真的没了。

## 安装

### 方式一：完整包，不需要装 Java（推荐）

在 [Releases](https://github.com/a1473838623/lagless-ssh/releases) 页面下载 `lagless-<版本>-windows-x64.zip`，解压到任意目录，例如 `C:\Tools`，得到一个 `lagless` 文件夹。里面自带一个裁剪过的 Java 运行时，机器上有没有 Java 都不影响。

解压后直接就能跑：

```bash
C:\Tools\lagless\lagless.cmd --help
```

接着做下面的「加入 PATH」。压缩包约 40 MB，解压后约 75 MB。

### 方式二：只下载 jar（机器上已有 Java 21+）

下载 `lagless.jar` 和对应平台的启动脚本（Windows 用 `lagless.cmd`，Linux/macOS 用 `lagless`，后者需要 `chmod +x lagless`），三者放进同一个目录即可。启动脚本会自动找同目录的 jar，并调用 PATH 里的 `java`。

检查 Java 版本：

```bash
java -version
```

低于 21 的话装一个 [Temurin](https://adoptium.net/) 或 [GraalVM](https://www.graalvm.org/downloads/)，或者直接用方式一。

### 方式三：从源码构建

需要 JDK 21+ 和 Maven：

```bash
git clone https://github.com/a1473838623/lagless-ssh.git
```

```bash
cd lagless-ssh && mvn -DskipTests package
```

生成 `cli/target/lagless-cli-standalone.jar` 和 `desktop/target/lagless-desktop-standalone.jar`，仓库根目录启动脚本默认使用前者，`gui` 使用后者；只需要终端可改用 `mvn -pl cli -am package`。

想自己打一个自带运行时的完整包（需要 JDK 里的 `jlink`）：

```bash
powershell -ExecutionPolicy Bypass -File scripts\build-dist.ps1
```

产物在 `dist\lagless-terminal-<版本>-windows-x64.zip`（独立终端）和 `dist\lagless-<版本>-windows-x64.zip`（含图形界面）。加 `-Edition terminal` 可只打包终端版。运行时是分平台的，要发布哪个平台的包就在哪个平台上执行这条命令。

### 加入 PATH

这样在任何目录敲 `lagless` 都能用。Windows 在 PowerShell 里执行一次，把路径换成你实际放置的目录：

```powershell
[Environment]::SetEnvironmentVariable("PATH", $env:PATH + ";C:\Tools\lagless", "User")
```

Linux/macOS 写进 `~/.bashrc` 或 `~/.zshrc`：

```bash
export PATH="$PATH:$HOME/tools/lagless"
```

都需要重开一个终端窗口才生效。

### 验证

```bash
lagless --help
```

看到用法说明就装好了。接着可以直接连一台已经在 `~/.ssh/config` 里的机器：

```bash
lagless
```

## 使用

```bash
lagless root@1.2.3.4
lagless -i ~/.ssh/id_ed25519 -p 2222 root@1.2.3.4
lagless myserver          # 读取 ~/.ssh/config 里的 Host 别名
lagless                   # 不带参数：列出 ~/.ssh/config 里的主机，按编号或名字选择
```

`~/.ssh/config`、`~/.ssh/known_hosts` 和 `~/.ssh/id_*` 会被自动使用。密码、私钥口令、未知主机指纹在终端里询问。

## 主机管理

主机配置就是 `~/.ssh/config`，和系统 ssh 共用一份，不另造格式：

```bash
lagless list                                   # 列出主机
lagless add box root@1.2.3.4:2222 -i C:\keys\box.pem   # 新增或覆盖一条 Host box
lagless rm box                                 # 删除（先备份到 config.bak）
```

lagless 自己的选项放在 `~/.lagless/config`，`lagless config` 会创建模板并显示。全局键在最前面，`[主机]` 小节按别名或地址覆盖：

```ini
quiet-ms = 120
completion = true
remote-history = true
predict = true
local-line = true
mouse = select
auto-update = true
update-check-hours = 24

[myserver]
prompt-regex = \$ $
```

## Windows Terminal 入口

```bash
lagless profiles           # 打印将要生成的 JSON
lagless profiles install   # 写入 %LOCALAPPDATA%\Microsoft\Windows Terminal\Fragments\lagless\hosts.json
```

Windows Terminal 会把这个片段合并进设置，新建标签的下拉菜单里每台主机一项，名字是 `lagless <主机>`。改了 `~/.ssh/config` 之后重新执行一次 `install` 即可。不想要了删掉那个 json 文件。

其他能自定义启动命令的终端（WezTerm、Tabby、MobaXterm 的本地终端、VS Code 终端 profile）同样可以把 `lagless <主机>` 作为命令来用。

## 更新

```bash
lagless version
```

```bash
lagless update --check
```

```bash
lagless update
```

`update` 读取仓库最新的 release，比对版本。终端版只下载 `lagless-terminal.jar`，桌面版下载 `lagless.jar`；不会把终端版自动替换为图形客户端。

平时不用管它：每次连接时会在后台查一次（默认最多一天一次），发现新版本就下载好，下次启动自动用上。想关掉或改频率，在 `~/.lagless/config` 里设 `auto-update = false` 或 `update-check-hours`。

手动执行 `lagless update` 的话，一条命令就完事，不需要再做别的。原理是运行中的 JVM 在 Windows 上没法替换自己的 jar，所以新 jar 先落在同目录的 `lagless.jar.new`，由启动脚本在 JVM 退出之后立刻搬到位，下次启动前还会再检查一遍。唯一的例外是绕过启动脚本直接 `java -jar`，那样不会触发就位。

自带的 `runtime/` 目录不会被动，它变动极少；万一某个版本需要新的运行时，更新时会提示你去下载完整包。

公开仓库无需令牌。使用私有 fork 时才需要有 `repo` 权限的令牌，按这个顺序查找，找到哪个用哪个：

1. 环境变量 `LAGLESS_GITHUB_TOKEN`、`GITHUB_TOKEN`、`GH_TOKEN`
2. `~/.lagless/config` 里的 `github-token`
3. 已登录的 GitHub CLI（执行 `gh auth token`）

装了 gh 并且 `gh auth login` 过的话，什么都不用配。仓库改成公开后则完全不需要令牌。要指向别的仓库，在 `~/.lagless/config` 里写 `update-repo = 用户名/仓库名`。

## 会话内文件传输

在本地编辑模式下，以 `:` 开头的这几条命令不会发给远程 shell：

```
:put 本地文件 [远程路径]     通过 SFTP 上传，远程相对路径相对于当前工作目录
:get 远程文件 [本地路径]     通过 SFTP 下载
:help                       显示帮助
```

传输在后台进行，进度和结果打印在编辑行上方，提示符保持可编辑。其他以 `:` 开头的行（比如误敲的 `:q`）照常发给远程。

| 选项 | 作用 |
|---|---|
| `-p PORT` / `-i FILE` / `-l USER` | 端口、私钥、登录名 |
| `--prompt-regex RE` | 手动指定提示符正则，不再自动学习 |
| `--quiet-ms N` | 输出安静多少毫秒后才判定为提示符（默认 120） |
| `--no-completion` | 不开第二通道，不做补全 |
| `--no-remote-history` | 不导入远程历史 |
| `--no-local-line` | Claude Code 输入框里按键逐个发送（走预回显），不在本地编辑整行 |
| `--no-predict` | 全屏程序里不做本地预回显，等远程回显 |
| `--app-mouse` | 让远程程序接管鼠标（默认鼠标用于选中复制） |
| `--raw` | 纯透传，用来对比 |
| `--debug` | 把完整字节流写到 `~/.lagless/debug.log` |
| `--record 文件` | 把远程输出、按键和写到终端的内容原样记录到文件，用于排查显示错乱（会包含会话内容） |

按键：

| 场景 | 按键 | 效果 |
|---|---|---|
| 编辑行 | 方向键、Home、End、Ctrl-A/E/W/U/K | 本地编辑 |
| 编辑行 | 上/下、Ctrl-R | 本地历史 |
| 编辑行 | Tab | 异步补全，命令名和路径 |
| 编辑行 | Ctrl-] | 把当前已输入内容交给远程，切到透传 |
| 编辑行 | Ctrl-C / Ctrl-D | 发给远程 |
| 透传 | Ctrl-] | 把当前行强制当作提示符进入本地编辑 |
| 透传 | Ctrl-] 连按两下 | 忘掉已学习的提示符，重新学习 |

## 工作原理

客户端只观察 pty 字节流，不依赖远程配合：

1. `OutputMonitor` 跟踪备用屏开关（`ESC[?1049h/l`）和当前未结束的输出行。
2. `PromptDetector` 在输出安静下来后看最后一行。第一次遇到以 `$ `、`# `、`% ` 等结尾的行就学下来（记住开头几个字符和结尾字符），之后只有同样结尾的行才算提示符。`Password: `、`> `、`Continue? [y/n]` 都进不来。也可用 `--prompt-regex` 指定。
3. 判定为提示符后，把该行清掉，用 JLine 在同一位置重画提示符并接管输入。
4. 回车时把整行加 `\r` 发出，`EchoFilter` 吞掉远程回显到换行为止；对不上就原样放行，不会丢数据。
5. 透传模式下，`ScreenModel` 按远程输出维护一份屏幕副本（每格字符、SGR、光标、备用屏），`LocalEcho` 据此把按键先画在本地，回显到达后核对，没对上的格子从副本重画。
6. `RemoteHelper` 在第二个 SSH 通道上按需执行 `ls -1Ap`、`compgen -c`，并通过 `/proc/<pid>/cwd` 跟踪交互 shell 的当前目录。

## 测试

单元测试直接 `mvn test`。端到端测试需要一个真实 sshd，设置环境变量后自动启用：

```bash
LAGLESS_TEST_HOST=localhost LAGLESS_TEST_PORT=2222 LAGLESS_TEST_USER=ubuntu LAGLESS_TEST_KEY=/path/to/key mvn test
```

它通过虚拟终端驱动客户端，验证行内编辑、回显吞掉、历史、Ctrl-C、vim 备用屏保持透传、异步补全、`:put`/`:get` 往返，以及命令执行期间抢先输入的三种情形（保留一次、可删掉、被程序消费后不回填）。`scripts/start-test-sshd.sh` 在 WSL 或任意 Linux 里以普通用户身份起一个 2222 端口的 sshd 供测试用，`stop` 参数停止。
