#!/bin/sh
set -eu
umask 077
: "${CANE_REDIS_PASSWORD:?Missing staging Redis secret}"
case "$CANE_REDIS_PASSWORD" in
  *[!a-f0-9]*|'') echo 'Staging Redis secret must be random hexadecimal text' >&2; exit 1 ;;
esac
if [ "${#CANE_REDIS_PASSWORD}" -lt 64 ]; then
  echo 'Staging Redis secret needs at least 32 random bytes' >&2; exit 1
fi
{
  printf '%s\n' 'bind 0.0.0.0' 'protected-mode yes' 'appendonly no' 'save ""' 'maxmemory 64mb' 'maxmemory-policy noeviction' 'user default off'
  printf 'user cane_runtime on >%s ~cane:staging:* +ping +get +set +del +incr +expire +pexpire +ttl +pttl +eval +evalsha +script|load +hello +auth +quit +select +client|setname +client|setinfo\n' "$CANE_REDIS_PASSWORD"
} > /dev/shm/cane-staging-redis.conf
exec redis-server /dev/shm/cane-staging-redis.conf
