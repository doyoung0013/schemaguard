#!/usr/bin/env python3
"""
SchemaGuard 결과를 판정하고 GitHub Step Summary 에 쓴다 (action.yml 의 Write summary 단계).

입력(환경변수): REPORT_DIR (results.tsv 와 파일별 JSON), FILE_COUNT, INPUT_FAIL_ON, GITHUB_WORKSPACE,
               GITHUB_STEP_SUMMARY, GITHUB_OUTPUT
출력(GITHUB_OUTPUT): blocked, high-count, medium-count, analyzed, should-fail, reason

판정
  파일별로: 리포트가 없거나 종료 코드가 0/1/3 이 아니면 실행 오류(차단),
           summary.blocked(HIGH 또는 읽지 못한 SQL)이거나 종료 코드가 0 이 아니면 차단.
  실패 여부는 fail-on 으로 정한다: high(기본) = 차단이 하나라도 있으면,
                                  parse-failure = 읽지 못한 SQL 이나 실행 오류가 있으면, none = 실패하지 않음.
  MEDIUM(잠금 범위 경고 등)은 차단하지 않고 표에만 보인다.
"""
import json
import os
import sys

report_dir = os.environ.get("REPORT_DIR", "")
file_count = int(os.environ.get("FILE_COUNT") or "0")
fail_on = (os.environ.get("INPUT_FAIL_ON") or "high").strip().lower()
workspace = os.environ.get("GITHUB_WORKSPACE", "").rstrip("/") + "/"
summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
output_path = os.environ.get("GITHUB_OUTPUT")

if fail_on not in ("high", "parse-failure", "none"):
    print(f"::warning title=SchemaGuard::fail-on '{fail_on}' 을 알 수 없어 high 로 봅니다")
    fail_on = "high"


def cell(s):
    return str(s).replace("|", "\\|").replace("\n", " ")


def write_outputs(**kv):
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            for k, v in kv.items():
                f.write(f"{k}={v}\n")


def write_summary(lines):
    text = "\n".join(lines) + "\n"
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(text)
    else:
        print(text)


if file_count == 0:
    write_summary(["## 🛡️ SchemaGuard Report", "", "✅ 검사할 마이그레이션 SQL 변경이 없습니다."])
    write_outputs(**{"blocked": "false", "high-count": 0, "medium-count": 0, "analyzed": 0,
                     "should-fail": "false", "reason": "no SQL migration changes"})
    sys.exit(0)

