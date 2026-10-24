# Lagless SSH

**Keep typing when the network cannot keep up.** Lagless brings local command editing, local history, and asynchronous completion to SSH over slow or unstable links. Use it inside your existing terminal, or choose the desktop workspace. Nothing needs to be installed on the SSH server.

[简体中文](README.zh-CN.md) · [Download](https://github.com/a1473838623/lagless-ssh/releases/tag/v0.2.0-rc.8.13) · [Desktop guide](docs/desktop.md) · [Terminal/core guide](docs/core.md) · [Plugin guide](docs/plugins.md)

![Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue) ![Java 21+](https://img.shields.io/badge/source-Java%2021%2B-0b7f70) ![Desktop preview](https://img.shields.io/badge/desktop-0.2.0%20preview-orange)

![Lagless desktop workspace with sample terminal output and a local command editor](docs/media/local-editor.png)

*The gallery is rendered from the actual desktop UI with sample `example.invalid` hosts and sample output. It is not a live SSH session.*

## Why Lagless?

On a high latency connection, a conventional SSH shell waits for a round trip before showing each keystroke. Lagless recognizes a shell prompt and edits the command **locally**. Your cursor, deletions, history search, and draft remain responsive; pressing Enter sends the completed line. Completion runs on a second SSH channel without blocking the editor. When a program needs direct input, Lagless passes keys through.

The **core remains independent**. You can install only the terminal edition and run `lagless user@host` in Windows Terminal or another terminal. The desktop app uses that same core and adds connection management, parallel sessions, SFTP, logs, and optional tools.

| | Terminal edition | Desktop edition |
| --- | --- | --- |
| Local shell editing and history | Yes | Yes |
| Asynchronous completion | Yes | Yes |
| Interactive programs | Pass through, with local prediction where supported | Terminal view and configurable wheel behavior |
| SSH jump hosts and SFTP | CLI commands | Connection profiles and file manager |
| Multiple sessions, local editor, logs, AI and plugins | — | Yes |
| Server installation | None | None |

## See it in action

### Type into a local draft

The input below is drawn locally while the SSH session waits. The desktop editor can sit below the terminal, to its right, or in its own window. The terminal edition provides the same latency sensitive core in your existing terminal.

![Typing a command into the local editor](docs/media/local-editing.gif)

### Explore the workspace

![Tour of the terminal, local editor, output mirror and AI assistant views](docs/media/workspace-tour.gif)

| Connection picker | Output mirror |
| --- | --- |
| ![Connection picker](docs/media/welcome.png) | ![Editable output mirror](docs/media/output-mirror.png) |

The [full gallery](docs/gallery.md) includes larger screenshots and explains which parts use sample data. A [share kit](docs/launch-kit.md) has short English and Chinese introductions and a social preview image.

## Download and start

**Current release: [v0.2.0-rc.8.13](https://github.com/a1473838623/lagless-ssh/releases/tag/v0.2.0-rc.8.13), a pre-release.** It contains both desktop and terminal-only downloads. A stable public 0.2 release has not been declared yet.

| Your setup | Asset to download | Start |
| --- | --- | --- |
| Windows x64, native desktop | `lagless-desktop-native-0.2.0-rc.8.13-windows-x64.zip` | Extract the full ZIP; run `Lagless.exe` |
| Linux x64, native desktop | `lagless-desktop-native-0.2.0-rc.8.13-linux-x64.zip` | Extract the full ZIP; run the bundled `Lagless` executable |
| Windows/Linux/macOS, desktop with bundled runtime | `lagless-0.2.0-rc.8.13-<platform>.zip` | Extract and run `lagless.cmd gui` on Windows or `./lagless gui` elsewhere |
| Terminal only, bundled runtime | `lagless-terminal-0.2.0-rc.8.13-<platform>.zip` | Extract and run `lagless.cmd user@host` on Windows or `./lagless user@host` elsewhere |
| Java 21+ already installed | `lagless.jar` (desktop) or `lagless-terminal.jar` | See [install details](docs/core.md) |

Use the asset matching your platform and architecture. Release ZIPs include checksums in `SHA256SUMS.txt`. The Windows native package does not require a separate Java installation.

```bash
# SSH target, or a Host alias from ~/.ssh/config
lagless user@example.com
lagless myserver

# Browse configured hosts
lagless
```

Lagless reads your existing `~/.ssh/config`, keys, and known hosts. The desktop connection library can import terminal profiles; it does not need an empty second account list. Passwords and unknown host fingerprints are requested when connecting.

## What the desktop adds

- **Workspace:** concurrent SSH tabs, multiple windows, a connection picker, and a local editor that preserves drafts.
- **Files and history:** two pane SFTP browser with a transfer queue, progress, file and folder operations; persistent terminal history, full SSH transcript, and application run log with an in-app viewer and export.
- **Resilience:** reconnect with backoff and preserve local drafts. If the server already has `tmux`, Lagless can reattach to it after a disconnect. An SSH client alone cannot preserve a remote PTY after the connection is lost.
- **Jump hosts:** use another SSH profile as a background jump connection. The target sees the jump host's address; this does not make the user anonymous to the jump host or network provider.
- **Optional assistance:** editable output mirror, AI assistant and translation, command review, and local plugins. AI requests require an explicitly configured service; [plugin development guide](docs/plugins.md).
- **Portability:** profile import/export, encrypted backups, restore, and update/rollback controls.

See the [desktop feature guide and limitations](docs/desktop.md). Some desktop behavior remains under active validation, so the 0.2 line is marked pre-release.

## Build from source

Requires JDK 21+ and Maven. Build the terminal edition alone, or both editions:

```bash
mvn -pl cli -am -DskipTests package
mvn -DskipTests package
```

The outputs are `cli/target/lagless-cli-standalone.jar` and `desktop/target/lagless-desktop-standalone.jar`. Platform bundles and Native Image builds are described in [desktop packaging](docs/native-image.md). GitHub Actions are currently paused to conserve the project's Actions quota; a passing badge would be misleading, so this README does not show one.

## Help this project grow

If Lagless makes a slow connection usable, **[star the repository](https://github.com/a1473838623/lagless-ssh)** so other developers can find it. The most useful next step is a reproducible issue: tell us your operating system, terminal, remote shell, latency, and the exact interaction that felt slow or broke. Screenshots and short recordings are welcome after removing hostnames, credentials, and session secrets.

Share the project with people who administer servers over distant or unreliable links. Contributions and documentation fixes are welcome; start with [CONTRIBUTING.md](CONTRIBUTING.md). See the [release history](CHANGELOG.md) for the current desktop preview and the stable terminal line.

## License

Lagless SSH is licensed under [Apache License 2.0](LICENSE). Third-party dependencies retain their own licenses.
