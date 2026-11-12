# 🛡️ SchemaGuard

> **Static Analysis Tool for Detecting Potential Error Points Caused by Database Schema Changes in Spring Boot-Based Backends**
>
> Spring Boot + JPA 기반 백엔드에서 DB 스키마 변경으로 인해 발생할 수 있는 런타임 오류 지점을 **배포 전에 사전 탐지**하는 정적 분석 CLI 도구

---

## 📌 프로젝트 소개

SchemaGuard는 SQL 마이그레이션 파일과 Java 소스 코드를 함께 분석하여, DB 스키마 변경으로 인해 영향을 받을 수 있는 API 엔드포인트를 사전에 찾아주는 도구입니다.

예를 들어 `users.email` 컬럼을 삭제했을 때,  
해당 컬럼과 연결된 Entity, Repository, Service, Controller, API까지 추적하여 어떤 API가 런타임 오류를 일으킬 수 있는지 리포트로 출력합니다.

---

## 🎯 프로젝트 목표

- DB 스키마 변경(컬럼 삭제, 컬럼명 변경, 타입 변경 등)으로 인한 런타임 오류 **사전 탐지**
- Entity → Repository → Service → Controller → API 엔드포인트까지의 **의존성 그래프 자동 구성**
- 변경된 스키마가 영향을 미치는 API를 **역방향 그래프 탐색(BFS)** 으로 식별
- 위험도(HIGH / MEDIUM / LOW) 기반 **경고 리포트** 생성
- **GitHub Actions CI/CD** 파이프라인 연동을 통한 PR 단계 자동 차단


---

## 🚀 실행 방법

SchemaGuard는 검사하고 싶은 Spring Boot 프로젝트와 같은 상위 폴더에 두고 사용하는 것을 권장합니다.

```bash
git clone https://github.com/doyoung0013/schemaguard
```

예시 폴더 구조는 다음과 같습니다.

```text
folder/
├── community/        ← 검사 대상 Spring Boot 프로젝트
│   └── src/
│       └── main/
│           ├── java/
│           └── resources/
│               └── db/
│                   └── migration/
│                       └── V2__drop_email.sql
│
└── schemaguard/      ← SchemaGuard 프로젝트
```

---

## 1. 검사 대상 프로젝트 준비

검사하고 싶은 Spring Boot 프로젝트에 마이그레이션 SQL 파일을 생성합니다.

생성 위치:

```text
src/main/resources/db/migration/
```

예시 SQL:

```sql
ALTER TABLE users DROP COLUMN email;
```

---

## 2. 실행 방법

SchemaGuard는 두 가지 방식으로 실행할 수 있습니다.

---

### 방법 1) IDE Run Configuration으로 실행

Eclipse 또는 IntelliJ에서 `schemaguard` 프로젝트의 `SchemaGuardMain` 클래스를 실행합니다.

실행 인자로 다음 옵션을 입력합니다.

```bash
--source 검사 대상 Java 소스 코드 경로 --migration 검사할 SQL 마이그레이션 파일 경로 --report-path JSON 리포트를 저장할 경로
```

실행 예시:
```bash
--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json
```

---

### 방법 2) CLI로 실행

기본 형식:

```bash
./gradlew run --args="--source 검사대상 Java경로 --migration 검사대상 MigrationSQL경로 --report-path 리포트저장경로"
```

실행 예시:

```bash
./gradlew run --args="--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json"
```

---

### 주요 옵션

| 옵션 | 설명 |
|---|---|
| `--source`, `-s` | 검사 대상 Java 소스 루트 (필수) |
| `--migration`, `-m` | 검사할 SQL 파일 또는 폴더 (필수). 여러 개를 줄 수 있고, 폴더는 안의 `.sql`을 파일 이름의 V 번호 순서로 분석. Java 소스는 한 번만 파싱 |
| `--report-path`, `-r` | JSON 리포트 경로 (마이그레이션이 하나일 때) |
| `--report-dir` | JSON 리포트를 `<폴더>/<파일명>.json`으로 저장하고 파일별 종료 코드를 `results.tsv`에 기록 |
| `--output`, `-o` | `console`(기본) / `json` |
| `--history` | 이전 마이그레이션 폴더. 검사 파일보다 V 번호가 작은 파일을 읽어 `DROP INDEX`의 대상(컬럼, UNIQUE 여부)을 찾음 |
| `--[no-]lock-scope` | 인덱스 삭제가 비관적 락 조회의 잠금 범위를 넓히는지 검사 (기본: 켬) |
| `--threads` | Java 파싱 스레드 수 (기본: 코어 수의 절반, 최대 4) |
| `--timing` | 구간별 소요 시간 출력 |

