# GraalVM 原生桌面版

## rc.8.9 构建与验收记录

Windows x64 原生包使用 Oracle GraalVM 25.0.5 Native Image（官方归档 SHA-256：`192d0328f9ce9c19f1e73278d638c06635e2d5ff744f11ba1c37b23f5db402d2`）。Oracle 安全公告将此更新列为 JDK 25.0.5.0.2；GraalVM 归档中的 `JAVA_VERSION`/`GRAALVM_VERSION` 显示为 `25.0.5`，安装脚本会固定校验官方归档并验证版本。JVM/终端运行包则由独立 Oracle JDK 25.0.5.0.2 构建。

rc.8.9 已运行实际原生验证：可搜索 PDF 生成及文本提取、原生窗口/弹窗、SSH 认证与跳板/SFTP、12,000 行历史回滚、剪贴板、编辑区调整不向 SSH 发送空行、加密备份、自动重连、原生更新替换和回滚。`NativeVerification` 会将可搜索 PDF 测试写进临时目录后从附件读取路径提取文字；PDF 不含可提取文本时会提示先 OCR。原生编译会将 SSH 通道所需的 `String` 序列化注册写入 GraalVM 25 `reachability-metadata.json`。

兼容模式和辅助视图的空行问题按输入事件生命周期修复：菜单回车的动作延迟到当前按键事件派发完成后执行；兼容模式切换也不再主动把焦点抢回终端；没有吞掉 Enter 或过滤远端空命令。AI 设置、插件管理和文件管理窗口会以调用方窗口为 owner，避免创建无主窗口。

## rc.8.2 文字排版修复

JDK 25 的 `sun.font.HBShaper` 使用 FFM 调用 HarfBuzz，并动态查找 Java 回调方法。`desktop/src/main/resources/META-INF/native-image/dev.lagless/desktop/reachability-metadata.json` 注册其 5 种 downcall 和 5 种 upcall 签名；原生构建脚本同时注册其反射方法。只注册 JNI 不能覆盖此路径，否则输入法组合文字等排版场景会使 HBShaper 初始化失败，并导致后续重绘反复报错、黑屏。签名依据当前构建 JDK 的 `lib/src.zip`，升级 JDK 时需一并核对；参考 [官方 FFM 文档](https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/)。本版仅编译打包，未启动 GUI 验收。

## 2026-10-22 安全构建状态

