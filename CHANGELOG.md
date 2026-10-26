# Release history

The public repository starts from a sanitized source snapshot; older releases remain in the private historical archive. This page groups the user-facing changes across the small release candidates. Detailed notes remain under [`docs/releases/`](docs/releases/).

## 0.2 desktop preview — current: [v0.2.0-rc.8.17](https://github.com/a1473838623/lagless-ssh/releases/tag/v0.2.0-rc.8.17)

The 0.2 line is a **pre-release**. It adds a cross-platform desktop workspace while retaining the independent core and terminal-only edition. Recent release candidates focus on:

- Local and right-side command editing, multiple sessions, connection profiles, SFTP transfers, persistent history, and in-app log viewing.
- Windows GraalVM Native Image distribution, plus bundled-runtime desktop and terminal ZIPs for Windows, Linux, and macOS where published.
- Rendering and input fixes for interactive programs, Chinese input, clipboard use, scrollback, and auxiliary views.
- Optional AI assistant, output mirror, plugin support, encrypted backup/restore, update and rollback.
- Window resizes no longer close the terminal edition, and folder transfers count faster with visible progress, in [rc.8.17](docs/releases/0.2.0-rc.8.17.md) (Windows packages only).
- Aligned menus, pop-ups that close when switching apps, a rollback that names its target version, and sturdier background sessions in [rc.8.16](docs/releases/0.2.0-rc.8.16.md).
- A cleaner desktop layout and more reliable creation of background sessions on systemd servers in [rc.8.15](docs/releases/0.2.0-rc.8.15.md).
- Improved full-screen input, Chinese preedit, wrapped-line copying, English UI with a first-run language choice, reliable native first start, and light/dark themes in [rc.8.14](docs/releases/0.2.0-rc.8.14.md).
- Optional tmux-backed background sessions, which can import output produced while Lagless was closed, in rc.8.14.
- Separation of ordinary terminal history, full SSH input/output history, and application logs in [rc.8.13](docs/releases/0.2.0-rc.8.13.md).

For exact behavior and caveats, read the [desktop guide](docs/desktop.md) and the release's own notes. Release assets are tied to their tagged source commits; documentation-only changes after a tag do not imply a new binary build.

## 0.1 terminal line — historical

The 0.1 line introduced the terminal-first SSH experience: local command editing, local history, asynchronous completion, automatic pass-through for interactive programs, and SFTP commands. The current 0.2 preview continues to offer a terminal-only package. Earlier 0.1 releases remain in the private historical archive and are not republished with the clean public source history.

## Release policy

- Normal feature releases get a new versioned release with checksums.
- A severe defect in the current pre-release may be fixed in place: rebuild every asset from the latest source, move that release tag to the rebuilt commit, replace its assets and checksums, and record the correction in its notes. Existing downloads must be replaced manually when the application cannot start or cannot detect a same-version rebuild.
- Do not force-push `main`; keep the source history intact.
- The private historical archive keeps its original commits, tags, and binaries. The public repository starts from a sanitized snapshot and publishes the current preview assets; old tags are not recreated against unrelated source.
- GitHub Actions are paused to limit usage. Release notes state which checks actually ran; a docs commit is not presented as a binary build.
