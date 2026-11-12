# SchemaGuard 변경 이력

기존 `readme.md` 이후의 변경 사항을 기록합니다. 항목은 커밋 단위이며 최신 항목이 위에 옵니다.

## 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | HIGH 위험 없음, 모든 SQL 구문 파싱 성공 |
| 1 | HIGH 위험 변경이 API에 영향 |
| 2 | 예기치 못한 오류 (마이그레이션 파일 없음 등) |
| 3 | 파싱하지 못한 SQL 구문이 있음 (분석 불완전 → 차단) |

> `./gradlew run`으로 실행하면 Gradle이 0이 아닌 종료 코드를 모두 빌드 실패(1)로 바꿉니다. 구분된 코드가 필요하면 jar를 직접 실행하세요.

---

## 21. 비관적 락 분석 끄기 옵션(`--no-lock-scope`)과 SchemaGuard 자체 부담 측정

### 헷갈리기 쉬운 점: 두 가지 "성능"

| | 무엇의 성능 | 값 |
|---|---|---|
| 20번 부하 측정 | SchemaGuard가 **검사하는 애플리케이션**의 DB. 인덱스를 잘못 지운 뒤 배포하면 생기는 장애 | 재고 차감 TPS 675 → 6.3 |
| 이번 측정 | **SchemaGuard 자체**의 실행 시간. 비관적 락 분석을 켰을 때 늘어나는 시간 | 측정 편차 안 (아래) |

20번의 "100배 느려짐"은 SchemaGuard가 배포 **전에** 경고하려는 장애입니다. SchemaGuard가 느려진다는 뜻이 아닙니다.

### 옵션

- `--lock-scope`(기본: 켬) / `--no-lock-scope`(끔)
- 끄면 `LOCK_SCOPE_WIDENED` 경고를 만들지 않습니다. 콘솔의 `Lock methods: N` 줄과 인덱스 삭제 근거에 덧붙던 잠금 관련 문구도 나오지 않아, 리포트가 18번 이전과 같아집니다.
- `@Lock` 어노테이션은 끄더라도 리포지토리 파싱 때 함께 읽습니다. 어노테이션 하나를 읽는 정도라 따로 끄지 않았습니다.

### SchemaGuard 자체 부담

**조건 (가장 불리한 경우)**
- 16번의 5,000개 파일 샘플에서 **모든 조회 메서드에 `@Lock(PESSIMISTIC_WRITE)`를 붙인 사본**을 만들었습니다(리포지토리 1,250개, 잠금 메서드 2,500개).
- 마이그레이션 18개를 한 번에 분석하고 `--history`를 줬습니다. 그중 4개가 `DROP INDEX`입니다.
- `installDist` 바이너리로 실행해 3회 중앙값을 냈습니다(20번과 같은 4 vCPU 머신).

| 샘플 | 켬: 전체 시간 | 끔: 전체 시간 | 켬: 분석 단계 합계(18개분) | 끔: 분석 단계 합계 | 켬: 잠금 경고 |
|---|---:|---:|---:|---:|---:|
| 잠금 메서드 2,500개 | 5.76 s | 5.90 s | 97.5 ms | 98.0 ms | 6개 |
| 잠금 메서드 0개 | 6.04 s | 5.77 s | 96.7 ms | 102.9 ms | 0개 |

- 켰을 때와 껐을 때의 차이가 **회차 간 편차(±5% 정도)보다 작습니다.** 측정할 만한 부담이 없습니다.
- 이유는 이렇습니다.
  - 잠금 분석은 `DROP INDEX` / UNIQUE 삭제가 있는 마이그레이션에서만, 그 테이블의 잠금 메서드만 봅니다.
  - 메서드 이름이나 `@Query` WHERE를 한 번 읽는 정도라서, Java 파싱(약 4.2초)과 비교할 크기가 아닙니다.
- 그래서 **기본값은 켬**으로 두고, 끄는 옵션은 경고가 필요 없는 프로젝트(MySQL이 아니거나 비관적 락을 쓰지 않는 경우)를 위해 남겼습니다.

### 테스트

- `LockScopeTest.lockScopeCanBeTurnedOff`: 끄면 잠금 경고가 없고 `detectedChanges`가 `[LOW] DROP_INDEX` 하나만(18번 이전과 같음), 종료 코드 0. `--lock-scope`를 명시하면 기본값과 같음
- 전체 164개 통과

**관련 파일:** `cli/SchemaGuardCLI.java`, 테스트 `cli/LockScopeTest.java`

---

## 20. 비관적 락 + 인덱스 삭제 영향 탐지 (3단계: 실제 MySQL 검증)

18·19번의 정적 분석이 실제 MySQL에서 맞는지 쟀습니다. 확인한 것은 세 가지입니다.
- SchemaGuard가 경고한 인덱스 삭제에서 **실제로 잠금 범위가 넓어지는지**
- 경고하지 않은 삭제에서는 **넓어지지 않는지**
- 넓어지면 **동시 요청이 얼마나 느려지는지**

### 1. 측정 환경과 도구

| 항목 | 값 |
|---|---|
| MySQL | 8.0.46 (Ubuntu 패키지), 실험 전용 인스턴스(127.0.0.1:3307), `innodb_buffer_pool_size` 512MB, 그 밖은 기본값 |
| 격리 수준 | REPEATABLE READ(기본값). 비교용으로 READ COMMITTED |
| 머신 | 클라우드 컨테이너, 4 vCPU (16번과 같은 환경) |
| 데이터 | `stocks` 10만 행. 상품 5만 개 × 상태 2개(`ON_SALE`, `HIDDEN`) |
| 초기 인덱스(V1) | `idx_stocks_product_status (product_id, status)`, `idx_stocks_status (status)` |
| 대상 코드 | `scripts/lock-experiment/project/` — 18·19번 테스트와 같은 재고 차감 프로젝트 |
| 도구 | `scripts/lock-experiment/mysql-local.sh`(실험 전용 MySQL 시작·종료), `scripts/lock-experiment/run.py`(측정, SchemaGuard 실행, 비교) |

**대상 조회** (프로젝트의 리포지토리 메서드가 만드는 SQL과 같은 조건)

| 이름 | 리포지토리 메서드 | SQL | API |
|---|---|---|---|
| decrease | `findByProductIdAndStatus` (파생 쿼리 + `@Lock`) | `WHERE product_id = ? AND status = 'ON_SALE' FOR UPDATE` | `POST /stocks/decrease` |
| restock | `lockAllOfProduct` (JPQL `@Query` + `@Lock`) | `WHERE product_id = ? FOR UPDATE` | `POST /stocks/restock` |
| adjust | `findById` (PK + `@Lock`) | `WHERE id = ? FOR UPDATE` | `POST /stocks/{id}/adjust` |
| on-sale | `findByStatus` (잠금 없음) | `WHERE status = 'ON_SALE'` | `GET /stocks/on-sale` |

**측정 방법** — 시나리오마다 DB를 새로 만들어 V1과 데이터를 넣고 "삭제 전"을 잰 뒤, 시나리오 마이그레이션을 적용하고 "삭제 후"를 잽니다.
1. **잠긴 레코드 수:** 트랜잭션 A가 상품 25000을 조회만 한 상태(커밋 전)에서 `performance_schema.data_locks`의 RECORD 잠금 수를 셉니다. 인덱스와 잠금 모드별로도 나눕니다.
2. **다른 행 요청이 막히는가:** A가 잠금을 쥔 채로, 트랜잭션 B가 **다른 상품**(30000)을 같은 조회로 요청합니다. `innodb_lock_wait_timeout = 1`로 두고 1초 안에 못 얻으면 "막힘"입니다.
3. **EXPLAIN:** `type`, `key`, 예상 `rows`
4. **실측 정답(라벨):** 삭제 후 잠긴 레코드 수가 삭제 전보다 늘면 "잠금 범위 확대"입니다.
5. **SchemaGuard 예측:** 같은 프로젝트와 마이그레이션(V1 + 시나리오를 V2로)으로 CLI를 `--history`와 함께 실행합니다. `LOCK_SCOPE_WIDENED`가 붙은 API를 예측으로 봅니다.

**측정 도구에서 고친 버그:** 처음에는 트랜잭션 A를 `information_schema.innodb_trx`의 `trx_id`로 찾았습니다. 그런데 결과가 서로 맞지 않았습니다. 예를 들어 다른 행 요청은 막혔는데 잠금 수가 0으로 나왔습니다. 원인은 이 표가 **최대 0.1초마다 갱신되는 캐시**라서 직전 트랜잭션의 id를 돌려준 것이었습니다(직접 확인: 표는 1975, 실제 잠금은 1976번 트랜잭션). `performance_schema.threads`의 스레드 id로 A의 잠금을 찾도록 바꾼 뒤에는 세 번 돌려 모두 같은 결과가 나왔습니다.

### 2. 결과: 잠금 범위와 SchemaGuard 예측

**삭제 전 기준** (모든 시나리오 공통, V1 상태)

| 조회 | EXPLAIN | 잠긴 레코드 |
|---|---|---:|
| decrease | `ref`, `idx_stocks_product_status`, rows 1 | 3 (보조 인덱스 X + GAP, PK 1) |
| restock | `ref`, `idx_stocks_product_status`, rows 2 | 5 |
| adjust | `const`, `PRIMARY` | 1 |
| on-sale | 잠금 없음 | 0 |

**시나리오별 삭제 후**

| 시나리오 | 조회 | EXPLAIN (key, rows) | 잠긴 레코드 | 다른 행 막힘 | 실측 | SchemaGuard |
|---|---|---|---:|---|---|---|
| **S1** `(product_id, status)` 삭제, `(status)`만 남음 | decrease | `idx_stocks_status`, 49,995 | 3 → **100,068** | **막힘** | 확대 | **경고** |
| | restock | 없음(`ALL`), 99,990 | 5 → **100,303** | **막힘** | 확대 | **경고** |
| **S2** 두 인덱스 모두 삭제 | decrease | 없음(`ALL`), 99,990 | 3 → **100,303** | **막힘** | 확대 | **경고** |
| | restock | 없음(`ALL`), 99,990 | 5 → **100,303** | **막힘** | 확대 | **경고** |
| **S3** `(product_id)`를 만들고 `(product_id, status)` 삭제 | decrease | `idx_stocks_product`, 2 | 3 → **5** | 안 막힘 | 확대 | **경고** |
| | restock | `idx_stocks_product`, 2 | 5 → 5 | 안 막힘 | - | - |
| **S4** `(status, product_id)`를 만들고 `(product_id, status)` 삭제 | decrease | `idx_stocks_status_product`, 1 | 3 → 3 | 안 막힘 | - | - |
| | restock | 없음(`ALL`), 99,990 | 5 → **100,303** | **막힘** | 확대 | **경고** |
| **S5** 같은 컬럼으로 이름만 바꿈 | 둘 다 | `…_v2` | 같음 | 안 막힘 | - | - |
| **S6** UNIQUE `(product_id, status, quantity)`가 있는 상태에서 삭제 | 둘 다 | `uq_…` | 같음 | 안 막힘 | - | - |
| **S7** `(status)`만 삭제 | 둘 다 | `idx_stocks_product_status` | 같음 | 안 막힘 | - | - |
| S1~S7 | adjust (PK) | `PRIMARY` | 1 → 1 | 안 막힘 | - | - |
| S1~S7 | on-sale (잠금 없음) | - | 0 → 0 | - | - | - |

**정밀도·재현율** (시나리오 7개 × 조회 4개 = 28쌍)

| | 실측 확대 | 실측 확대 아님 |
|---|---:|---:|
| SchemaGuard 경고 | **TP 6** | FP 0 |
| 경고 없음 | FN 0 | **TN 22** |

정밀도 1.0, 재현율 1.0입니다. 측정을 세 번 반복했고 모두 같았습니다.

**결과에서 읽을 점**
- **S4가 19번에서 짚은 "조회마다 다르게 작용하는 교체"를 실측으로 확인했습니다.** `(status, product_id)`로 바꾸면 decrease는 잠금이 3개 그대로인데, restock은 테이블 전체(100,303개)를 잠그고 다른 상품 요청까지 막습니다. SchemaGuard는 restock만 경고했습니다.
- **잠긴 레코드 100,303개 = 10만 행 + α입니다.** 테이블 전체를 훑으며 PK의 모든 레코드에 next-key 잠금이 걸렸습니다. MySQL 문서의 "인덱스가 없으면 모든 행이 잠긴다"와 같습니다.
- **대조군(S5, S6, S7, PK 조회, 잠금 없는 조회)은 하나도 확대되지 않았고**, SchemaGuard도 경고하지 않았습니다.

### 3. 결과: 동시 요청 부하

- **조건:** 스레드 16개가 각자 무작위 상품(5만 개 중, 서로 거의 겹치지 않음)을 골라 잠금 조회 → 20ms 대기(작업 흉내) → 수량 갱신 → 커밋을 10초 동안 반복합니다. `innodb_lock_wait_timeout` = 5초, 3회 중앙값입니다.
- **편차:** 3회 결과의 편차가 1~3%로 작습니다(16번의 ±20%와 달리 이 측정은 잠금 대기가 지배하기 때문으로 보입니다).

| 조회 | 시나리오 | TPS | p50 | p95 | p99 | 잠금 대기 타임아웃 |
|---|---|---:|---:|---:|---:|---:|
| decrease | 삭제 전(S0) | **675.1** | 23.1 ms | 26.1 ms | 30.3 ms | 0 |
| decrease | S1 `(status)`만 남음 | **6.3** | 2,509.5 ms | 2,590.8 ms | 2,614.6 ms | 0 |
| decrease | S2 인덱스 없음 | **6.9** | 2,315.8 ms | 2,446.6 ms | 2,473.4 ms | 0 |
| restock | 삭제 전(S0) | **686.1** | 22.9 ms | 25.2 ms | 27.4 ms | 0 |
| restock | S4 `(status, product_id)`로 교체 | **8.1** | 1,980.7 ms | 2,134.7 ms | 2,161.3 ms | 0 |

- 서로 다른 상품을 다루는 요청인데도 **처리량이 약 100배 줄었습니다**(675 → 6.3). p50은 23ms에서 2.5초로 늘었습니다. 요청이 사실상 하나씩 줄을 섰기 때문입니다.
- 타임아웃은 0건이었습니다. 요청 하나가 테이블 전체를 잠그고 푸는 데 약 160ms가 걸리고 16개가 줄을 서면 대기는 약 2.5초인데, 이 값이 대기 한도 5초 안에 들어오기 때문입니다.
- **동시 요청을 늘리면 타임아웃이 납니다.** 스레드 32개, 잠금 유지 50ms로 1회 측정한 결과입니다.
  - S0: TPS 587.4, p99 60.6ms, 타임아웃 0
  - S1: TPS 5.7, p50 5,133.7ms, p99 5,480.9ms, **타임아웃 5건**
  - p50이 대기 한도(5초)에 닿았습니다. 실제 서비스에서 기본 한도(50초)라면 그 전에 HTTP 요청 타임아웃이나 커넥션 풀 고갈이 먼저 올 가능성이 큽니다(측정하지 않음).
- 데드락은 어느 경우에도 없었습니다. 모든 요청이 같은 순서(PK 순)로 테이블을 훑기 때문으로 보입니다.

### 4. 결과: READ COMMITTED

S2(인덱스 없음)를 READ COMMITTED로 다시 쟀습니다.

| 조회 | 잠긴 레코드 (REPEATABLE READ → READ COMMITTED) | 다른 행 막힘 |
|---|---|---|
| decrease | 100,303 → **1** | **막힘** (1.06초 대기 후 타임아웃) |
| restock | 100,303 → **2** | **막힘** |

- READ COMMITTED에서는 조건에 맞지 않는 행의 잠금을 바로 풀기 때문에, **남는 잠금은 1~2개**입니다.
- 그래도 **다른 상품 요청은 똑같이 막혔습니다.** B도 테이블 전체를 훑는데, 그 과정에서 A가 잡고 있는 행을 지나가려면 기다려야 하기 때문입니다.
- 18번에 쓴 "READ COMMITTED에서는 영향이 훨씬 작다"는 **틀린 설명이었습니다.** 18번 본문에 정정 메모를 달았고, 경고 문구도 고쳤습니다.
  - 콘솔 가이드: "MySQL InnoDB 기준. READ COMMITTED 는 남는 잠금이 적지만, 훑는 동안 다른 요청이 잡은 행에서 똑같이 기다린다"
  - 경고 근거: "(READ COMMITTED 도 훑는 중 다른 요청이 잡은 행에서 기다림)"

### 5. 위험도 결론: MEDIUM 유지, HIGH 승격은 하지 않음

18번에서는 "실측으로 정확도가 확인된 조건(예: 남은 인덱스 `r = 0`)만 HIGH 승격을 검토"하기로 했습니다. 실측해 보니 이 기준은 맞지 않습니다.

| 시나리오 | 정적 분석 판정 | 실제 잠긴 레코드 | 다른 행 막힘 | TPS |
|---|---|---:|---|---:|
| S1 decrease | **부분 확대** (`(status)`로 앞 1개, `r = 1`) | **100,068** | 막힘 | 6.3 |
| S2 decrease | 전체 훑기 (`r = 0`) | 100,303 | 막힘 | 6.9 |
| S3 decrease | **부분 확대** (`(product_id)`로 앞 1개, `r = 1`) | **5** | 안 막힘 | (측정 안 함, 잠금 2개 증가) |

- S1과 S3은 정적 분석상 똑같이 "부분 확대(`r = 1`)"입니다. 그런데 실제로는 S1이 전체 훑기와 같은 장애이고, S3은 같은 상품의 다른 상태 행 하나를 더 잠그는 정도입니다.
- 차이는 **남은 인덱스 앞 컬럼의 카디널리티**입니다. `status`는 값이 2개뿐이라 절반의 행이 걸리고, `product_id`는 상품마다 행이 2개뿐입니다.
- 카디널리티는 코드와 마이그레이션만으로는 알 수 없습니다. 그래서 `r = 0`이나 `r < d` 같은 정적 기준으로 HIGH를 나누면 S1을 놓치거나 S3을 과하게 막게 됩니다.
- **결론:**
  - 잠금 범위 확대 경고는 **MEDIUM(차단하지 않음)으로 유지합니다.** 확대 여부의 판정(정밀도·재현율 1.0)은 믿을 만하지만, 장애의 크기는 데이터가 정합니다.
  - 근거 문구의 "앞 컬럼 값이 같은 행을 모두 잠글 수 있음"이 바로 이 카디널리티 의존을 말합니다. 경고를 받으면 EXPLAIN의 `rows`를 확인하라는 콘솔 가이드가 그래서 필요합니다.
  - HIGH로 올리려면 실행 계획의 예상 행 수 같은 데이터 정보가 있어야 합니다. 예를 들어 스테이징 DB에 EXPLAIN을 돌리는 선택 옵션을 생각해 볼 수 있지만, 이번 범위에는 넣지 않았습니다.

### 6. 이 측정의 한계

- **표본이 작고 시나리오를 직접 설계했습니다.** 28쌍은 SchemaGuard를 만든 쪽이 고른 시나리오입니다. 실제 프로젝트의 인덱스 변경 이력으로 잰 것이 아니므로, 정밀도·재현율 1.0은 "설계한 규칙이 MySQL 동작과 일치한다"는 뜻이지 실제 프로젝트에서의 정확도는 아닙니다. 9번처럼 실제 프로젝트(community)로 재는 것이 다음 검증입니다.
- **합성 데이터 하나입니다.** 테이블 하나, 행 10만 개, 상태 2종입니다. 카디널리티와 데이터 분포가 다르면 부분 확대의 크기는 크게 달라집니다(위 5절).
- **"확대"는 잠긴 레코드 수가 조금이라도 늘면 참입니다.** S3처럼 2개 늘어난 것도 TP로 셌습니다. 크기를 구분한 지표가 아닙니다.
- **부하는 Python 스레드로 만든 합성 부하**이고, MySQL과 같은 머신에서 돌렸습니다. Spring 애플리케이션, 커넥션 풀, HTTP 타임아웃은 넣지 않았습니다.
- 19번의 판단하지 않는 경우(조인 잠금, 서브쿼리, `NOWAIT`/`SKIP LOCKED`)는 시나리오에 넣지 않았습니다.

### 7. 재현 방법

```bash
scripts/lock-experiment/mysql-local.sh start /tmp/lockexp-mysql
```
```bash
./gradlew installDist
```
```bash
pip install pymysql
```
```bash
python3 scripts/lock-experiment/run.py --out /tmp/lockexp-result --load --reps 3
```
```bash
scripts/lock-experiment/mysql-local.sh stop /tmp/lockexp-mysql
```

- 결과는 `<out>/results.json`에 저장됩니다. 시나리오·조회별 잠금 수(인덱스·모드별), 막힘 여부, EXPLAIN, SchemaGuard 경고와 근거, 혼동 행렬, READ COMMITTED 비교, 부하 결과(회차별·중앙값)가 들어 있습니다.
- `--load` 없이 돌리면 잠금 측정과 비교만 합니다(약 1분).
- 부하 조건은 `--threads`, `--seconds`, `--hold-ms`, `--timeout-s`로 바꿀 수 있습니다.
- 실험 전용 MySQL은 비밀번호 없는 root로 127.0.0.1에서만 받습니다. 측정이 끝나면 멈추고 데이터 폴더를 지우세요.

**관련 파일**
- 신규: `scripts/lock-experiment/mysql-local.sh`, `scripts/lock-experiment/run.py`, `scripts/lock-experiment/project/`(Java 소스 5개, `db/V1__init.sql`)
- 수정(READ COMMITTED 설명): `report/ConsoleReporter.java`, `analyzer/LockScopeAnalyzer.java`, CHANGELOG 18번

---

## 19. 비관적 락 + 인덱스 삭제 영향 탐지 (2단계: `@Query` 조건 해석)

18번 1단계는 파생 쿼리 이름(`findByProductIdAndStatus`)만 판단했습니다. `@Query`로 쓴 잠금 조회는 "판단하지 않은 비관적 락 조회"로 이름만 남겼습니다. 그런데 실무에서는 `@Lock` + `@Query("… where s.id = :id")`가 가장 흔한 형태라 이번에 그 WHERE 조건을 해석합니다. 판정 규칙(leftmost prefix, 남은 인덱스 기준, MEDIUM)은 18번과 같고, **조건을 어디서 얻느냐**만 넓어졌습니다.

### 1. 어떤 메서드를 잠금 조회로 보나

| 메서드 | 잠금 조회? | 조건 출처 | 리포트의 `lockMode` |
|---|---|---|---|
| 파생 쿼리 + `@Lock(PESSIMISTIC_*)` | 예 (18번) | 메서드 이름 | `PESSIMISTIC_WRITE` 등 |
| JPQL `@Query` + `@Lock(PESSIMISTIC_*)` | **예 (신규)** | WHERE 절 | `PESSIMISTIC_WRITE` 등 |
| 네이티브 `@Query`, SQL에 `FOR UPDATE` / `FOR SHARE` / `LOCK IN SHARE MODE` | **예 (신규). `@Lock`이 없어도** | WHERE 절 | `FOR UPDATE (네이티브 SQL)` |
| 네이티브 `@Query` + `@Lock`, SQL에 잠금 절 없음 | 예, 단 근거에 주의 문구 | WHERE 절 | `PESSIMISTIC_WRITE (네이티브)` |
| 네이티브 `@Query`, 잠금 절도 `@Lock`도 없음 | 아니오 | - | - |
| JPQL `@Query`, `@Lock` 없음 / `OPTIMISTIC` | 아니오 | - | - |

- 네이티브 쿼리는 JPA가 SQL을 고치지 않으므로, 잠금은 SQL에 쓴 `FOR UPDATE`로 걸립니다. 그래서 `@Lock`이 없어도 잠금 조회입니다.
- 네이티브 쿼리에 붙인 `@Lock`은 JPA 구현과 버전에 따라 적용되지 않거나 오류가 날 수 있습니다. 그래서 판정은 하되, 근거에 "네이티브 쿼리의 @Lock 은 JPA 구현에 따라 적용되지 않을 수 있음(SQL 에 FOR UPDATE 가 없음)"을 덧붙입니다.
- 어느 테이블의 잠금인지는 쿼리의 FROM으로 정합니다. JPQL은 FROM 엔티티의 테이블, 네이티브는 FROM 테이블입니다. 리포지토리의 엔티티와 다른 엔티티를 조회하는 쿼리도 맞는 테이블에 연결됩니다.

### 2. WHERE 조건 해석 (`QueryConditions`, 신규)

**방법**
1. 쿼리에서 FROM의 루트(엔티티 또는 테이블)와 별칭을 찾습니다.
   - JPQL은 조인 별칭을 루트 기준 경로로 기록합니다. 예: `join s.product p` → `p` = `product`, `left join p.maker m` → `m` = `product.maker`
   - 네이티브의 조인 별칭은 "다른 테이블"로 표시합니다.