CVE-2026-91403 的官方 JDK 修复包为 Oracle JDK 25.0.5.0.2，见 [Oracle 修复说明](https://ops.java/emudhra)及[官方下载页](https://www.oracle.com/java/technologies/downloads/)。rc.8 JVM 桌面和终端包使用该原版 JDK。Windows 原生版采用另一条官方应急路径：在隔离副本 Oracle GraalVM 25.0.4+7.1 中应用上述官方修复包原样提供的 `lib/security/blocked.certs`，再重新生成 EXE。原生编译检查确认 JDK 内存中的全部 86 个指纹与官方文件相同，并将其在构建时初始化、嵌入 EXE。详见 [证书补丁来源与范围](https://github.com/a1473838623/lagless-ssh/tree/main/security/cve-2026-91403)。下面的旧 GraalVM 25+37.1 验收记录不能作为安全修复依据。

发行脚本默认要求 Oracle JDK/GraalVM 的基础 JDK 至少为 25.0.5.0.2（同属 JDK 25）。原生构建的 `-UseOfficialCertificateMitigation` 只允许已校验的 Windows x64 Oracle GraalVM 25.0.4 配合固定 SHA-256 的官方证书文件，不放开其他旧 JDK。`build-jdk.json` 如实标出 `fullJdkSecurityBaselineSatisfied: false` 和已应用的证书缓解措施；这不代表基础编译器已包含其余后续安全更新。原生重打包核对原 EXE 的构建记录；把文件放在旧 EXE 旁或更新系统 Java，均不作为完成本次修复的依据。

当前 Windows 原生包构建（只编译、不运行测试或 GUI）：

```powershell
. ./scripts/prepare-native-jdk.ps1
./scripts/build-native.ps1 -SkipTests -ReuseMetadata -UseOfficialCertificateMitigation
```

包内 `security/native-blocklist-build.json` 是原生编译阶段的捕获报告，`security/blocked.certs`、`policy.json` 和说明文件供审计，不依赖这些旁置文件来让旧程序动态生效。完整修复版 GraalVM 可用后应改回完整工具链更新。

桌面程序使用与终端版相同的 `core`，编译成真正的 Native Image。Windows 用户解压后直接双击 `Lagless.exe`，无需 Java、JRE、JAR 或浏览器。终端版仍可独立构建和安装，详情见 [core](core.md)。

## 当前验收范围

本轮 DirectWrite / Direct2D、全屏交互记录开关、中文输入法定位、字体间距和交互程序滚轮（真实鼠标协议、按帧合并、变化行处理及绘制缓存）修改按用户要求只编译打包，未运行测试或启动 GUI。以下自动化验收为此前版本记录，不能作为这些新增修改的验收结论。

菜单点击无响应的日志报错为 `InternalError: couldn't create component peer`。Windows AWT 在创建弹出窗口时通过 JNI 查找 `javax.swing.Popup$HeavyWeightWindow`；构建已补齐该类型及相关菜单窗口的注册，并注册 FlatLaf 创建重量级弹窗使用的 `PopupFactory` 方法。菜单关闭额外的透明阴影窗口，保留覆盖原生终端所需的普通弹窗。原生终端还显式分派已打开菜单的方向键、回车，以及工作区的 Ctrl+Shift+L/E/H/N 快捷键；普通 SSH 控制键保持透传。此修复同样仅编译打包，未进行本次 GUI 验收。

本机 Windows x64：Oracle GraalVM 25+37.1 / Native Image 25.0.0，Visual Studio 2022 / MSVC 19.44。已实际编译、运行完整客户端，并通过自动化原生验收。压缩包约 28 MB，解压约 68 MB；具体以当前构建产物为准。

- Swing / FlatLaf 图形工作台、中文、下拉菜单、按需连接库、底部/右侧本地编辑、镜像换行、前台日志查看及导出、账号 JSON / OpenSSH / PuTTY / Xshell 互导。
- 真实 SSH 密码及加密私钥认证、跳板配置下拉选择、SFTP 文件及文件夹往返、双栏文件管理；模拟远端每条命令等待 600 毫秒，本地编辑须在 500 毫秒内可见，而且回车前远端没有收到命令。
- 自动重连保留草稿、不自动重放；危险命令拒绝后不写入 SSH。
- 原生子进程插件 JSON-lines 通信、AI助手 HTTP 接口、加密备份与恢复。
- 完整原生包的校验、退出后更新助手、上一版本回滚。更新和回滚在隔离安装副本中实际执行。
- 真实原生弹窗及文件选择器：添加保存、编辑保存、导入导出文件、删除测试连接、AI 设置、插件、协作、备份入口、更新窗口、草稿选择器。验收会执行实际按钮动作及模态事件循环，确认结果落盘，不能只凭离屏绘制判定可交互。连接库还会连续打开三次，检查无需鼠标/键盘输入就完成首帧绘制和列表布局；`closable-views` 检查关闭并重新打开视图保留内容且 SSH 继续工作。
- 首次读取终端版保存的 SSH 配置；手动刷新及去重。Windows 构建完整注册窗口回调与 `java.awt.Point` 等原生返回值类型，避免主窗口可用而弹窗定位失败。EXE 含 PerMonitorV2 DPI 声明，界面使用系统中文字体及抗锯齿。

新增验收项 `sftp-directories`、`local-editor-layouts`、`file-browser-window`、`software-events` 分别检查目录传输、两种编辑布局与多行输入安全、文件管理窗口、独立连接事件日志。`local-command-log` 验证本地命令日志；`resizable-local-editor` 验证底部默认一行、隐藏滚动条与右下角高度调节；`corner-drag-no-input` 检查拖动不提交换行或命令。编辑器提交检查还验证已提交行立即清除，未发送行保留。

历史翻页还会实际绘制不同页面及缩放、非抗锯齿图形路径（`history-scrollbar-paint`），检查拖动滚动条后文字跟随变化。Windows 元数据包含 `sun.java2d.pipe` 的原生字段，避免软件绘制历史提示条时初始化 `ShapeSpanIterator` 失败而反复黑屏。

新增原生验收还覆盖：`clipboard-shortcuts-native`（剪切、复制、粘贴和 Windows 剪贴板所需的 RMI/字符串反射初始化，使用独立剪贴板，不覆盖用户内容）、`terminal-paste`、`editor-wrap`、`download-queue-progress`、`ctrl-chords-alt-passthrough`、`disk-scrollback-rows` 和 `fullscreen-history`。下载队列在原生窗口中实际渲染进度条，关闭文件窗口后继续；同时检查传输期间的本地输入仍不等待远端回显。历史验收写入并回读 12000 行，覆盖内存缓存之外的早期记录。

报告和实际原生界面截图在 `desktop/target/native/native-report.json` 与 `native-desktop.png`。`native: true` 表示验收运行于生成的二进制；JVM 预检另存为 `jvm-report.json`。

`dialog-actions` 列出历史验收通过的弹窗与数据操作；`native-window-events` 检查真实显示、移动、缩放和关闭处理。rc.8.2 起 GUI 未捕获异常写入本次软件运行日志 `~/.lagless/desktop/events/*-application-*.log`，与全部窗口的操作和连接事件统一归档；只有主日志不可写时尝试 `native-errors.log`。软件日志查看入口只列按程序运行归档的记录。

Linux 的原生编译及相同验收已加入 CI，须以该平台运行结果为准；本机没有 Linux/macOS 原生验收结果。macOS 可继续使用带精简 JVM 的桌面包，当前不承诺 Oracle GraalVM 的 macOS Swing 原生包。不能把脚本或 CI 配置存在当作这些平台已验证。

## 构建

需要 PowerShell 7、Maven、GraalVM 25 Native Image，以及平台 C/C++ 工具链。Windows 当前环境已具备；Linux 还需 X11、字体及相关开发库，见 `.github/workflows/native.yml`。

```powershell
pwsh -NoProfile -File scripts/build-native.ps1
```

脚本执行 Maven 全量验证，启动仅监听本机的 SSH/AI 测试服务，在 JVM 上采集反射/JNI/资源元数据，再编译完整桌面入口，执行原生验收及更新回滚测试，最后生成：

```text
dist/lagless-desktop-native-0.2.0-rc.8.18-windows-x64.zip
desktop/target/native/bundle/Lagless.exe
```

采集覆盖已知桌面和 SSH 路径；JSch 随附的 JCA 算法构造器另有完整注册，不只依赖测试时协商到的算法。变更界面、JDK 或依赖后应重新采集并验收。`-ReuseMetadata` 仅用于同环境迭代；`-SkipCompilation` 用于复验已有二进制和重新打包。CI 使用 `-SkipUnitTests`，跳过与桌面工作流重复的 Maven 单元测试，仍执行 JVM 元数据采集、原生 SSH/界面验收及更新回滚检查。`-SkipTests -ReuseMetadata` 仅编译打包，复用已有元数据，不运行 Maven 测试、界面/SSH 验收或更新回滚检查；该包没有本次运行的验收结论。

Linux 无显示器构建可用 `xvfb-run -a pwsh -NoProfile -File scripts/build-native.ps1`。每个平台/架构须分别构建，不能交叉复制 Windows 二进制。

包内保留 AWT、字体等必要原生库与 `lib/fontconfig.bfc`，必须整体保留。`java.dll`、`jvm.dll` 是 Native Image 的小型 JNI shim，不是 JVM。没有 `java.exe`、Java 模块运行时或客户端 JAR。CPU 使用 compatibility 目标，避免要求构建机器同等级的指令集。

Windows x64 还带 `lagless-render.dll`，通过 JAWT 取得窗口句柄，使用 Windows SDK 的 DirectWrite / Direct2D 绘制终端及日志正文。它是本项目的原生 C++ 模块，静态链接 C++ 运行库，无需额外安装。`scripts/build-native-renderer.ps1` 使用 Visual Studio C++ 工具链构建；原生 EXE 包和带 JVM 的桌面包均自动携带该库，CLI 包不包含它。DLL 同样纳入更新、备份和校验清单，不能只复制 EXE。可以在「视图 → 显示渲染…」查看当前后端及回退原因。 原生画布创建时关闭 AWT Canvas 的绘制前背景擦除，并使用 Direct2D 的 [RETAIN_CONTENTS](https://learn.microsoft.com/en-us/windows/win32/api/d2d1/ne-d2d1-d2d1_present_options) 保留已呈现帧；每次输出仍绘制完整画面，不复制滚动像素。

## 更新、回滚与备份

界面「更新」根据平台选择 `lagless-desktop-native-<版本>-<平台>-<架构>.zip`；仓库发布时请保持该命名。也支持「导入原生更新包」。Windows 安装目录受保护时需要允许 UAC；其他平台须具备安装目录写权限。

更新先验证平台、文件清单、大小、SHA-256 和路径，再暂存。安装目录不可写（例如 Program Files）时，下载和助手暂存到用户目录 `.lagless/desktop/updates/<安装目录标识>`；安装助手通过 Windows 标准 UAC 请求权限，取消时不修改程序。助手使用独立的完整运行库，确认启动后界面才显示就绪。点击「重启并应用更新」会保存草稿和日志、关闭窗口，待旧进程退出后替换当前安装目录中的整包并重新打开；重新打开的客户端沿用原用户权限。安装期间再次启动会转交等待流程，避免旧 EXE 再次占用文件。保留 `.updates/previous.zip` 用于回滚；结果写入控制目录的 `result.json`，更新窗口显示实际安装目录、上次结果与日志位置。外部程序占用文件仍可能导致失败，此时记录错误并尝试恢复原文件。rc4 及更早版本自身的旧安装流程无法由新包修复，首次迁移可能仍需手动完整替换。

也可调用 `Lagless.exe --install-update <完整包.zip>` 或 `--rollback`。这是替换程序包，不会自动运行更新包提供的脚本。清单校验检测损坏，不是发布者数字签名；只导入自己信任的包。在线来源沿用配置的 GitHub 发布仓库与 HTTPS。

完整加密备份包含原生程序及依赖库，恢复时可取消「同时恢复相同类型的应用程序包」，只恢复账号、草稿、日志等数据。JVM/原生版之间可以恢复数据；程序包只恢复相同类型、相同平台。原生应用恢复同样在当前程序退出后安装。

## 插件与边界

原生版支持 `.plugin.json` 独立进程插件，详见 [插件协议](plugins.md)。插件可以自身就是原生程序；客户端不需要额外 Java。现有 JAR 插件继续由 JVM 桌面版支持，原生版遇到 JAR 会给出明确提示。

Native Image 不改变 SSH 的远端会话生命周期：TCP 完全断开后，单靠客户端无法保证远程 PTY 无损恢复；可复用服务器已有 tmux。跳板向目标隐藏来源 IP，但不能对跳板或所有网络节点匿名。

参考：[GraalVM Reachability Metadata](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/)、[Native Image Compatibility](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/Compatibility/)。
