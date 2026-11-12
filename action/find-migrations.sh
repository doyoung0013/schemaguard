#!/usr/bin/env bash
# 검사할 SQL 마이그레이션 목록을 만든다 (action.yml 의 첫 단계).
#   INPUT_FILES 가 있으면 그 목록, 없으면 비교 기준 커밋과 HEAD 사이에 추가·수정된 INPUT_MIGRATION_DIR 의 .sql
# 출력: list (목록 파일), count, report-dir
set -euo pipefail
cd "$GITHUB_WORKSPACE"

REPORT_DIR="$RUNNER_TEMP/schemaguard-reports"
LIST="$RUNNER_TEMP/schemaguard-files.txt"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"
: > "$LIST"
dir="${INPUT_MIGRATION_DIR%/}"

if [ -n "${INPUT_FILES:-}" ]; then
  printf '%s\n' "$INPUT_FILES" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | { grep -v '^$' || true; } > "$LIST"
else
  base="${INPUT_BASE:-${PR_BASE_SHA:-}}"
  if [ -z "$base" ]; then
    echo "::notice title=SchemaGuard::pull_request 이벤트가 아니라 비교 기준이 없습니다. files 또는 base 입력을 주세요. 검사를 건너뜁니다."
  else
    # actions/checkout 기본값(커밋 1개)이어도 되도록 비교 기준 커밋만 따로 가져온다.
    # pull_request 의 checkout 은 base + PR 의 병합 커밋이라 base..HEAD 가 곧 PR 의 변경이다.
    if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
      git fetch --no-tags --depth=1 origin "$base"
    fi
    git -c core.quotePath=false diff --name-only --diff-filter=d "$base" HEAD -- "$dir" \
      | { grep -E '\.sql$' || true; } > "$LIST"
  fi
fi

count=$(grep -c . "$LIST" || true)
{
  echo "list=$LIST"
  echo "count=$count"
  echo "report-dir=$REPORT_DIR"
} >> "$GITHUB_OUTPUT"
echo "SQL migrations to check ($count):"
cat "$LIST"