종료 코드: `0` 통과 / `1` HIGH 위험 / `2` 실행 오류 / `3` 읽지 못한 SQL (분석 불완전). 여러 파일이면 가장 심각한 코드(2 > 3 > 1 > 0).

여러 마이그레이션을 한 번에 분석하는 예시:

```bash
./gradlew installDist
lib/build/install/lib/bin/lib --source ../community/src/main/java --migration ../community/src/main/resources/db/migration --history ../community/src/main/resources/db/migration --report-dir ../community/build/reports/schemaguard
```

> `./gradlew run`은 0이 아닌 종료 코드를 모두 1로 바꿉니다. 종료 코드를 구분해야 하면 `installDist`로 만든 실행 파일을 직접 실행하세요.

---

## 🌐 영어 리포트로 실행하기

일부 터미널에서 UTF-8 또는 한글 출력이 깨질 수 있습니다.

이 경우 `SCHEMAGUARD_LANG=en` 옵션을 앞에 붙이면 영어 리포트로 출력할 수 있습니다.

실행 예시:

```bash
SCHEMAGUARD_LANG=en ./gradlew run --args="--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json"
```

Windows PowerShell에서는 다음과 같이 실행할 수 있습니다.

```powershell
$env:SCHEMAGUARD_LANG="en"
./gradlew run --args="--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json"
```

---

## ⚙️ GitHub Actions 연동 방법

SchemaGuard는 **GitHub Action**으로 제공됩니다. 검사 대상 Spring Boot 프로젝트에 아래 workflow 파일 하나만 추가하면, PR에서 추가·수정된 마이그레이션 SQL을 자동으로 검사하고 위험한 변경이면 병합을 막습니다.

SchemaGuard를 직접 clone하거나 빌드할 필요는 없습니다. 실행 절차(JDK 준비, 빌드, 변경 파일 찾기, 분석, 판정, 리포트)는 SchemaGuard 저장소의 `action.yml`이 관리합니다.

---

### 1) Workflow 파일 추가

검사 대상 프로젝트에 `.github/workflows/schemaguard.yml`을 만듭니다.

```text
community/
├── src/main/java/
├── src/main/resources/db/migration/
└── .github/workflows/schemaguard.yml
```

```yaml
name: SchemaGuard

on:
  pull_request:
    paths:
      - 'src/main/resources/db/migration/**'

permissions:
  contents: read

jobs:
  schema-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: doyoung0013/schemaguard@v1
        with:
          source: src/main/java
          migration-dir: src/main/resources/db/migration
```

- `actions/checkout`의 `fetch-depth`는 바꾸지 않아도 됩니다. 비교 기준(PR의 base 커밋)은 Action이 직접 가져옵니다.
- `@v1`은 SchemaGuard 저장소에 `v1` 태그가 게시된 뒤에 쓸 수 있습니다. 그 전에는 브랜치 이름이나 커밋 SHA로 지정합니다(예: `doyoung0013/schemaguard@main`).
- 같은 내용의 예시 파일: [`examples/github-actions/schemaguard-action.yml`](examples/github-actions/schemaguard-action.yml)

---

### 2) 입력값

| 입력 | 기본값 | 설명 |
|---|---|---|
| `source` | `src/main/java` | Java 소스 루트 |
| `migration-dir` | `src/main/resources/db/migration` | 마이그레이션 폴더. PR에서 이 폴더의 추가·수정된 `.sql`을 검사 (삭제된 파일 제외) |
| `files` | (없음) | 검사할 SQL을 직접 지정 (한 줄에 하나). 주면 PR 변경 파일을 찾지 않음. `push`, `workflow_dispatch`에서 사용 |
| `base` | PR의 base 커밋 | 비교 기준 커밋 SHA |
| `history` | `true` | 이전 마이그레이션(파일 이름의 V 번호가 더 작은 것)을 읽어 `DROP INDEX`의 대상을 찾음. 파일 이름이 `V<번호>__<설명>.sql` 규칙이 아니면 `false` |
| `lock-scope` | `true` | 인덱스 삭제가 비관적 락 조회의 잠금 범위를 넓히는지 검사 (MEDIUM 경고) |
| `fail-on` | `high` | `high`: HIGH 위험·읽지 못한 SQL·실행 오류면 실패 / `parse-failure`: 읽지 못한 SQL·실행 오류만 / `none`: 실패하지 않고 결과만 남김 |
| `language` | `ko` | SchemaGuard 콘솔 출력 언어 (`ko` / `en`). Step Summary는 한국어 |
| `upload-artifact` | `true` | JSON 리포트를 Artifact로 업로드 |
| `artifact-name` | `schemaguard-reports` | Artifact 이름 (같은 job에서 여러 번 쓰면 다르게) |

