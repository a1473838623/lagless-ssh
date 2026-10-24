# Core 与独立终端版

低延迟 core 是独立的 Java 库。终端版只依赖 core；图形客户端是可选前端，继续使用相同的 core。只需要命令行时，不必安装图形客户端，也不需要后台 GUI 进程或本地网页服务。

| 模块 | 内容 | 依赖 | 构建产物 |
| --- | --- | --- | --- |
| `core` | SSH/SFTP、跳板连接、本地编辑、历史、异步补全、提示符识别、全屏回显预测 | JLine、JSch | `core/target/lagless-core.jar`，供代码集成，非启动程序 |
| `cli` | 命令行入口、主机选择与管理、Windows Terminal profiles、更新 | core | `cli/target/lagless-cli-standalone.jar`，可独立启动，已含 core 和运行依赖 |
| `desktop` | 图形工作台、窗口、镜像、插件、AI、桌面备份 | core、cli、FlatLaf | `desktop/target/lagless-desktop-standalone.jar`，含可选 GUI 与 CLI |

依赖始终从前端指向 core，core 不依赖 cli 或 desktop。CLI 通过 ServiceLoader 查找可选图形入口，编译和运行均不要求桌面模块。终端包不含 `dev.lagless.desktop` 或 FlatLaf，随包运行时也不含 `java.desktop`。JLine 内部的可选扩展不在低延迟路径加载。

## 只安装终端版

下载 `lagless-terminal-<版本>-<平台>-<架构>.zip`，解压后直接在现有终端运行：

```powershell
lagless.cmd user@host
lagless.cmd myserver
lagless.cmd profiles install
```

Linux/macOS 对应 `./lagless user@host`；解压后必要时先 `chmod +x lagless`。包内已含精简 Java 运行时，不需要 Java、图形桌面或 Lagless 图形客户端。与 Windows Terminal、WezTerm、Tabby、系统终端等现有终端配合使用；远端服务器不安装任何 Lagless 组件。

如果已有 Java 21+，只需独立终端 JAR：

```sh
java -jar cli/target/lagless-cli-standalone.jar user@host
```

根目录的 `lagless` / `lagless.cmd` 在源码目录中默认选择终端产物；`lagless gui` 才选择 desktop 产物。发行包目录中则使用随包的 `lagless.jar`。终端包执行 `gui` 会说明图形版为可选安装，并以状态 2 退出；不会尝试加载 Swing。

命令行原有的本地编辑、历史、异步补全、全屏预测、`:put` / `:get`、`-J` 跳板、`--record`、调试与主机管理均保留。GUI 专属的窗口、AI、镜像等功能不属于 CLI。终端版仍是客户端一侧执行的 SSH 程序；仅安装一个普通系统终端，不运行任何 Lagless 组件，不会自动获得 lagless 能力。

## 单独构建与打包

```sh
# 只验证和构建 core，不构建任何前端
mvn -pl core -am verify

# 只构建 core + 独立终端，不编译或加载 desktop
mvn -pl cli -am verify

# 全部模块及回归
mvn verify
```

使用 PowerShell 7（Windows/macOS/Linux 均可）打包对应平台运行时：

```powershell
pwsh -File scripts/build-dist.ps1 -Edition terminal
pwsh -File scripts/build-dist.ps1 -Edition desktop
pwsh -File scripts/build-dist.ps1 -Edition both
```

默认 `both`。产物在 `dist/`：终端完整包 `lagless-terminal-*.zip`、完整桌面包 `lagless-*.zip`；发布资产 `lagless-terminal.jar` 为独立终端，`lagless.jar` 为桌面版，`lagless-core.jar` 为集成库。发布时应同时上传两种可执行 JAR，更新器会按当前安装类型选择。

终端版只下载 `lagless-terminal.jar`，没有该资产时保持现有版本；不回退到图形客户端。下载、回滚均校验 JAR 类型，防止用 GUI 包覆盖终端版。两种类型的后台更新检查时间戳也分别保存。旧 JAR 没有类型字段时，会根据是否包含图形客户端代码判断类型，早期纯 CLI 同样保持终端版；旧完整客户端仍可使用 `lagless.jar` 更新。

## 兼容性细节

本地编辑不依赖远端回显；全屏程序中的预回显则需要能识别输入位置，目前主要用于备用屏中的输入。远端程序的实际处理和画面更新仍受网络速度影响。

- 如果 shell 提示符与交互程序的提示符结尾相同，可用 `--prompt-regex` 指定 shell 提示符。未闭合引号进入续行提示符后，后续输入按键透传。
- 路径补全通过第二个 SSH 通道读取远端目录。服务器禁止多会话时，补全自动禁用；远端为 macOS 且无法跟踪当前目录时，相对路径以家目录为准。
- zsh autosuggestions 或 fish 语法高亮可能改变远端回显，导致命令行偶尔重复显示；命令仍会正常执行。

## 集成 core

`mvn -pl core -am install` 将 core 安装到本机 Maven 仓库，然后声明 `dev.lagless:lagless-core:0.2.0-rc.7` 依赖。core JAR 不内嵌第三方依赖，Maven 会传递引入 JLine 和 JSch。已有集成使用的 `dev.lagless` 包名保持不变。

通过 `Options`、`SshConnection.connect` 和 `TerminalSession` 可接入任意 JLine `Terminal`；本地输入与渲染交给调用方，低延迟处理统一保留在 core。GUI、CLI 两端直接使用同一 `TerminalSession`，不另写一套预测/补全逻辑。认证通过 `SshConnection.Prompter` 交给前端处理。

core 的全部回归在 `java.desktop` 模块被排除的 JVM 中运行，另有本机真实 SSH 测试验证 600ms 回显延迟时的本地行编辑和命令拒绝；终端构建在打包后检查真实独立 JAR，并以禁用图形模块的 Java 进程验证入口。图形客户端仍运行自己的集成测试。这些检查随三平台 CI 执行，当前本机实际验证平台为 Windows。