2. `WHERE` 뒤부터 `ORDER BY` / `GROUP BY` / `HAVING` / `LIMIT` / `FOR UPDATE` 앞까지의 **조건식만** 떼어 JSQLParser(`parseCondExpression`)로 읽습니다. 쿼리 전체가 아니라 조건식만 읽는 이유는 JPQL 고유 문법(`join fetch`, `new …Dto(…)`)이 SELECT·FROM 쪽에 주로 있기 때문입니다. 조건식은 SQL과 거의 같고, `s.product.id` 같은 경로와 `:name` / `?1` 파라미터도 JSQLParser가 읽습니다(직접 확인함).
3. **전체를 다 읽어야 성공으로 칩니다**(`allowPartialParse = false`). 실제로 JSQLParser는 기본 설정에서 `s.tags is empty`를 `s.tags`까지만 읽고 뒤를 조용히 버렸습니다. 이렇게 일부만 읽은 결과로 판정하면 틀린 결론이 나므로, 끝까지 읽지 못하면 실패로 돌려줍니다. 실패한 메서드는 판단하지 않고 근거에 남깁니다. 예: `StockRepository.unreadable(@Query 해석 실패: WHERE 를 읽지 못함: s.tags is empty)`
4. **OR는 갈래로 펼칩니다**(논리합 표준형). 예: `a AND (b OR c)` → `[a, b]`, `[a, c]`. 갈래마다 18번 규칙으로 판정하고 **가장 나쁜 갈래**를 씁니다. 그래서 `s.id = :id OR (s.product.id = :p AND s.status = :st)`처럼 PK 갈래가 안전해도 다른 갈래가 인덱스를 잃으면 경고합니다. 갈래가 32개를 넘으면 판단하지 않습니다.

**조건별 인덱스 사용 가능 여부** (18번 파생 쿼리와 같은 기준)

| 조건 | 구분 |
|---|---|
| `col = 값`, `col IN (…)`, `col IS NULL`, `col = true` | 같음 |
| `col > / >= / < / <= 값`, `BETWEEN`, `LIKE 'abc%'`(앞이 고정된 문자열) | 범위 |
| `<>`, `NOT IN`, `NOT BETWEEN`, `IS NOT NULL`, `NOT (…)`, `LIKE :param`(패턴을 모름), `LIKE '%abc'` | 못 씀 |
| 함수로 감싼 컬럼(`upper(s.email) = upper(:e)`) | 못 씀 |
| 컬럼끼리 비교(`s.a = s.b`, 조인 조건) | 못 씀 |
| `EXISTS`, `MEMBER OF` 같은 그 밖의 식 | 안의 컬럼은 못 씀 |

- 컬럼이 오른쪽에 있어도 됩니다(`0 < s.qty`).
- JSQLParser가 `true`/`false`를 컬럼으로 읽는 것은 리터럴로 보정했습니다.

**경로 → 이 테이블의 컬럼** (`LockScopeAnalyzer.queryColumn`)

| 쿼리 | 경로 | 컬럼 |
|---|---|---|
| JPQL | `s.status` (루트 별칭) | `status` 필드의 컬럼 |
| JPQL | `s.product`, `s.product.id` | `product` 연관의 조인 컬럼(`product_id`) |
| JPQL | `join s.product p` 후 `p.id` | `product_id` |
| JPQL | 별칭 없는 `status` | 루트 엔티티의 속성 |
| JPQL | `p.name` (조인한 엔티티의 다른 속성) | 다른 테이블 → 그 메서드는 판단하지 않음 |
| 네이티브 | `product_id`, `s.product_id` (루트 별칭 또는 테이블 이름) | 그대로 |
| 네이티브 | `p.name` (조인한 테이블) | 다른 테이블 → 판단하지 않음 |

### 3. 쿼리 문자열 읽기 개선 (`RepositoryParser`)

- `@Query`의 문자열을 `MethodEntry`에 보관합니다(`query`, `nativeQuery`). 1단계의 `hasQuery`는 `query != null`로 바꿨습니다.
- 지금까지는 어노테이션 값의 소스 표현에서 따옴표만 뗐습니다. 그래서 아래 두 형태를 제대로 읽지 못했습니다.
  - 이어 붙인 문자열 `"select s from Stock s " + "where …"` → `select s from Stock s " + "where …`처럼 깨짐
  - 텍스트 블록 `"""…"""`
- 이제 문자열 리터럴과 텍스트 블록의 값을 이어 붙이고 공백을 하나로 줄입니다. 상수 참조(`QUERY + "…"`)가 섞이면 값을 알 수 없어서 예전처럼 소스 표현을 쓰고, WHERE 해석이 실패하면 판단하지 않습니다.
- 저장한 쿼리 문자열은 이번 기능에서만 씁니다(다른 곳에서 읽지 않음을 확인). 기존 JPQL `join fetch` 경로 추출(11번)은 이어 붙인 문자열에서 오히려 정확해집니다.

### 4. 함께 고친 버그: Java 17 문법 파일이 통째로 분석에서 빠짐

- **증상:** 텍스트 블록 테스트를 쓰다가, 그 리포지토리 파일이 `sourceParseFailures`에 들어가 분석에서 빠지는 것을 발견했습니다.
- **원인:** `JavaSourceScanner`가 JavaParser의 기본 언어 수준(`POPULAR` = Java 11 문법)으로 파싱했습니다. 그래서 **텍스트 블록, `record`, `sealed`/`permits`** 가 있는 파일은 통째로 파싱에 실패했습니다. 경고만 남고 그 파일의 엔티티·리포지토리·서비스·컨트롤러가 그래프에서 빠졌습니다.
- **영향:** Spring Boot 3은 Java 17 이상이라 DTO를 `record`로 쓰는 프로젝트가 많습니다. 13번의 DTO·매퍼 static 메서드 추적(`UserResponse.from(user)`)도 `record` DTO에서는 동작하지 않았던 셈입니다.
- **수정:** 언어 수준을 Java 17로 올렸습니다(symbol solver의 소스 파서 포함).
- **확인:**
  - 1단계 빌드로 `record StockResponse(...)` 파일이 있는 프로젝트를 돌리면 `sourceParseFailures: [com/shop/StockResponse.java]`가 나오고, 새 빌드는 `[]`입니다.
  - 신규 테스트 `JavaSourceScannerTest.java17SyntaxIsParsed`: record + static 팩토리, 텍스트 블록 `@Query`, `sealed`/`permits`, 패턴 매칭 `instanceof`가 모두 파싱되고, 텍스트 블록 쿼리 문자열이 한 줄로 읽힘

### 5. 결과

**1단계 테스트 프로젝트(재고 차감)에서 달라진 점.** `StockRepository.lockAllOfProduct`(`@Lock` + `@Query("select s from Stock s where s.product.id = :productId")`)를 이제 판정합니다. 1단계 테스트 6개의 기대값을 이에 맞게 바꿨습니다.

| 시나리오 | 1단계 | 2단계 |
|---|---|---|
| `(product_id, status)` 삭제, `(status)`만 남음 | `decrease`만 경고 | `decrease` + **`restock`** 경고 (`restock`의 조건 `product_id`에 쓸 인덱스가 없음) |
| 두 인덱스 모두 삭제 | `decrease` | `decrease` + `restock` |
| `(status, product_id)`를 만들고 `(product_id, status)` 삭제 | 경고 없음 | **`restock`만 경고.** `decrease`는 두 조건 모두 `=`라 순서가 달라도 앞 2개를 씀. `restock`은 조건이 `product_id` 하나라 맨 앞이 `status`인 인덱스를 쓰지 못함(leftmost prefix) |
| 짧은 대체 인덱스 `(product_id)` | `decrease` 부분 확대 | 같음 (`restock`은 `(product_id)`로 충분) |
| 엔티티 `@Index` 기준(`--history` 없음) | `decrease` | `decrease` + `restock` |
| 잠금 조회가 쓰던 UNIQUE 삭제 | HIGH + `decrease` | HIGH + `decrease` + `restock` |

세 번째 줄은 "같은 컬럼 집합이면 대체 인덱스"라고 단순하게 판단하면 놓치는 경우입니다. 조회마다 조건이 달라서, 같은 인덱스 교체가 한 조회에는 안전하고 다른 조회에는 위험할 수 있습니다.

**회귀**
- 잠금 메서드가 없는 16번 샘플(100 / 1,000개 × 18개)에서 1단계 빌드와 비교했습니다. `--history`가 있을 때와 없을 때 모두 JSON 리포트와 종료 코드가 같습니다.
- 결정성 스냅샷(8번)과 병렬 파싱 동일성 테스트(16번)도 그대로 통과합니다.

### 6. 테스트

- **신규 `QueryConditionsTest` 10개**
  - JPQL 루트·별칭·조건
  - 조인 별칭 경로(`join fetch s.product p left join p.maker m` → `p` = `product`, `m` = `product.maker`), 패키지까지 쓴 엔티티 이름
  - OR 펼치기
  - 연산자별 구분(위 표 전부, 컬럼이 오른쪽인 경우, 함수, 컬럼끼리 비교)
  - `true` 리터럴
  - 네이티브 `FOR UPDATE` / `FOR SHARE` / `LOCK IN SHARE MODE` 감지, 네이티브 조인 별칭
  - WHERE 없음, **끝까지 읽지 못하면 실패**(`is empty`), OR 갈래 초과(64갈래)
- **`LockScopeTest` 6개 추가** (전체 18개)
  - JPQL 잠금 조회 판정과 근거(`(PESSIMISTIC_WRITE, @Query, 조건: product_id)`), 대체 UNIQUE가 있으면 경고 없음
  - 컬럼 순서가 조회마다 다르게 작용(위 세 번째 줄)
  - 조인 별칭 `p.id`는 경고, PK 조회 `s.id`는 경고 없음, 다른 테이블 속성 `p.name`은 "속성을 컬럼으로 못 바꿈", 읽지 못하는 WHERE는 "@Query 해석 실패"
  - OR 갈래 중 가장 나쁜 갈래
  - 네이티브: `FOR UPDATE`만 있는 조회는 잠금(`FOR UPDATE (네이티브 SQL)`), 잠금 없는 네이티브 조회는 대상 아님, `@Lock`만 있는 네이티브 조회는 주의 문구
  - 이어 붙인 문자열과 텍스트 블록 `@Query`, 그 파일의 파싱 실패 없음
- **`JavaSourceScannerTest` 1개 추가:** Java 17 문법
- 전체 163개 통과

### 7. 한계

- **잠금 범위는 루트 테이블만 봅니다.** 조인이 있는 `FOR UPDATE`는 조인한 테이블의 행도 잠그지만, 그쪽 인덱스 삭제는 보지 않습니다. 조인한 테이블의 속성이 WHERE에 있으면 그 메서드는 판단하지 않습니다.
- **서브쿼리 조건**(`EXISTS (select …)`, `col IN (select …)`)은 "못 씀"으로 둡니다. `IN (select …)`은 실제로는 인덱스를 쓸 수 있어서 경고를 놓칠 수 있습니다(미탐 방향).
- **상수를 참조한 쿼리**(`@Query(Queries.LOCK_STOCK)`), `@NamedQuery`, `orm.xml`, QueryDSL·Specification·`EntityManager`로 만든 잠금 쿼리는 읽지 않습니다.
- **`FOR UPDATE NOWAIT` / `SKIP LOCKED`** 도 일반 `FOR UPDATE`와 같이 봅니다. 실제로는 다릅니다. `NOWAIT`은 기다리지 않고 바로 오류가 나고, `SKIP LOCKED`는 잠긴 행을 건너뛰어 대기가 아니라 "처리되지 않는 행"으로 나타납니다.
- **JPQL 함수**는 JSQLParser가 읽는 범위에서만 다룹니다. `treat()`, `key()`, `size()` 등 JPQL 전용 함수가 WHERE에 있으면 해석 실패로 판단하지 않습니다.
- 18번의 한계(옵티마이저의 실제 선택, 카디널리티, 갭 잠금 세부사항, FK 인덱스 삭제 오류)는 그대로입니다.

### 8. 다음 단계

(20번에서 진행) 18번의 3단계 계획(실제 MySQL 8.0으로 잠긴 레코드 수·EXPLAIN·부하 측정, 정밀도·재현율)을 진행합니다. 이번에 판정 대상이 된 `@Query` 잠금 조회와 컬럼 순서 사례(위 세 번째 줄)도 실측 시나리오에 넣습니다.

**관련 파일**
- 신규: `parser/QueryConditions.java`, 테스트 `parser/QueryConditionsTest.java`
- 수정: `analyzer/LockScopeAnalyzer.java`, `parser/RepositoryParser.java`, `model/RepositoryInfo.java`, `parser/JavaSourceScanner.java`(언어 수준), 테스트 `cli/LockScopeTest.java`, `parser/JavaSourceScannerTest.java`

---

## 18. 비관적 락 + 인덱스 삭제 영향 탐지 (1단계: 파생 쿼리)

> 인덱스 삭제 때문에 비관적 락 조회의 잠금 범위가 넓어질 **구조적 위험**이 있는지 판단합니다.
> 새 핵심 기능을 따로 붙인 것이 아니라 **15번 인덱스 삭제 분석의 정밀도를 높인 하위 기능**입니다.
>
> - 전: `DROP INDEX` → 일반 인덱스 삭제 → LOW
> - 후: `DROP INDEX` → 그 인덱스를 쓰던 잠금 조회 확인 → 대체 인덱스 검사 → 잠금 메서드를 거치는 API에 경고(MEDIUM)

### 1. 문제 상황

- DB 성능 최적화로 중복 인덱스를 정리하거나 인덱스를 다시 설계하다 보면 기존 인덱스를 지우게 됩니다.
- 지운 인덱스를 `SELECT … FOR UPDATE`가 쓰고 있었다면, 조회할 때 훑는 범위가 넓어지면서 **잠기는 범위도 넓어질 수 있습니다.**
- 그러면 동시 요청이 몰릴 때 잠금 대기, 응답 지연, 타임아웃이 생깁니다.
- SQL 문법도 맞고 API도 정상 동작하기 때문에, 일반적인 API 실행 테스트로는 찾기 어렵습니다. 개발 환경은 데이터가 적고 동시 요청이 없어서 아무 문제가 보이지 않습니다. 15번의 UNIQUE 삭제처럼 **배포 시점에는 오류가 나지 않는 장애**입니다.

15번까지 SchemaGuard는 일반 인덱스 삭제를 "기존 SQL을 실패시키지 않는다"는 이유로 LOW(영향 없음)로 분류했습니다. 15번의 한계에 "비관적 락 조회가 쓰던 인덱스가 사라지면 잠금 범위가 넓어진다. 다음 과제"라고 적어 둔 부분입니다.

### 2. 무슨 일이 생기나 (MySQL InnoDB)

| 단계 | 내용 |
|---|---|
| 잠금 쿼리 | `@Lock(LockModeType.PESSIMISTIC_WRITE)` 리포지토리 메서드는 MySQL에서 `SELECT … FOR UPDATE`, `PESSIMISTIC_READ`는 `SELECT … FOR SHARE`(`LOCK IN SHARE MODE`)가 됩니다. 둘 다 잠금을 겁니다. |
| 무엇을 잠그나 | InnoDB는 조건에 맞는 행만이 아니라 **찾는 동안 훑은 인덱스 레코드**를 잠급니다(기본 격리 수준 REPEATABLE READ의 next-key lock). |
| 인덱스가 있으면 | WHERE 컬럼에 쓸 수 있는 인덱스가 있으면 몇 줄만 훑고 몇 줄만 잠깁니다. |
| 인덱스가 사라지면 | 테이블 전체(또는 남은 인덱스로 좁힌 넓은 범위)를 훑으면서 **훑은 행을 모두 잠급니다.** MySQL 문서에도 "문장에 맞는 인덱스가 없어 테이블 전체를 훑어야 하면 테이블의 모든 행이 잠긴다"고 나옵니다. |
| 결과 | 쿠폰 발급, 재고 차감처럼 **서로 다른 행**을 다루는 요청들이 줄을 섭니다. `innodb_lock_wait_timeout`(기본 50초)을 넘기거나 데드락이 나면 예외로 실패하고, 애플리케이션이 처리하지 않으면 500 응답이 됩니다. JPA 잠금 타임아웃 힌트(`jakarta.persistence.lock.timeout`)를 쓰면 더 빨리 실패합니다. |

**이 위험의 범위**
- **격리 수준:** REPEATABLE READ 기준입니다. READ COMMITTED에서는 WHERE를 평가한 뒤 조건에 맞지 않는 행의 잠금을 바로 풀기 때문에 영향이 훨씬 작습니다.
  > **20번 실측으로 정정:** READ COMMITTED에서는 남는 잠금이 1~2개로 적은 것은 맞습니다. 하지만 인덱스 없이 훑는 조회는 다른 요청이 잡은 행에서 **똑같이 기다렸습니다**(다른 상품을 요청한 트랜잭션이 막힘). "영향이 훨씬 작다"는 틀린 설명이었고, 경고 문구도 고쳤습니다.
- **DB:** PostgreSQL의 `FOR UPDATE`는 조건에 맞아 **반환된 행만** 잠급니다. 인덱스가 사라지면 느려지기는 하지만 잠금 범위가 넓어지지는 않습니다. 그래서 경고 문구에 "MySQL InnoDB(REPEATABLE READ)"를 적습니다.
- **낙관적 락:** `OPTIMISTIC` 계열은 SELECT에 잠금을 걸지 않아 대상이 아닙니다.

### 3. 설계 결정

| 결정 | 이유 |
|---|---|
| **위험도는 MEDIUM(경고), 차단하지 않음** | 쿼리가 깨지는 게 아니라 성능·동시성 문제입니다. 실제로 남은 인덱스 중 무엇을 쓸지는 옵티마이저가 정하기 때문에, 정적 분석은 "가능성"까지만 말할 수 있습니다. 3단계 실측으로 정확도가 확인된 조건만 HIGH로 올리는 것을 검토합니다(12번에서 위험도를 실측으로 다시 정한 방식과 같음). |
| **삭제 전후 비교는 `--history`(17번)가 기준** | 지운 인덱스의 컬럼과 남은 인덱스 목록을 이전 마이그레이션에서 정확히 압니다. 이력이 없으면 엔티티 선언(`@Table(indexes)`, `@Column(unique)`, `@Id`)으로 대신하고, 근거에 "실제 DB 인덱스와 다를 수 있음"이라고 적습니다. |
| **인덱스 정의를 모르면 판단하지 않음** | "대체 인덱스 없음"이라고 단정하면 오탐이 됩니다. 정의를 모르는 삭제는 15번 원칙대로 이미 보수적으로 HIGH(UNIQUE로 간주)이고, 근거에 "비관적 락 조회 N개가 있지만 인덱스 정의를 몰라 잠금 범위 영향은 판단하지 못함 (--history 를 주면 …)"만 덧붙입니다. |
| **영향 경로는 잠금 메서드를 거치는 API만** | 같은 테이블의 다른 조회(잠금 없음)나 같은 리포지토리의 다른 메서드는 잠금과 무관합니다. 그래서 그 컬럼을 쓰는 모든 API가 아니라 잠금 메서드에서 시작합니다. |
| **별도 변경 종류 `LOCK_SCOPE_WIDENED`** | 원래의 `DROP_INDEX`(LOW)나 `DROP_UNIQUE`(HIGH)는 그대로 두고, 그 옆에 MEDIUM 경고를 덧붙입니다. UNIQUE 삭제처럼 원래 판정과 잠금 경고가 함께 있어야 하는 경우도 같은 방식으로 표현할 수 있습니다. 원래 변경의 근거에는 "비관적 락 조회 N개의 잠금 범위가 넓어질 수 있음 (LOCK_SCOPE_WIDENED 참고)"이라고 적습니다. |
| **단계 나누기** | 1단계 파생 쿼리 → 2단계 `@Query` → 3단계 실제 MySQL 검증. 이번 항목은 1단계입니다. |

### 4. 1단계 구현

**① `@Lock` 읽기** (`RepositoryParser`)
- 메서드의 `@Lock`에서 모드 이름을 읽습니다. `@Lock(LockModeType.PESSIMISTIC_WRITE)`, `@Lock(value = PESSIMISTIC_READ)`(static import) 모두 받습니다.
- `PESSIMISTIC_*`만 잠금으로 봅니다(`PESSIMISTIC_WRITE`, `PESSIMISTIC_READ`, `PESSIMISTIC_FORCE_INCREMENT`).
- `@Query`가 붙었는지도 기록합니다. `@Query` 메서드는 이름이 조회 조건이 아니라서 1단계에서는 판단하지 않습니다.
- `RepositoryInfo.MethodEntry`에 `lockMode`, `hasQuery`를 추가했습니다.

**② 잠금 조건 컬럼 찾기** (`DerivedQuery`, 신규)
- 파생 쿼리 이름을 조건으로 나눕니다. 예: `findByProductIdAndStatus` → `productId =`, `status =`
- 접두사는 `find/read/get/query/search/stream/count/exists/delete/remove`이고, `First`, `Top3`, `Distinct` 같은 주어 부분은 건너뜁니다.
- `Or`는 따로 찾는 갈래로 나눕니다. 갈래마다 판정하고 가장 나쁜 갈래를 씁니다.
- `OrderBy` 뒤는 정렬이라 버립니다.
- `Or`/`And` 뒤가 대문자일 때만 나눕니다. 그래서 `OrderNo`, `AndroidId`, `BrandName` 같은 속성 이름은 나누지 않습니다.
- **연산자마다 인덱스를 탈 수 있는지(seek) 표시합니다.** MySQL B-tree 기준입니다.

  | 구분 | 연산자 | 복합 인덱스에서 |
  |---|---|---|
  | 같음 | `=`, `In`, `IsNull`, `True/False` | 다음 컬럼도 쓸 수 있음 |
  | 범위 | `LessThan(Equal)`, `GreaterThan(Equal)`, `Between`, `Before/After`, `StartingWith` | 이 컬럼까지만 씀 |
  | 못 씀 | `Not`, `NotIn`, `Like`(패턴을 모름), `Containing`, `EndingWith`, `IsNotNull`, `IgnoreCase`(`UPPER(col)`로 바뀜), 컬렉션 연산 | 맞지 않은 것과 같음 |

- **속성을 컬럼으로 바꿉니다** (`LockScopeAnalyzer`). 리포지토리 엔티티의 매핑을 씁니다.
  - `status` → `status` 필드의 컬럼
  - `product` → `product` 연관의 조인 컬럼(`findByProduct(Product p)`)
  - `productId`, `product_Id` → `product` 연관의 조인 컬럼. 연관 엔티티의 id가 이 테이블의 FK 컬럼이기 때문입니다.
  - 연관 엔티티의 다른 속성(`productName` → `product.name`)은 조인이 필요해서 이 테이블의 컬럼이 아닙니다. 이런 조건이 있으면 그 메서드는 판단하지 않습니다.

**③ 삭제 전후 인덱스 목록** (`IndexCatalog`, `SqlMigrationParser`)
- `SqlMigrationParser.catalogAfterParse()`: 이력과 이 파일의 구문을 모두 적용한 **마이그레이션 적용 후** 인덱스 목록입니다. 같은 파일에서 대체 인덱스를 먼저 만들고 지우는 경우도 반영됩니다.
- `IndexCatalog.indexesOf(table)`, `knowsTable(table)`을 추가했습니다. `knowsTable`은 이력에 그 테이블의 `CREATE TABLE`이나 인덱스가 있는지 봅니다. 없으면 남은 인덱스를 이력으로 판단할 수 없어서 엔티티 선언 기준으로 바꿉니다.
- `EntityMapping`에 `@Id` 여부(`isId`)를 추가했습니다. 이력이 없을 때 PK를 남은 인덱스로 칩니다.
- **남은 인덱스 기준**

  | 조건 | 남은 인덱스 |
  |---|---|
  | `--history`가 있고 이력이 그 테이블을 앎 | 이력 + 이 파일을 적용한 뒤의 PK, UNIQUE, 일반 인덱스, FK |
  | 그 밖 | 엔티티 선언(`@Id`, `@Table(indexes / uniqueConstraints)`, `@Column(unique)`)과 이 파일의 구문. 이 마이그레이션에서 지운 이름은 엔티티에 남아 있어도 뺍니다. |

  FK 제약은 남은 인덱스로 칩니다. MySQL은 FK 컬럼에 인덱스를 요구해서, FK가 남아 있으면 그 컬럼으로 시작하는 인덱스도 남아 있기 때문입니다.

**④ 판정 규칙** (`LockScopeAnalyzer`, 신규)
1. **지운 인덱스가 이 조회에 쓰일 수 있었나:** 인덱스 맨 앞 컬럼부터 조건 컬럼과 맞는 개수 `d`를 셉니다(leftmost prefix). 같음 조건이면 다음 컬럼으로 넘어가고, 범위 조건이면 그 컬럼에서 멈춥니다. `d = 0`이면 원래 이 조회에 쓰이지 않던 인덱스라서 위험이 없습니다.
2. **남은 인덱스로 얼마나 쓸 수 있나:** 남은 인덱스마다 같은 방식으로 세서 가장 큰 값 `r`을 구합니다.
3. **`r < d`이면 잠금 범위 확대 가능** → `LOCK_SCOPE_WIDENED`(MEDIUM)
   - `r = 0`: "남은 인덱스 중 이 조건에 쓸 수 있는 것이 없음 → 테이블을 훑으며 훑은 행을 모두 잠글 수 있음"
   - `0 < r < d`: "남은 인덱스 X 로는 앞 r개 컬럼만 쓸 수 있음(삭제 전 d개) → 앞 컬럼 값이 같은 행을 모두 잠글 수 있음"
4. **`@Lock`을 붙인 `findById`는 항상 안전합니다.** PK가 남기 때문입니다.
5. **한 마이그레이션에서 같은 잠금 조회가 쓰던 인덱스 여러 개를 함께 지우면** 경고는 잠금 메서드마다 하나입니다. 그 조회가 가장 많이 쓰던 인덱스(`d`가 큰 것)를 원인으로 잡습니다.
6. 같은 점수의 대체 인덱스가 여럿이면 이름순으로 첫 번째를 근거에 적습니다. 그래서 결과가 입력 순서에 좌우되지 않습니다.

