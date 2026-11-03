#!/bin/sh

check_app_logs() (
  set -eu
  umask 077
  log_dir=$(mktemp -d "${TMPDIR:-/tmp}/aqa-logs.XXXXXXXX")
  publish_log_check() {
    compose exec --no-TTY runner /bin/sh -c \
      'umask 077; cat > "$1.tmp" && mv "$1.tmp" "$1"' sh "/work/log-check/$1"
  }
  cleanup_logs() {
    log_status=$?
    trap - EXIT INT TERM
    if [ "$log_status" -ne 0 ]; then
      printf '%s\n' '{"observed":false}' | publish_log_check facts.json || true
      printf '%s\n' 'Не удалось подтвердить сбор новых логов.' >&2
    fi
    rm -rf "$log_dir"
    exit "$log_status"
  }
  trap cleanup_logs EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  compose exec --no-TTY runner cat /work/log-check/request.txt > "$log_dir/keys"
  {
    IFS= read -r supplied_key
    IFS= read -r configured_key
    if IFS= read -r extra; then exit 1; fi
  } < "$log_dir/keys"
  [ -n "$supplied_key" ] && [ -n "$configured_key" ] || exit 1

  compose logs --no-color --no-log-prefix --timestamps app > "$log_dir/baseline"
  printf '%s\n' ready | publish_log_check ready
  deadline=$(( $(date +%s) + 15 ))
  until compose exec --no-TTY runner test -f /work/log-check/done; do
    [ "$(date +%s)" -lt "$deadline" ] || exit 1
    sleep 0.1
  done
  sleep 2
  compose logs --no-color --no-log-prefix --timestamps app > "$log_dir/current"
  baseline_size=$(wc -c < "$log_dir/baseline" | tr -d ' ')
  head -c "$baseline_size" "$log_dir/current" > "$log_dir/prefix"
  cmp -s "$log_dir/baseline" "$log_dir/prefix"
  tail -c "+$((baseline_size + 1))" "$log_dir/current" > "$log_dir/fresh"

  supplied_leaked=false
  if grep -aF -- "$supplied_key" "$log_dir/fresh" > /dev/null; then
    supplied_leaked=true
  else
    [ "$?" -eq 1 ] || exit 1
  fi
  configured_leaked=false
  if grep -aF -- "$configured_key" "$log_dir/fresh" > /dev/null; then
    configured_leaked=true
  else
    [ "$?" -eq 1 ] || exit 1
  fi
  printf '{"observed":true,"suppliedKeyLeaked":%s,"configuredKeyLeaked":%s,"observationSeconds":2}\n' \
    "$supplied_leaked" "$configured_leaked" | publish_log_check facts.json
)
