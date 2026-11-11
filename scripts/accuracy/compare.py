"""SchemaGuard 예측(JSON 리포트들) vs 실제 MySQL 측정(actual.json) 비교.

actual.json 은 community 의 SchemaGuardAccuracyTest 가 만든다 (build/schemaguard-accuracy/actual.json).

usage: python scripts/accuracy/compare.py <actual.json> <schemaguard-report-dir> [--codes codes.txt]
  report dir: <scenario>.json (SchemaGuard --output json 결과)
  codes.txt : "<scenario> <exit code>" 줄 (선택)
"""
import json, sys, os

BASE_FILES = {"V1__init", "V12__create_tag_report_notification_bookmark"}


def main():
    actual = json.load(open(sys.argv[1], encoding="utf-8"))
    rep_dir = sys.argv[2]
    codes = {}
    if "--codes" in sys.argv:
        for line in open(sys.argv[sys.argv.index("--codes") + 1], encoding="utf-8"):
            k, v = line.split()
            codes[k] = int(v)

    universe = set(actual["endpoints"]) - set(actual.get("baselineBroken", []))
    rows, tot = [], {"all": [0, 0, 0], "high": [0, 0, 0]}
    misses, falses = [], []

    for sc in sorted(actual["scenarios"], key=lambda s: (int(s[1:].split("_")[0]), s)):
        if sc in BASE_FILES:
            continue
        a = actual["scenarios"][sc]
        act = set(a["dbFailingApis"]) & universe
        rep = json.load(open(os.path.join(rep_dir, sc + ".json"), encoding="utf-8"))
        pred_all = {x["apiId"] for x in rep["affectedApis"]} & universe
        pred_high = {x["apiId"] for x in rep["affectedApis"] if x["riskLevel"] == "HIGH"} & universe
        unknown_pred = {x["apiId"] for x in rep["affectedApis"]} - set(actual["endpoints"])

        for key, pred in (("all", pred_all), ("high", pred_high)):
            tp, fp, fn = len(pred & act), len(pred - act), len(act - pred)
            tot[key][0] += tp; tot[key][1] += fp; tot[key][2] += fn
        tp, fp, fn = len(pred_all & act), len(pred_all - act), len(act - pred_all)
        rows.append((sc, codes.get(sc, "?"), len(act), len(pred_all), tp, fp, fn,
                     len(a.get("migrationStatementErrors", []))))
        for api in sorted(act - pred_all):
            misses.append((sc, api, a["details"].get(api, {}).get("exception", "")))
        for api in sorted(pred_all - act):
            cause = [x["cause"]["type"] + " " + str(x["cause"]["table"]) + "." + str(x["cause"]["column"])
                     for x in rep["affectedApis"] if x["apiId"] == api]
            falses.append((sc, api, a["details"].get(api, {}).get("status"), cause))
        if unknown_pred:
            print(f"[warn] {sc}: predicted ids not in endpoint list: {sorted(unknown_pred)}")

    print(f"\nendpoints={len(actual['endpoints'])} usable={len(universe)} baselineBroken={actual.get('baselineBroken')}")
    print(f"{'scenario':45} exit actual pred  TP  FP  FN stmtErr")
    for r in rows:
        print(f"{r[0]:45} {str(r[1]):>4} {r[2]:6} {r[3]:4} {r[4]:3} {r[5]:3} {r[6]:3} {r[7]:7}")
    for key, (tp, fp, fn) in tot.items():
        p = tp / (tp + fp) if tp + fp else float("nan")
        rc = tp / (tp + fn) if tp + fn else float("nan")
        print(f"[{key:4}] TP={tp} FP={fp} FN={fn} precision={p:.3f} recall={rc:.3f}")
    print("\n-- missed (FN)")
    for m in misses: print("  ", m[0], "|", m[1], "|", m[2][:160])
    print("\n-- false alarms (FP)")
    for f in falses: print("  ", f[0], "|", f[1], "| status", f[2], "|", "; ".join(f[3])[:160])


if __name__ == "__main__":
    main()