| 예 (잠금 조건: `product_id =`, `status =`) | `d` | 남은 인덱스 | `r` | 결과 |
|---|---:|---|---:|---|
| `(product_id, status)` 삭제, `(status)`만 남음 | 2 | `idx_stocks_status (status)` | 1 | **MEDIUM**, 부분 확대 |
| 위 두 인덱스를 모두 삭제 | 2 | PK `(id)` | 0 | **MEDIUM**, 전체 훑기 |
| `(product_id)`를 새로 만들고 `(product_id, status)` 삭제 | 2 | `(product_id)` | 1 | **MEDIUM**, 부분 확대 |
| `(status, product_id)`를 새로 만들고 삭제 | 2 | `(status, product_id)` | 2 | 경고 없음 (둘 다 `=`라 순서 무관) |
| `UNIQUE (product_id, status, quantity)`가 남음 | 2 | 그 UNIQUE | 2 | 경고 없음 |
| `(status)`만 삭제 | 1 | `(product_id, status)` | 2 | 경고 없음 |

**⑤ 영향 경로** (`ImpactAnalyzer`)
- `LOCK_SCOPE_WIDENED`의 시작점은 그 잠금 메서드 노드(`repo:StockRepository.findByProductIdAndStatus`) 하나입니다.
- UNIQUE 삭제의 쓰기 경로처럼, 경로 맨 앞에 지운 인덱스의 첫 컬럼을 붙여 왜 그 메서드인지 보이게 합니다.

  ```
  -> 컬럼 : stocks.product_id
    -> Repository : StockRepository.findByProductIdAndStatus   (StockRepository.java:3)
      -> Service : StockService.decrease
        -> Controller : StockController.decrease
          -> API : POST /stocks/decrease
  ```

**⑥ 리포트**
- `detectedChanges`: `[MEDIUM] LOCK_SCOPE_WIDENED on stocks.product_id (INDEX: idx_stocks_product_status) (LOCK: StockRepository.findByProductIdAndStatus)`
- JSON `cause`에 `lockMethod`, `lockMode`를 추가했습니다. 잠금 경고일 때만 넣어서 다른 변경의 리포트 모양은 그대로입니다.
- 근거(`note`) 예:
  > 비관적 락 조회 StockRepository.findByProductIdAndStatus (PESSIMISTIC_WRITE, 조건: product_id, status) 가 쓸 수 있던 인덱스 idx_stocks_product_status (product_id, status) 삭제. 남은 인덱스 idx_stocks_status (status) 로는 앞 1개 컬럼만 쓸 수 있음(삭제 전 2개) → 앞 컬럼 값이 같은 행을 모두 잠글 수 있음. MySQL InnoDB(REPEATABLE READ)는 찾는 동안 훑은 인덱스 레코드를 잠그므로 동시 요청이 잠금 대기·타임아웃으로 실패할 수 있음. 남은 인덱스 기준: 마이그레이션 이력(--history)과 이 파일을 적용한 뒤의 인덱스
- 판단하지 않은 잠금 조회는 원래 변경의 근거에 이름을 남깁니다. 예: `잠금 영향을 판단하지 않은 비관적 락 조회: StockRepository.lockAllOfProduct(@Query)`
- 콘솔
  - 수정 가이드: EXPLAIN의 `type/key/rows` 확인, 서로 다른 행을 다루는 동시 요청의 대기·타임아웃 확인, 격리 수준 안내
  - 추천 수정: 잠금 조건 컬럼으로 시작하는 인덱스를 남기거나 대체 인덱스를 먼저 만든 뒤 삭제, 잠금 타임아웃 힌트 확인
  - 소스 요약에 잠금 메서드가 있을 때만 `Lock methods: N (@Lock PESSIMISTIC_*)` 줄을 출력합니다.
- 종료 코드: MEDIUM이라 이 경고만으로는 차단하지 않습니다(0). UNIQUE 삭제와 함께면 UNIQUE 쪽 HIGH로 1입니다.

### 5. 검증

**테스트** — 재고 차감 시나리오(`LockScopeTest`)

테스트 프로젝트 구성:
- `Stock`(`product_id` FK, `status`, `quantity`)
- `StockRepository`
  - `@Lock(PESSIMISTIC_WRITE) findByProductIdAndStatus`
  - `@Lock(PESSIMISTIC_WRITE) findById`
  - `@Lock + @Query lockAllOfProduct`
  - 잠금 없는 `findByStatus`
- 다른 테이블의 `ProductRepository`
- V1: `(product_id, status)`, `(status)` 인덱스

| 구분 | 시나리오 | 기대 결과 |
|---|---|---|
| 위험 | `(product_id, status)` 삭제 | `POST /stocks/decrease`만 MEDIUM, 종료 코드 0, 경로는 `col:stocks.product_id → repo:…findByProductIdAndStatus → …` |
| 위험 | 위 근거 | "앞 1개 컬럼만 쓸 수 있음(삭제 전 2개)", 기준 "마이그레이션 이력(--history)" |
| 위험 | 두 인덱스 모두 삭제 | 경고 하나(원인은 `(product_id, status)`), "쓸 수 있는 것이 없음" |
| 위험 | 짧은 대체 인덱스 `(product_id)` | 부분 확대로 MEDIUM |
| 위험 | 잠금 조회가 쓰던 UNIQUE 삭제 | `DROP_UNIQUE` HIGH(쓰기 경로) + `LOCK_SCOPE_WIDENED` MEDIUM 함께 |
| 대조군 | 동등한 대체 인덱스 `(status, product_id)` | 경고 없음 |
| 대조군 | 대체 UNIQUE `(product_id, status, quantity)`가 남음 | 경고 없음 |
| 대조군 | 잠금 조회가 쓰지 않던 `(status)` 삭제 | 경고 없음, `DROP_INDEX` LOW 그대로 |
| 대조군 | PK 잠금(`findById`), 잠금 없는 조회(`findByStatus`), 다른 테이블(`products`) | 어느 시나리오에서도 경고 없음 |
| 정의 모름 | `--history` 없이 삭제 | 보수적 HIGH(15번), 잠금 경고 없음, 근거에 "잠금 조회 3개 … 판단하지 못함 (--history 를 주면 …)" |
| 엔티티 기준 | `--history` 없이, 엔티티 `@Index`로 정의를 앎 | MEDIUM, 근거에 "--history 가 없어 실제 DB 인덱스와 다를 수 있음" |
| 판단 안 함 | `@Query` 잠금 조회 | 경고 없이 근거에 이름만 |
| 속성 해석 | `findByProduct`, `findByProduct_IdAndStatus`(`PESSIMISTIC_READ`), `OPTIMISTIC` | 앞의 둘은 조인 컬럼으로 해석해 경고, `OPTIMISTIC`은 대상 아님 |

`DerivedQueryTest` 8개:
- 같음·And, Or 갈래, 속성 이름 안의 키워드(`OrderNo`, `AndroidId`, `BrandName`, `Status`), `OrderBy`
- 연산자별 seek 구분, `IgnoreCase`/`AllIgnoreCase`, 파생 쿼리가 아닌 이름
- leftmost prefix 계산(복합 인덱스의 중간이 끊기는 경우, 맨 앞이 조건이 아닌 경우, 범위 조건에서 멈춤, 못 쓰는 조건)

전체 146개가 통과합니다(새로 20개).

**회귀:** 잠금 메서드가 없는 16번 샘플(100 / 1,000개 × 18개)에서 18번 변경 전 빌드와 비교했습니다. `--history`가 있을 때와 없을 때 모두 JSON 리포트와 종료 코드가 같고, 콘솔 출력도 바이트 단위로 같습니다.

### 6. 한계 (1단계)

- **`@Query`(JPQL·네이티브)로 쓴 잠금 조회는 판단하지 않습니다.** `@Lock` + `@Query("… where s.id = :id")`가 실무에서 흔한 형태라 2단계가 꼭 필요합니다. 지금은 근거에 이름만 남깁니다. → **19번에서 해결.**
- **어노테이션이 아닌 잠금은 보지 않습니다.** `EntityManager.find(…, LockModeType)`, `Query.setLockMode`, QueryDSL의 `setLockMode`가 그렇습니다.
- **인덱스가 있어도 많이 잠기는 경우는 보지 않습니다.** `findByStatus`처럼 값 종류가 적은 컬럼이 그렇습니다. 판정은 인덱스 유무와 leftmost prefix뿐이고 데이터 분포(카디널리티)는 모릅니다.
- **범위 조건의 갭 잠금 세부사항은 다루지 않습니다.** 범위 조건은 "그 컬럼까지만 쓴다"는 규칙만 적용합니다.
- **FK가 쓰던 인덱스를 지우는 경우:** MySQL은 다른 인덱스가 대신하지 않으면 삭제를 거부합니다(오류 1553). 이 경우는 잠금 위험보다 마이그레이션 실패가 먼저인데, 아직 따로 알리지 않습니다.
- **옵티마이저의 실제 선택은 모릅니다.** 쓸 수 있는 인덱스가 남아 있어도 옵티마이저가 다른 계획을 고를 수 있습니다. 그래서 MEDIUM입니다.
- **연관의 다른 속성 조건(`findByProductName`)이나 `@Embedded` 속성**은 컬럼으로 바꾸지 못해 판단하지 않습니다.

### 7. 다음 단계

**2단계: `@Query` 조건 해석** (19번에서 구현)
- JPQL의 `where s.product.id = :productId and s.status = :status`에서 별칭.속성을 컬럼으로 바꿉니다.
- 네이티브 쿼리는 JSQLParser로 WHERE를 읽습니다.
- 지금 "판단하지 않은 잠금 조회"로 남기는 메서드를 판정 대상으로 넣는 것이 목표입니다.

**3단계: 실제 MySQL 검증** (20번에서 진행) — 이 환경에서 apt로 MySQL 8.0.46을 설치할 수 있는 것을 확인했습니다.

1. **문제 재현**
   - 재고 10만 건을 준비합니다.
   - 트랜잭션 A가 `SELECT … FOR UPDATE`(상품 1)를 잡은 채로, 트랜잭션 B가 **다른 상품**을 차감할 때 막히는지 봅니다.
   - 인덱스 삭제 전후로 비교합니다. 잠금을 쥔 트랜잭션을 따로 두어야 차이가 드러납니다.
2. **지표**
   - 1순위: `performance_schema.data_locks`의 **잠긴 레코드 수**. 결정적이라 SchemaGuard 경고가 맞았는지 판정하는 기준(정답 라벨)으로 씁니다.
   - 실행 계획: `EXPLAIN`의 접근 방식(`type`, `key`)과 예상 스캔 행 수(`rows`)
   - 부하: 동시 차감 요청의 TPS, p95/p99 응답 시간, 잠금 대기 시간, 타임아웃 건수. 이 값들은 편차가 크므로(16번에서 ±20%) 여러 번 재서 중앙값을 씁니다.
3. **고정할 조건:** 격리 수준(REPEATABLE READ, 비교용으로 READ COMMITTED도), MySQL 버전, `innodb_lock_wait_timeout`, 데이터 건수
4. **오탐 검증:** 이번 테스트의 대조군(대체 인덱스, PK 조회, 잠금 없는 조회, 다른 리포지토리)을 실측에도 넣고, 경고의 **정밀도·재현율**을 9번처럼 계산합니다.
5. **위험도 조정:** 실측으로 정확도가 충분히 확인된 조건(예: 남은 인덱스 `r = 0`)만 HIGH 승격을 검토합니다. → 20번 실측 결과, 잠금 범위의 크기는 카디널리티가 정해서 정적 기준으로는 HIGH를 나눌 수 없음. MEDIUM 유지.

**관련 파일**
- 신규: `analyzer/LockScopeAnalyzer.java`, `parser/DerivedQuery.java`
- 수정: `parser/RepositoryParser.java`, `model/RepositoryInfo.java`, `model/EntityMapping.java`, `parser/EntityParser.java`, `parser/IndexCatalog.java`, `parser/SqlMigrationParser.java`, `model/ChangeType.java`, `model/SchemaChange.java`, `analyzer/ImpactAnalyzer.java`, `report/JsonReporter.java`, `report/ConsoleReporter.java`, `cli/SchemaGuardCLI.java`
- 테스트(신규): `cli/LockScopeTest.java`, `parser/DerivedQueryTest.java`

---

## 17. 이전 마이그레이션 읽기를 옵션으로 (`--history`), "Flyway" 표현 정리

### 문제

15번부터 SchemaGuard는 검사 파일과 **같은 폴더**에 있는 이전 마이그레이션을 **자동으로** 읽었습니다. `DROP INDEX`의 대상(컬럼, UNIQUE 여부)을 찾기 위해서입니다. 설명에는 "Flyway 이름 규칙", "Flyway 버전 순서"라고 적었습니다. 이 방식에는 두 가지 문제가 있었습니다.

- **Flyway를 지원하는 것처럼 읽힙니다.** 실제로는 Flyway 라이브러리도, 설정(적용 이력 테이블, `locations`)도 보지 않습니다. 파일 이름의 `V<번호>`만 봅니다.
- **사용자가 모르는 사이에 결과가 달라집니다.** 같은 SQL이라도 그 폴더에 어떤 파일이 함께 있느냐에 따라 판정이 바뀝니다. 폴더 안의 파일이 실제로 적용된 이력인지도 알 수 없습니다. 15번의 community 사례처럼 서로 독립된 시나리오 파일이 있는 폴더라면 이력이 아닙니다.

### 변경

**1. 표현 정리**
- 코드 주석, 옵션 설명, 테스트 이름, CHANGELOG 15·16번의 "Flyway 이름 규칙 / Flyway 버전 순서"를 **"파일 이름의 V 번호(순서)"** 로 바꿨습니다.
- 테스트 이름도 `earlierVersionsInFlywayOrder`에서 `earlierFilesInVNumberOrder`로 바꿨습니다.
- `MigrationHistory` 주석에 "마이그레이션 도구의 설정은 읽지 않고 파일 이름만 본다"를 적었습니다.
- 9번의 측정 환경 설명("Flyway, Liquibase, `spring.sql.init`은 끕니다")은 그대로 뒀습니다. 이 문장은 측정한 앱의 실제 Flyway 설정을 끈다는 뜻이고, SchemaGuard의 순서 규칙과는 관계가 없습니다.

**2. `--history <폴더>` 옵션 (기본은 읽지 않음)**

| | 기본 (옵션 없음) | `--history <폴더>` |
|---|---|---|
| 이전 마이그레이션 | **읽지 않음** | 그 폴더에서 검사 파일보다 **파일 이름의 V 번호가 작은** `.sql`을 번호 순서로 읽음 (15번 방식) |
| `DROP INDEX`의 정의 찾기 | 검사 파일 안의 앞선 구문 → 엔티티 선언(`@Table`, `@Column(unique)`) → 그래도 모르면 보수적으로 UNIQUE | 이전 마이그레이션 → 검사 파일 안의 앞선 구문 → 엔티티 선언 → 보수적으로 UNIQUE |
| 콘솔 | `Migration history` 줄 없음 | `Migration history: N earlier file(s), M index/constraint definition(s)` |

- 이력 폴더는 검사 파일과 다른 폴더여도 됩니다. 번호만 비교합니다.
- 검사 파일 이름에 V 번호가 없으면 앞선 파일을 알 수 없습니다. 그래서 옵션을 줘도 이력을 쓰지 않고 `Migration history: not used (file name has no V number: …)`를 출력합니다.
- 폴더가 없으면 종료 코드 2입니다.
- **옵션 없이 정의를 찾지 못했을 때는 근거에 그 사실을 적습니다.** 사용자가 옵션이 있다는 것을 알 수 있게 하려는 것입니다.
  - 리포트 `note`: `인덱스 idx_… 의 정의를 이전 마이그레이션과 엔티티에서 찾지 못함 (이전 마이그레이션은 --history 를 줄 때만 읽음): UNIQUE 인지 알 수 없어 …`
  - 테이블을 모르는 `DROP INDEX name`의 파싱 실패 사유에도 같은 문구를 붙입니다.
  - `--history`를 주면 문구는 15번과 같습니다.
- 여러 마이그레이션을 한 번에 분석할 때(16번)도 같습니다. 각 파일은 `--history` 폴더에서 자기보다 V 번호가 작은 파일을 이력으로 씁니다.
- 폴더를 `--migration`으로 줄 때 분석 순서를 V 번호로 정하는 것(16번)은 그대로입니다. 이것은 리포트 순서일 뿐 판정에는 영향이 없습니다.

**3. 이력을 쓰던 곳에 옵션 명시**
- `examples/github-actions/schemaguard.yml`: `--history "$GITHUB_WORKSPACE/$MIGRATION_DIR"`를 넣었습니다. 파일 이름이 `V<번호>__<설명>.sql` 규칙이 아니면 이 줄을 지우라고 주석에 적었습니다.
- `scripts/accuracy/predict.sh`: 9·15번 측정과 같은 조건이 되도록 `--history`를 넣었습니다.

### 결과

**동작 비교** (16번 샘플 100 / 1,000개 × 마이그레이션 18개, 폴더 한 번 실행)

| 비교 | 결과 |
|---|---|
| 변경 전(이전 파일 자동 읽기) vs `--history` | 18개 JSON 리포트가 **바이트 단위로 같음**, 종료 코드 같음 |
| 변경 전 vs 옵션 없음 | `DROP INDEX` 4개 파일만 다름 (아래) |

| 파일 | 변경 전 / `--history` | 옵션 없음 |
|---|---|---|
| `DROP INDEX idx_t15_name ON t15` (V1의 일반 인덱스) | `[LOW] DROP_INDEX on t15.name` | `[HIGH] DROP_UNIQUE on t15` (정의를 몰라 보수적) |
| `DROP INDEX uq_t8_email ON t8` (V1의 UNIQUE) | `[HIGH] DROP_UNIQUE on t8.email` | `[HIGH] DROP_UNIQUE on t8` (컬럼을 모름) |

이 샘플에는 저장 경로(`save*`)가 없어서 4개 모두 영향 API가 0개이고 종료 코드도 0으로 같습니다. 저장 경로가 있는 프로젝트에서는 옵션 없이 **일반 인덱스 삭제가 차단될 수 있습니다**(아래 `SchemaGuardCLITest` 참고). 15번에서 정한 "모르면 보수적으로" 원칙에 따른 결과입니다.

**워크플로 시뮬레이션** (16번과 같은 방식): `--history`를 넣은 수정본으로 16번의 시나리오를 다시 돌렸습니다. 판정이 같습니다(HIGH·파싱 실패 차단, 주입 시도 파일명은 실행 안 됨, 안전한 파일 하나는 병합 가능).

### 테스트

- `SchemaGuardCLITest`: 이력을 쓰는 기존 3개에 `--history`를 넣었습니다. 4개를 추가했습니다.
  - 같은 폴더에 V1이 있어도 옵션이 없으면 읽지 않음: 일반 인덱스 삭제가 보수적으로 `DROP_UNIQUE`, `note`에 `--history` 안내
  - 이력 폴더가 검사 파일과 다른 폴더여도 됨
  - V 번호 없는 검사 파일은 옵션을 줘도 이력 없음
  - 없는 이력 폴더는 종료 코드 2
- `MultiMigrationTest`: 기존 비교에 `--history`를 넣었습니다. 1개를 추가했습니다. 옵션 없이 폴더를 분석하면 V4(일반 인덱스 삭제)가 차단되고 리포트에 안내가 있음
- `MigrationHistoryTest` 1개 추가: 이력 폴더가 다를 때, `hasVersion`
- 전체 126개 통과

### 한계

- 옵션 없이는 이전 마이그레이션에만 정의가 있는 **일반 인덱스 삭제도 차단**됩니다. 엔티티에 `@Index`로 선언되어 있으면 통과합니다. V 번호 규칙을 쓰는 프로젝트는 CI에서 `--history`를 주는 것이 좋습니다.
- `--history`의 동작은 15번 그대로입니다. 한 폴더, 하위 폴더 제외, 이름에 V 번호가 있는 파일만 봅니다. 폴더 안의 파일이 실제로 적용된 이력이라고 가정합니다.

---

## 16. 여러 마이그레이션을 한 번에 분석, 파일 단위 병렬 파싱

10번 측정에서 남은 CI 시간의 대부분은 JVM 시작과 Java 파싱이었습니다. 이번에는 두 가지를 바꿨습니다.

| | 문제 | 변경 |
|---|---|---|
| 1 | 워크플로(5번)가 SQL 파일마다 CLI를 따로 실행해서, 마이그레이션이 18개면 Java 소스를 18번 다시 파싱했습니다(community 기준 약 19초, 10번). | `--migration`이 파일·폴더를 여러 개 받습니다. Java 파싱과 그래프 생성은 **한 번만** 하고, 마이그레이션마다 분석·리포트만 따로 합니다. |
| 2 | 5,000개 기준 전체 시간의 약 90%가 Java 파싱인데 한 스레드로만 했습니다. 6번에서는 `StaticJavaParser`가 전역 설정을 공유한다는 이유로 미뤘습니다. | 14번에서 `JavaSourceScanner`가 스캔마다 `JavaParser` 인스턴스를 쓰게 바뀌어 막는 것이 거의 없어졌습니다. 파일 단위로 병렬 파싱합니다(`--threads`). |

### 1. 여러 마이그레이션을 한 번에 분석

**사용법**

```
# 폴더: 안의 .sql 을 파일 이름의 V 번호 순서로
lib --source src/main/java --migration src/main/resources/db/migration --report-dir build/reports/schemaguard

# 파일 여러 개 (-m a b c, 또는 -m 반복)
lib --source src/main/java --migration V16__a.sql V17__b.sql --report-dir build/reports/schemaguard
```

- `--report-dir`에 `<마이그레이션 파일명>.json`을 파일마다 쓰고, `results.tsv`(파일, 종료 코드, 리포트 경로)를 남깁니다. 기존 워크플로가 직접 만들던 `results.tsv`와 같은 형식입니다.
- `--report-dir`를 주면 파일이 하나여도 이 방식으로 동작합니다. 그래서 CI에서 바뀐 SQL이 하나뿐이어도 `results.tsv`가 생깁니다.
- 콘솔에는 파일마다 `==== [i/N] <경로> ====` 구역이 나오고, 마지막에 파일별 종료 코드 요약이 나옵니다.
- 마이그레이션 하나에 `--report-dir` 없이 실행하면(기존 사용법) 출력과 리포트가 전과 **바이트 단위로 같습니다.**

**규칙**

| 항목 | 동작 | 이유 |
|---|---|---|
| 분석 순서 | 인자 순서와 상관없이 파일 이름의 V 번호 순서(V2 < V10). 번호 없는 파일은 이름순으로 뒤에. 같은 파일을 두 번 주면 한 번만 | 리포트·요약 순서가 매번 같게 |
| 이력(15번) | 파일마다 따로 실행할 때처럼, **그 파일보다 V 번호가 작은 파일**에서 인덱스 정의를 읽음. 17번부터는 `--history`를 줄 때만 | 함께 분석하는 파일끼리 영향을 주지 않아 리포트가 따로 실행한 것과 같음. 처음에는 V 번호 순서대로 카탈로그를 누적하는 방식을 생각했지만, 그러면 같은 결과를 보장하려고 따로 증명할 게 늘어남. SQL 이력 읽기는 수 ms라 이득도 없음 |
| Java 소스 | 변경이 있는 첫 마이그레이션에서 파싱하고 그래프를 만든 뒤 재사용. 변경이 하나도 없으면 파싱하지 않음(전과 같음) | 영향 분석(`ImpactAnalyzer`)과 재분류(`ChangeClassifier`)는 그래프를 바꾸지 않음을 확인함 |
| 한 파일 실패 | 오류를 출력하고 나머지를 끝까지 분석. 그 파일의 코드는 2 | 워크플로의 "한 파일이 실패해도 계속" 동작을 유지 |
| 전체 종료 코드 | 가장 심각한 것: **2(실행 오류) > 3(분석 불완전) > 1(HIGH) > 0** | 하나라도 차단이면 차단 |
| 잘못된 사용 | `--report-path`에 여러 파일, `--report-path`와 `--report-dir`을 함께 줌, 다른 폴더의 같은 파일명(리포트 이름 충돌), `.sql`이 없는 폴더는 종료 코드 2 | 리포트를 덮어쓰거나 조용히 빠뜨리지 않게 |
| json 모드 기본 폴더 | 여러 개 + `--output json` + `--report-dir` 없음 → `./schemaguard-reports` | 기존 `--report-path` 기본값과 같은 취지 |

### 2. 파일 단위 병렬 파싱

**방법** (`JavaSourceScanner.scan(files, sourceRoot, threads)`)
- 스레드마다 `JavaParser` 인스턴스와 네 추출기(`EntityParser`, `RepositoryParser`, `ServiceParser`, `ControllerParser`)를 **따로** 둡니다. `ServiceParser`에는 타입 해석 통계라는 상태가 있어서 공유하면 안 됩니다. 통계는 끝나고 합칩니다.
- `--symbol-solver`를 켜면 type solver와 그 캐시도 스레드마다 따로 만듭니다. JavaParser의 `JavaParserFacade.get`은 `synchronized`이고 solver별로 인스턴스를 두므로 스레드 간에 섞이지 않습니다.
- 파일 크기가 제각각이라 미리 나누지 않고, 일을 끝낸 스레드가 다음 파일을 가져갑니다.
- 파일별 결과를 입력 순서의 칸에 담았다가 **입력 순서대로 합칩니다.** 그래서 그래프 노드·엣지의 삽입 순서, 파싱 실패 목록, `[WARN] Parse failed` 경고 순서가 순차 파싱과 같습니다. 경고는 파싱 중이 아니라 합칠 때 출력합니다.
- AST는 파일 하나를 처리하면 바로 놓습니다. 한 번에 메모리에 남는 AST는 스레드 수만큼입니다.
- 추출기들에 static 가변 상태가 없는지 확인했습니다(`static final` 상수 집합뿐).

