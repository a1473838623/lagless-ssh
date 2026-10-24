# Lagless 桌面插件

两种客户端都支持独立进程插件。JVM 桌面版另支持 Java JAR 插件；原生版不会动态加载新 JAR。「工具 → 插件」可安装/导入、导出、启用、禁用、配置和卸载插件，也可离线查看开发指南。管理操作会立即重新加载插件；已有待处理请求可能因此失败。插件拥有当前用户权限；只安装自己信任的扩展。

插件根目录为 `~/.lagless/plugins/`。单个 `.plugin.json` 或 JVM JAR 可直接安装；原生进程插件推荐 ZIP 包：ZIP 根目录放一个 `.plugin.json` 和它引用的可执行文件，描述文件里的相对命令路径按所在目录解析。例如 ZIP 中含 `example.plugin.json` 和 `plugin.exe`，描述文件的 `command` 为 `["plugin.exe"]`。ZIP 最多 256 项、解压后最多 100 MiB；不接受跳出安装目录的路径。导出目录插件会得到 ZIP；单文件插件导出原文件。配置保存在插件旁的 `.config.json`，导出 ZIP 会包含配置。把插件配置中的秘密只交给可信代码。

## 独立进程协议（原生版与 JVM 版）

创建 `example.plugin.json`，把可执行文件路径改成实际路径；相对路径以描述文件目录为基准。参数直接传给进程，不经过 shell。示例可使用 Lagless 自带的纯原生协议演示入口，不需 Java：

```json
{"version":1,"name":"本地命令示例","command":["C:/Tools/Lagless/Lagless.exe","--plugin-example"],"timeout-ms":30000,"output-events":true}
```

插件按需启动，stdin/stdout 使用 UTF-8 JSON-lines。stdout 只能写协议响应；stderr 被持续读取并丢弃，插件自己的持久日志请另存文件。输出镜像事件不需要响应：

```json
{"version":1,"id":0,"type":"output","session":"session-id","text":"远程文本"}
```

主动辅助请求及对应响应：

```json
{"version":1,"id":1,"type":"assist","action":"translate","text":"text","context":"context"}
{"version":1,"id":1,"ok":true,"text":"翻译或命令草稿"}
```

错误响应为 `{"version":1,"id":1,"ok":false,"error":"原因"}`。action 为 `ask`、`command`、`complete`、`translate`。id 关联响应，允许乱序返回；无执行接口。超时默认 30 秒，可设 100–120000 毫秒；最多 32 个待响应请求，传输队列 128 项，单行最多 1 MiB。过载输出事件会计数省略，原始会话日志独立保留。关闭或禁用插件会终止子进程。插件进程异常退出后可在插件管理中禁用再启用以重新加载。

「配置」接受 JSON 对象，例如 `{"language":"zh-CN"}`。描述文件内的 `config` 对象与用户配置合并，同名键由用户配置覆盖；合并后由 `LAGLESS_PLUGIN_CONFIG` 环境变量传给子进程。插件应自行校验配置并避免把密钥打印到 stdout/stderr。翻译请求的 `context` 中会有用户选择的源语言与目标语言。

插件的响应进入本地可编辑 AI 窗口，需用户审阅后发送。示例只返回 `git status` 或 `ls -lah` 等固定草稿，不是通用翻译器。通用翻译可使用内置 AI 服务或自行实现此协议。

## Java SPI（仅 JVM 桌面版）

实现 `dev.lagless.desktop.LaglessPlugin`，将实现类完整名称写入 JAR 内的 `META-INF/services/dev.lagless.desktop.LaglessPlugin`。

`onOutput(sessionId,text)` 收到原始远程 UTF-8 文本（含 ANSI，可能是半个控制序列），不包含客户端认证密码、本地逐键输入。事件由单独后台线程按队列顺序调用；插件应快速返回。队列最多 128 批，过载会省略插件事件并计数；内置日志独立保存全量输出。单个慢插件会拖慢其他插件事件，不会阻塞终端收发。需要可靠副本时使用会话日志导出，不依赖尽力而为的插件事件流。

`assist(action,text,context)` 仅在用户主动请求、审阅输入后在后台调用。action 为 `ask`、`command`、`complete`、`translate`。返回文本进入可编辑 AI 窗口；接口不提供命令执行句柄。Java 插件本身不是沙箱，不应自行执行未审阅的命令或传输凭据。内置输出镜像可直接从 GUI 分离为本地编辑窗，无需安装插件。

可选覆盖 `configure(Map<String,Object> settings)` 接收用户编辑的 JSON 配置；默认实现什么也不做。禁用或配置变更会重新加载 JAR 实例与类加载器；插件应在 `close()` 释放自己的资源。

## 可构建示例

示例源码和 ServiceLoader 描述文件在 `examples/plugins/`，它提供本地 shell 用法提示 action，演示如何在不调用网络的情况下补全/解释命令。

先 `mvn package`，然后在 PowerShell：

```powershell
New-Item -ItemType Directory -Force target/example-plugin | Out-Null
javac --release 21 -encoding UTF-8 -cp desktop/target/lagless-desktop-standalone.jar -d target/example-plugin examples/plugins/src/example/LocalHelpPlugin.java
Copy-Item examples/plugins/resources/META-INF target/example-plugin -Recurse -Force
jar --create --file target/local-help-plugin.jar -C target/example-plugin .
```

Linux/macOS：

```sh
mkdir -p target/example-plugin
javac --release 21 -encoding UTF-8 -cp desktop/target/lagless-desktop-standalone.jar -d target/example-plugin examples/plugins/src/example/LocalHelpPlugin.java
cp -R examples/plugins/resources/META-INF target/example-plugin/
jar --create --file target/local-help-plugin.jar -C target/example-plugin .
```

自行从插件管理界面安装生成的 JAR。示例不是通用翻译器；内置 AI助手使用用户配置的服务。SPI 不保证未来不兼容变更，应用升级后应重新编译并核验第三方插件。

界面主题使用 [FlatLaf](https://www.formdev.com/flatlaf/)，嵌入式测试服务参考 [Apache MINA SSHD server setup](https://github.com/apache/mina-sshd/blob/master/docs/server-setup.md)。二者均不要求在被连接的服务器安装任何东西；MINA 只属于测试依赖。
