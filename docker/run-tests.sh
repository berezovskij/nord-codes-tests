#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  printf '%s\n' 'Этот скрипт не принимает overrides или дополнительные параметры.' >&2
  exit 2
fi
project_root=$(CDPATH='' cd "$(dirname "$0")/.." && pwd)
docker_cli() {
  env -i HOME="$HOME" PATH=/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin \
    docker "$@"
}
compose() {
  docker_cli compose --env-file /dev/null \
      --project-name aqa-isolated --file "$project_root/compose.yaml" --profile tests "$@"
}
. "$project_root/docker/collect-app-logs.sh"

lock_dir="$project_root/.aqa-run.lock"
if ! mkdir "$lock_dir" 2>/dev/null; then
  printf '%s\n' 'Есть блокировка .aqa-run.lock. Проверьте, не выполняется ли другой прогон.' >&2
  exit 2
fi
started=0
exported=0
report_dir=''
export_results() {
  compose exec --no-TTY runner tar --directory /work/results --create --file - . \
    > "$report_dir/results.tar" || return 1
  for name in test-exit-code report-exit-code run-exit-code READY; do
    compose exec --no-TTY runner cat "/work/results/$name" > "$report_dir/$name" || return 1
  done
  if [ "$(cat "$report_dir/report-exit-code")" = 0 ]; then
    mkdir -p "$report_dir/allure-report" || return 1
    compose exec --no-TTY runner cat /work/results/allure-report/index.html \
      > "$report_dir/allure-report/index.html" || return 1
    if [ ! -s "$report_dir/allure-report/index.html" ]; then
      printf '%s\n' 'Allure сообщил об успехе, но HTML-отчёт пуст.' >&2
      return 1
    fi
  fi
  for path in surefire/TEST-aqa.tests.EndpointTest.xml surefire/aqa.tests.EndpointTest.txt; do
    if compose exec --no-TTY runner test -f "/work/results/$path"; then
      mkdir -p "$report_dir/${path%/*}" || return 1
      compose exec --no-TTY runner cat "/work/results/$path" > "$report_dir/$path" || return 1
    fi
  done
}
cleanup() {
  result=$?
  trap - EXIT INT TERM
  if [ "$started" -eq 1 ]; then
    compose logs --no-color wiremock runner > "$report_dir/compose.log" 2>&1 || true
    if [ "$exported" -eq 0 ]; then
      export_results >/dev/null 2>&1 || true
    fi
    if ! compose down --timeout 10; then
      printf '%s\n' 'Не удалось полностью остановить Docker-проект; проверьте контейнеры.' >&2
      if [ "$result" -eq 0 ]; then result=2; fi
    fi
  fi
  rmdir "$lock_dir" || true
  if [ -n "$report_dir" ]; then
    printf 'Результаты и логи: %s\n' "$report_dir"
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

compose config --quiet
if [ -n "$(compose ps --all --quiet)" ]; then
  printf '%s\n' 'Проект aqa-isolated уже содержит контейнеры. Сначала сохраните результаты и удалите их.' >&2
  exit 2
fi
mkdir -p "$project_root/artifacts"
report_dir=$(mktemp -d "$project_root/artifacts/run-XXXXXXXX")
started=1
compose up --detach --no-build --pull never
compose config > "$report_dir/compose.yaml"
docker_cli inspect "$(compose ps --all --quiet wiremock)" \
  "$(compose ps --all --quiet app)" "$(compose ps --all --quiet runner)" \
  > "$report_dir/containers.json"

deadline=$(( $(date +%s) + 600 ))
log_capture_done=0
while ! compose exec --no-TTY runner test -f /work/results/READY >/dev/null 2>&1; do
  if [ "$log_capture_done" -eq 0 ] && compose exec --no-TTY runner test -f /work/log-check/request.txt >/dev/null 2>&1; then
    check_app_logs
    log_capture_done=1
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    printf '%s\n' 'Превышено время ожидания завершения прогона: 10 минут.' >&2
    exit 2
  fi
  for service in wiremock app runner; do
    if [ -z "$(compose ps --status running --quiet "$service")" ]; then
      printf 'Контейнер %s остановился до завершения прогона. См. compose.log.\n' "$service" >&2
      exit 2
    fi
  done
  sleep 2
done

export_results
exported=1
if [ "$(cat "$report_dir/report-exit-code")" = 0 ]; then
  mkdir -p "$project_root/reports/allure"
  report_tmp=$(mktemp "$project_root/reports/allure/.index-XXXXXXXX")
  if ! cp "$report_dir/allure-report/index.html" "$report_tmp" || \
     ! mv -f "$report_tmp" "$project_root/reports/allure/index.html"; then
    rm -f "$report_tmp"
    exit 2
  fi
fi
run_exit=$(cat "$report_dir/run-exit-code")
case "$run_exit" in
  ''|*[!0-9]*) printf '%s\n' 'Некорректный код завершения раннера.' >&2; exit 2 ;;
esac
if [ "$run_exit" -gt 255 ]; then exit 2; fi
exit "$run_exit"