**기본 스레드 수: 코어 수의 절반(1~4)**

처음에는 "코어 수(최대 8)"로 했습니다. 그런데 4코어 머신에서 재 보니 스레드 4개가 2개보다 나은 경우는 10,000개 규모뿐이었고, 1,000개에서는 순차보다 느린 회차도 있었습니다(아래 표). 파싱 중에는 JIT 컴파일러와 GC 스레드도 코어를 쓰기 때문으로 추정하지만, 따로 검증하지는 않았습니다. GitHub 호스팅 러너(`ubuntu-latest`)도 4 vCPU라서 기본값을 절반으로 정했습니다. `--threads N`으로 바꿀 수 있고, `--threads 1`은 순차 파싱입니다. `--timing`에 스레드 수를 표시합니다.

```
Timing (ms): collect 42.7 | parse 4719.3 (5000 parse calls, 2 thread(s)) | graph 203.4 | analyze 10.2
```

### 결과 동일성 검증

| 무엇을 | 어떻게 | 결과 |
|---|---|---|
| 개선 전 바이너리(`afb7ec8`) vs 새 바이너리 | 샘플 100 / 1,000개 × 마이그레이션 18개를 아래 네 방식으로 실행: ① 개선 전, 파일마다 따로 ② 새 바이너리, 파일마다 따로 `--threads 1` ③ 새 바이너리, 폴더 한 번(기본 스레드) ④ 새 바이너리, 폴더 한 번 `--threads 1` | JSON 리포트 18개가 네 방식 모두 **바이트 단위로 같음.** 파일별 종료 코드도 같고, `results.tsv`의 코드 = 따로 실행한 코드 |
| 콘솔 출력 | 마이그레이션 하나, `--report-dir` 없이(기존 사용법) ①과 ② 비교 | 18개 모두 **바이트 단위로 같음** |
| 대규모 | 샘플 5,000 / 10,000개 × 18개: ① vs ③ | 18개 모두 **바이트 단위로 같음**(영향 API 합계 32개), 파일별 종료 코드도 같음 |
| 스레드 수 | `ParallelScanTest`: 404개(파싱 실패 4개 섞음)를 스레드 1/2/3/4/8/16으로, symbol solver를 켠 120개를 1/4로 | 노드·엣지 **삽입 순서**, 실패 목록 순서, 타입 해석 통계, JSON 리포트가 모두 같음 |
| 결정성 스냅샷(8번) | `--threads 1/2/4/8` 각각 | 정답 파일과 같음 |

### 성능

**측정 조건**
- 머신: 클라우드 컨테이너, 4 vCPU, 메모리 15GB, OpenJDK 17.0.21. JVM 옵션은 기본값(`installDist` 바이너리 그대로)입니다.
- 샘플: 6번의 `SampleProjectGenerator`로 만든 100 / 1,000 / 5,000 / 10,000개 파일 프로젝트입니다. 마이그레이션은 18개입니다. V1(인덱스 정의)과 변경 8종류(컬럼 삭제, 테이블 이름 변경, MODIFY, 컬럼 추가, UNIQUE·일반 인덱스 삭제, CHANGE, 테이블 삭제)를 돌려 쓴 V2~V18입니다.
- 실제 CLI를 실행해 JVM 시작부터 끝까지의 wall time을 쟀습니다. 3회 중앙값입니다.
- community 프로젝트는 이 측정 환경에 없어서 다시 재지 못했습니다. 샘플 100개 규모의 "파일마다 따로 실행"이 18.8초로, 10번의 community 측정(18.6~19.7초)과 비슷합니다.

**① 마이그레이션 18개 전체 (CI 한 번에 해당)**

| 파일 수 | 개선 전: 파일마다 따로 18회 | 한 번 실행, `--threads 1` | 한 번 실행, 기본(2스레드) | 개선 전 대비 |
|---:|---:|---:|---:|---:|
| 100 | 18.81 s | 1.60 s | 1.61 s | **11.7배** |
| 1,000 | 45.88 s | 3.32 s | 2.82 s | **16.3배** |
| 5,000 | 137.56 s | 8.26 s | 6.18 s | **22.3배** |
| 10,000 | 측정 안 함 (1회 실행 14.6 s × 18) | 15.03 s | 10.07 s | - |

- 처음 예상한 대로 community 규모(약 100개)에서 19초가 **1.6초**가 됐습니다. 남은 시간은 JVM 시작, 1회 파싱, 마이그레이션 18개 분석(각 수 ms)입니다.
- 줄어든 폭은 대부분 "파싱을 한 번만" 하는 데서 나옵니다. 병렬 파싱은 규모가 클 때 추가로 줄입니다(5,000개 8.26 s → 6.18 s).
- 참고로 처음 기본값(4스레드)으로 잰 값은 1.57 / 3.64 / 5.92 s(100 / 1,000 / 5,000)였습니다. 1,000개에서 순차보다 느렸던 것이 기본값을 바꾼 이유입니다.

**② 파싱 구간만 (마이그레이션 하나, `--timing`의 parse)**

| 파일 수 | 개선 전 | 1스레드 | 2스레드 | 4스레드 | 2스레드 / 1스레드 |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 1.96 s | 2.02 s | 1.56 s | 2.64 s (1.70~2.71) | 1.3배 |
| 5,000 | 8.57 s | 6.95 s | 4.76 s | 4.99 s | 1.5배 |
| 10,000 | 13.68 s | 16.46 s (14.6~18.1) | 8.45 s | 7.20 s | 1.9배 |

마이그레이션 하나의 전체 wall time은 1,000개 2.39 → 1.97 s, 5,000개 9.30 → 5.39 s, 10,000개 14.57 → 9.45 s(2스레드)입니다.

- **4코어에서 병렬 파싱은 1.3~1.9배**입니다. 코어 수만큼(4배) 빨라지지는 않습니다. 규모가 클수록 이득이 커지는데, 작은 규모에서는 JIT 워밍업 중인 코드를 여러 스레드가 함께 돌리기 때문으로 보입니다.
- "개선 전"과 "1스레드"는 같은 순차 코드인데 5,000개에서 8.57 vs 6.95 s, 10,000개에서 13.68 vs 16.46 s로 차이가 납니다. 이 환경의 측정 편차가 ±20% 정도라는 뜻이므로, 배수는 대략적인 값으로 봐야 합니다.

### 워크플로 변경 (`examples/github-actions/schemaguard.yml`)

- Run 단계가 바뀐 SQL 파일 목록을 셸 배열로 모아 **CLI를 한 번만** 실행합니다. 파일명은 여전히 스크립트에 끼워 넣지 않습니다(5번의 주입 방지 유지).
- `results.tsv`를 SchemaGuard가 씁니다. Check 단계는 그대로 파일별로 판정합니다. 경로의 `$GITHUB_WORKSPACE/` 앞부분은 떼고 표시합니다.
- `results.tsv`가 없으면(인자 오류 등으로 분석을 시작하지 못함) 실행 오류로 차단합니다.
- **로컬 시뮬레이션:** YAML에서 Run·Check 스크립트를 꺼내 `bash -eo pipefail`로 실행했습니다(5번과 같은 방식).

| PR 변경 | 결과 |
|---|---|
| `V2__drop_email.sql`(HIGH), `V3__bad.sql`(파싱 실패), `` V4__$(touch PWNED).sql ``, `V5__safe file.sql`(공백 포함) | 한 번 실행으로 4개 모두 분석. V2·V3 차단, V4·V5 통과, `PWNED` 파일 생성 안 됨 → 병합 차단 |
| `V5__safe file.sql` 하나만 | 병합 가능 |
| SchemaGuard 바이너리 없음 | `results.tsv` 없음 → 실행 오류로 차단 |

  시뮬레이션 중에 파일 하나 + `--report-dir`일 때 `results.tsv`가 생기지 않는 버그를 찾아 고쳤습니다(위 "사용법"의 두 번째 항목). GitHub에서 직접 돌려 보지는 않았습니다.
- `scripts/accuracy/predict.sh`는 바꾸지 않았습니다. 파일마다 실행하는 방식 그대로 동작합니다.

### 테스트

- 신규 `MultiMigrationTest` 9개
  - 폴더 한 번 실행의 리포트 6개가 파일마다 따로 실행한 리포트와 바이트 단위로 같음. 종료 코드(0/1/1/0/3/0)와 `results.tsv`, 전체 코드 3
  - 인자 순서가 뒤섞이고 같은 파일이 중복돼도 V 번호 순서로 한 번씩, `--threads 4`로도 순차 단독 실행과 같음
  - 없는 파일이 섞여도 나머지 분석, 전체 코드 2
  - 파일 하나 + `--report-dir`도 `results.tsv`를 씀
  - `--report-path` + 여러 파일, `--report-path` + `--report-dir`, 리포트 이름 충돌, 빈 폴더는 종료 코드 2
  - 종료 코드 우선순위
- 신규 `ParallelScanTest` 3개: 스레드 수별 순서까지 같음, symbol solver 병렬, 빈 입력·파일보다 많은 스레드
- `DeterminismTest` 1개 추가: 스레드 1/2/4/8 모두 스냅샷과 같음
- 전체 120개 통과

### 한계

- **병렬 이득은 코어 수와 규모에 따라 다릅니다.** 4코어 한 대에서만 쟀습니다. 코어가 많은 머신에서는 기본값(절반, 최대 4)이 최적이 아닐 수 있어 `--threads`로 조정해야 합니다.
- **병렬 파싱의 메모리:** 동시에 스레드 수만큼 AST가 메모리에 있고, symbol solver를 켜면 스레드마다 solver 캐시가 따로 생깁니다. 이번에는 최대 힙을 재지 않았습니다.
- **여러 마이그레이션을 묶어도 "누적된 스키마"를 보지는 않습니다.** 각 파일은 따로 실행한 것과 같은 기준으로 분석합니다. 예를 들어 V2가 컬럼을 지우고 V3가 같은 이름으로 다시 만드는 경우, 두 변경을 합쳐서 판단하지 않습니다.
- 리포트 파일 이름은 마이그레이션 파일 이름에서 정합니다. 다른 폴더에 같은 이름이 있으면 함께 분석할 수 없습니다(종료 코드 2, 따로 실행해야 함).

---

## 15. 인덱스·UNIQUE 삭제 탐지와 조용히 통과하던 DROP 구문 수정

### 문제: 인덱스를 지우는 마이그레이션이 종료 코드 0으로 통과

| 구문 | 기대 | 수정 전 |
|---|---|---|
| `ALTER TABLE users DROP INDEX uq_users_email` | UNIQUE면 차단 | **아무것도 탐지 안 됨.** 파싱 실패로도 안 잡힘 |
| `DROP INDEX idx_users_email ON users` | 인덱스 삭제 | **`DROP_TABLE on idx_users_email`**로 분류. 그런 테이블이 없어 영향 API 0개 |
| `ALTER TABLE users DROP KEY uq_users_email` (MySQL) | 인덱스 삭제 | JSQLParser가 읽지 못해 종료 코드 3 |
| `ALTER TABLE users DROP CONSTRAINT fk_x` (PostgreSQL의 FK·UNIQUE 삭제) | 제약 종류에 따라 | 무시 |
| `DROP SEQUENCE users_seq` | 판단 불가 → 차단 | `DROP_TABLE on users_seq` |
| `CREATE UNIQUE INDEX uq ON users (email)` | UNIQUE 추가 (MEDIUM) | 무시 |

원인은 두 곳입니다.
- `SqlMigrationParser.handleAlter`의 `DROP` 분기가 컬럼 이름이 없으면 그냥 지나갔습니다. `DROP INDEX`, `DROP CONSTRAINT`는 컬럼 이름이 없습니다.
- `handleDrop`이 `Drop` 구문의 대상 종류(TABLE, INDEX, SEQUENCE …)를 보지 않고 전부 테이블 삭제로 만들었습니다.

1번 항목의 원칙("읽지 못한 변경은 '변경 없음'이 아니라 '알 수 없음'")과 어긋나는 구멍이었습니다.

### UNIQUE 삭제를 HIGH로 보는 이유

UNIQUE 제약이 사라져도 쿼리는 깨지지 않습니다. 대신 **같은 값을 저장해도 오류 없이 성공**합니다. 중복 가입, 같은 글에 좋아요 두 번, 같은 거래 번호로 두 번 지급 같은 일이 런타임 오류 없이 쌓입니다. 애플리케이션이 `existsBy...`로 미리 검사하더라도, DB 제약이 없으면 동시에 들어온 두 요청은 둘 다 검사를 통과합니다. 오류가 나지 않아서 배포 후에도 알아채기 어렵기 때문에 HIGH(차단)로 둡니다. 영향 범위는 그 테이블에 저장하는 경로(`save*`)입니다(12번의 쓰기 범위).

### 방법: 인덱스 이름으로 정의를 찾는다

`DROP INDEX`에는 인덱스 이름만 있습니다. 어떤 컬럼인지, UNIQUE인지는 없습니다. 그래서 아래 순서로 정의를 찾습니다.

| 순서 | 출처 | 예 |
|---|---|---|
| 1 | **이전 마이그레이션** (`IndexCatalog`, 신규) | V1의 `email VARCHAR(255) NOT NULL UNIQUE`, V12의 `CONSTRAINT uq_post_tags_post_tag UNIQUE (post_id, tag_id)`, `CREATE INDEX ...` |
| 2 | **엔티티 선언** (`ChangeClassifier`) | `@Table(uniqueConstraints = @UniqueConstraint(name = ...))`, `@Table(indexes = @Index(name = ..., unique = true))`, `@Column(unique = true)` |
| 3 | 그래도 모르면 | UNIQUE일 수 있으므로 **보수적으로 UNIQUE**로 봅니다 |

**이전 마이그레이션 읽기** (`MigrationHistory`, 신규)

> 17번에서 바뀌었습니다. 지금은 기본으로 이전 파일을 읽지 않고, `--history <폴더>`를 줄 때만 읽습니다.

- 검사하는 파일과 같은 폴더에서 파일 이름의 V 번호(`V<번호>__<설명>.sql`)가 **더 작은 파일**만 고릅니다. 번호는 숫자 단위로 비교합니다(V2 < V10, V1.2 < V1.10). community처럼 `V2.sql`로 쓴 파일도 받고, `R__` 파일은 번호가 없어 뺍니다.
- 구문을 차례로 적용해 그 시점의 인덱스 목록을 만듭니다. `CREATE TABLE`(컬럼 `UNIQUE`/`PRIMARY KEY`, `UNIQUE KEY`, `KEY`, `CONSTRAINT ... UNIQUE`/`FOREIGN KEY`), `CREATE [UNIQUE] INDEX`, `ALTER TABLE ADD/DROP`, `DROP INDEX`, `DROP TABLE`, 테이블·컬럼 이름 변경을 반영합니다.
- 검사하는 파일 안의 앞선 구문도 반영합니다. 같은 파일에서 만들고 지운 인덱스도 찾습니다.
- 이름 없이 선언한 제약은 DB가 붙이는 이름으로도 찾습니다. MySQL은 컬럼 UNIQUE의 인덱스 이름을 컬럼 이름(`email`)으로, PostgreSQL은 `users_email_key`로 만듭니다. PRIMARY KEY는 `PRIMARY`, `users_pkey`입니다.
- 이전 마이그레이션은 이미 적용된 이력이므로, 읽지 못한 구문이 있어도 실패로 보지 않고 개수만 출력합니다. 예: `Migration history: 17 earlier file(s), 24 index/constraint definition(s), 1 statement(s) skipped (unparseable)`

**분류 규칙**

| 찾은 정의 | 결과 | 위험도 | 영향 범위 |
|---|---|---|---|
| UNIQUE 제약·인덱스, PRIMARY KEY | `DROP_UNIQUE` (신규) | HIGH | 그 테이블의 저장 경로 |
| 일반 인덱스 | `DROP_INDEX` (신규) | LOW | 없음 (쿼리는 깨지지 않음) |
| FK 제약 (`DROP CONSTRAINT fk_x`) | `DROP_FOREIGN_KEY` | LOW | 없음 (12번과 같음) |
| 정의를 못 찾음, 테이블은 앎 | `DROP_UNIQUE` (보수적) | HIGH | 그 테이블의 저장 경로 |
| 테이블도 모름 (`DROP INDEX name`, PostgreSQL) | 파싱 실패 | - | 종료 코드 3 (분석 불완전) |
| 같은 파일에서 **같은 컬럼 집합**에 UNIQUE를 다시 만듦 | `MODIFY_COMPATIBLE` | LOW | 없음 (인덱스 이름 변경 패턴) |

- 컬럼 집합이 다르면 상쇄하지 않습니다. 예를 들어 `(email)`을 지우고 `(email, team_id)`를 만들면, 팀이 다를 때 같은 email을 허용하므로 `DROP_UNIQUE`가 남습니다.
- 분류 근거를 `note`에 남깁니다. 엔티티가 여전히 unique로 선언하고 있으면 그것도 적습니다. 예: `UNIQUE (post_id, tag_id) 삭제 (정의: V12__create_tag_report_notification_bookmark.sql): 같은 값을 저장해도 오류 없이 성공함. … 엔티티 PostTag (@Table uniqueConstraints) 는 아직 unique 로 선언되어 있어 코드와 DB 가 어긋남`
- JSON `cause`에 `indexName`, `indexColumns`를 추가했습니다. 인덱스 변경일 때만 넣어서 다른 변경의 리포트 모양은 그대로입니다.

**함께 고친 것**
- `DROP` 구문: `TABLE`과 `VIEW`는 테이블 삭제(엔티티가 뷰에 매핑될 수 있음), `INDEX`는 위 규칙, 그 밖의 대상(`SEQUENCE` 등)은 파싱 실패(종료 코드 3)입니다.
- `ALTER TABLE ... DROP` 중 컬럼·인덱스·제약 어느 것도 아닌 형태는 무시하지 않고 파싱 실패로 처리합니다.
- `ALTER TABLE ... DROP KEY`를 정규식으로 처리합니다. `DROP UNIQUE (cols)`, `DROP PRIMARY KEY`도 `DROP_UNIQUE`로 탐지합니다.
- `CREATE UNIQUE INDEX`를 `ADD_UNIQUE`로 탐지합니다.
- `ADD UNIQUE INDEX uq (email)`의 대상 컬럼을 `unknown` 대신 `email`로 기록합니다.

### 결과

**community 마이그레이션 18개 회귀 비교** (이전 빌드 `362683a` vs 새 빌드)
- 종료 코드 18개 모두 같습니다.
- JSON 리포트 17개는 바이트 단위로 같습니다. V16 하나만 `ADD_UNIQUE`의 `cause`에 `indexName`, `indexColumns`가 붙었고, 이 두 필드를 빼면 같습니다.

**새 시나리오** (community `test-schemaguard-complex` 코드에 V18로 추가해 실행)

| V18 | 수정 전 | 수정 후 |
|---|---|---|
| `ALTER TABLE post_tags DROP INDEX uq_post_tags_post_tag` | 탐지 없음, 종료 코드 0 | **HIGH `DROP_UNIQUE`, 종료 코드 1.** `POST /api/tags/posts/{postId}` (`TagService.attachTags → PostTagRepository.save`). 정의는 V12, 엔티티도 아직 unique라고 표시 |
| `DROP INDEX email ON users` | `DROP_TABLE on email`, 영향 0, 종료 코드 0 | **HIGH, `POST /signup`** (`UserService.signUp → UserRepository.save`), 종료 코드 1 |
| `ALTER TABLE post_likes DROP INDEX uk_post_like_post_user` | 탐지 없음, 종료 코드 0 | **HIGH, `POST /posts/{postId}/like`**, 종료 코드 1. 마이그레이션에는 없고 엔티티 `@UniqueConstraint`로 찾음 |
| `ALTER TABLE reports DROP INDEX uq_reports_reporter_post` | 탐지 없음, 종료 코드 0 | **HIGH, `POST /api/reports`**, 종료 코드 1. 정의는 V16 |
| `DROP INDEX idx_notifications_receiver_read ON notifications` | `DROP_TABLE on idx_…`, 종료 코드 0 | LOW `DROP_INDEX`, 영향 없음, 종료 코드 0 |
| `uq_post_tags_post_tag` 삭제 + 같은 컬럼에 `uq_post_tag_pair` 추가 | MEDIUM `ADD_UNIQUE`, 종료 코드 0 | LOW `MODIFY_COMPATIBLE`, 영향 없음 |

`DROP INDEX email ON users`는 이전 마이그레이션이 아니라 엔티티 `User.email`의 `@Column(unique = true)`로 찾았습니다. community의 V0과 V11이 `users.email`을 지우는 시나리오라서, V0~V17을 차례로 적용하면 V18 시점에는 email 컬럼과 그 인덱스가 이미 없기 때문입니다. community의 마이그레이션은 서로 독립된 측정 시나리오이고, 실제 프로젝트의 마이그레이션처럼 차례로 쌓이는 이력이 아닙니다. 실제 프로젝트에서는 이전 마이그레이션 쪽에서 찾게 됩니다.

### 테스트

- 신규 `IndexChangeParserTest` 24개
  - 수정 전 버그 재현: `DROP INDEX`가 무시되지 않음, `DROP INDEX … ON`이 테이블 삭제가 아님, `DROP KEY`, 테이블을 모르는 `DROP INDEX`는 실패, `DROP SEQUENCE`는 실패, `DROP VIEW`/`DROP TABLE IF EXISTS`
  - `CREATE UNIQUE INDEX`, 일반 `CREATE INDEX`(변경 아님), `ADD UNIQUE INDEX`의 컬럼
  - 이전 마이그레이션: 이름 있는 UNIQUE, MySQL·PostgreSQL 암묵 이름, `CREATE UNIQUE INDEX`, 일반 인덱스, 테이블 없는 `DROP INDEX`, `DROP CONSTRAINT`가 FK인 경우, `DROP PRIMARY KEY`, `DROP UNIQUE (cols)`
  - 같은 컬럼 재생성은 상쇄, 컬럼이 늘면 상쇄하지 않음, 같은 파일 안의 인덱스, 이력의 삭제·테이블 이름 변경 반영, 읽지 못한 이력 구문은 세기만 함
- 신규 `IndexChangeClassifierTest` 7개: 엔티티 `@UniqueConstraint`/`@Index(unique)`/일반 `@Index`/`@Column(unique)` 선언 읽기와 분류, 모르는 인덱스는 보수적으로 UNIQUE, 엔티티가 아직 unique로 선언하면 근거에 표시
- 신규 `MigrationHistoryTest` 3개: 파일 이름의 V 번호 순서(V2 < V10, V1.2 < V1.10, `V2.sql`), `R__` 제외, 번호 없는 파일은 이력 없음
- `SchemaGuardCLITest` 5개 추가: 이력의 UNIQUE 삭제는 쓰기 경로만 차단(`GET /me`는 영향 없음), 일반 인덱스는 통과, 모르는 인덱스는 차단, 테이블을 모르면 종료 코드 3, **V 번호가 더 큰 파일은 이력이 아님**
- 결정성 스냅샷은 그대로입니다.
- 전체 107개 통과

### 한계

- **이력은 한 폴더에서 이름에 V 번호가 있는 파일만 봅니다.** 하위 폴더나 여러 폴더, 마이그레이션 도구의 설정(적용 이력 테이블, 위치 설정)은 보지 않고 파일 이름만 봅니다. 이력은 차례로 쌓인다고 가정합니다(위 community 사례).
- **이전 마이그레이션에서 읽지 못한 구문의 인덱스는 모릅니다.** community V1은 `posts` 정의 뒤 `;`가 빠져 `posts`, `comments`의 정의를 읽지 못합니다. 그 인덱스를 지우면 엔티티로 찾거나 보수적으로 HIGH가 됩니다.
- **정의를 모르는 일반 인덱스 삭제도 차단될 수 있습니다.** UNIQUE일 가능성을 배제할 수 없어서입니다. 리포트의 `note`에 "일반 인덱스라면 이 경고는 무시해도 됨"이라고 적습니다.
- **CHECK 제약은 기록하지 않습니다.** `DROP CONSTRAINT chk_…`는 정의를 모르는 제약이라 보수적으로 HIGH가 됩니다. MySQL의 `DROP CHECK`는 JSQLParser가 읽지 못해 종료 코드 3입니다.
- **읽기 쪽 영향은 아직 보지 않습니다.** 중복이 쌓인 뒤에는 단건을 돌려주는 조회(`Optional<User> findByEmail`)가 `IncorrectResultSizeDataAccessException`으로 실패합니다. 지금은 콘솔 가이드에만 적고 영향 경로로는 잡지 않습니다.
- **쓰기 범위는 테이블 단위입니다.** 그 컬럼을 실제로 쓰는 저장 경로만 고르지는 않습니다(12번과 같음).
- **일반 인덱스 삭제의 성능 영향은 보지 않습니다.** 특히 비관적 락(`@Lock(PESSIMISTIC_WRITE)`) 조회가 쓰던 인덱스가 사라지면 잠금 범위가 넓어집니다. 다음 과제입니다(18번에서 1단계 구현).
- `ALTER TABLE ... RENAME INDEX`는 JSQLParser 4.7이 읽지 못해 종료 코드 3입니다(통과시키지 않음).

---

## 14. 연관 접근을 이름 추정에서 루트 타입 해석으로

11번과 13번의 연관 초기화 추적은 `x.getPost()`의 **속성 이름 `post`만 보고** 어느 엔티티인지 추정했습니다. 이 방식에는 세 가지 구멍이 있습니다.

