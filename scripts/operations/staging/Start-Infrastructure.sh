#!/bin/sh
set -eu
fail() { echo 'Invalid staging infrastructure configuration; no deployment was started' >&2; exit 1; }
[ "$#" -eq 2 ] || fail
action=$1
env_file=$2
case "$action" in validate|check|start) ;; *) fail ;; esac
[ -f "$env_file" ] && [ -r "$env_file" ] || fail
seen_names=:
seen_values=:
count=0
cr=$(printf '\r')
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%"$cr"}
  case "$line" in ''|\#*) continue ;; esac
  case "$line" in
    CANE_STAGING_POSTGRES_ADMIN_PASSWORD=*|CANE_STAGING_MIGRATION_PASSWORD=*|CANE_STAGING_API_DB_PASSWORD=*|CANE_STAGING_WORKER_DB_PASSWORD=*|CANE_STAGING_REDIS_PASSWORD=*) ;;
    *) fail ;;
  esac
  name=${line%%=*}
  value=${line#*=}
  [ "${#value}" -eq 64 ] || fail
  case "$value" in *[!0-9a-f]*) fail ;; esac
  case "$seen_names" in *":$name:"*) fail ;; esac
  case "$seen_values" in *":$value:"*) fail ;; esac
  seen_names=$seen_names$name:
  seen_values=$seen_values$value:
  count=$((count + 1))
done < "$env_file"
[ "$count" -eq 5 ] || fail
[ "$action" != validate ] || exit 0
# Compose process variables take precedence over --env-file. Use only this validated file.
unset CANE_STAGING_POSTGRES_ADMIN_PASSWORD CANE_STAGING_MIGRATION_PASSWORD CANE_STAGING_API_DB_PASSWORD CANE_STAGING_WORKER_DB_PASSWORD CANE_STAGING_REDIS_PASSWORD
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
docker compose --project-name cane-wp01-staging --env-file "$env_file" -f "$script_dir/compose.yaml" config --quiet
if [ "$action" = start ]; then
  docker compose --project-name cane-wp01-staging --env-file "$env_file" -f "$script_dir/compose.yaml" up --detach --wait --wait-timeout 120
fi