**출력값:** `blocked`(`'true'`/`'false'`), `high-count`, `medium-count`, `analyzed`(검사한 파일 수), `report-dir`

```yaml
      - id: schemaguard
        uses: doyoung0013/schemaguard@v1
        with:
          fail-on: none
      - if: steps.schemaguard.outputs.blocked == 'true'
        run: echo "위험한 스키마 변경이 있습니다"
```

---

### 3) 동작

```text
PR 생성 / push
→ PR에서 추가·수정된 마이그레이션 SQL 찾기 (base 커밋과 비교)
→ SchemaGuard 빌드 (JDK 17은 러너에 설치된 것을 사용, Gradle 캐시)
→ 바뀐 SQL 전체를 한 번에 분석 (Java 소스는 한 번만 파싱)
→ 파일별 판정, Step Summary 작성, JSON 리포트 Artifact 업로드
→ fail-on 조건에 해당하면 실패 (병합 차단)
```

- 바뀐 SQL이 없으면 빌드와 분석을 건너뛰고 통과합니다.
- 한 파일이 실패해도 나머지 파일을 끝까지 분석합니다.
- 파일명은 스크립트에 끼워 넣지 않고 환경변수와 배열로 넘깁니다. PR 작성자가 파일명으로 셸 명령을 주입할 수 없습니다.
- `--source` 경로가 없거나 그 아래에 Java 파일이 없으면 실행 오류로 실패합니다. 경로를 잘못 줘서 모든 PR이 검사 없이 통과하는 일을 막기 위해서입니다.

---

### 4) 판정 기준

| 결과 | 판정 (`fail-on: high`) |
|---|---|
| 🔴 HIGH 위험 변경이 API에 영향 | ❌ 실패 (병합 차단) |
| ⚠️ 읽지 못한 SQL 구문이 있음 (분석 불완전) | ❌ 실패 |
| 실행 오류 (리포트 없음, 경로 오류 등) | ❌ 실패 |
| 🟡 MEDIUM만 있음 (NOT NULL 추가, 비관적 락 잠금 범위 확대 등) | ✅ 통과 (Step Summary에 경고 표시) |
| 🟢 LOW / 영향 없음 | ✅ 통과 |

예를 들어 다음 SQL이 PR에 포함되면,

```sql
ALTER TABLE users DROP COLUMN email;
```

SchemaGuard는 `users.email`과 연결된 Entity → Repository → Service → Controller → API 경로를 추적하고, HIGH 위험이 있으면 workflow를 실패시킵니다. 개발자는 병합 전에 영향을 받는 코드를 수정할 수 있습니다.

---

### 5) 분석 리포트 확인

- **Step Summary:** workflow 실행 화면의 Summary 탭에 파일별 위험도, 파싱하지 못한 SQL 구문(줄 번호), 영향받는 API, 영향 경로, 잠금 범위 경고의 근거가 표시됩니다.
- **Artifact (`schemaguard-reports`):** 파일별 JSON 리포트(`<파일명>.json`)와 파일별 종료 코드(`results.tsv`)

JSON 리포트에는 다음 정보가 들어 있습니다.

```text
- 감지된 DB 스키마 변경 유형
- 변경 대상 테이블 및 컬럼
- 위험도
- 영향받는 Entity, Repository, Service, Controller
- 영향받는 API 엔드포인트와 파일:라인
- 판단 근거 (note)
```

---

### 6) Action 없이 직접 실행하기

실행 절차를 직접 고쳐야 하면 [`examples/github-actions/schemaguard.yml`](examples/github-actions/schemaguard.yml)을 검사 대상 프로젝트의 `.github/workflows/`에 복사해 쓸 수 있습니다. SchemaGuard를 clone해 빌드한 뒤 CLI를 실행하는 방식입니다.

---

## 📋 리포트 출력 예시

