#!/bin/sh
set -eu

project_root=$(CDPATH='' cd "$(dirname "$0")/.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/aqa-log-tests.XXXXXXXX")
trap 'rm -rf "$test_dir"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for scenario in clean both supplied configured baseline binary rotated logs_error request_error empty_key extra_key timeout publish_error grep_error; do
  case_dir="$test_dir/$scenario"
  mkdir -p "$case_dir/tmp"
  printf '%s\n' '-supplied.[key]' 'configured*key' > "$case_dir/request"
  printf 'old log\n' > "$case_dir/baseline"
  case "$scenario" in
    baseline) cat "$case_dir/request" >> "$case_dir/baseline" ;;
    empty_key) printf '\nconfigured*key\n' > "$case_dir/request" ;;
    extra_key) printf 'extra\n' >> "$case_dir/request" ;;
  esac
  cp "$case_dir/baseline" "$case_dir/current"
  case "$scenario" in
    both|binary) cat "$case_dir/request" >> "$case_dir/current" ;;
    supplied) printf '%s\n' '-supplied.[key]' >> "$case_dir/current" ;;
    configured) printf '%s\n' 'configured*key' >> "$case_dir/current" ;;
    rotated) printf 'rotated\n' > "$case_dir/current" ;;
  esac
  if [ "$scenario" = binary ]; then
    printf '\000\n\n' >> "$case_dir/baseline"
    cat "$case_dir/baseline" "$case_dir/request" > "$case_dir/current"
  fi
  if TMPDIR="$case_dir/tmp" sh -s "$project_root" "$case_dir" "$scenario" <<'SH'
set -eu
. "$1/docker/collect-app-logs.sh"
case_dir=$2
scenario=$3
compose() {
  case "$4" in
    cat)
      [ "$scenario" != request_error ] || return 1
      cat "$case_dir/request" ;;
    test) [ "$scenario" != timeout ] ;;
    /bin/sh)
      for target do :; done
      if [ "$scenario" = publish_error ] && [ "${target##*/}" = ready ]; then return 1; fi
      cat > "$case_dir/${target##*/}" ;;
    --timestamps)
      [ "$scenario" != logs_error ] || return 1
      if [ -f "$case_dir/read" ]; then
        cat "$case_dir/current"
      else
        touch "$case_dir/read"
        cat "$case_dir/baseline"
      fi ;;
    *) return 99 ;;
  esac
}
sleep() { :; }
date() {
  tick=0
  if [ -f "$case_dir/clock" ]; then tick=$(cat "$case_dir/clock"); fi
  tick=$((tick + 1))
  printf '%s\n' "$tick" > "$case_dir/clock"
  printf '%s\n' "$tick"
}
if [ "$scenario" = grep_error ]; then
  grep() { return 2; }
fi
check_app_logs
SH
  then
    case "$scenario" in
      both|binary) supplied=true; configured=true ;;
      supplied) supplied=true; configured=false ;;
      configured) supplied=false; configured=true ;;
      clean|baseline) supplied=false; configured=false ;;
      *) printf 'FAIL: %s accepted\n' "$scenario" >&2; exit 1 ;;
    esac
    expected=$(printf '{"observed":true,"suppliedKeyLeaked":%s,"configuredKeyLeaked":%s,"observationSeconds":2}' "$supplied" "$configured")
    [ "$(cat "$case_dir/facts.json")" = "$expected" ]
  else
    case "$scenario" in
      clean|both|supplied|configured|baseline|binary) printf 'FAIL: %s rejected\n' "$scenario" >&2; exit 1 ;;
    esac
    [ "$(cat "$case_dir/facts.json")" = '{"observed":false}' ]
  fi
  [ -z "$(ls -A "$case_dir/tmp")" ]
  printf 'PASS: %s\n' "$scenario"
done