| 구멍 | 예 | 결과 |
|---|---|---|
| 같은 이름의 연관이 서로 다른 엔티티를 가리킴 | `Pet.owner → User`, `Shop.owner → Company` | 어느 쪽인지 몰라서 **연결하지 않음 (놓침)** |
| 지역 변수에 연관을 꺼낸 뒤 접근 | `Pet p = visit.getPet(); p.getOwner().getName()` | 체인이 끊겨 놓침 |
| 다단계 체인의 중간 단계 | `report.getPost().getAuthor()` | `author`를 "Post의 author"가 아니라 이름으로 따로 추정 |

### 방법

getter 체인을 **루트(체인이 시작하는 변수나 식)부터** 해석합니다. 루트 타입을 아래 순서로 찾은 뒤, FK 매핑을 따라 한 단계씩 정확히 따라갑니다(`Report.post → Post`, `Post.author → User`).

| 순서 | 방법 | 대상 |
|---|---|---|
| 1 | **선언 타입** (`DECLARED`) | 명시 타입 람다 파라미터, 지역 변수(`var` 제외), 메서드 파라미터, 필드 |
| 2 | **가벼운 추론** (`INFERRED`) | 타입을 생략한 람다의 원소 타입: `List<Post> posts` → `posts.stream().filter(…).map(p -> …)`<br>리포지토리 호출 결과: `postTagRepository.findByPostId(id).stream().map(pt -> …)`, `visitRepository.findById(id).orElseThrow().getPet()` |
| 3 | **symbol solver** (`SOLVER`, 옵션 `--symbol-solver`) | 위 규칙으로 모르는 루트. JavaParser symbol solver에 JDK와 프로젝트 소스를 붙여 해석 |
| 4 | **이름 추정** (`NAME`) | 그래도 모르면 기존 방식(같은 이름의 FK가 모두 같은 엔티티를 가리킬 때만) |

**추가로 바뀐 규칙**
- 루트 타입이 **엔티티가 아니면**(DTO 등) 연관으로 보지 않습니다. 이름 추정이었다면 `view.getOwner()`를 `Pet.owner`나 `Shop.owner`로 오인할 수 있습니다.
- 엔티티 타입 지역 변수의 getter도 초기화로 봅니다(13번의 "엔티티 파라미터" 규칙 확장). `Pet p = v.getPet(); p.getOwner()`가 그 예입니다.
- 리포지토리 호출 결과는 `repo:<필드 타입>`으로 넘기고, 그래프 빌더가 **실제 리포지토리 목록**으로 엔티티를 찾습니다. 이름 접미사 `Repository`에 기대지 않습니다. 리포지토리가 아니면 이름 추정으로 넘어갑니다.
- 해석 결과를 `--timing`에 표시합니다. 예: `Association chain roots: declared 139 | inferred 22 | … ; links {linked-declared=12, linked-inferred=4, …}`

### 결과

**community (Java 파일 65개):**
- 연관을 초기화하는 체인 중 그래프에 연결된 16건이 모두 타입으로 해석됐습니다(선언 12, 추론 4). 이전에 이름으로 추정하던 4건(`PostService.getPosts`의 람다 2건, `TagService`의 리포지토리 스트림 2건)이 추론으로 바뀌었습니다.
- 리포트 18개는 13번 빌드와 **바이트 단위까지 같습니다.** 이 프로젝트에서는 이름 추정이 틀린 적이 없었습니다.
- 실제 MySQL 측정 대비 지표도 그대로입니다(재현율 100%, HIGH 정밀도 97.1%).

**community를 5,000개 규모로 키운 프로젝트 (4,977개):** community를 79벌 복제해 클래스 이름에 접미사를 붙인 프로젝트라, `post`나 `author` 같은 속성 이름이 79개의 서로 다른 엔티티를 가리킵니다.

| | 이름 추정 (13번) | 루트 타입 해석 (기본) | + `--symbol-solver` |
|---|---|---|---|
| 연결된 연관 접근 | 이름이 겹쳐 대부분 포기 | 선언 790 + 추론 316 | 같음 |
| 이름이 모호해 포기 | 다수 | **0** | 0 |
| 파싱 시간 | | 4.5~5.1초 | 11.5~11.8초 (**약 2.4배**) |
| 리포트 | | | 기본과 **바이트 단위로 같음** |

13번 빌드(`07770c6`)와 V3(`posts.title` 이름 변경)으로 비교하면 영향 API가 13개에서 15개로 늘었습니다.
- `POST /api/admin/reports/{reportId}/accept`: `ReportService.process`의 `report.getPost().getTitle()`이 `post` 이름 충돌로 버려졌다가, `report`의 선언 타입(Report)으로 정확히 연결됐습니다. **실제 MySQL에서 실패하는 API입니다**(9번 측정).
- `POST …/reject`: 같은 메서드를 쓰는 거절 분기입니다. 11번에서 설명한 분기 한계의 오탐입니다.

### symbol solver를 기본으로 켜지 않은 이유

- community에서 solver는 추가로 연결한 연관이 **0건**이었고, 파싱 시간은 약 2배가 됐습니다(약 400ms → 750~1,100ms).
- 이유는 두 가지입니다.
  1. 타입을 생략한 람다 파라미터를 풀려면 solver가 **람다 본문의 getter 반환 타입까지** 추론해야 합니다. 그런데 Lombok `@Getter`가 만드는 getter는 소스에 없습니다.
  2. `JpaRepository` 같은 Spring 타입도 클래스패스에 없습니다.
- 그래서 실제로 자주 쓰는 패턴(컬렉션 원소, 리포지토리 호출 결과)은 직접 만든 추론 규칙으로 처리하고, solver는 getter가 소스에 있는 프로젝트를 위한 옵션으로 남겼습니다.
- solver가 규칙보다 나은 사례는 테스트로 고정했습니다. 같은 클래스의 메서드가 돌려주는 `List<Pet>`의 원소 같은 경우입니다.

### 함께 고친 버그

- **문법 오류 파일이 실패로 보고되지 않던 문제:** `JavaSourceScanner`가 `StaticJavaParser` 대신 스캔마다 `JavaParser` 인스턴스를 쓰도록 바꾸면서 생긴 문제입니다. 인스턴스는 문법 오류에도 예외 없이 부분 결과를 돌려줍니다. `isSuccessful()`을 확인해 실패로 보고하게 했습니다. 기존 `JavaSourceScannerTest`와 결정성 스냅샷(파싱 실패 파일 포함)이 이 문제를 잡아 줬습니다.
- **symbol solver가 람다 파라미터를 `? super Pet` 같은 제약 타입으로 돌려줌:** 이 경우 경계 타입(`Pet`)으로 풀어서 씁니다.

### 테스트

- 신규 `TypedAssociationTest` 5개
  - 같은 이름 `owner`가 두 엔티티를 가리키는 프로젝트에서 각 테이블 변경이 맞는 API에만 닿는지: 파라미터, 지역 변수, 컬렉션 원소, 리포지토리 스트림, 리포지토리 호출 루트
  - DTO 루트는 연관이 아님
  - 파서가 기록하는 루트 타입과 해석 방법
  - 엔티티 타입 지역 변수
  - solver 옵션이 규칙으로 모르는 루트를 해석하는지: solver가 없으면 놓치고, 있으면 찾음
- 전체 68개 통과

### 한계

- 추론 규칙은 자주 쓰는 형태만 다룹니다. 스트림 원소 연산(`map`, `filter`, `forEach` 등)과 Optional 꺼내기(`orElseThrow` 등)입니다. `Collectors.groupingBy` 이후의 원소나 사용자 정의 메서드가 돌려주는 컬렉션은 solver 옵션이나 이름 추정에 맡깁니다.
- 지역 변수 타입은 메서드 안에서 이름이 같은 첫 선언을 씁니다. 블록마다 같은 이름을 다른 타입으로 다시 선언하는 코드는 틀릴 수 있습니다.

---

## 13. DTO·매퍼 static 메서드에서의 연관 로딩 추적

11번에서는 연관 초기화(`x.getPost().getTitle()`)를 `@Service` 클래스 안에서만 찾았습니다. 그런데 실제 코드에서는 이 접근이 **DTO의 static 팩토리 메서드**에 있는 경우가 많습니다.

```java
// ReportService
return reportRepository.findTop20ByOrderByIdDesc().stream().map(ReportSummaryResponse::from).toList();
// ReportSummaryResponse
public static ReportSummaryResponse from(Report report) {
    ... report.getPost().getTitle() ... report.getReporter().getNickname() ...   // 여기서 LAZY 프록시 초기화
}
```

community의 기존 API들은 같은 연관을 `@EntityGraph`로도 로딩하고 있어서 이 구멍이 측정에서 드러나지 않았습니다. 그래서 이번에는 **검증용 API를 먼저 추가해 실제로 놓치는 것을 확인**한 뒤에 고쳤습니다.

### 1. 검증 시나리오 추가 (community, 커밋 `857f752`, `4f94c67`)

| API | 경로 | 기대 |
|---|---|---|
| `GET /api/admin/reports/recent` | `@EntityGraph` 없는 `findTop20ByOrderByIdDesc()` → `.map(ReportSummaryResponse::from)` (메서드 참조) → DTO 안에서 `getPost().getTitle()`, `getReporter().getNickname()` | posts나 users 컬럼이 깨지면 실패 |
| `GET /api/admin/reports/recent-ids` (대조군) | 같은 조회 → `.map(r -> ReportIdResponse.of(r))` (람다 안 static 호출) → DTO 안에서 `getPost().getId()`, `getReporter().getId()`만 읽음 | 프록시가 id를 갖고 있어 **실패하지 않아야 함** |

- 두 서비스 메서드는 `@Transactional(readOnly = true)`입니다. 지연 로딩이 서비스 안의 DTO 변환에서 일어납니다.
- 9번 하네스에 두 API를 추가해 엔드포인트를 30개로 늘린 뒤 실제 MySQL로 다시 측정했습니다. 13분 37초 걸렸습니다.
  - 기준선 DB 오류: 0건
  - 기존 28개 API의 결과는 18개 시나리오 모두 이전과 같았습니다.
  - `recent`는 V0, V2, V3, V11에서 실패했습니다(`Unknown column 'u1_0.email'`, `'p1_0.title'`).
  - `recent-ids`는 18개 시나리오 모두 통과했습니다.
- 정확도 테스트는 `@Tag("accuracy")`를 붙여 **기본 `./gradlew test`에서 제외**했습니다. 실행하려면 `./gradlew test -Paccuracy --tests '*SchemaGuardAccuracyTest*'`를 씁니다. 그래서 community의 일반 테스트는 23초로 돌아왔습니다.

**개선 전 SchemaGuard(`7d28edc`)로 비교한 결과:** `recent`의 실제 실패 4건을 **모두 놓쳤습니다**(FN 4, 재현율 96.0%).

### 2. 변경 내용

**helper 클래스 수집** (`ServiceParser`)
- `@Service`가 아닌 일반 클래스(DTO, 매퍼 등)의 **static 메서드**를 `ServiceInfo(helper = true)`로 모읍니다.
- 그래프 노드는 `helper:<Class>.<method>`(`NodeType.HELPER`)입니다.
- 엔티티, 컨트롤러, 설정, 컴포넌트, 인터페이스는 제외합니다.

**static 호출 수집** (`AccessCollector` 신규, 서비스·컨트롤러·helper 공용)
- `Type.method(...)` 호출: 람다 안의 호출도 포함합니다.
- `Type::method` 메서드 참조
- 서비스→helper, 컨트롤러→helper, helper→helper 호출을 `CALLS` 엣지로 연결합니다. 예: `UserResponse.from(user)`
- 연결은 **알려진 helper 클래스일 때만** 합니다. `ResponseEntity.ok`, `List.of` 같은 라이브러리 호출은 노드를 만들지 않습니다.
- 컨트롤러의 static 호출은 기존 `serviceCalls`와 별도 목록(`EndpointMapping.staticCalls`)에 둡니다. 기존 "첫 서비스 호출" 호환 필드가 `ResponseEntity.ok` 같은 호출로 바뀌지 않게 하기 위해서입니다.

**연관 초기화 신호 추가**
- 기존 getter 체인(`x.getPost().getTitle()`)을 helper 메서드에서도 봅니다.
- **엔티티 타입 파라미터의 getter**를 새로 봅니다. 중첩 DTO 패턴 `AuthorDto.from(User user) { user.getNickname() }`처럼 파라미터 타입이 엔티티이면, `getId()`가 아닌 getter 호출을 그 엔티티의 초기화로 봅니다. 같은 이름의 람다 파라미터가 가리는 경우는 제외합니다.

**경로 선택 (`ImpactAnalyzer`)**
- BFS를 **(연관 로딩 노드 수, 경로 길이) 순으로 최소인 경로**를 찾는 우선순위 탐색으로 바꿨습니다. 동률이면 노드 id 순입니다.
- 이전 BFS는 처음 도달한 경로만 남겼습니다. 그래서 helper가 추가되자 `GET /me`가 `col:users.email → field:User.email → repo:UserRepository.findByEmail → …`(직접 경로) 대신 한 단계 짧은 `col:users.email → load:User → helper:UserResponse.from → …`로 바뀌었습니다.
- 직접 조회 경로가 수정할 위치(필드, 리포지토리)를 더 잘 보여 주므로, 이제는 연관 로딩을 덜 거치는 경로를 먼저 고릅니다. 시작 노드가 여럿일 때의 중복 제거 기준(`BETTER_PATH`)도 같은 순서로 맞췄습니다.

### 결과

**30개 엔드포인트 측정 기준 (actual.json 새로 측정)**

| | 개선 전 (`7d28edc`) | 개선 후 |
|---|---:|---:|
| 재현율 | 96.0% (95/99) | **100% (99/99)** |
| 정밀도 (전체) | 95.0% | 95.2% (99/104) |
| 정밀도 (HIGH) | 96.9% | 97.1% (99/102) |
| 대조군 `recent-ids` | 예측 없음 | **예측 없음 (정답)** |

새로 잡힌 경로:
```
V0 : col:users.email → load:User → helper:ReportSummaryResponse.from → svc:ReportService.getRecentSummaries → ctrl:AdminReportController.recentReports → api:GET /api/admin/reports/recent
V3 : col:posts.title → load:Post → helper:ReportSummaryResponse.from → svc:ReportService.getRecentSummaries → … → api:GET /api/admin/reports/recent
```

남은 오탐 5건은 12번과 같습니다. 분기 한계 3건, 그리고 실제 MySQL로 위험을 확인한 MEDIUM 2건입니다.

**기존 결과와의 비교 (회귀 확인)**
- 이전 community 코드(`1151962`, 엔드포인트 28개)에서 12번 빌드와 새 빌드의 리포트를 비교했습니다.
- (API, 원인) 집합은 18개 파일 모두 같았습니다. 경로만 27건 바뀌었습니다.
  - 18건: 연관 로딩 경로 대신 직접 조회 경로를 고른 경우입니다. 예를 들어 `GET /api/bookmarks`는 로그인 사용자를 `UserRepository.findByEmail`로 조회하므로, 그 경로를 보여 줍니다.
  - 7건: `@EntityGraph` 리포지토리 경로와 DTO helper 경로가 같은 길이일 때 id 순으로 helper가 선택된 경우입니다.
  - 2건: 서비스 경로끼리 동률을 정리하는 순서가 바뀐 경우입니다.
- 바뀐 경로에 포함된 연관 로딩 노드는 27개에서 7개로 줄었습니다.

### 테스트

- 신규 `HelperAssociationLoadTest` 6개
  - 메서드 참조로 호출한 DTO의 연관 초기화와 그 경로(`helper:` 노드 포함)
  - 중첩 DTO의 엔티티 파라미터 접근
  - id만 읽는 DTO는 영향 없음
  - 자기 테이블 변경은 두 API 모두 영향
  - 알 수 없는 클래스의 static 호출 무시
  - 파서 수집 결과
- 결정성 스냅샷은 그대로입니다. 픽스처에 DTO가 없고, 직접 경로만 있습니다.
- 전체 63개 통과

### 성능

| 파일 수 | graph | analyze (변경 12 / 100 / 1,000) |
|---:|---:|---:|
| 5,000 | 153 ms | 5.2 / 14.4 / 80.0 ms |
| 20,000 | 609 ms | 5.9 / 19.3 / 86.0 ms |

우선순위 큐 탐색으로 바꿨지만 분석 시간은 11번과 같은 수준입니다. 11번은 20,000개, 변경 1,000개에서 114ms였습니다.

### 한계

- **이름 관례에 의존합니다.** static 호출 대상은 대문자로 시작하는 이름(클래스명 관례)으로 판단합니다.
- **인스턴스 메서드는 보지 않습니다.** 빈으로 주입된 매퍼의 인스턴스 메서드(`mapper.toDto(x)`, MapStruct 등)는 대상이 아닙니다. 주입 필드 타입을 이용해 연결할 수 있지만, MapStruct는 생성된 코드를 봐야 합니다.
- **getter 체인과 파라미터만 봅니다.** 지역 변수에 연관을 꺼낸 뒤 접근하는 경우는 놓칩니다. 예: `Post p = report.getPost(); p.getTitle()`
- **Thymeleaf 템플릿에서의 지연 로딩**은 아직 보지 않습니다. community는 템플릿에 DTO만 넘겨서 해당되지 않습니다.

---

## 12. 위험도 판정 개선: 변경을 코드(엔티티 매핑)와 비교해 재분류

11번까지의 남은 오탐 53건 중 50건은 그래프 탐색이 아니라 **변경 종류를 판정하는 방식**에서 나왔습니다(9번 분석). SQL만 보고 `MODIFY`는 모두 HIGH, FK 제약 변경도 HIGH로 판정했기 때문입니다. 이번에는 두 가지를 바꿨습니다.
1. 변경을 엔티티 매핑(코드가 기대하는 스키마)과 비교해 다시 분류합니다.
2. 변경마다 "어떤 API가 실패할 수 있는가"의 범위를 나눕니다.

### 1. 영향 범위를 세 가지로 나눔 (`ImpactAnalyzer`)

| 범위 | 변경 | 시작점 | 이유 |
|---|---|---|---|
| **읽기 실패** | `DROP_COLUMN`, `RENAME_COLUMN`, `DROP_FK_COLUMN`, 비호환 `MODIFY_TYPE`, `DROP/RENAME_TABLE` | 컬럼을 읽는 모든 경로 + 연관 로딩(11번) | 엔티티를 SELECT하는 순간 실패 |
| **쓰기 실패** | `ADD_NOT_NULL`, `ADD_UNIQUE`, `NARROW_COLUMN`, `ADD_FOREIGN_KEY`, `MODIFY_FK_REFERENCE` | 그 테이블의 엔티티를 저장하는 `save*` 메서드 | 기존 값을 읽는 데는 영향이 없고, 조건에 맞지 않는 값을 저장할 때만 실패 |
| **영향 없음** | `DROP_FOREIGN_KEY`, `MODIFY_COMPATIBLE`, `ADD_COLUMN` | 없음 | 쿼리가 깨질 수 없음 |

- 쓰기 경로는 `DependencyGraphBuilder`가 만드는 `writeMethodsByTable`(테이블 → `repo:XRepository.save*` 노드) 색인으로 찾습니다. 선언 메서드, 기본 CRUD, 서비스가 호출하는 `saveAll` 등을 모두 포함하고, `nodeIndex`는 한 번만 훑습니다.
- 쓰기 경로의 영향 경로는 맨 앞에 대상 컬럼을 붙여서 왜 그 저장 경로인지 보이게 했습니다. 예: `col:reports.reason → repo:ReportRepository.save → svc:ReportService.report → … → api:POST /api/reports`
- 분석기는 이제 `ImpactAnalyzer.of(builder)`로 만듭니다. 그래프 빌더의 색인을 모두 사용합니다. 기존 생성자도 그대로 동작합니다.

### 2. 엔티티 매핑과 비교해 재분류 (`ChangeClassifier`, 신규)

**필요한 정보 수집**
- `EntityParser`가 필드마다 다음을 추가로 읽습니다.
  - **Java 타입**: `@Enumerated(STRING)`은 String, `ORDINAL`은 Integer로 봅니다.
  - **`@Column(length)`**: 지정하지 않으면 JPA 기본값 255
  - **NULL을 쓸 수 있는지**: `nullable = false`, 기본형, `@Id`이면 쓸 수 없음
- `SqlMigrationParser`는 `MODIFY`, `CHANGE`, `ALTER ... TYPE`에서 새 컬럼 정의를 함께 담습니다. 예: `VARCHAR(200) NOT NULL` → `SchemaChange.ColumnDef`

**분류 규칙**

| 상황 | 결과 | 위험도 |
|---|---|---|
| Java 타입과 맞지 않는 SQL 타입 (String 필드인데 `INT`) | `MODIFY_TYPE` | HIGH (읽기) |
| 엔티티 길이·범위보다 작아짐 (`length 500` → `VARCHAR(200)`, `Long` → `INT`) | `NARROW_COLUMN` | MEDIUM (쓰기) |
| `NOT NULL` 추가 + 엔티티는 NULL을 쓸 수 있음 | `ADD_NOT_NULL` | MEDIUM (쓰기) |
| `NOT NULL` 추가 + 엔티티가 NULL을 쓰지 않음 | `MODIFY_COMPATIBLE` | LOW |
| 같은 타입에 같거나 큰 길이 | `MODIFY_COMPATIBLE` | LOW |
| 엔티티가 매핑하지 않은 컬럼에 FK 추가 | `MODIFY_COMPATIBLE` | LOW (앱은 항상 NULL이라 FK 위반 없음) |
| 매핑을 모르는 컬럼이나 타입 (날짜, 불리언, 소수 등) | 그대로 유지 | 보수적으로 HIGH |

- 축소와 `NOT NULL`이 함께 있으면 두 변경으로 나눕니다.
- 재분류한 변경에는 근거(`note`)를 붙여 콘솔과 JSON(`cause.note`, `cause.newDefinition`)에 보여 줍니다. 예: `엔티티 길이 500 → VARCHAR(200): 201~500자 값은 저장 실패(strict 모드) 또는 잘림. 기존 데이터 길이 확인 필요`
- 리포트의 `parse.detectedChanges`도 재분류된 결과를 보여 줍니다.

**파서 기본 위험도도 바꿨습니다**
- `DROP_FOREIGN_KEY`: HIGH → LOW
- `MODIFY_FK_REFERENCE`: HIGH → MEDIUM
- DEFAULT 없이 `NOT NULL` 컬럼을 추가하면 `ADD_NOT_NULL`(쓰기)입니다. 엔티티가 모르는 컬럼이라 INSERT에서 값이 빠지기 때문입니다. DEFAULT가 있으면 `ADD_COLUMN`입니다.
- `ADD CONSTRAINT ... UNIQUE (a, b)`의 대상 컬럼을 인덱스 정의에서 읽습니다. 이전에는 `unknown`이었습니다.

### 결과: 9번의 실제 MySQL 측정과 다시 비교

측정 데이터(actual.json)는 9번과 같습니다.

| 기준 | 9번 | 11번 | **12번** |
|---|---:|---:|---:|
| 재현율 | 89.5% | 100% | **100% (95/95)** |
| 정밀도 (예측된 모든 위험도) | 62.5% | 64.2% | **95.0% (95/100)** |
| 정밀도 (HIGH만, 즉 CI 차단 기준) | 62.5% | 64.2% | **96.9% (95/98)** |
| 마이그레이션 단위 차단 | 10/10 차단, 불필요한 차단 4 | 같음 | **10/10 차단, 불필요한 차단 0** |

이제 **실제로 API를 깨뜨린 마이그레이션 10개만 정확히 차단**합니다(종료 코드 1). 나머지 6개는 통과합니다(종료 코드 0).

| 시나리오 | 9번 판정 | 12번 판정 | 실제 |
|---|---|---|---|
| V5 `MODIFY nickname VARCHAR(50) NOT NULL` | HIGH, API 15개 | **LOW `MODIFY_COMPATIBLE`** (엔티티가 이미 `nullable=false, length=50`) | 실패 없음 |
| V7 `DROP FOREIGN KEY fk_posts_author` | HIGH, API 11개 | **LOW, API 없음** | 실패 없음 |
| V9 posts.category_id FK가 users를 참조 | HIGH, API 18개 | **MEDIUM, `POST /posts` 1개** | 측정 시드에서는 실패 없음 (아래 검증) |
| V10 새 컬럼 reviewer_id에 FK 추가 | (예측 없음) | **LOW** (엔티티가 쓰지 않는 컬럼) | 실패 없음 |
| V14 FK 재정의(CASCADE) + use_count 삭제 | HIGH, API 5개 | HIGH 4개 + MEDIUM 1개 | HIGH 4개와 일치 |
| V16 reason `VARCHAR(200)`, UNIQUE 추가 | HIGH, API 5개 | **MEDIUM, `POST /api/reports` 1개** | 측정 시드에서는 실패 없음 (아래 검증) |

**남은 오탐 5건**
- 3건(HIGH): 11번에서 설명한 분기 한계입니다. 승인과 거절이 같은 `ReportService.process`를 쓰는 `reject` 사례입니다.
- 2건(MEDIUM): V9와 V16입니다. 실제 MySQL에서 직접 검증해 보니 **실제 위험**이었습니다. 9번 측정의 시드 데이터에 경계값이 없어서 드러나지 않았을 뿐입니다.

