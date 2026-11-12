#!/usr/bin/env bash
# 목록의 SQL 을 SchemaGuard 한 번으로 분석한다 (Java 소스는 한 번만 파싱).
# 판정하지 않는다: 파일별 종료 코드는 REPORT_DIR/results.tsv 에 남고, 다음 단계(summary.py)가 읽는다.
set -uo pipefail
cd "$GITHUB_WORKSPACE"
export JAVA_HOME="${JAVA_HOME_17_X64:-${JAVA_HOME:-}}"
export JAVA_OPTS="${JAVA_OPTS:-} -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
BIN="$GITHUB_ACTION_PATH/lib/build/install/lib/bin/lib"

FILES=()
while IFS= read -r f; do
  [ -n "$f" ] && FILES+=("$GITHUB_WORKSPACE/$f")
done < "$FILE_LIST"

ARGS=(--source "$GITHUB_WORKSPACE/$INPUT_SOURCE" --migration "${FILES[@]}" --report-dir "$REPORT_DIR")
if [ "$INPUT_HISTORY" = "true" ]; then
  ARGS+=(--history "$GITHUB_WORKSPACE/$INPUT_MIGRATION_DIR")
fi
if [ "$INPUT_LOCK_SCOPE" != "true" ]; then
  ARGS+=(--no-lock-scope)
fi

"$BIN" "${ARGS[@]}"
echo "→ SchemaGuard overall exit code $?"
exit 0
