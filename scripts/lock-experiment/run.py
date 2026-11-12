#!/usr/bin/env python3
"""
비관적 락 + 인덱스 삭제: 실제 MySQL 측정과 SchemaGuard 예측 비교 (CHANGELOG 20).

  python3 scripts/lock-experiment/run.py --out <결과 폴더> [--port 3307] [--rows 100000] [--load] [--reps 3]

준비:
  1) scripts/lock-experiment/mysql-local.sh start <데이터 폴더>      (실험 전용 MySQL, 127.0.0.1:3307)
  2) ./gradlew installDist                                           (SchemaGuard 바이너리)
  3) pip install pymysql

시나리오마다 DB 를 새로 만들고 V1 (+ 시나리오의 사전 인덱스) 적용 → 데이터 적재 → "삭제 전" 측정
→ 시나리오 마이그레이션 적용 → "삭제 후" 측정. 측정 항목은 조회마다
  ① 잠긴 레코드 수 (performance_schema.data_locks, 트랜잭션 A 가 조회만 한 상태)
  ② 다른 행을 요청한 트랜잭션 B 가 막히는지 (innodb_lock_wait_timeout = 1)
  ③ EXPLAIN 의 type / key / rows
실측 정답: 삭제 후 잠긴 레코드 수가 삭제 전보다 늘면 "잠금 범위 확대".
SchemaGuard 예측: 같은 프로젝트(project/)와 마이그레이션으로 CLI 를 돌려 LOCK_SCOPE_WIDENED 가 붙은 API.
--load 를 주면 대표 시나리오에서 동시 요청 부하(TPS, 지연, 타임아웃)도 잰다.
"""
import argparse, json, os, random, shutil, statistics, subprocess, sys, threading, time
from pathlib import Path

import pymysql

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PROJECT = HERE / "project"
V1 = (PROJECT / "db" / "V1__init.sql").read_text()
BIN = REPO / "lib" / "build" / "install" / "lib" / "bin" / "lib"

DB = "lockexp"
TARGET_PRODUCT = 25_000          # 트랜잭션 A 가 잠그는 상품
OTHER_PRODUCT = 30_000           # 트랜잭션 B 가 요청하는 다른 상품 (A 와 겹치는 행 없음)

# 조회: 프로젝트의 리포지토리 메서드가 만드는 SQL 과 같은 조건. api 는 SchemaGuard 리포트의 API.
QUERIES = {
    "decrease": dict(api="POST /stocks/decrease", lock=True,
                     sql="SELECT * FROM stocks WHERE product_id = %s AND status = 'ON_SALE' FOR UPDATE",
                     arg=lambda p: (p,), desc="findByProductIdAndStatus (파생 쿼리 + @Lock)"),
    "restock": dict(api="POST /stocks/restock", lock=True,
                    sql="SELECT * FROM stocks WHERE product_id = %s FOR UPDATE",
                    arg=lambda p: (p,), desc="lockAllOfProduct (JPQL @Query + @Lock)"),
    "adjust": dict(api="POST /stocks/{id}/adjust", lock=True,
                   sql="SELECT * FROM stocks WHERE id = %s FOR UPDATE",
                   arg=lambda p: (p * 2,), desc="findById (PK + @Lock)"),
    "on-sale": dict(api="GET /stocks/on-sale", lock=False,
                    sql="SELECT * FROM stocks WHERE status = 'ON_SALE' LIMIT 20",
                    arg=lambda p: (), desc="findByStatus (잠금 없음)"),
}

SUPERSET_UNIQUE = "CREATE UNIQUE INDEX uq_stocks_product_status_qty ON stocks (product_id, status, quantity);"