**V9와 V16 검증** (`community-mysql` 컨테이너, MySQL 8.0.36, 기본 `sql_mode`에 `STRICT_TRANS_TABLES` 포함. 별도 DB `sg_verify`에 기본 스키마와 시드를 넣고 확인한 뒤 삭제)
- **V16:** 적용 후 300자 신고 사유를 INSERT하면 `ERROR 1406 (22001): Data too long for column 'reason'`이 납니다. 앱의 `ReportCreateRequest`는 `@Size(max = 500)`이라 검증을 통과합니다. 즉 사용자가 201~500자 사유를 입력하면 `POST /api/reports`가 500으로 실패합니다.
- **V9:** 적용 전에는 users에 없는 id(4)의 카테고리로 게시글을 저장해도 성공합니다. 적용 후에는 `ERROR 1452 (23000): ... FOREIGN KEY (category_id) REFERENCES users (id)`로 실패합니다. 즉 `POST /posts`는 카테고리 id가 사용자 id와 겹치지 않으면 실패합니다.

이 두 건을 경계값 데이터에서 실제로 실패한 것으로 치면 정밀도는 97.0%(97/100)입니다. 측정 하네스에 경계값 데이터 시나리오를 추가하는 것이 다음 과제입니다(앞서 이야기한 "행 수는 같은데 값이 달라지는 문제").

### 테스트

- 신규 `ChangeClassifierTest` 12개
  - 엔티티와 같은 정의, 넓히기, 길이 축소, JPA 기본 길이(255), 정수 범위 축소, 비호환 타입, enum STRING
  - 축소 + NOT NULL 분리, NULL을 쓰지 않는 필드의 SET NOT NULL, 모르는 컬럼은 보수적으로 유지
  - 매핑되지 않은 컬럼의 FK, FK 제약 변경의 위험도
- `SchemaGuardCLITest`
  - `SET NOT NULL` 테스트를 "쓰기 경로만"으로 바꿨습니다. 픽스처에 `POST /signup`(`save`)을 추가하고, 읽기 API `GET /me`는 영향이 없음을 확인합니다.
  - 호환 MODIFY, 축소(MEDIUM, note 포함), 비호환(HIGH, 종료 코드 1), FK 제약 삭제(종료 코드 0) 테스트 4개를 추가했습니다.
- 결정성 스냅샷: 바뀐 내용을 확인한 뒤 다시 만들었습니다.
  - 읽기 API에 붙어 있던 `ADD_NOT_NULL users.nickname` 3건이 빠졌습니다(NOT NULL은 읽기를 깨지 않음). 픽스처에 저장 경로가 없어서 남는 항목은 없습니다.
  - `cause`에 `newDefinition`, `note` 필드가 추가됐습니다.
- 전체 57개 통과

### 성능

- community를 5,000개 규모로 키운 프로젝트(Java 파일 4,977개), V16 기준 `--timing`입니다.
  - 그래프 생성: 221~241ms
  - 분석: 21~26ms
  - 파싱: 약 4.4초로, 여전히 전체 시간의 대부분입니다.
- 재분류는 변경 수에 비례하는 작업이라 측정할 만큼의 비용이 없습니다.

### 한계

- **이전 스키마를 직접 보지 않습니다.** "코드가 기대하는 스키마"(엔티티)와만 비교합니다. 엔티티의 `@Column(length)`가 실제 DB와 이미 다르면 판단이 틀릴 수 있습니다. 이전 마이그레이션을 차례로 적용해 실제 이전 타입을 아는 방식으로 보완할 수 있습니다.
- **날짜, 불리언, 소수 타입의 변경은 아직 판단하지 않습니다.** 이 경우는 보수적으로 HIGH를 유지합니다.
- **dirty checking(엔티티를 수정만 하고 `save`는 호출하지 않는 업데이트)은 쓰기 경로로 보지 않습니다.** 예를 들어 게시글 수정 API가 `post.update(...)`만 호출하면 길이 축소의 영향 목록에서 빠집니다.
- **UNIQUE 추가는 앱의 사전 중복 검사 여부를 보지 않습니다.** 예를 들어 `existsByReporterIdAndPostId`로 미리 검사하는 경우에도 쓰기 경로를 MEDIUM으로 표시합니다.

---

## 11. 연관 엔티티 로딩 전파: 실제 MySQL 측정에서 놓친 10건 해결

9번 측정에서 놓친 10건(FN)은 원인이 모두 같았습니다. JPA는 **연관 엔티티를 로딩할 때 그 엔티티에 매핑된 컬럼을 전부 SELECT**합니다. 그런데 SchemaGuard는 "컬럼 → 그 컬럼을 가진 엔티티의 Repository"로만 따라갔습니다. 그래서 `users.email`을 지우면 User를 직접 조회하지 않아도 `Post.author`를 함께 로딩하는 게시글 목록이 실패하는데, 이 경로를 몰랐습니다.

### 처음 시도한 방법과 버린 이유

먼저 단순한 규칙을 시험했습니다. "컬럼을 삭제하거나 이름을 바꾸면, 그 테이블을 FK로 참조하는 모든 엔티티로 전파"하는 방식입니다(7번의 `fkFieldsReferencingTable` 재사용). 9번의 실제 측정 결과(actual.json)와 비교한 결과는 다음과 같습니다.

| | FN | FP | 정밀도 | 재현율 |
|---|---:|---:|---:|---:|
| 개선 전 | 10 | 51 | 62.5% | 89.5% |
| 단순 전파 | 0 | **68 (+17)** | 58.3% | 100% |

FN은 사라졌지만 오탐이 17건 늘었습니다. 늘어난 오탐은 모두 연관을 **실제로는 로딩하지 않는** 경로였습니다.
- `countByPostAndStatus`: count 쿼리라 엔티티를 아예 로딩하지 않습니다.
- `report.getPost().getAuthor()`를 그대로 넘기는 경우: User 프록시는 초기화되지 않습니다.
- `post.getAuthor().getId()`: 프록시가 id를 이미 들고 있어서 쿼리가 나가지 않습니다.

community의 연관은 전부 `fetch = LAZY`라서, "참조한다"와 "로딩한다"를 구분해야 했습니다.

### 최종 방법: 연관이 실제로 로딩되는 곳만 연결

새 노드 `load:<Entity>`(`NodeType.ENTITY_LOAD`)는 "이 엔티티가 연관으로 로딩된다"는 뜻입니다. 새 관계 `LOADS`로 연결합니다.

```
load:User ──LOADS──▶ col:users.*                           로딩하면 모든 컬럼(FK 컬럼 포함)을 읽는다
repo:PostRepository.findAllByOrderByIdDesc ──LOADS──▶ load:User    ① @EntityGraph(attributePaths = "author")
repo:ReportRepository.findFull ──LOADS──▶ load:Post, load:User     ② JPQL "join fetch r.post p join fetch p.author"
repo:PostRepository.* ──LOADS──▶ load:User                         ③ Post.author 가 EAGER (fetch 미지정 @ManyToOne 포함)
load:Post ──LOADS──▶ load:User                                      ③ Post 를 연관으로 로딩해도 EAGER author 가 따라온다
svc:ReportService.process ──LOADS──▶ load:Post                     ④ report.getPost().getTitle() (지연 로딩 초기화)
```

**추출(파서)**
- `EntityParser`: 연관 어노테이션의 `fetch`를 읽습니다. 지정되지 않으면 JPA 기본값을 씁니다(`@ManyToOne`/`@OneToOne`은 EAGER, `@OneToMany`/`@ManyToMany`는 LAZY). 결과는 `FkMapping.isEagerFetch()`에 담깁니다.
- `RepositoryParser`: 메서드별로 `@EntityGraph(attributePaths = ...)`와 JPQL의 `join fetch`를 읽어 루트 엔티티 기준 경로로 바꿉니다(예: `post.author`). 결과는 `RepositoryInfo.MethodEntry.fetchPaths`에 담깁니다.
- `ServiceParser`: getter 체인 `x.getFoo().getBar()`에서 바깥 호출이 `getId()`가 아니면 연관 `foo`를 초기화하는 접근으로 기록합니다. 결과는 `ServiceInfo.AssociationAccess`에 담깁니다.

**그래프** (`DependencyGraphBuilder.addAssociationLoadEdges`)
- 위 ①~④를 `LOADS` 엣지로 만듭니다.
- `@EntityGraph` 경로는 단계별로 따라가며 로딩되는 엔티티마다 연결합니다. `post.author`라면 Post와 User 둘 다입니다.
- 서비스 getter 접근은 같은 이름의 FK 필드가 모두 같은 엔티티를 가리킬 때만 연결합니다(이름 기반 추정).

**분석** (`ImpactAnalyzer`)
- `LOADS` 엣지는 **읽기가 깨지는 변경**(`DROP_COLUMN`, `RENAME_COLUMN`, `DROP_FK_COLUMN`, `DROP_TABLE`, `RENAME_TABLE`)에서만 따라갑니다.
- 타입 변경, `NOT NULL`, `UNIQUE`는 기존 값을 읽는 데 영향이 없거나 데이터에 따라 달라서 제외합니다. 9번 측정에서 오탐이 가장 많았던 종류입니다.

**영향 경로 예시** (community, 실제 리포트)

```
V11 GET /                : col:users.email → load:User → svc:PostService.getPosts → ctrl:PostController.postList → api:GET /
V3  GET /api/bookmarks   : col:posts.title → load:Post → repo:BookmarkRepository.findByUserIdOrderByIdDesc → svc:BookmarkService.getMyBookmarks → … → api:GET /api/bookmarks
V3  POST …/accept        : col:posts.title → load:Post → svc:ReportService.process → ctrl:AdminReportController.accept → api:POST /api/admin/reports/{reportId}/accept
```

### 결과: 9번의 실제 MySQL 측정과 다시 비교

비교 방법은 9번과 같습니다. 측정 데이터(actual.json)는 그대로 쓰고, SchemaGuard 예측만 새 빌드로 다시 만들었습니다.

| 기준 | 개선 전 (9번) | 개선 후 |
|---|---:|---:|
| 재현율 (전체) | 89.5% (85/95) | **100% (95/95)** |
| 정밀도 (전체) | 62.5% (85/136) | 64.2% (95/148) |
| 구조 변경 시나리오 9개: 재현율 | 89.0% (81/91) | **100% (91/91)** |
| 구조 변경 시나리오 9개: 정밀도 | 98.8% (81/82) | 96.8% (91/94) |
| 마이그레이션 단위 차단: 재현율 / 정밀도 | 100% / 71.4% | 100% / 71.4% (변화 없음) |

시나리오별로 FN이 있던 V0(2건), V2(2건), V3(3건), V11(3건)이 모두 0이 됐습니다.

**새로 생긴 오탐 2건:** V2와 V3의 `POST /api/admin/reports/{reportId}/reject`입니다.
- 승인과 거절이 같은 서비스 메서드 `ReportService.process(id, accepted)`를 쓰고, `if (accepted)` 분기 안에서만 `report.getPost().getTitle()`을 호출합니다.
- SchemaGuard는 메서드 단위로 판단해서 분기를 구분하지 못합니다. 9번의 V13 오탐 1건과 같은 종류의 한계입니다.

남은 오탐 53건 중 50건은 9번에서 분석한 위험도 판정 문제입니다(FK 제약 변경 30건, 데이터 의존 타입/NOT NULL 변경 20건). 이번 변경과는 관계없습니다.

### 함께 고친 버그: `EntityParser`의 `mappedBy` 처리

`@OneToMany(mappedBy = ...)` 필드를 만나면 `continue`가 아니라 `return`을 해서, **그 엔티티의 뒤쪽 필드가 전부 누락**됐습니다. 예를 들어 `posts` 컬렉션 아래에 선언된 `email` 컬럼은 그래프에 없었습니다. community에는 `@OneToMany`가 없어서 측정에는 영향이 없었지만, 일반적인 양방향 매핑 코드에서는 탐지가 크게 빠졌을 버그입니다.

### 테스트

- 신규 `AssociationLoadTest` 11개
  - 전파되는 경우: `@EntityGraph`, 다단계 `join fetch`, 서비스 getter 접근, EAGER, EAGER 연쇄(`load:Post → load:User`)
  - 전파되지 않는 경우: count 쿼리, 다른 연관만 초기화, `getId()` 프록시 접근, 타입 변경과 `NOT NULL`
  - 경로 표시와 파서 3종, `mappedBy` 버그 회귀 테스트
- `GraphPropagationTest`: "전파 색인이 없으면 놓친다"를 확인하는 테스트의 픽스처가 EAGER(fetch 미지정)였습니다. 그래서 새 로딩 경로가 그 API를 정상적으로 찾아 버려서, 테스트 의도(FK 참조 색인 검증)를 유지하도록 픽스처를 `LAZY`로 바꿨습니다.
- 결정성 스냅샷은 항목 하나가 늘었습니다. `GET /posts`의 `DROP_COLUMN users.email`(경로: `col:users.email → load:User → repo:PostRepository.findByAuthor → …`)입니다. 픽스처의 `Post.author`가 fetch를 지정하지 않은 `@ManyToOne`(= EAGER)이라 실제로도 게시글을 읽을 때 User를 함께 SELECT하므로 맞는 변화입니다. 확인한 뒤 정답 파일을 다시 만들었습니다.
- 전체 41개 통과

### 성능

**벤치마크 생성기 변경:** 10번 벤치마크의 샘플 엔티티는 `@ManyToOne parent`가 fetch를 지정하지 않아(EAGER) t(k) → t(k-1) → … → t0로 길이 1,250의 EAGER 사슬을 이뤘습니다.
- 이 상태로 새 코드를 돌리면, 파일 5,000개와 변경 12개에서 결과가 22,427건이 나오고 분석이 4.9초 걸렸습니다.
- 의미상 맞는 결과입니다. Hibernate도 이 사슬 전체를 로딩합니다. 하지만 실제 프로젝트를 닮지 않았습니다.
- 그래서 생성기를 실제 Spring 코드 관례에 맞췄습니다. `parent`는 `fetch = LAZY`로 바꾸고, `findByEmail`에는 `@EntityGraph("parent")`를 붙였습니다. 이로써 1단계 연관 로딩을 벤치마크에서도 측정합니다.

| 파일 수 | 노드 | 엣지 | graph | analyze (변경 12) | analyze (변경 100) | analyze (변경 1,000) |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 572 | 1,185 | 4.7 ms | 1.9 ms | 7.8 ms | 36.0 ms |
| 1,000 | 5,747 | 11,985 | 46.1 ms | 4.9 ms | 10.3 ms | 58.8 ms |
| 5,000 | 28,747 | 59,985 | 199.0 ms | 5.4 ms | 14.1 ms | 65.7 ms |
| 10,000 | 57,497 | 119,985 | 501.3 ms | 3.3 ms | 14.1 ms | 88.2 ms |
| 20,000 | 114,997 | 239,985 | 704.5 ms | 3.6 ms | 11.4 ms | 113.9 ms |

- 10번 표와 비교하면, 20,000개 기준으로 노드는 4.5%, 엣지는 11.6% 늘었습니다(`load:` 노드와 `LOADS` 엣지). 그래프 생성은 0.63초에서 0.70초로 늘었습니다.
- 분석 시간은 여전히 규모와 거의 무관합니다(변경 1,000개에서 최대 114ms).
- 영향 API 수는 연관 로딩 경로가 더해져 늘었습니다(변경 1,000개 기준 4,000건 → 4,250건).

### 한계

- 분기를 구분하지 못합니다(메서드 단위 판단). 위 `reject` 사례가 그렇습니다.
- DTO나 템플릿에서의 지연 로딩을 보지 못합니다. getter 접근은 `@Service` 클래스 안에서만 봅니다. `ReportResponse.from(report)` 같은 DTO 정적 메서드나 Thymeleaf `${post.author.nickname}`처럼 다른 곳에서 초기화하면 놓칩니다. 이번 community 측정에서는 해당 API들이 `@EntityGraph`로도 잡혀서 드러나지 않았습니다.
- 서비스 getter 접근은 이름으로 추정합니다. `getPost()`처럼 같은 이름의 FK 필드가 서로 다른 엔티티를 가리키면 연결하지 않습니다.
- 깊은 EAGER 사슬은 결과 수와 분석 시간이 사슬 길이에 비례해 늘어납니다. 실제로 그만큼의 API가 영향을 받는다는 뜻이라 결과 자체는 맞습니다.

---

## 10. 규모별 성능 측정과 그래프·분석 단계 병목 개선

6번에서는 파싱 단계만 다뤘습니다. 이번에는 **파싱 이후 단계(그래프 생성, 영향 분석)** 가 규모와 변경 수에 따라 어떻게 늘어나는지 측정했습니다. 그 과정에서 찾은 병목 두 곳을 고쳤습니다.

### 측정 방법

**도구:** `ScaleBenchmark`(`lib/src/test/java/schemaguard/bench/ScaleBenchmark.java`)를 새로 만들었습니다. 실행 명령은 `./gradlew scaleBenchmark`입니다.

**샘플 프로젝트:** 6번의 `SampleProjectGenerator`로 Java 파일 100, 1,000, 5,000, 10,000, 20,000개짜리를 만듭니다. 4개 파일(Entity, Repository, Service, Controller)이 한 그룹이고, 그룹마다 테이블 하나가 있습니다.

**마이그레이션:** 변경 12, 100, 1,000개짜리를 만듭니다. 테이블 t0부터 차례로 `DROP COLUMN`, `RENAME TABLE`, `DROP TABLE`, `MODIFY`를 번갈아 겁니다. 테이블 단위 변경이 절반이라 "테이블의 모든 컬럼 찾기" 비용이 그대로 드러납니다.

**측정 절차:**
- 규모마다 별도 JVM(`-Xmx4g`, Java 17.0.21)을 띄웁니다.
- 파싱은 1회만 합니다. 파싱 비교는 6번 `ParseBenchmark`의 몫이라 참고용입니다.
- 그래프 생성과 영향 분석은 각각 워밍업 1회 후 3회 측정해 중앙값을 냅니다.
- 그래프 유지 힙은 마지막 그래프를 만들기 전과 후, 각각 GC를 한 직후의 사용 힙 차이입니다.

**결과 동일성:** 영향 API, 위험도, 원인, 경로 전체를 SHA-256 지문으로 만들어 개선 전후를 비교합니다.

**개선 전 측정:** 이전 커밋(`fe9189a`)의 worktree에 같은 벤치마크 파일을 넣어 같은 조건으로 실행했습니다.

### 개선 전 결과와 병목

| 파일 수 | 노드 | 엣지 | graph | 그래프 유지 힙 | analyze (변경 12) | analyze (변경 100) | analyze (변경 1,000) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 548 | 1,066 | 4.2 ms | ~0 MB | 2.9 ms | 10.4 ms | 30.8 ms |
| 1,000 | 5,498 | 10,741 | 41.1 ms | 4 MB | 6.3 ms | 27.4 ms | 147.7 ms |
| 5,000 | 27,498 | 53,741 | 222.0 ms | 21 MB | 34.3 ms | 206.7 ms | 1.34 s |
| 10,000 | 54,998 | 107,491 | 449.4 ms | 45 MB | 60.2 ms | 413.4 ms | 4.11 s |
| 20,000 | 109,998 | 214,991 | 1.28 s | 92 MB | 97.3 ms | 1.48 s | **12.27 s** |

노드와 엣지 수, 메모리는 파일 수에 비례합니다. 시간은 두 곳이 비례보다 빠르게 늘어났습니다.

1. **영향 분석이 (변경 수 × 노드 수)에 비례했습니다.**
   - 변경 1,000개 기준으로, 파일을 5,000개에서 20,000개로 4배 늘리면 분석 시간은 1.34초에서 12.27초로 9배 늘었습니다.
   - 원인은 `ImpactAnalyzer.allColumnNodesOfTable`입니다. 테이블 단위 변경이 하나 올 때마다 `nodeIndex` 전체를 `"col:<테이블>."` 접두사로 훑었습니다. `allFkFieldNodesOfTable`도 이 메서드를 부르기 때문에 변경 하나당 두 번 훑었습니다.
2. **그래프 생성이 (리포지토리 수 × 전체 필드 수)에 비례했습니다.**
   - 파일을 5,000개에서 20,000개로 4배 늘리면 그래프 생성 시간은 0.22초에서 1.28초로 5.8배 늘었습니다.
   - 원인은 `DependencyGraphBuilder.addRepositoryEdges`입니다. 리포지토리마다 엔티티 매핑 목록과 FK 매핑 목록 전체를 `filter`로 훑어 자기 엔티티의 필드를 찾았습니다.

### 변경

- `ImpactAnalyzer`
  - 처음 필요할 때 `nodeIndex`를 **한 번만** 훑어 "테이블 → 컬럼 노드" 인덱스(`columnsByTable`)를 만들고, 이후에는 조회만 합니다.
  - 노드 id `col:<table>.<column>`의 마지막 `.`을 기준으로 테이블과 컬럼을 나눕니다. 컬럼명에는 `.`이 없습니다.
- `DependencyGraphBuilder.addRepositoryEdges`
  - 엔티티 매핑과 FK 매핑을 엔티티 클래스별로 **한 번** 묶어 두고 조회합니다.

### 개선 후 결과

| 파일 수 | graph (전 → 후) | analyze 변경 12 (전 → 후) | analyze 변경 100 (전 → 후) | analyze 변경 1,000 (전 → 후) | 결과 지문 |
|---:|---:|---:|---:|---:|---|
| 100 | 4.2 → 5.0 ms | 2.9 → 3.4 ms | 10.4 → 8.0 ms | 30.8 → 21.7 ms | 일치 |
| 1,000 | 41.1 → 41.7 ms | 6.3 → 3.7 ms | 27.4 → 9.2 ms | 147.7 → 53.8 ms | 일치 |
| 5,000 | 222.0 → 231.9 ms | 34.3 → 2.4 ms | 206.7 → 11.0 ms | 1.34 s → 58.0 ms (23배) | 일치 |
| 10,000 | 449.4 → 388.6 ms | 60.2 → 3.0 ms | 413.4 → 10.9 ms | 4.11 s → 62.1 ms (66배) | 일치 |
| 20,000 | 1.28 s → 630.1 ms (2.0배) | 97.3 → 2.8 ms | 1.48 s → 10.6 ms | **12.27 s → 59.1 ms (208배)** | 일치 |

- **영향 분석:** 시간이 프로젝트 크기와 거의 무관해졌습니다. 변경 1,000개면 규모와 상관없이 약 60ms이고, 영향받는 API 수에 비례하는 일만 남았습니다.
- **그래프 생성:** 20,000개에서 2배 빨라졌습니다. 5,000개 이하에서는 차이가 측정 오차 범위입니다.
- **결과 지문:** 모든 규모와 변경 수 조합에서 개선 전후가 같습니다. 기존 테스트 30개도 결정성 스냅샷을 포함해 그대로 통과합니다.
- **파싱:** 파일 20,000개에서 51~63초로 여전히 전체 시간의 대부분을 차지합니다. 단, 이번 측정에서는 JVM마다 1회만 재서 편차가 ±30% 정도 됩니다. 정확한 비교는 6번 표를 참고하세요.

### CI 관점: PR 하나에 추가되는 검사 시간

`installDist` 바이너리를 실제로 실행해 JVM 시작부터 리포트 저장까지의 wall time을 쟀습니다. 마이그레이션은 `V15__rename_tags_to_hashtags.sql`이고, 5회 중앙값입니다.

| 대상 프로젝트 | Java 파일 수 | 개선 전 | 개선 후 |
|---|---:|---:|---:|
| community | 63 | 1.04 s | 1.07 s |
| community 복제 (1,000) | 1,008 | 2.85 s | 2.93 s |
| community 복제 (5,000) | 4,977 | 9.29 s | 7.35 s |
| community, 마이그레이션 18개를 하나씩 순차 실행 | 63 | 18.62 s | 19.68 s |

- 마이그레이션 파일 하나를 검사하는 PR의 추가 시간은 community 규모에서 **약 1초**이고, Java 파일 5,000개 규모에서도 **약 7~9초**입니다.
- 실제 PR은 변경 수가 적습니다(수 개~수십 개). 그래서 이번 분석 단계 개선은 CI 시간에서 거의 체감되지 않습니다. 5,000개에서 줄어든 2초도 그래프 생성 측정에서는 재현되지 않아서, 디스크 캐시 같은 실행 환경 차이로 봅니다.
- 이번 개선이 효과를 내는 경우는 따로 있습니다. 대량의 마이그레이션을 한 번에 검사하는 경우(예: 누적된 마이그레이션 전체 재검사)이거나 1만 개가 넘는 대형 모노레포인 경우입니다. 이때 분석 단계가 초 단위로 늘어나던 문제를 없앴습니다.
- **CI 시간의 실제 지배 요인**은 JVM 시작(약 0.5~1초)과 Java 파싱입니다. 지금 워크플로(5번 항목)는 마이그레이션 파일마다 CLI를 따로 실행해서 소스를 매번 다시 파싱합니다(18개면 약 19초). 다음 개선 후보는 "여러 마이그레이션 파일을 한 번의 실행으로 분석해 소스를 한 번만 파싱"하는 것입니다.

---

## 9. 실제 MySQL로 검증한 탐지 정확도

SchemaGuard의 핵심 주장은 "배포 전에 런타임 오류가 날 API를 찾는다"입니다. 이 주장을 사람이 결과를 읽어 보는 방식이 아니라 **실제 DB에서 API를 호출해 생기는 실패**와 비교해 검증했습니다. 이 항목은 측정만 했고, SchemaGuard 코드는 바꾸지 않았습니다.

### 요약

- 실제로 DB 오류가 난 API 95건 중 **85건을 사전에 탐지**했습니다(재현율 89.5%). 정밀도는 62.5%(예측 136건 중 85건 적중)입니다.
- 마이그레이션 단위로 보면 실제로 API를 깨뜨린 마이그레이션 10개를 **모두 차단**했습니다(누락 0). 깨뜨리지 않은 6개 중 4개는 불필요하게 차단했습니다.
- 컬럼이나 테이블을 삭제하거나 이름을 바꾸는 **구조 변경**만 보면 정밀도 98.8%, 재현율 89.0%입니다. 오탐 51건 중 49건은 **제약이나 타입만 바꾸는** 마이그레이션 6개에서 나왔습니다.
- 놓친 10건은 모두 원인이 하나입니다. **연관 엔티티 로딩**(`@EntityGraph`와 지연 로딩)으로 다른 테이블의 컬럼을 읽는 경로를 모델링하지 않았습니다.

