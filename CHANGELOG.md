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

- `SourceScanner`(신규): 파일당 `StaticJavaParser.parse()`를 **한 번만** 호출하고, 만든 AST(`CompilationUnit`)를 네 추출기가 공유합니다. AST는 파일 하나를 처리한 뒤 바로 참조를 놓으므로 메모리에 남는 AST는 최대 한 개입니다. 파싱 호출 수와 실패 파일 목록도 함께 돌려줍니다.
- 파서 4개에 `parse(CompilationUnit cu, String filePath)`를 추가했습니다. 기존 `parse(File)`, `parseSource(String)`는 그대로 남겨 호환성을 유지합니다.
- `SchemaGuardCLI`가 `SourceScanner`를 사용합니다. Java 파일 수집 함수도 `SourceScanner.collectJavaFiles`로 옮겼습니다.
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
- `SourceScannerTest`(신규): 파싱 호출 수 = 파일 수, 파서별 개별 파싱 결과와 추출 개수 일치, 문법 오류 파일은 실패 목록에 들어가고 나머지는 정상 분석되는지 확인합니다.

**남은 병목 (후속 후보)**

개선 후에도 5,000개 기준 전체 시간의 92%가 parse 구간입니다. 다음 후보는 이렇습니다.

- 파일 단위 병렬 파싱. 현재 `StaticJavaParser`는 전역 설정을 공유하므로, 스레드별 `JavaParser` 인스턴스로 바꿔야 합니다.
- 관련 어노테이션(`@Entity`, `@Service` 등)이나 `Repository` 상속이 없는 파일은 파싱 전에 건너뛰기

**관련 파일**: `SourceScanner.java`(신규), `EntityParser.java`, `RepositoryParser.java`, `ServiceParser.java`, `ControllerParser.java`, `SchemaGuardCLI.java`, `lib/build.gradle`(`benchmark` 태스크), `bench/SampleProjectGenerator.java`·`bench/ParseBenchmark.java`(신규, 테스트 소스), `SourceScannerTest.java`(신규)

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

- `MigrationParser`
  - `ALTER TABLE ... RENAME TO ...`와 `RENAME TABLE a TO b[, c TO d]`를 `RENAME_TABLE`로 탐지합니다. 새 테이블명은 `newName`에 담깁니다.
  - `ALTER [COLUMN] col SET NOT NULL`은 `ADD_NOT_NULL`, `ALTER [COLUMN] col TYPE t` / `SET DATA TYPE t`는 `MODIFY_TYPE`으로 탐지합니다.
  - `DROP NOT NULL`, `SET/DROP DEFAULT`는 기존 코드를 깨지 않으므로 계속 무시합니다.
- `ImpactAnalyzer`: `RENAME_TABLE`은 `DROP_TABLE`과 같은 범위(테이블의 모든 컬럼과 FK 필드)에서 탐색을 시작합니다.

**결과**

- 샘플 프로젝트에서 `ALTER TABLE users RENAME TO members;` → `GET /me` HIGH, 종료 코드 1
- `ALTER COLUMN email SET NOT NULL` → `GET /me` MEDIUM, 종료 코드 0 (MEDIUM은 차단하지 않음)

**관련 파일**: `MigrationParser.java`, `ImpactAnalyzer.java`, `MigrationParserTest.java`, `SchemaGuardCLITest.java`

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

- `MigrationParser`가 SQL 파일 전체를 `parseStatements` 한 번으로 파싱했습니다. 구문 하나만 실패해도 경고 한 줄을 찍고 빈 목록을 돌려줬습니다.
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

**관련 파일**: `MigrationParser.java`, `SchemaGuardCLI.java`, `MigrationParserTest.java`(신규)
