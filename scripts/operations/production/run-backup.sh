#!/usr/bin/env bash
set -Eeuo pipefail
exec 8>/run/lock/cane-production-backup.lock
flock -w 60 8
type=diff
if [[ $(TZ=Asia/Ho_Chi_Minh date +%u) == 7 ]]; then type=full; fi
exec docker exec --user postgres cane-production-postgres-1 pgbackrest --stanza=cane --log-level-console=info --type="$type" backup