```
========================================
        SchemaGuard Report
========================================

📌 1. 요약
  - 영향 API 수 : 14
  - 전체 위험도 : HIGH

⚠️ 2. 영향 원인과 수정 가이드
  [1] [HIGH] DROP_COLUMN on users.email -> 영향 API 8
      수정 가이드:
        - 삭제된 컬럼과 매핑된 Entity 필드 확인
        - 해당 필드를 사용하는 Repository 메서드 확인
        - Service/DTO/Controller 참조 제거 또는 수정

      추천 수정:
        - 삭제된 컬럼 참조 제거 또는 대체: users.email
        - 매핑된 Entity 필드 제거 또는 수정: User.email

🔥 3. 핵심 영향 노드
  - User.email -> 8개 API에 영향
  - UserRepository.findByEmail -> 7개 API에 영향

🛠️ 4. 상세 경로
[1] GET /me
      Cause : [HIGH] DROP_COLUMN on users.email
      Risk  : HIGH

      상세 경로:
        -> 컬럼 : users.email
          -> Entity : User.email
             (com/example/user/entity/User.java:21)
            -> Repository : UserRepository.findByEmail
               (com/example/user/repository/UserRepository.java:10)
              -> Service : UserService.getByEmail
              -> Controller : UserController.myPage
              -> API : GET /me

========================================
  Total HIGH : 2
  Total MEDIUM: 0
  Total LOW   : 0
========================================
```

---

## 📁 schemaguard 프로젝트 폴더 구조

```
schemaguard/
├── action.yml                      ← GitHub Action 정의
├── action/                         ← Action 단계 스크립트 (변경 파일 찾기, 실행, 판정·요약) + 테스트 SQL
├── .github/workflows/
│   └── action-test.yml             ← Action 자체 테스트
├── examples/github-actions/        ← 검사 대상 프로젝트용 workflow 예시 (Action 사용 / 직접 실행)
├── scripts/
│   ├── accuracy/                   ← 실제 MySQL 정확도 측정 보조 스크립트
│   └── lock-experiment/            ← 비관적 락 + 인덱스 삭제 실측 (MySQL, 측정 대상 프로젝트)
├── settings.gradle
├── gradlew
├── gradle/wrapper/
│
└── lib/
    ├── build.gradle
    └── src/main/java/schemaguard/
        ├── SchemaGuardMain.java
        ├── cli/
        │   └── SchemaGuardCLI.java
        ├── model/                  ← SchemaChange, ChangeType, RiskLevel, Node, Edge, EntityMapping,
        │                             FkMapping, RepositoryInfo, ServiceInfo, ControllerInfo, IndexDef,
        │                             AffectedApi, ImpactResult
        ├── parser/
        │   ├── SqlMigrationParser.java   ← SQL 마이그레이션 (JSQLParser)
        │   ├── MigrationHistory.java     ← 이전 마이그레이션 찾기 (V 번호 순서)
        │   ├── IndexCatalog.java         ← 인덱스·제약 목록 (삭제 전후)
        │   ├── JavaSourceScanner.java    ← 파일당 한 번 파싱, 병렬 파싱
        │   ├── EntityParser.java
        │   ├── RepositoryParser.java     ← 쿼리 메서드, @Query, @Lock
        │   ├── ServiceParser.java
        │   ├── ControllerParser.java
        │   ├── AccessCollector.java      ← 연관 접근(getter 체인) 해석
        │   ├── DerivedQuery.java         ← 파생 쿼리 이름 → 조회 조건
        │   └── QueryConditions.java      ← @Query WHERE → 조회 조건
        ├── graph/
        │   └── DependencyGraphBuilder.java
        ├── analyzer/
        │   ├── ChangeClassifier.java     ← 엔티티 매핑과 비교해 위험도 재분류
        │   ├── ImpactAnalyzer.java       ← 역방향 탐색으로 영향 API 식별
        │   └── LockScopeAnalyzer.java    ← 인덱스 삭제의 비관적 락 잠금 범위 영향
        └── report/
            ├── ReportGenerator.java
            ├── ConsoleReporter.java
            └── JsonReporter.java
```

---

## ⚙️ 기술 스택

| 구분 | 기술 | 용도 |
|---|---|---|
| Language | Java 17 | - |
| Build | Gradle | 의존성 관리 및 빌드 |
| Java 파싱 | JavaParser + JavaSymbolSolver | AST 기반 소스 코드 분석 |
| SQL 파싱 | JSQLParser | DDL 구문 파싱 (ALTER, DROP 등) |
| 그래프 | JGraphT | 방향성 의존성 그래프 구성 및 탐색 |
| CLI | Picocli | 커맨드라인 옵션 처리 |
| 직렬화 | Jackson | JSON 리포트 출력 |
| 테스트 | JUnit 5 | 단위 테스트 |