SCENARIOS = [
    dict(id="S1", desc="(product_id, status) 삭제, (status) 만 남음",
         migration="DROP INDEX idx_stocks_product_status ON stocks;"),
    dict(id="S2", desc="두 인덱스 모두 삭제",
         migration="DROP INDEX idx_stocks_status ON stocks;\nDROP INDEX idx_stocks_product_status ON stocks;"),
    dict(id="S3", desc="(product_id) 를 만들고 (product_id, status) 삭제",
         migration="CREATE INDEX idx_stocks_product ON stocks (product_id);\nDROP INDEX idx_stocks_product_status ON stocks;"),
    dict(id="S4", desc="(status, product_id) 를 만들고 (product_id, status) 삭제",
         migration="CREATE INDEX idx_stocks_status_product ON stocks (status, product_id);\nDROP INDEX idx_stocks_product_status ON stocks;"),
    dict(id="S5", desc="같은 컬럼으로 이름만 바꿈",
         migration="CREATE INDEX idx_stocks_product_status_v2 ON stocks (product_id, status);\nDROP INDEX idx_stocks_product_status ON stocks;"),
    dict(id="S6", desc="UNIQUE (product_id, status, quantity) 가 있는 상태에서 (product_id, status) 삭제",
         before=SUPERSET_UNIQUE, migration="DROP INDEX idx_stocks_product_status ON stocks;"),
    dict(id="S7", desc="(status) 만 삭제",
         migration="DROP INDEX idx_stocks_status ON stocks;"),
]


def connect(port, db=DB, autocommit=True):
    return pymysql.connect(host="127.0.0.1", port=port, user="root", database=db, autocommit=autocommit)


def statements(sql):
    return [s.strip() for s in sql.split(";") if s.strip()]


def setup(port, rows, before):
    c = pymysql.connect(host="127.0.0.1", port=port, user="root", autocommit=True)
    with c.cursor() as cur:
        cur.execute(f"DROP DATABASE IF EXISTS {DB}")
        cur.execute(f"CREATE DATABASE {DB}")
        cur.execute(f"USE {DB}")
        for s in statements(V1 + "\n" + (before or "")):
            cur.execute(s)
        # 상품마다 ON_SALE / HIDDEN 두 행: product_id 1..rows/2, id = 2*product_id-1, 2*product_id
        cur.execute("SET SESSION cte_max_recursion_depth = 10000000")
        cur.execute(f"""
            INSERT INTO stocks (id, product_id, status, quantity)
            WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < {rows})
            SELECT n, (n + 1) DIV 2, IF(n % 2 = 1, 'ON_SALE', 'HIDDEN'), n FROM seq""")
        cur.execute("ANALYZE TABLE stocks")
    c.close()


def apply(port, sql):
    c = connect(port)
    with c.cursor() as cur:
        for s in statements(sql):
            cur.execute(s)
        cur.execute("ANALYZE TABLE stocks")
    c.close()


def measure(port, q, isolation="REPEATABLE READ"):
    """트랜잭션 A 가 조회만 한 상태에서 잠긴 레코드 수, 다른 행을 요청한 B 가 막히는지, EXPLAIN."""
    spec = QUERIES[q]
    out = {}
    mon = connect(port)
    with mon.cursor() as cur:
        cur.execute("EXPLAIN " + spec["sql"].replace(" FOR UPDATE", ""), spec["arg"](TARGET_PRODUCT))
        cols = [d[0] for d in cur.description]
        row = dict(zip(cols, cur.fetchone()))
        out["explain"] = {"type": row["type"], "key": row["key"], "rows": row["rows"]}

    a = connect(port, autocommit=False)
    with a.cursor() as ca:
        ca.execute(f"SET SESSION TRANSACTION ISOLATION LEVEL {isolation}")
        ca.execute("BEGIN")
        ca.execute(spec["sql"], spec["arg"](TARGET_PRODUCT))
        ca.fetchall()
        # information_schema.innodb_trx 는 최대 0.1초마다 갱신되는 캐시라 직전 트랜잭션 id 를 돌려줄 수 있다.
        # performance_schema 의 스레드 id 로 A 의 잠금을 찾는다 (A 의 이전 트랜잭션은 이미 끝났다).
        ca.execute("SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID = CONNECTION_ID()")
        thread = ca.fetchone()[0]
    locks = {}
    with mon.cursor() as cur:
        cur.execute("""SELECT INDEX_NAME, LOCK_MODE, COUNT(*) FROM performance_schema.data_locks
                       WHERE THREAD_ID = %s AND LOCK_TYPE = 'RECORD'
                       GROUP BY INDEX_NAME, LOCK_MODE""", (thread,))
        for idx, mode, n in cur.fetchall():
            locks[f"{idx}:{mode}"] = n
    out["locked_records"] = sum(locks.values())
    out["locks_by_index"] = locks

    # B: 다른 상품의 같은 조회. 1초 안에 못 얻으면 막힌 것
    blocked, waited = False, 0.0
    if spec["lock"]:
        b = connect(port, autocommit=False)
        with b.cursor() as cb:
            cb.execute(f"SET SESSION TRANSACTION ISOLATION LEVEL {isolation}")
            cb.execute("SET SESSION innodb_lock_wait_timeout = 1")
            cb.execute("BEGIN")
            t = time.monotonic()
            try:
                cb.execute(spec["sql"], spec["arg"](OTHER_PRODUCT))
                cb.fetchall()
            except pymysql.err.OperationalError as e:
                if e.args[0] != 1205:
                    raise
                blocked = True
            waited = time.monotonic() - t
            cb.execute("ROLLBACK")
        b.close()
    out["other_row_blocked"] = blocked
    out["other_row_wait_s"] = round(waited, 3)
    a.rollback()
    a.close()
    mon.close()
    return out


