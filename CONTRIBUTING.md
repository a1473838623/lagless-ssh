# Contributing to Lagless SSH

Thanks for helping people use SSH over difficult networks. Small, reproducible fixes and documentation improvements are welcome.

## Report an issue

Include the Lagless version and edition, local operating system and terminal, remote operating system and shell, and steps to reproduce. For lag or rendering issues, include approximate round-trip time and whether the remote program uses a full-screen terminal. Remove hostnames, IP addresses, credentials, private keys, and sensitive terminal output from screenshots and logs. Application logs and full SSH transcripts may contain session content.

## Work on the source

The repository has three Maven modules: `core` contains the latency-sensitive SSH implementation, `cli` is the standalone terminal frontend, and `desktop` is optional. Keep core usable without installing the desktop app.

Build the terminal edition with `mvn -pl cli -am -DskipTests package`, or all modules with `mvn -DskipTests package`. Run focused local checks for the part you change and describe them in your pull request. GitHub Actions are currently paused; maintainers may ask for additional local verification when needed.

Use descriptive commits that explain a change, such as `fix: preserve terminal selection while scrolling`. Do not rewrite published tags or force-push shared history. Update user documentation when behavior changes, and use sample data in screenshots.

Contributions submitted for inclusion are licensed under the project's [Apache License 2.0](LICENSE), unless you state otherwise as described by that license. Keep third-party code under its original compatible terms and identify its source.
