# Share Lagless / 分享 Lagless

Use the [social preview image](media/social-preview.png), [local editing GIF](media/local-editing.gif), and [workspace tour](media/workspace-tour.gif) when introducing the project. All UI media uses sample data. Please do not describe the images as live network benchmarks.

## Short English introduction

Lagless SSH keeps shell editing responsive over slow or unstable links. It edits commands locally, keeps local history, and fetches completion asynchronously; interactive programs still receive direct input. Install only the terminal edition for your existing terminal, or use the desktop workspace for parallel sessions, SFTP, logs, and optional AI/plugins. Nothing is installed on the SSH server. The 0.2 desktop line is a pre-release: https://github.com/a1473838623/lagless-ssh

If it helps your workflow, a Star helps other remote developers find it. Reproducible bug reports are just as valuable.

## 中文简介

Lagless SSH 面向高延迟或不稳定网络：Shell 命令在本地编辑，历史在本地查，补全异步获取；交互程序仍可透传按键。可以只安装终端版，沿用现有终端，也可以用桌面工作区管理多个连接、SFTP、日志与可选插件。服务器不需要安装 Lagless。0.2 桌面版目前是预发布版：https://github.com/a1473838623/lagless-ssh

如果它改善了你的 SSH 体验，欢迎点 Star 帮助更多人发现；可复现的问题反馈同样重要。

## What to show next / 下一段演示

A future video should show the same real SSH task under a measured round-trip delay with ordinary SSH and Lagless side by side, including the latency value and capture method. That would demonstrate the core advantage more clearly than a UI-only animation. Do not claim zero network latency or lossless remote PTY recovery after a complete disconnect.