def schemaguard(scenario, work):
    """프로젝트 + V1(+사전 인덱스) + 시나리오 마이그레이션으로 SchemaGuard 를 돌려 잠금 경고가 붙은 API."""
    mig = work / f"migrations-{scenario['id']}"
    shutil.rmtree(mig, ignore_errors=True)
    mig.mkdir(parents=True)
    (mig / "V1__init.sql").write_text(V1)
    if scenario.get("before"):
        (mig / "V1_1__before.sql").write_text(scenario["before"] + "\n")
    (mig / "V2__change.sql").write_text(scenario["migration"] + "\n")
    report = work / f"report-{scenario['id']}.json"
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    p = subprocess.run([str(BIN), "--source", str(PROJECT / "src" / "main" / "java"),
                        "--migration", str(mig / "V2__change.sql"), "--history", str(mig),
                        "--output", "json", "--report-path", str(report)],
                       capture_output=True, text=True, env=env)
    data = json.loads(report.read_text())
    warned = {a["apiId"]: a["cause"].get("note") for a in data["affectedApis"]
              if a["cause"]["type"] == "LOCK_SCOPE_WIDENED"}
    return {"exit": p.returncode, "warned": warned, "detected": data["parse"]["detectedChanges"]}


# ── 부하 ──────────────────────────────────────────────────────────────────