### 측정 방법

**측정 대상**
- 앱: `community` 저장소 `test/schemaguard-complex` 브랜치(커밋 `1151962`)
- 마이그레이션: `src/main/resources/db/migration`의 18개 파일 전부
- 시나리오: V1, V12는 `CREATE TABLE` 파일이라 다음 항목의 기본 스키마로 쓰고 시나리오에서 뺐습니다. 실제 시나리오는 16개입니다.

**환경**

| 항목 | 값 |
|---|---|
| DB | `mysql:8.0.36` (Testcontainers 2.0.4, Spring Boot 4.0.5 BOM 관리 버전, Docker Engine 29.6.2) |
| 컨테이너 옵션 | `--innodb-flush-log-at-trx-commit=0 --skip-log-bin` (복원 속도용, 쿼리 의미에는 영향 없음) |
| JPA | `spring.jpa.hibernate.ddl-auto=none`. `validate`면 스키마가 달라졌을 때 앱이 아예 뜨지 않고, `update`면 Hibernate가 사라진 컬럼을 다시 만들어 오류를 덮습니다. Flyway, Liquibase, `spring.sql.init`은 끕니다. |
| 테스트 | `community`의 `src/test/java/com/schemaguard/community/accuracy/SchemaGuardAccuracyTest.java`. 실행 명령은 `./gradlew test --tests '*SchemaGuardAccuracyTest*'`이고 약 13분 걸립니다. |

**기본 스키마** (`src/test/resources/accuracy/base-schema.sql`)
- V1 + V12로 만들었습니다. V1의 `posts` 정의 뒤에 빠진 `;`를 보충했습니다. 이 누락 때문에 SchemaGuard가 V1에 종료 코드 3을 냅니다. 마이그레이션 원본은 SchemaGuard의 입력이라 고치지 않았습니다.
- 엔티티와 맞지 않는 부분을 보정했습니다. `users.password`와 `users.role`을 추가했고, `comments.content`를 `VARCHAR(1000)`으로 바꿨습니다.
- 마이그레이션이 이름으로 참조하는 FK 제약 이름(`fk_comments_post` 등)은 V1, V12의 것을 그대로 유지했습니다.
- 검증: 이 스키마에서 `ddl-auto=validate`로 컨텍스트가 정상적으로 뜨는 것을 확인했습니다(`CommunityApplicationTests.contextLoads` 통과).

**시드 데이터** (`seed.sql`)
- 모든 테이블에 FK가 맞는 행을 2~3개씩 넣었습니다(사용자, 카테고리, 게시글, 댓글, 좋아요, 태그, 북마크, 신고, 알림). 데이터가 없어서 쿼리가 실행되지 않는 API가 없도록 하기 위해서입니다.
- 사용자 3명의 비밀번호는 `password1234`의 bcrypt 해시입니다.

**시나리오마다 하는 일**
1. 모든 테이블을 삭제하고 기본 스키마와 시드를 적용합니다.
2. 대상 마이그레이션을 구문 단위로 실행합니다. 실패한 구문은 기록하고 다음 구문으로 계속 갑니다.
3. Hikari 커넥션 풀을 비워서 이전 스키마 메타데이터가 남지 않게 합니다.
4. 엔드포인트를 전부 호출합니다.
   - 호출하기 전마다 모든 테이블을 "마이그레이션 직후 스냅샷"으로 되돌립니다(TRUNCATE + INSERT). 삭제나 생성 같은 호출이 뒤 호출의 결과에 영향을 주지 않게 하기 위해서입니다.

**엔드포인트**
- 목록은 `RequestMappingHandlerMapping`에서 앱 패키지의 핸들러 메서드를 자동으로 수집합니다. 손으로 만든 호출 표와 다르면 테스트가 실패합니다. 총 28개입니다.
- API id 형식은 SchemaGuard와 같은 `"<HTTP 메서드> <경로 패턴>"`입니다(예: `POST /posts/{id}/delete`).
- 호출은 MockMvc로 합니다.
  - 시드 사용자로 인증하고(`with(user(...))`, 앱의 `UserDetails` 구현 사용), 모든 요청에 `csrf()`를 붙입니다.
  - 경로 변수에는 시드 id를 넣고, 폼과 JSON 본문은 검증을 통과하는 값으로 채웁니다.
  - 좋아요나 북마크처럼 분기가 있는 엔드포인트는 분기별로 여러 번 호출합니다(추가/취소, 내 글/남의 글, 필터 유무). 그중 하나라도 DB 오류가 나면 실패로 칩니다.
- Spring Security는 유지했습니다. 앱 구성을 바꾸지 않고 측정하기 위해서입니다.

**실패 판정**
- 예외 체인(`perform()`이 던진 예외, `getResolvedException()`)의 원인을 끝까지 따라갑니다.
- `DataAccessException`, `SQLException`, `JDBCException`, `PersistenceException` 중 하나라도 있으면 "스키마로 인한 실패"로 판정합니다.
- 그 밖의 4xx/5xx는 "DB 외 실패"로 따로 기록합니다. 이번 측정에서는 0건이었습니다.

**기준선**
- 기본 스키마에서 28개 엔드포인트 모두 DB 오류가 0건입니다. 실험 전부터 깨져 있던 엔드포인트도 없습니다.
- 예외 하나: 이 앱의 `deletePost`는 신고(`reports`)를 지우지 않아서, 신고된 글은 기본 스키마에서도 FK 오류로 삭제가 안 됩니다. 그래서 삭제 호출에는 신고가 없는 글(id 3)을 썼습니다. 이 앱의 실제 버그이고, 다음 개선 후보인 "삭제 경로의 FK 정리 누락"의 사례입니다.

**비교 방법**
- 예측: 같은 마이그레이션마다 SchemaGuard CLI(`da5892e` 빌드)를 `--output json`으로 실행하고, `affectedApis`의 `apiId` 집합을 씁니다.
- 실제: 시나리오별 `dbFailingApis`
- 집계: (시나리오, API) 쌍 단위로 TP/FP/FN을 셉니다. 정밀도 = TP / (TP+FP), 재현율 = TP / (TP+FN)
- 재현: `scripts/accuracy/predict.sh <community> <out>`로 예측을 만들고, `python scripts/accuracy/compare.py actual.json <out> --codes <out>/codes.txt`로 비교합니다.

### 결과

**시나리오별**

| 마이그레이션 | 내용 | 종료 코드 | 실제 실패 | 예측 | TP | FP | FN |
|---|---|---:|---:|---:|---:|---:|---:|
| V0 | users.email 삭제, comments.post_id 삭제 등 | 1 | 18 | 16 | 16 | 0 | 2 |
| V2 | users.nickname 삭제, posts.title 이름 변경 | 1 | 20 | 18 | 18 | 0 | 2 |
| V3 | posts.title → subject | 1 | 14 | 11 | 11 | 0 | 3 |
| V4 | users.age 타입 변경 (엔티티에 없는 컬럼) | 0 | 0 | 0 | 0 | 0 | 0 |
| V5 | `MODIFY nickname VARCHAR(50) NOT NULL` | 1 | 0 | 15 | 0 | 15 | 0 |
| V6 | comments 테이블 삭제 | 1 | 4 | 4 | 4 | 0 | 0 |
| V7 | FK `fk_posts_author` 삭제 | 1 | 0 | 11 | 0 | 11 | 0 |
| V8 | comments.post_id 삭제 | 1 | 4 | 4 | 4 | 0 | 0 |
| V9 | posts.category_id FK가 users를 참조하도록 변경 | 1 | 0 | 18 | 0 | 18 | 0 |
| V10 | post_likes.user_id FK 추가 | 0 | 0 | 0 | 0 | 0 | 0 |
| V11 | users.email 삭제 | 1 | 18 | 15 | 15 | 0 | 3 |
| V13 | notifications.is_read → read_flag | 1 | 5 | 6 | 5 | 1 | 0 |
| V14 | post_tags FK 재정의(CASCADE) + tags.use_count 삭제 | 1 | 4 | 5 | 4 | 1 | 0 |
| V15 | tags → hashtags | 1 | 5 | 5 | 5 | 0 | 0 |
| V16 | reports.reason `VARCHAR(200) NOT NULL`, UNIQUE 추가, DEFAULT 변경 | 1 | 0 | 5 | 0 | 5 | 0 |
| V17 | bookmarks.post_id 삭제 | 1 | 3 | 3 | 3 | 0 | 0 |
| **합계** | | | **95** | **136** | **85** | **51** | **10** |

**지표**

| 기준 | 정밀도 | 재현율 |
|---|---:|---:|
| 전체 (API 단위) | 62.5% (85/136) | **89.5% (85/95)** |
| 구조 변경 시나리오 9개 (V0, V2, V3, V6, V8, V11, V13, V15, V17) | **98.8% (81/82)** | 89.0% (81/91) |
| 혼합 시나리오 1개 (V14) | 80.0% (4/5) | 100% (4/4) |
| 제약·타입만 바꾼 시나리오 6개 (V4, V5, V7, V9, V10, V16) | 0% (0/49) | (실제 실패 없음) |
| 마이그레이션 단위 (차단 여부) | 71.4% (10/14) | **100% (10/10)** |

이번 측정에서 예측한 영향은 모두 HIGH 위험이었습니다. 그래서 "HIGH만" 기준으로 계산해도 지표가 같습니다.

### 놓친 10건(FN) 분석: 연관 엔티티 로딩

10건 모두 오류가 `Unknown column '<별칭>.<컬럼>' in 'field list'` 형태입니다. **조회하는 엔티티가 아니라 연관된 다른 엔티티의 컬럼**에서 났습니다.

| 시나리오 | API | 실제 오류 | 경로 |
|---|---|---|---|
| V0, V11 | `GET /` | `a1_0.email` | `PostRepository`의 `@EntityGraph(attributePaths = {"author", "category"})`가 게시글 목록을 조회할 때 작성자(User) 컬럼 전체를 JOIN으로 읽음 |
| V11 | `POST /posts/{id}/delete` | `a1_0.email` | 같은 이유 (게시글 조회 시 author 로딩) |
| V0, V11 | `GET /api/admin/reports` | `r2_0.email` | 신고 목록을 응답으로 만들 때 신고자(User)를 지연 로딩 |
| V2, V3 | `GET /api/admin/reports`, `POST /api/admin/reports/{reportId}/accept` | `p1_0.title` | 신고 → 게시글(Post) 로딩 |
| V3 | `GET /api/bookmarks` | `p1_0.title` | `BookmarkRepository`의 `@EntityGraph({"post", "post.author"})` |

SchemaGuard는 "컬럼 → 그 컬럼을 가진 엔티티의 필드 → **그 엔티티의** Repository"로만 따라갑니다. JPA는 연관 엔티티를 로딩할 때(EntityGraph, fetch join, 지연 로딩 프록시 초기화) 그 엔티티의 **모든 컬럼**을 SELECT합니다. 그래서 `users.email`을 지우면 User를 연관으로 가진 Post, Report, Bookmark를 조회하는 API도 실제로 깨집니다.

**개선 방향:** 컬럼 단위 변경(DROP, RENAME, MODIFY_TYPE)의 시작점에 "그 테이블을 FK로 참조하는 FK 필드"를 추가하면 됩니다. 7번에서 만든 `fkFieldsReferencingTable` 색인을 테이블 단위 변경에 이어 컬럼 단위 변경에도 쓰는 방식입니다. 10건이 모두 이 경로에 해당합니다.

### 잘못 잡은 51건(FP) 분석

| 원인 | 시나리오 | FP |
|---|---|---:|
| **제약만 바꾼 변경을 HIGH로 판정.** FK 제약을 삭제하거나 다른 테이블을 참조하게 바꿔도 SELECT, UPDATE, DELETE 쿼리 자체는 깨지지 않습니다. V9에서 새 FK에 위반되는 INSERT가 있으면 실패하겠지만, 시드에서 users와 categories의 id 범위(1~3)가 겹쳐 재현되지 않았습니다. | V7, V9, V14(FK 재정의 부분) | 30 |
| **데이터에 따라 달라지는 타입·NOT NULL 변경을 HIGH로 판정.** `VARCHAR(100)→(50) NOT NULL`, `VARCHAR(200) NOT NULL`은 기존 값이 짧고 NULL이 없으면 성공하고, 이후 쓰기도 정상 값이면 실패하지 않습니다. 파서가 `MODIFY`를 모두 `MODIFY_TYPE`(HIGH)으로 분류하는 것도 원인입니다. | V5, V16 | 20 |
| **메서드 단위 근사.** `POST /api/admin/reports/{reportId}/reject`는 알림 테이블을 쓰는 경로가 그래프에 연결되어 있지만, 실제로 거절 분기에서는 알림 컬럼을 건드리지 않습니다. | V13 | 1 |

FP 51건 중 50건은 그래프 탐색의 문제가 아닙니다. **변경 종류별 위험도 판정**에서 나왔습니다. 개선 방향은 다음과 같습니다.
- FK 제약 삭제는 "쿼리 오류 위험 없음, 무결성 위험"으로 내립니다(LOW).
- FK 참조 변경은 쓰기 경로(INSERT/UPDATE)만 영향으로 봅니다.
- `MODIFY`는 이전 타입과 비교해 실제 타입 변경인지, 줄이는 변경인지, NOT NULL만 추가하는지 구분합니다. 이전 마이그레이션이나 엔티티의 `@Column(length)`를 보면 알 수 있습니다.

### 측정의 한계

- 시드 데이터에 따라 결과가 달라지는 시나리오가 있습니다(V5, V9, V16). 예를 들어 50자가 넘는 닉네임이나 users에 없는 category_id를 시드에 넣으면 실제 실패가 생깁니다. 이번 측정은 "정상 데이터에서 쿼리가 깨지는가"를 기준으로 했습니다.
- 데이터를 매번 마이그레이션 이후 스냅샷으로 되돌리므로, 마이그레이션이 지운 데이터는 복원되지 않습니다.
- 엔드포인트 호출 값(파라미터, 폼, 분기)은 하네스에서 직접 정했습니다. 호출하지 않은 분기의 실패는 측정되지 않습니다.
- 대상 앱이 하나(엔드포인트 28개)입니다. 수치는 이 앱 기준이고, 일반화하려면 다른 프로젝트에서도 측정해야 합니다.

### 부수 결과

- `community`에 로컬 실행 환경을 만들었습니다.
  - Docker 컨테이너 `community-mysql`(포트 3307)에 기본 스키마와 시드를 넣었습니다.
  - gitignore 대상인 `application.properties`를 만들었습니다(`ddl-auto=validate`).
  - 이전에 실패하던 `CommunityApplicationTests.contextLoads`가 통과합니다.
  - `bootRun` 후 폼 로그인(alice@example.com / password1234)과 주요 페이지가 정상 동작합니다.
- 이 정확도 테스트는 일반 `./gradlew test`에도 포함되어 있어서, community의 테스트 시간이 약 13분 늘었습니다. 태그로 분리하는 것이 다음 할 일입니다.

---

## 8. 같은 입력이면 같은 결과 (결정성)

**문제**

같은 커밋을 Windows 로컬과 Ubuntu CI에서 분석하면 리포트가 달라질 수 있었습니다. 영향받는 API 집합은 같지만, 표시되는 영향 경로와 순서, 파일 위치가 달라졌습니다. 원인은 네 가지입니다.

