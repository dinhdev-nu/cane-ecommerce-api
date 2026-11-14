#!/bin/sh
# Runs only on an isolated Linux test host with systemd; never starts the real JAR.
set -eu
test "$(id -u)" -eq 0 || { echo 'Run this check as root on an isolated test host.' >&2; exit 1; }
test "$(cat /proc/1/comm)" = systemd || { echo 'A running systemd manager is required.' >&2; exit 1; }
root=$(mktemp -d /run/cane-systemd-check-XXXXXXXX)
name=$(basename "$root")
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")/staging" && pwd)
cleanup() {
    for mode in api worker; do
        unit="$name-$mode.service"
        systemctl stop "$unit" 2>/dev/null || :
        rm -f -- "/run/systemd/system/$unit"
        rm -f -- "/run/systemd/system/$unit.d/check.conf"
        rmdir -- "/run/systemd/system/$unit.d" 2>/dev/null || :
        systemctl reset-failed "$unit" 2>/dev/null || :
    done
    systemctl daemon-reload
    case "$root" in /run/cane-systemd-check-*) rm -rf -- "$root" ;; esac
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
cat > "$root/probe.sh" <<'PROBE'
#!/bin/sh
set -eu
printf 'attempt\n' >> attempts
test -f dependency-ready || exit 1
touch recovered
exec /bin/sleep infinity
PROBE

for mode in api worker; do
    unit="$name-$mode.service"
    work="$root/$mode"
    mkdir "$work" "/run/systemd/system/$unit.d"
    cp "$source_dir/cane-$mode.service" "/run/systemd/system/$unit"
    # Preserve restart policy/start limits. Only shorten the retry delay and replace
    # machine-specific paths/credentials with a deterministic failing dependency.
    cat > "/run/systemd/system/$unit.d/check.conf" <<OVERRIDE
[Service]
User=root
Group=root
WorkingDirectory=$work
EnvironmentFile=
ExecStart=
ExecStart=/bin/sh $root/probe.sh
RestartSec=1
ReadWritePaths=$root
OVERRIDE
    systemctl daemon-reload
    systemd-analyze verify "/run/systemd/system/$unit"
    systemctl start "$unit"
    attempts=0
    for tick in $(seq 1 20); do
        if test -f "$work/attempts"; then attempts=$(wc -l < "$work/attempts"); fi
        test "$attempts" -lt 5 || break
        sleep 1
    done
    test "$attempts" -ge 5 || { echo "$mode stopped retrying before recovery." >&2; exit 1; }
    touch "$work/dependency-ready"
    for tick in $(seq 1 10); do
        test ! -f "$work/recovered" || break
        sleep 1
    done
    test -f "$work/recovered"
    systemctl is-active --quiet "$unit"
    systemctl stop "$unit"
    sleep 2
    test "$(systemctl show "$unit" --property=ActiveState --value)" = inactive
    echo "$mode: retried beyond three failures, recovered, and stayed stopped after manual stop."
done