rows = []
results = os.path.join(report_dir, "results.tsv")
if os.path.exists(results):
    with open(results, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line:
                sql, code, path = line.split("\t")
                sql = sql[len(workspace):] if sql.startswith(workspace) else sql
                rows.append((sql, int(code), path))

out = ["## 🛡️ SchemaGuard Report", ""]
blocked_any = errors = parse_failures = False
high_total = medium_total = 0

# results.tsv 가 없으면 SchemaGuard 가 분석을 시작하지 못한 것이다(인자 오류, 빌드 실패 등).
if not rows:
    blocked_any = errors = True
    out += ["❌ **SchemaGuard 실행 오류**: 결과 파일이 없습니다. Run SchemaGuard 단계 로그를 확인하세요.", ""]

for sql, code, path in rows:
    out += [f"### `{cell(sql)}`", ""]
    data = None
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)

    if data is None or code not in (0, 1, 3):
        blocked_any = errors = True
        out += [f"❌ **SchemaGuard 실행 오류** (exit code {code}). Run SchemaGuard 단계 로그를 확인하세요.", ""]
        continue

    s = data.get("summary", {})
    parse = data.get("parse", {})
    apis = data.get("affectedApis", [])
    blocked = bool(s.get("blocked")) or code != 0
    blocked_any |= blocked
    parse_failures |= code == 3 or not s.get("analysisComplete", True)
    high_total += int(s.get("high", 0))
    medium_total += int(s.get("medium", 0))

    if s.get("hasHighRisk"):
        overall = "🔴 HIGH"
    elif s.get("medium", 0) > 0:
        overall = "🟡 MEDIUM"
    else:
        overall = "🟢 LOW"
    if not s.get("analysisComplete", True):
        overall += " (⚠️ 분석 불완전)"

    out += [
        "| 항목 | 값 |", "|---|---|",
        f"| 전체 위험도 | {overall} |",
        f"| 🔴 HIGH | {s.get('high', 0)}건 |",
        f"| 🟡 MEDIUM | {s.get('medium', 0)}건 |",
        f"| 🟢 LOW | {s.get('low', 0)}건 |",
        f"| 영향 API 수 | {len(apis)}개 |",
        f"| SQL 구문 | {parse.get('totalStatements', '-')}개 중 파싱 실패 {s.get('parseFailures', 0)}개 |",
        f"| 판정 | {'❌ 차단' if blocked else '✅ 통과'} |",
        "",
    ]

    failures = parse.get("failures", [])
    if failures:
        out += ["#### ⚠️ 파싱하지 못한 SQL 구문", "",
                "SchemaGuard가 읽지 못한 구문은 영향 여부를 알 수 없으므로 차단합니다.", "",
                "| 줄 | 구문 | 사유 |", "|---|---|---|"]
        for fl in failures:
            out.append(f"| {fl.get('line')} | `{cell(fl.get('statement', ''))[:120]}` | {cell(fl.get('reason', ''))} |")
        out.append("")

    src_fail = parse.get("sourceParseFailures", [])
    if src_fail:
        out += [f"> ℹ️ Java 파일 {len(src_fail)}개를 파싱하지 못해 해당 파일의 영향 경로가 누락될 수 있습니다.", ""]

    if apis:
        out += ["#### 🔥 영향받는 API", "",
                "| # | API | 위험도 | 변경 유형 | 테이블.컬럼 | 잠금 조회 |", "|---|---|---|---|---|---|"]
        for i, api in enumerate(apis, 1):
            risk = api.get("riskLevel", "-")
            badge = {"HIGH": "🔴", "MEDIUM": "🟡"}.get(risk, "🟢")
            c = api.get("cause", {})
            lock = f"`{cell(c['lockMethod'])}` ({cell(c.get('lockMode', ''))})" if c.get("lockMethod") else "-"
            out.append(f"| {i} | `{cell(api.get('apiId', '-'))}` | {badge} {risk} | {c.get('type', '-')} "
                       f"| {c.get('table') or '-'}.{c.get('column') or '-'} | {lock} |")
        out.append("")
        for api in apis:
            note = (api.get("cause") or {}).get("note")
            out += [f"<details><summary>{cell(api.get('apiId', '-'))} 영향 경로</summary>", ""]
            if note:
                out += [f"> {cell(note)}", ""]
            out += ["```"]
            for n in api.get("impactPath", []):
                loc = f"  ({n.get('filePath')}:{n.get('line')})" if n.get("line") else ""
                out.append(f"-> [{n.get('type', '')}] {n.get('name', '')}{loc}")
            out += ["```", "", "</details>", ""]

out += ["---"]
if blocked_any:
    out += ["## ❌ 병합 차단", "",
            "> HIGH 위험도 변경 또는 분석할 수 없는 SQL 구문이 있습니다. 영향받는 코드를 수정하거나 구문을 확인한 뒤 다시 push 해주세요."]
elif medium_total:
    out += ["## ✅ 병합 가능 (경고 있음)", "",
            f"> HIGH 위험도는 없지만 MEDIUM 경고 {medium_total}건이 있습니다. 위 표를 확인하세요."]
else:
    out += ["## ✅ 병합 가능", "", "> HIGH 위험도 없음, 모든 SQL 구문 분석 완료."]

if fail_on == "high":
    should_fail = blocked_any
elif fail_on == "parse-failure":
    should_fail = errors or parse_failures
else:
    should_fail = False
if fail_on != "high" and blocked_any and not should_fail:
    out += ["", f"> fail-on: {fail_on} 이라 실패로 끝내지 않았습니다."]

write_summary(out)

if errors:
    reason = "SchemaGuard 실행 오류"
elif blocked_any:
    reason = f"병합 차단: HIGH {high_total}건" + (", 읽지 못한 SQL 있음" if parse_failures else "")
else:
    reason = f"병합 가능 (HIGH 0건, MEDIUM {medium_total}건)"
write_outputs(**{"blocked": str(blocked_any).lower(), "high-count": high_total, "medium-count": medium_total,
                 "analyzed": len(rows), "should-fail": str(should_fail).lower(), "reason": reason})
print(("🚨 " if blocked_any else "✅ ") + reason)
