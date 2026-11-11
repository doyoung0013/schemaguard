#!/usr/bin/env bash
# SchemaGuard 예측 리포트 생성: 마이그레이션 파일마다 CLI를 실행해 <out>/<파일명>.json 과 종료 코드 목록을 만든다.
# usage: scripts/accuracy/predict.sh <community 경로> <out 디렉터리>
#   먼저 ./gradlew installDist 로 lib/build/install/lib 를 만든다.
set -u
COMMUNITY="$1"; OUT="$2"
BIN="$(cd "$(dirname "$0")/../.." && pwd)/lib/build/install/lib/bin/lib"
mkdir -p "$OUT"
: > "$OUT/codes.txt"
for f in "$COMMUNITY"/src/main/resources/db/migration/*.sql; do
  n=$(basename "$f" .sql)
  "$BIN" --source "$COMMUNITY/src/main/java" --migration "$f" --output json --report-path "$OUT/$n.json" >/dev/null 2>&1
  echo "$n $?" >> "$OUT/codes.txt"
done
cat "$OUT/codes.txt"
