#!/usr/bin/env bash
set -euo pipefail

archive_name='graalvm-jdk-25.0.5_linux-x64_bin.tar.gz'
archive_url="https://gds.oracle.com/download/graal/25/archive/${archive_name}"
expected_sha256='2ae8b03dfcb0a5bbcb55ffd122e8ffee24e3afce04bccc82932789acd005d1e1'
install_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/lagless-graalvm"
archive="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/${archive_name}"

curl --fail --location --retry 3 --output "$archive" "$archive_url"
printf '%s  %s\n' "$expected_sha256" "$archive" | sha256sum --check --status
mkdir -p "$install_root"
tar -xzf "$archive" --no-same-owner --directory "$install_root"
graal_home="$(find "$install_root" -mindepth 1 -maxdepth 1 -type d -name 'graalvm-jdk-25.0.5*' -print -quit)"
test -n "$graal_home"
grep -Fx 'JAVA_VERSION="25.0.5"' "$graal_home/release"
grep -Fx 'GRAALVM_VERSION="25.0.5"' "$graal_home/release"

echo "JAVA_HOME=$graal_home" >> "$GITHUB_ENV"
echo "$graal_home/bin" >> "$GITHUB_PATH"
echo "Verified Oracle GraalVM 25.0.5; Linux archive SHA-256 $expected_sha256"