def load(port, q, threads, seconds, hold_ms, timeout_s):
    """threads 개가 서로 다른 상품을 골라 잠금 조회 → hold_ms 대기 → 수량 차감 → 커밋을 반복한다."""
    spec = QUERIES[q]
    stop = time.monotonic() + seconds
    lat, timeouts, deadlocks, lock = [], [0], [0], threading.Lock()
    products = list(range(1, 50_001))

    def worker(seed):
        rnd = random.Random(seed)
        c = connect(port, autocommit=False)
        with c.cursor() as cur:
            cur.execute(f"SET SESSION innodb_lock_wait_timeout = {timeout_s}")
            while time.monotonic() < stop:
                p = rnd.choice(products)
                t = time.monotonic()
                try:
                    cur.execute("BEGIN")
                    cur.execute(spec["sql"], spec["arg"](p))
                    cur.fetchall()
                    time.sleep(hold_ms / 1000)
                    cur.execute("UPDATE stocks SET quantity = quantity - 1 WHERE product_id = %s AND status = 'ON_SALE'"
                                if q == "decrease" else "UPDATE stocks SET quantity = quantity + 1 WHERE product_id = %s", (p,))
                    cur.execute("COMMIT")
                    with lock:
                        lat.append(time.monotonic() - t)
                except pymysql.err.OperationalError as e:
                    cur.execute("ROLLBACK")
                    with lock:
                        if e.args[0] == 1205: timeouts[0] += 1
                        elif e.args[0] == 1213: deadlocks[0] += 1
                        else: raise
        c.close()

    ts = [threading.Thread(target=worker, args=(i,)) for i in range(threads)]
    started = time.monotonic()
    for t in ts: t.start()
    for t in ts: t.join()
    elapsed = time.monotonic() - started
    lat.sort()
    pct = lambda x: round(lat[min(len(lat) - 1, int(len(lat) * x))] * 1000, 1) if lat else None
    return {"committed": len(lat), "tps": round(len(lat) / elapsed, 1), "p50_ms": pct(0.50), "p95_ms": pct(0.95),
            "p99_ms": pct(0.99), "lock_wait_timeouts": timeouts[0], "deadlocks": deadlocks[0], "elapsed_s": round(elapsed, 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--port", type=int, default=3307)
    ap.add_argument("--rows", type=int, default=100_000)
    ap.add_argument("--load", action="store_true")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--threads", type=int, default=16)
    ap.add_argument("--seconds", type=int, default=10)
    ap.add_argument("--hold-ms", type=int, default=20)
    ap.add_argument("--timeout-s", type=int, default=5)
    args = ap.parse_args()
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    if not BIN.exists():
        sys.exit(f"SchemaGuard binary not found: {BIN} (run ./gradlew installDist)")

    c = pymysql.connect(host="127.0.0.1", port=args.port, user="root")
    with c.cursor() as cur:
        cur.execute("SELECT VERSION(), @@transaction_isolation, @@innodb_lock_wait_timeout")
        version, iso, wait = cur.fetchone()
    c.close()
    result = {"mysql": version, "isolation": iso, "innodb_lock_wait_timeout": wait, "rows": args.rows, "scenarios": []}
    print(f"MySQL {version}, {iso}, rows {args.rows}")

    for sc in SCENARIOS:
        setup(args.port, args.rows, sc.get("before"))
        before = {q: measure(args.port, q) for q in QUERIES}
        apply(args.port, sc["migration"])
        after = {q: measure(args.port, q) for q in QUERIES}
        sg = schemaguard(sc, out)
        rows = []
        for q, spec in QUERIES.items():
            widened = after[q]["locked_records"] > before[q]["locked_records"]
            predicted = spec["api"] in sg["warned"]
            rows.append(dict(query=q, api=spec["api"], before=before[q], after=after[q],
                             widened=widened, predicted=predicted, note=sg["warned"].get(spec["api"])))
            print(f"{sc['id']} {q:9s} locks {before[q]['locked_records']:>7} -> {after[q]['locked_records']:>7} "
                  f"blocked {before[q]['other_row_blocked']!s:5} -> {after[q]['other_row_blocked']!s:5} "
                  f"key {before[q]['explain']['key']} -> {after[q]['explain']['key']} "
                  f"| actual {'WIDENED' if widened else '-':7} predicted {'WARN' if predicted else '-'}")
        result["scenarios"].append(dict(id=sc["id"], desc=sc["desc"], before_sql=sc.get("before"),
                                        migration=sc["migration"], schemaguard_exit=sg["exit"],
                                        detected=sg["detected"], rows=rows))

    # 정밀도·재현율 (시나리오 × 조회)
    tp = fp = fn = tn = 0
    for sc in result["scenarios"]:
        for r in sc["rows"]:
            if r["predicted"] and r["widened"]: tp += 1
            elif r["predicted"]: fp += 1
            elif r["widened"]: fn += 1
            else: tn += 1
    result["confusion"] = dict(tp=tp, fp=fp, fn=fn, tn=tn,
                               precision=round(tp / (tp + fp), 3) if tp + fp else None,
                               recall=round(tp / (tp + fn), 3) if tp + fn else None)
    print("confusion", result["confusion"])

    # READ COMMITTED 비교 (S2: 인덱스 없음)
    setup(args.port, args.rows, None)
    apply(args.port, SCENARIOS[1]["migration"])
    result["read_committed_S2"] = {q: measure(args.port, q, "READ COMMITTED") for q in ("decrease", "restock")}
    print("READ COMMITTED S2", {q: (v["locked_records"], v["other_row_blocked"]) for q, v in result["read_committed_S2"].items()})

    if args.load:
        cases = [("S0", None, "decrease"), ("S1", SCENARIOS[0]["migration"], "decrease"),
                 ("S2", SCENARIOS[1]["migration"], "decrease"), ("S0", None, "restock"),
                 ("S4", SCENARIOS[3]["migration"], "restock")]
        result["load"] = []
        for name, mig, q in cases:
            runs = []
            for rep in range(args.reps):
                setup(args.port, args.rows, None)
                if mig: apply(args.port, mig)
                runs.append(load(args.port, q, args.threads, args.seconds, args.hold_ms, args.timeout_s))
            med = {k: statistics.median([r[k] for r in runs if r[k] is not None]) if any(r[k] is not None for r in runs) else None
                   for k in runs[0]}
            result["load"].append(dict(scenario=name, query=q, runs=runs, median=med))
            print("load", name, q, med)

    (out / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print("saved", out / "results.json")


if __name__ == "__main__":
    main()
