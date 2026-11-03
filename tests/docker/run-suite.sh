#!/bin/sh
set -u
umask 077

mkdir -p /work/results || exit 2
test_exit=0
/usr/share/maven/bin/mvn --offline --batch-mode --no-transfer-progress \
  -Dmaven.repo.local=/opt/maven-repository test || test_exit=$?

report_exit=0
if [ -d /work/results/allure-results ] && \
   [ -n "$(find /work/results/allure-results -name '*-result.json' -print -quit)" ]; then
  JAVA_OPTS='-Xms32m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -Duser.home=/tmp -Djava.io.tmpdir=/tmp' \
    /opt/allure/bin/allure generate /work/results/allure-results \
      --output /work/results/allure-report --clean --single-file --lang ru || report_exit=$?
else
  printf '%s\n' 'Allure results отсутствуют; успешный отчёт не создаётся.' >&2
  report_exit=2
fi

run_exit=$test_exit
if [ "$run_exit" -eq 0 ] && [ "$report_exit" -ne 0 ]; then
  run_exit=$report_exit
fi
printf '%s\n' "$test_exit" > /work/results/test-exit-code || exit 2
printf '%s\n' "$report_exit" > /work/results/report-exit-code || exit 2
printf '%s\n' "$run_exit" > /work/results/run-exit-code || exit 2
touch /work/results/READY || exit 2
printf '%s\n' 'Прогон завершён. Раннер сохраняет tmpfs до копирования отчётов через Docker.'

trap 'exit "$run_exit"' TERM INT
while :; do
  sleep 1
done
