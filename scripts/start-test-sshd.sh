#!/bin/sh
# Starts an unprivileged sshd on port 2222 for the end-to-end test.
# Needs no root: it runs as the current user and only accepts that user with
# the generated key. State lives in ~/.lagless-test. Run inside WSL or any Linux:
#   sh scripts/start-test-sshd.sh          start (or restart)
#   sh scripts/start-test-sshd.sh stop     stop
set -e
D="$HOME/.lagless-test"
PORT=2222
if [ "$1" = "stop" ]; then
    [ -f "$D/sshd.pid" ] && pkill -F "$D/sshd.pid" && echo "stopped" || echo "not running"
    exit 0
fi
mkdir -p "$D"; chmod 700 "$D"; cd "$D"
[ -f hostkey ] || ssh-keygen -q -t ed25519 -N "" -f hostkey
[ -f testkey ] || ssh-keygen -q -t ed25519 -N "" -f testkey
cp testkey.pub authorized_keys; chmod 600 authorized_keys
cat > sshd_config <<EOC
Port $PORT
ListenAddress 0.0.0.0
HostKey $D/hostkey
PidFile $D/sshd.pid
AuthorizedKeysFile $D/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
PubkeyAuthentication yes
StrictModes no
LogLevel VERBOSE
Subsystem sftp internal-sftp
EOC
[ -f sshd.pid ] && pkill -F sshd.pid 2>/dev/null || true
/usr/sbin/sshd -f "$D/sshd_config" -E "$D/sshd.log"
sleep 1
echo "sshd pid $(cat sshd.pid), port $PORT, user $(id -un)"
echo "private key: $D/testkey"
echo "run tests with:"
echo "  LAGLESS_TEST_HOST=localhost LAGLESS_TEST_PORT=$PORT LAGLESS_TEST_USER=$(id -un) LAGLESS_TEST_KEY=$D/testkey mvn test"