---

## 🔍 분석 대상

분석 도구가 파싱하고 추적하는 대상은 다음과 같다.

**DB / SQL**
- SQL 마이그레이션 파일 (`ALTER TABLE`, `DROP COLUMN`, `MODIFY COLUMN` 등)

**Java 소스 코드**
- Entity 클래스 (`@Entity`, `@Table`, `@Column`)
- Repository 계층 (`JpaRepository` 상속, 쿼리 메서드, `@Query`, `@Lock` 비관적 락, 네이티브 `FOR UPDATE`)
- Service 계층 (메서드 호출 관계)
- Controller 계층 (`@GetMapping`, `@PostMapping` 등 엔드포인트 매핑)

**확장 예정**
- JPQL / Native Query 내 컬럼 참조
- DTO Projection
- QueryDSL

---

## 🚨 DB 스키마 변경 유형 및 위험도

| 변경 유형 | 위험도 | 설명 |
|---|---|---|
| 컬럼 삭제 | 🔴 HIGH | `@Column`, JPQL, Native Query, DTO 매핑 불일치로 런타임 오류 |
| 컬럼명 변경 | 🔴 HIGH | Entity, Query, Projection 불일치 |
| 타입 변경 | 🔴 HIGH | Java ↔ DB 타입 불일치로 변환/캐스팅 오류 |
| 테이블 삭제 | 🔴 HIGH | Repository 및 Query 실행 실패 |
| 테이블명 변경 | 🔴 HIGH | `@Table` 불일치 및 Query 오류 |
| FK 컬럼 삭제/변경 | 🔴 HIGH | `@JoinColumn`, `@ManyToOne` 등 연관관계 매핑 깨짐 |
| nullable → not null | 🟡 MEDIUM | INSERT/UPDATE 시 null 값 오류 |
| unique 제약 추가 | 🟡 MEDIUM | 중복 데이터 INSERT/UPDATE 시 제약 위반 |
| UNIQUE 제약·인덱스 삭제 | 🔴 HIGH | 오류 없이 중복 데이터가 저장됨 (중복 가입·중복 지급 등) |
| 일반 인덱스 삭제 | 🟢 LOW | 쿼리는 깨지지 않음 (조회 성능만 영향) |
| 비관적 락 조회가 쓰던 인덱스 삭제 | 🟡 MEDIUM | 대체 인덱스가 없으면 MySQL InnoDB가 훑은 행을 모두 잠가 동시 요청이 줄을 섬 (실측: 재고 차감 TPS 675 → 6.3). 차단하지 않고 경고 |
| 컬럼 추가 | 🟢 LOW | 기존 코드에 영향 없음 |

---

## 🔄 처리 흐름

```
SQL 마이그레이션 파일                Java 소스 코드
       │                                  │
       ▼                                  ▼
 SqlMigrationParser                   EntityParser
 (JSQLParser)                      RepositoryParser     ← JavaParser (AST)
       │                           ServiceParser
       │                           ControllerParser
       │                                  │
       └──────────────┬───────────────────┘
                      ▼
           DependencyGraphBuilder
           (방향성 그래프 구성, JGraphT)
                      │
                      ▼
              ImpactAnalyzer
          (BFS 역방향 탐색으로
           영향받는 API 식별)
                      │
                      ▼
             ReportGenerator
          (콘솔 출력 / JSON 출력)
```

---

## 📊 의존성 그래프 구조

```
[col:users.email]           ← DB 컬럼 (변경 시작점)
  ↑ maps
[field:User.email]          ← Entity 필드
  ↑ uses
[repo:UserRepository.findByEmail]   ← Repository 메서드
  ↑ calls
[svc:UserService.getUserByEmail]    ← Service 메서드
  ↑ calls
[ctrl:UserController.getUser]       ← Controller 메서드
  ↑ maps
[api:GET /users/{id}]               ← API 엔드포인트 (최종 탐지 대상)
```

---

## 📦 향후 확장

- **배포 속도**: Action에서 매번 빌드하지 않도록 릴리스에 빌드된 jar 게시
- **IDE 플러그인**: IntelliJ 플러그인으로 실시간 경고 표시
- **JPQL / Native Query 심층 분석**: 쿼리 내부 컬럼 참조 완전 추적
- **시각화**: 의존성 그래프 HTML 리포트 출력
- **다양한 프레임워크 지원**: MyBatis, QueryDSL 등