1. `File.listFiles()`의 순서는 OS와 파일시스템에 따라 다릅니다. NTFS는 대체로 이름순이고, ext4는 해시 순서입니다. 파일 순서가 그래프에 노드와 엣지가 들어가는 순서를 정하고, 이 순서가 BFS가 이웃을 방문하는 순서를 정합니다.
2. `deduplicate`는 같은 (API, 원인) 쌍이 여러 개면 **처음 들어온** 경로를 남겼습니다. 리포트 순서도 SQL 순서와 탐색 순서를 따랐습니다.
3. 그래프 노드의 위치(파일, 라인)는 그 노드를 **처음 만든 쪽**의 값으로 정해졌습니다. 예를 들어 `svc:B.m`을 A의 호출 지점이 먼저 만들면 파일 경로가 없고 A의 호출 라인이 남았습니다. B의 정의가 먼저 처리되면 B의 파일과 라인이 남았습니다.
4. JSON의 줄바꿈은 OS 기본값(Windows CRLF, Linux LF)이었습니다. 소스 파싱 실패 경로는 `javaFile.getPath()` 그대로라 구분자가 `\`일 수도 `/`일 수도 있었습니다.

`HashMap`(`nodeIndex`) 자체는 큰 원인이 아닙니다. `String.hashCode()`는 명세로 고정되어 있어, 넣는 순서가 같으면 순회 순서도 같습니다. 그래도 아래 2번의 정렬로 이 의존도 함께 끊었습니다.

**변경**

1. `JavaSourceScanner.collectJavaFiles`가 경로(`/` 정규화) 기준으로 정렬된 목록을 돌려줍니다.
2. `ImpactAnalyzer`
   - 시작 노드를 중복 제거한 뒤 노드 id 순으로 탐색합니다.
   - BFS가 이웃을 노드 id 순으로 방문합니다.
   - 같은 (API, 원인) 쌍이 여러 개면 **짧은 경로**를 남기고, 길이가 같으면 경로의 노드 id 사전순으로 정합니다.
   - 결과를 위험도(HIGH 먼저), API, 원인 순으로 정렬합니다.
3. `DependencyGraphBuilder.getOrCreate`의 위치 선택 규칙을 바꿨습니다. 순서와 무관하게 파일 경로가 있는 위치가 없는 위치보다 우선합니다. 둘 다 있으면 (경로, 라인)이 작은 쪽을 남깁니다.
4. JSON 리포트
   - 줄바꿈을 LF로 고정했습니다.
   - `parse.sourceParseFailures`를 `--source` 기준 상대경로와 `/` 구분자로 바꾸고 정렬했습니다.
   - `.gitattributes`에서 스냅샷 정답 파일을 LF로 고정했습니다.

**테스트** (`DeterminismTest`, 4개)

| 테스트 | 내용 |
|---|---|
| `reportMatchesSnapshot` | 샘플 프로젝트(엔티티 2, 서비스 3, 컨트롤러 3, 파싱 실패 파일 1)와 마이그레이션 4구문으로 만든 JSON 리포트를 `lib/src/test/resources/snapshots/determinism-report.json`과 바이트 단위로 비교합니다. `GET /admin/users`처럼 같은 API에 닿는 경로가 여러 개인 구조를 일부러 넣었습니다. |
| `repeatedRunsProduceIdenticalReports` | CLI를 3번 실행해 리포트가 같은지 확인합니다. |
| `javaFileOrderDoesNotChangeReport` | Java 파일 입력 순서를 섞어(시드 20개 + 역순) 리포트가 같은지 확인합니다. OS마다 다른 `listFiles` 순서를 흉내 냅니다. |
| `collectJavaFilesReturnsPathSortedList` | 파일 목록이 정렬되어 나오는지 확인합니다. |

결과를 의도적으로 바꿨다면 아래 명령으로 정답 파일을 다시 만들고 diff를 검토한 뒤 커밋합니다.

```
./gradlew test --tests '*DeterminismTest*' -PupdateSnapshots=true
```

**검증**

- 이전 main 코드에 새 테스트를 돌리면 `reportMatchesSnapshot`과 `javaFileOrderDoesNotChangeReport`가 실패하고, 새 코드에서는 통과합니다. `collectJavaFilesReturnsPathSortedList`는 이전 코드에서도 통과했는데, Windows NTFS가 원래 이름순으로 돌려주기 때문입니다. Linux에서는 이 보장이 없습니다.
- 전체 테스트: 30개 모두 통과.
- community 마이그레이션 18개로 전후를 비교했습니다.
  - 종료 코드와 summary, (API, 위험도, 원인) 160건이 모두 같습니다.
  - 영향 경로가 바뀐 54건 중 5건은 더 짧아졌고, 49건은 길이가 같은 경로 중 id 순으로 정해진 쪽으로 바뀌었습니다. 더 길어진 것은 없습니다.
  - 이전 리포트는 Windows에서 CRLF였고, 새 리포트는 LF입니다.
- 같은 OS에서 두 번 실행한 결과는 이전 코드도 바이트 단위로 같았습니다. 이번 변경이 고정하는 것은 OS나 파일시스템, 입력 순서가 달라질 때의 차이입니다.

---

## 7. 실제 프로젝트(community) 검증에서 찾은 누락 2건 수정

`community` Spring Boot 프로젝트에 태그·신고·알림·북마크 기능(Java 파일 25개)과 마이그레이션 V12~V17을 더 붙인 뒤, 마이그레이션 18개를 SchemaGuard로 돌려 결과를 사람이 읽어 보며 찾은 누락입니다. 이 기능은 `community` 저장소의 `test/schemaguard-complex` 브랜치에만 있습니다.

**누락 1: 컨트롤러가 서비스를 여러 개 호출해도 첫 호출만 연결**

- 문제: `ControllerParser`가 컨트롤러 메서드 안에서 처음 만난 서비스 호출 하나만 기록했습니다. 두 번째 이후 서비스에 깨지는 컬럼이 있어도 API가 결과에서 빠졌습니다.
- 예: `PostController.postDetail`은 `postService.getPost(...)`, `commentService.getCommentsByPostId(...)` 순서로 호출합니다. `DROP TABLE comments`를 하면 상세 페이지 `GET /posts/{id}`가 깨지는데 결과에 없었습니다.
- 변경: 메서드 안의 서비스 호출을 전부(같은 `서비스.메서드`는 한 번만) 기록합니다. `ControllerInfo.EndpointMapping`에 `serviceCalls` 목록을 추가했고, 기존 `calleeServiceClass` 등 필드는 첫 호출을 그대로 가리켜 호환됩니다. 그래프 빌더는 모든 호출에 컨트롤러→서비스 엣지를 만듭니다.

**누락 2: 테이블 삭제/이름 변경이 FK로 참조하는 엔티티로 번지지 않음**

- 문제: `DROP TABLE`, `RENAME TABLE`의 탐색 시작점이 "그 테이블의 컬럼과 그 테이블이 가진 FK 필드"뿐이었습니다. 다른 엔티티가 `@ManyToOne`으로 그 테이블을 참조하는 경우는 시작점이 아니었습니다.
- 예: `tags`의 이름을 바꾸면 `Tag` 엔티티는 물론 `@ManyToOne Tag tag`를 가진 `PostTag`를 쓰는 API도 깨지는데, `PostTag`를 거치는 3개가 빠졌습니다.
- 변경: `DependencyGraphBuilder`가 "테이블 → 그 테이블을 FK로 참조하는 FK 필드 노드" 색인(`getFkFieldsReferencingTable`)을 만듭니다. `ImpactAnalyzer`는 `DROP_TABLE`/`RENAME_TABLE`의 시작점에 이 노드를 추가합니다. 색인 없이 만든 기존 생성자도 그대로 동작합니다.

**결과 (community, 마이그레이션 18개)**

| 마이그레이션 | 영향 API 수 (수정 전 → 후) | 새로 잡힌 것 |
|---|---|---|
| V6 `DROP TABLE comments` | 3 → 4 | `GET /posts/{id}` (`PostController.postDetail`이 두 번째로 호출하는 `commentService`) |
| V15 `RENAME TABLE tags TO hashtags` | 2 → 5 | `GET /api/tags/posts/{postId}`, `GET /api/tags/{name}/posts`, `POST /posts/{id}/delete` (모두 `PostTag` 경유) |
| V0, V2, V3, V5, V7, V8, V9, V11 | 각각 1~3개 증가 | 첫 서비스 호출 뒤에 있던 서비스 경유 API |
| V1, V4, V10, V12, V13, V14, V16, V17 | 변화 없음 | - |

수정 전에 있던 결과가 사라진 경우는 없습니다(모든 마이그레이션에서 개수가 같거나 늘었습니다).

**테스트**: `GraphPropagationTest`(신규, 6개)
- 같은 서비스 호출은 한 번만 기록하고 서로 다른 호출은 모두 기록하는지
- 두 번째 서비스의 컬럼 삭제로 API가 잡히는지
- `RENAME TABLE`과 `DROP TABLE`이 FK 참조 엔티티의 API로 전파되는지 (색인 없는 기존 동작은 놓친다는 대조 포함)
- 무관한 테이블 변경은 전파되지 않는지, 색인이 `tags → PostTag.tag`를 올바르게 만드는지

**알려진 한계**

- FK 전파는 `@ManyToOne` 같은 FK 어노테이션이 있는 필드만 대상입니다. `@OneToMany`(`@JoinColumn` 없음)와 `Long tagId`처럼 FK를 연관관계 없이 숫자로 들고 있는 경우는 찾지 못합니다.
- 컨트롤러가 호출하는 서비스는 "필드로 선언된 서비스"로 한정됩니다. 지역 변수나 메서드 체이닝으로 얻은 서비스 호출은 여전히 놓칩니다.

**관련 파일**: `ControllerInfo.java`, `ControllerParser.java`, `DependencyGraphBuilder.java`, `ImpactAnalyzer.java`, `SchemaGuardCLI.java`, `ParseBenchmark.java`, `GraphPropagationTest.java`(신규)

---

## 6. Java 파일 중복 파싱 제거 (파일당 4회 → 1회)

**문제**

`SchemaGuardCLI`가 Java 파일마다 `EntityParser`, `RepositoryParser`, `ServiceParser`, `ControllerParser`의 `parse(File)`를 차례로 호출했습니다. 각 파서가 안에서 `StaticJavaParser.parse()`를 따로 실행하기 때문에, 파일 N개를 분석하면 **파싱이 4N번** 일어났습니다.

**측정 방법**

- `SampleProjectGenerator`가 Entity·Repository·Service·Controller가 서로 연결된 샘플 프로젝트를 100 / 1,000 / 5,000 파일 규모로 만듭니다. FK, 서비스 간 호출, 엔드포인트 2개, 파일당 보조 메서드 8개를 넣었습니다. 마이그레이션에는 DROP COLUMN 10개, MODIFY 1개, RENAME TABLE 1개가 들어갑니다.
- `ParseBenchmark`가 `legacy`(개선 전 스캔 루프를 그대로 옮긴 것)와 `shared`(개선 후)를 **각각 별도 JVM**에서 3회씩 번갈아 실행하고 중앙값을 냅니다.
- 구간: collect(파일 수집 + SQL 파싱) / parse(Java 파싱 + 추출) / graph(그래프 생성) / analyze(BFS)
- 메모리:
  - **파싱 단계 할당량**: parse 구간에서 새로 할당한 바이트 수 (`ThreadMXBean`)
  - **분석 후 유지 힙**: 분석이 끝나고 GC 직후 남아 있는 힙. AST가 쌓여 남지 않는지 확인하는 용도입니다.
  - 최대 힙(샘플링): 2ms 간격 샘플링. GC 정책에 좌우되므로 참고용입니다.
- 결과 일치: 영향 API·원인·경로(파일:라인), 그래프 노드·엣지, 추출 개수 전체를 정렬해 SHA-256으로 비교합니다.
- 실행: `./gradlew benchmark` (옵션 `-PbenchArgs="--sizes 100,1000,5000 --reps 3 --xmx 2g"`). 결과는 `lib/build/reports/benchmark/parse-benchmark.md`에 저장됩니다.

**원인 확인 (개선 전 구간별 시간)**

| 파일 수 | collect | parse | graph | analyze | 전체 | parse 비중 |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 112 ms | 1.34 s | 111 ms | 16 ms | 1.58 s | 85% |
| 1,000 | 185 ms | 5.49 s | 85 ms | 15 ms | 5.77 s | 95% |
| 5,000 | 453 ms | 34.24 s | 355 ms | 25 ms | 35.14 s | 97% |

규모가 커질수록 전체 시간의 대부분이 Java 파싱이었습니다. 그래프 생성과 BFS는 병목이 아니었습니다.

**변경**

- `JavaSourceScanner`(신규): 파일당 `StaticJavaParser.parse()`를 **한 번만** 호출하고, 만든 AST(`CompilationUnit`)를 네 추출기가 공유합니다. AST는 파일 하나를 처리한 뒤 바로 참조를 놓으므로 메모리에 남는 AST는 최대 한 개입니다. 파싱 호출 수와 실패 파일 목록도 함께 돌려줍니다.
- 파서 4개에 `parse(CompilationUnit cu, String filePath)`를 추가했습니다. 기존 `parse(File)`, `parseSource(String)`는 그대로 남겨 호환성을 유지합니다.
- `SchemaGuardCLI`가 `JavaSourceScanner`를 사용합니다. Java 파일 수집 함수도 `JavaSourceScanner.collectJavaFiles`로 옮겼습니다.
- `--timing` 옵션(신규)을 주면 구간별 소요 시간과 파싱 호출 수를 출력합니다.

  ```
  Timing (ms): collect 76.2 | parse 2523.5 (1000 parse calls) | graph 128.1 | analyze 12.0
  ```

**결과 (3회 중앙값, 각 실행 별도 JVM, -Xmx2g, Java 17)**

| 파일 수 | 모드 | 파싱 호출 | parse | 전체 | 파싱 단계 할당량 | 분석 후 유지 힙 | 결과 일치 |
|---:|---|---:|---:|---:|---:|---:|---|
| 100 | 개선 전 | 400 | 1.34 s | 1.58 s | 373 MB | 3.4 MB | ✅ |
| 100 | 개선 후 | 100 | 0.73 s | 0.98 s | 113 MB | 3.4 MB | ✅ |
| 1,000 | 개선 전 | 4,000 | 5.49 s | 5.77 s | 3,442 MB | 8.1 MB | ✅ |
| 1,000 | 개선 후 | 1,000 | 2.57 s | 2.92 s | 925 MB | 8.1 MB | ✅ |
| 5,000 | 개선 전 | 20,000 | 34.24 s | 35.14 s | 17,053 MB | 30.4 MB | ✅ |
| 5,000 | 개선 후 | 5,000 | 10.60 s | 11.46 s | 4,455 MB | 30.5 MB | ✅ |

| 파일 수 | 파싱 호출 | parse 구간 | 전체 | 파싱 단계 할당량 |
|---:|---:|---:|---:|---:|
| 100 | 4배 감소 | 1.8배 빠름 | 1.6배 빠름 | 3.3배 감소 |
| 1,000 | 4배 감소 | 2.1배 빠름 | 2.0배 빠름 | 3.7배 감소 |
| 5,000 | 4배 감소 | 3.2배 빠름 | 3.1배 빠름 | 3.8배 감소 |

**해석**

- 파싱 호출이 4배 줄었다고 전체 시간이 4배 줄지는 않습니다. 파일 읽기, 추출, 그래프 생성 같은 고정 비용이 남아 있고, 규모가 작을수록 JVM 시작과 JIT 워밍업 비중이 큽니다. 규모가 커질수록 개선 폭이 4배에 가까워집니다.
- 파싱 단계 할당량이 약 3.8배 줄어 GC 부담이 함께 줄었습니다.
- 분석 후 유지 힙은 두 방식이 같습니다. 개선 전에도 AST는 파일마다 버려지고 있었고, 개선 후에도 AST가 쌓이지 않는다는 뜻입니다. 최대 힙(샘플링)은 두 방식 모두 약 160MB로 큰 차이가 없습니다. JVM이 GC를 돌리기 전에 기본 힙 크기까지 채우기 때문입니다.
- 같은 조건에서도 실행마다 ±20% 정도 편차가 있었습니다(예: 1,000개 legacy 5.2~5.8 s). 그래서 3회 중앙값을 사용했습니다.

**기존 결과와의 일치 확인**

- 벤치마크: 세 규모 모두 legacy와 shared의 결과 지문(SHA-256)이 일치했습니다.
- 실제 CLI: 이전 커밋으로 빌드한 CLI와 새 CLI를 1,000개, 5,000개 샘플에 실행했습니다. JSON 리포트가 **바이트 단위로 동일**했고(영향 API 36개), 종료 코드도 같았습니다(1).
- `JavaSourceScannerTest`(신규): 파싱 호출 수 = 파일 수, 파서별 개별 파싱 결과와 추출 개수 일치, 문법 오류 파일은 실패 목록에 들어가고 나머지는 정상 분석되는지 확인합니다.

**남은 병목 (후속 후보)**

개선 후에도 5,000개 기준 전체 시간의 92%가 parse 구간입니다. 다음 후보는 이렇습니다.

- 파일 단위 병렬 파싱. 현재 `StaticJavaParser`는 전역 설정을 공유하므로, 스레드별 `JavaParser` 인스턴스로 바꿔야 합니다.
- 관련 어노테이션(`@Entity`, `@Service` 등)이나 `Repository` 상속이 없는 파일은 파싱 전에 건너뛰기

**보충: community 코드 기반 규모 확장으로 재측정**

합성 샘플 말고 실제 코드로도 확인하려고 `community`(Java 63개, Lombok·JPA·Spring Security 포함)를 모듈 단위로 복제해 키웠습니다. 모듈마다 패키지(`m{i}`), 클래스명(`...M{i}`), 테이블명(`..._m{i}`), URL(`/m{i}/...`)을 바꿔서 서로 독립된 프로젝트처럼 연결됩니다. 마이그레이션은 알림 컬럼명 변경, 태그 컬럼 삭제, 댓글 테이블 삭제 등입니다. 변환 스크립트는 저장소에 넣지 않았습니다. 실제 5,000개 파일짜리 프로젝트가 아니라 **63개 파일 구조를 반복한 것**이라는 점은 감안해야 합니다.

벤치마크 워커로 3회씩 번갈아 실행한 중앙값입니다(개선 전 스캔 방식 vs 개선 후, 별도 JVM).

| 파일 수 | 모드 | 파싱 호출 | parse | 전체 | 파싱 단계 할당량 | 분석 후 유지 힙 |
|---:|---|---:|---:|---:|---:|---:|
| 1,008 | 개선 전 | 4,032 | 3.47 s | 3.88 s | 1,530 MB | 8.1 MB |
| 1,008 | 개선 후 | 1,008 | 1.53 s | 2.02 s | 436 MB | 8.1 MB |
| 4,977 | 개선 전 | 19,908 | 9.09 s | 10.36 s | 7,515 MB | 28.9 MB |
| 4,977 | 개선 후 | 4,977 | 4.22 s | 5.52 s | 2,060 MB | 28.9 MB |

- 전체 시간 1.9배(1,008개), 1.9배(4,977개), parse 구간 2.3배 / 2.2배, 파싱 단계 할당량 3.5배 / 3.7배 감소입니다.
- 두 방식의 결과 지문(영향 API 116개, 경로, 그래프 노드·엣지)이 같습니다.
- 실제 CLI(JVM 시작 포함)도 개선 전/후 커밋으로 각각 3회 실행했습니다. 1,008개는 4.83 s → 2.77 s, 4,977개는 11.12 s → 7.02 s(중앙값)이고, 두 버전의 JSON 리포트는 바이트 단위로 같았습니다. 4,977개 개선 전 3회 중 한 번이 18.0 s로 튀었고 개선 후도 6.0~7.3 s로 흔들렸습니다. 같은 PC에서 다른 작업이 함께 돌던 영향일 수 있어 정밀한 배수로 보기는 어렵습니다.

합성 샘플(4,977개 기준 3.1배)보다 개선 폭이 작습니다. 이 프로젝트는 파일이 작고 DTO처럼 추출할 게 적은 파일이 많아서 파일당 고정 비용(파일 수집 약 1초, JIT 워밍업)의 비중이 크기 때문으로 추정하지만, 원인을 따로 검증하지는 않았습니다. 파싱 호출이 4배 줄어드는 것은 구조상 항상 같고, 전체 시간 개선 폭은 코드 모양에 따라 1.5~3배 정도로 달라진다고 보는 게 안전합니다.

**관련 파일**: `JavaSourceScanner.java`(신규), `EntityParser.java`, `RepositoryParser.java`, `ServiceParser.java`, `ControllerParser.java`, `SchemaGuardCLI.java`, `lib/build.gradle`(`benchmark` 태스크), `bench/SampleProjectGenerator.java`·`bench/ParseBenchmark.java`(신규, 테스트 소스), `JavaSourceScannerTest.java`(신규)

---

## 5. GitHub Actions 워크플로 수정본 추가

`readme.md`의 워크플로 예시는 수정하지 않았습니다. 고친 버전은 **`examples/github-actions/schemaguard.yml`** 에 있습니다. 검사 대상 프로젝트의 `.github/workflows/`에 이 파일을 복사하면 됩니다.

**기존 워크플로의 문제**

| # | 문제 | 결과 |
|---|---|---|
| 1 | Run 단계에서 SchemaGuard가 0이 아닌 코드로 끝나면 `bash -e` 때문에 단계가 즉시 실패 | 첫 HIGH 파일 이후 나머지 SQL은 분석되지 않고, HIGH 체크/Step Summary 단계는 건너뜀 |
| 2 | `--output json` 없이 `--report-path`만 넘김 | JSON이 생성되지 않음 (3번 변경으로 해결) |
| 3 | `git diff`에 **삭제된** SQL 파일도 포함 | 없는 파일을 분석하다 실행 오류 |
| 4 | 파일명을 `${{ steps...sql_files }}`로 `run:` 스크립트에 직접 삽입 | PR 작성자가 파일명으로 셸 명령어를 주입할 수 있음 (예: `V4__$(curl ...).sql`) |
| 5 | `./gradlew run`으로 실행 | 종료 코드가 모두 1로 바뀌어 HIGH/오류/파싱 실패를 구분할 수 없음 |
| 6 | 판정에 `hasHighRisk`만 사용 | SQL 파싱 실패가 있어도 통과 |

**수정본의 동작**

1. `git diff --diff-filter=d`로 추가/수정된 마이그레이션 SQL만 고릅니다. 목록은 파일로 넘기고 스크립트에 삽입하지 않습니다.
2. SchemaGuard를 `installDist`로 한 번 빌드한 뒤 설치본을 직접 실행합니다. 종료 코드 0/1/2/3을 그대로 받습니다.
3. Run 단계는 판정하지 않고 모든 SQL 파일을 끝까지 분석해 `results.tsv`(파일, 종료 코드, 리포트 경로)에 기록합니다.
4. Check 단계에서 파일별로 판정합니다.
   - 리포트가 없거나 종료 코드가 0/1/3이 아니면 **실행 오류 → 차단**
   - `summary.blocked`(HIGH 또는 파싱 실패)가 true이거나 종료 코드가 0이 아니면 **차단**
5. Step Summary에 파일별 위험도, 파싱 실패 구문(줄 번호), 영향 API, 영향 경로를 표시합니다.
6. 리포트는 성공/실패와 관계없이 Artifact로 올립니다.
7. 그 밖에 `permissions: contents: read`로 최소 권한을 지정했고, Gradle 캐시를 `gradle/actions/setup-gradle`로 바꿨습니다. SchemaGuard 버전은 `SCHEMAGUARD_REF`로 고정할 수 있습니다.

**검증 (로컬 시뮬레이션)**

워크플로 YAML에서 각 단계의 스크립트를 추출해 GitHub Actions 기본 셸(`bash -eo pipefail`)로 실행했습니다. 가짜 PR 저장소에는 아래 변경을 넣었습니다.

| PR 변경 | 결과 |
|---|---|
| `V1__init.sql` 삭제 | 분석 대상에서 제외 |
| `V2__drop_email.sql` (DROP COLUMN) | 종료 코드 1, HIGH, `GET /me` 영향 경로 표시 → 차단 |
| `V3__bad.sql` (2개 중 1개 파싱 불가) | 종료 코드 3, "분석 불완전", 실패 구문 줄 2 표시 → 차단 |
| `V4__$(touch PWNED).sql` (주입 시도 파일명) | 명령어 실행되지 않음(PWNED 파일 없음), 종료 코드 0 → 통과 |
| 리포트 없음 + 종료 코드 2 | 실행 오류로 차단 |
| 안전한 파일만 있는 경우 | 병합 가능, Check 단계 종료 코드 0 |

> GitHub에서 직접 돌려보지는 않았습니다. `actions/checkout`, `setup-java`, `setup-gradle`, `upload-artifact` 단계는 실제 PR에서 한 번 확인이 필요합니다.

**관련 파일**: `examples/github-actions/schemaguard.yml`(신규)

---

## 4. 테이블 이름 변경, `ALTER COLUMN ... SET NOT NULL` / `TYPE` 탐지

**문제**

`readme.md`의 위험도 표에는 테이블명 변경이 HIGH로 적혀 있습니다. 그런데 실제로는 다음 구문들이 파싱은 되면서 **변경으로 탐지되지 않고 조용히 무시**되었습니다.

| 구문 | 기대 | 수정 전 |
|---|---|---|
| `ALTER TABLE users RENAME TO members` | RENAME_TABLE (HIGH) | 무시 |
| `RENAME TABLE users TO members` (MySQL) | RENAME_TABLE (HIGH) | 무시 |
| `ALTER TABLE users ALTER COLUMN email SET NOT NULL` | ADD_NOT_NULL (MEDIUM) | 무시 |
| `ALTER TABLE users ALTER COLUMN age TYPE BIGINT` (PostgreSQL) | MODIFY_TYPE (HIGH) | 무시 |

`RENAME_TABLE`은 `ChangeType`과 콘솔 수정 가이드에 이미 있었습니다. 하지만 파서가 이 변경을 만들지 않았고 `ImpactAnalyzer`도 시작 노드를 찾지 않아서 실제로는 쓰이지 않았습니다.

**변경**

- `SqlMigrationParser`
  - `ALTER TABLE ... RENAME TO ...`와 `RENAME TABLE a TO b[, c TO d]`를 `RENAME_TABLE`로 탐지합니다. 새 테이블명은 `newName`에 담깁니다.
  - `ALTER [COLUMN] col SET NOT NULL`은 `ADD_NOT_NULL`, `ALTER [COLUMN] col TYPE t` / `SET DATA TYPE t`는 `MODIFY_TYPE`으로 탐지합니다.
  - `DROP NOT NULL`, `SET/DROP DEFAULT`는 기존 코드를 깨지 않으므로 계속 무시합니다.
- `ImpactAnalyzer`: `RENAME_TABLE`은 `DROP_TABLE`과 같은 범위(테이블의 모든 컬럼과 FK 필드)에서 탐색을 시작합니다.

**결과**

- 샘플 프로젝트에서 `ALTER TABLE users RENAME TO members;` → `GET /me` HIGH, 종료 코드 1
- `ALTER COLUMN email SET NOT NULL` → `GET /me` MEDIUM, 종료 코드 0 (MEDIUM은 차단하지 않음)

**관련 파일**: `SqlMigrationParser.java`, `ImpactAnalyzer.java`, `SqlMigrationParserTest.java`, `SchemaGuardCLITest.java`

---

## 3. JSON 리포트: 파싱 실패 포함, `--report-path`만 줘도 생성, 항상 생성

**문제**

- JSON 리포트는 `--output json`일 때만 만들어졌습니다. 그런데 `readme.md`의 실행 예시와 GitHub Actions 예시는 `--report-path`만 넘기고 있어서 **실제로는 JSON 파일이 생성되지 않았습니다**.
- 마이그레이션에서 변경을 하나도 찾지 못하면 리포트를 쓰기 전에 종료해서 JSON이 없었습니다.
- 1번에서 추가한 SQL 파싱 실패 정보는 콘솔에만 찍히고 JSON에는 없었습니다. JSON만 보면 "HIGH 0건, 깨끗함"으로 보였습니다.
- JSON 쓰기에 실패하면 에러 한 줄만 찍고 정상 종료 코드를 냈습니다.

**변경**

- 출력 규칙:

  | 옵션 | 콘솔 리포트 | JSON 리포트 |
  |---|---|---|
  | (기본) | O | X |
  | `--report-path <경로>` | O | O (지정 경로) |
  | `--output json` | X | O (`--report-path` 또는 `./schemaguard-report.json`) |

  `readme.md`에 적힌 명령이 문서대로 JSON까지 만들게 됩니다.
- 변경 사항이 0건이어도 리포트를 항상 생성합니다.
- 리포트 경로의 상위 디렉터리가 없으면 만듭니다.
- JSON 쓰기에 실패하면 종료 코드 2로 끝냅니다. 리포트 없이 통과하지 않게 하려는 것입니다.
- JSON 스키마 추가 필드:

  ```jsonc
  {
    "summary": {
      "high": 1, "medium": 0, "low": 0,
      "hasHighRisk": true,
      "parseFailures": 0,          // 신규: 파싱 실패 구문 수
      "analysisComplete": true,    // 신규: 파싱 실패가 없으면 true
      "blocked": true              // 신규: hasHighRisk || 파싱 실패 → CI 차단 여부
    },
    "parse": {                     // 신규
      "totalStatements": 2,
      "failedStatements": 0,
      "failures": [ { "line": 2, "statement": "...", "reason": "..." } ],
      "detectedChanges": [ "[HIGH] DROP_COLUMN on users.email" ],
      "sourceParseFailures": []    // JavaParser가 읽지 못한 .java 파일 (경고만, 차단하지 않음)
    },
    "affectedApis": [ ... ]        // 기존과 동일
  }
  ```

**알려진 한계**

- Java 소스 파일 파싱 실패(`sourceParseFailures`)는 기존처럼 경고로만 남기고 차단하지 않습니다. 해당 파일이 관여하는 영향 경로가 누락될 수 있습니다.

**관련 파일**: `SchemaGuardCLI.java`, `JsonReporter.java`, `SchemaGuardCLITest.java`(신규, 샘플 프로젝트로 종료 코드와 JSON 검증)

---

## 2. Windows에서 한글 리포트가 깨지던 문제 수정

**문제**

원인이 두 군데 있었습니다.

1. `build.gradle`에 컴파일 인코딩 설정이 없어서 Windows에서는 소스(UTF-8)를 MS949로 읽어 컴파일했습니다. 경고(`unmappable character`)가 약 100건 나왔고, 한글 문자열 리터럴이 바이트 단위로 깨졌습니다.
2. `run` 태스크의 프로그램은 UTF-8로 출력하는데, Gradle 데몬(MS949)이 그 출력을 MS949로 읽어 다시 쓰면서 한 번 더 깨졌습니다. 결과적으로 UTF-8로도 CP949로도 읽을 수 없는 바이트가 나왔습니다.

**변경**

- `lib/build.gradle`: `JavaCompile` 태스크에 `options.encoding = 'UTF-8'`을 지정했습니다.
- `gradle.properties`(신규): `org.gradle.jvmargs=-Dfile.encoding=UTF-8`로 Gradle 데몬도 UTF-8로 실행합니다.

**결과**

- 컴파일 인코딩 경고: 100건 → 0건
- `./gradlew run` 출력은 이제 터미널 기본 인코딩(한국어 Windows는 CP949)과 일치해서 기본 cmd/PowerShell에서 정상적으로 보입니다.
- 터미널이 UTF-8(코드 페이지 65001, Git Bash, Windows Terminal 일부 설정)이면 Gradle 클라이언트 쪽 인코딩도 맞춰야 합니다. 이 설정은 `gradle.properties`로는 지정할 수 없습니다.

  ```powershell
  $env:GRADLE_OPTS="-Dfile.encoding=UTF-8"
  ./gradlew run --args="..."
  ```

  ```bash
  GRADLE_OPTS=-Dfile.encoding=UTF-8 ./gradlew run --args="..."
  ```

  기존 방법인 `SCHEMAGUARD_LANG=en`(영어 리포트)도 그대로 쓸 수 있습니다. Linux CI(GitHub Actions)는 원래 UTF-8이라 영향이 없습니다.

**관련 파일**: `lib/build.gradle`, `gradle.properties`(신규)

---

## 1. SQL 파싱 실패 시 "변경 없음"으로 통과하던 문제 수정

**문제**

- `SqlMigrationParser`가 SQL 파일 전체를 `parseStatements` 한 번으로 파싱했습니다. 구문 하나만 실패해도 경고 한 줄을 찍고 빈 목록을 돌려줬습니다.
- CLI는 이를 "No schema changes detected"로 받아들여 **종료 코드 0**을 냈고, 위험한 변경이 있어도 PR이 통과했습니다.
- 재현: 아래 파일을 넣으면 HIGH 3건이 모두 사라지고 종료 코드 0이 나왔습니다.

  ```sql
  ALTER TABLE users DROP COLUMN email;
  ALTER TABLE `posts` DROP FOREIGN KEY fk_post_author;   -- 백틱 때문에 FK 정규식을 빠져나가 파서에서 실패
  ALTER TABLE users DROP COLUMN nickname;
  ```

**변경**

- SQL을 `;` 기준 **구문 단위로 나눠 하나씩 파싱**합니다. 따옴표 안의 `;`와 주석(`--`, `#`, `/* */`)은 구분자로 보지 않습니다.
- 실패한 구문은 `ParseResult.failures()`에 줄 번호, SQL, 사유와 함께 모읍니다. 나머지 구문의 탐지 결과는 그대로 유지합니다.
- CLI는 실패 구문이 하나라도 있으면 리포트를 출력한 뒤 `[BLOCKED]`와 함께 **종료 코드 3**을 냅니다. 탐지된 변경이 0건이어도 마찬가지입니다. 검사 도구가 읽지 못한 변경은 "변경 없음"이 아니라 "알 수 없음"이기 때문입니다.
- 같이 고친 조용한 누락 경로:
  - FK 정규식이 백틱/따옴표 식별자와 `db.table` 접두어를 받도록 수정했습니다.
  - `ADD FOREIGN KEY ... ON DELETE CASCADE`처럼 뒤에 절이 붙으면 남은 `ON DELETE CASCADE;`가 파싱에 실패하던 문제를 고쳤습니다.
  - JSQLParser가 돌려준 이름의 백틱을 제거합니다. 전에는 `` `users` ``가 그래프의 `users` 노드와 매칭되지 않아 영향 API가 0건이 되었습니다.
  - JSQLParser가 모르는 ALTER 절을 예외 없이 `UNSPECIFIC`으로 돌려주던 경우를 파싱 실패로 처리합니다.
- `--help`에 종료 코드 목록을 표시합니다.

**결과**

| 입력 | 수정 전 | 수정 후 |
|---|---|---|
| 구문 10개 중 1개 파싱 불가 | 탐지 0건, 종료 코드 0 | 9건 탐지 + 실패 1건 경고(줄 번호 포함), 종료 코드 3 |
| 위 재현 SQL | 탐지 0건, 종료 코드 0 | 3건 탐지 |

**알려진 한계**

- JSQLParser가 읽지 못하는 정상 구문(프로시저용 `DELIMITER` 블록 등)도 차단됩니다. 의도한 fail-closed 동작이므로 실제 마이그레이션 파일로 한 번 확인하는 것을 권장합니다.

**관련 파일**: `SqlMigrationParser.java`, `SchemaGuardCLI.java`, `SqlMigrationParserTest.java`(신규)
