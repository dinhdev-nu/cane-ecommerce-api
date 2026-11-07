#!/bin/sh
set -eu
umask 077
: "${CANE_REDIS_PASSWORD:?Missing local Redis secret}"
CANE_ENVIRONMENT=${CANE_ENVIRONMENT:-local}
case "$CANE_ENVIRONMENT" in
  local|test) ;;
  *) echo 'Unsupported Redis local/test namespace' >&2; exit 1 ;;
esac
case "$CANE_REDIS_PASSWORD" in
  *[!a-f0-9]*|'') echo 'Redis local secret must be generated hexadecimal text' >&2; exit 1 ;;
esac
{
  printf '%s\n' 'bind 0.0.0.0' 'protected-mode yes' 'appendonly no' 'save ""' 'maxmemory 64mb' 'maxmemory-policy noeviction' 'user default off'
  printf 'user cane_runtime on >%s ~cane:%s:* +ping +get +set +del +incr +expire +pexpire +ttl +pttl +eval +evalsha +script|load +hello +auth +quit +select +client|setname +client|setinfo\n' "$CANE_REDIS_PASSWORD" "$CANE_ENVIRONMENT"
} > /dev/shm/cane-redis.conf
exec redis-server /dev/shm/cane-redis.conf
