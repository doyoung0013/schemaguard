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

SchemaGuard는 GitHub Actions와 연동하여 Pull Request 또는 main 브랜치 병합 과정에서 자동으로 실행할 수 있습니다.

이를 통해 개발자가 DB 스키마 변경을 포함한 코드를 PR로 올렸을 때, SchemaGuard가 자동으로 SQL 마이그레이션 파일을 분석하고 Java 코드 영향 범위를 검사할 수 있습니다.

---

### 1) GitHub Actions Workflow 폴더 생성

검사 대상 Spring Boot 프로젝트의 루트 경로에 다음 폴더를 생성합니다.

```text
.github/workflows/
```

예시 구조:

```text
community/
├── src/
│   └── main/
│       ├── java/
│       └── resources/
│           └── db/
│               └── migration/
├── .github/
│   └── workflows/
│       └── schemaguard.yml
└── build.gradle
```

---

### 2) yml 파일 생성

`.github/workflows/` 폴더 안에 GitHub Actions Workflow 파일을 생성합니다.

파일명:

```text
schemaguard.yml
```

---

### 3) Workflow 내용 작성

생성한 `schemaguard.yml` 파일에 아래 제공된 GitHub Actions 설정 코드를 복사하여 붙여넣습니다.


```yaml
name: SchemaGuard - Schema Change Analysis

on:
  pull_request:
    branches:
      - main
    paths:
      - '**.sql'

jobs:
  schema-guard:
    name: Analyze Schema Changes
    runs-on: ubuntu-latest

    steps:
      # 1. 검사 대상 프로젝트 체크아웃
      - name: Checkout target repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      # 2. SchemaGuard 별도 레포 클론
      - name: Clone SchemaGuard
        run: |
          git clone https://github.com/doyoung0013/schemaguard.git $GITHUB_WORKSPACE/schemaguard

      # 3. Java 17 세팅
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      # 4. Gradle 캐시
      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/schemaguard/**/*.gradle*', '**/schemaguard/**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      # 5. gradlew 실행 권한 부여
      - name: Grant execute permission for gradlew
        run: chmod +x $GITHUB_WORKSPACE/schemaguard/gradlew

      # 6. PR에서 변경된 SQL 마이그레이션 파일 감지
      - name: Detect changed SQL migration files
        id: changed-sql
        run: |
          CHANGED_SQL=$(git diff --name-only origin/${{ github.base_ref }}...HEAD \
            | grep -E 'src/main/resources/db/migration/.*\.sql$' \
            || true)

          echo "변경된 SQL 파일 목록:"
          echo "$CHANGED_SQL"

          if [ -z "$CHANGED_SQL" ]; then
            echo "sql_changed=false" >> $GITHUB_OUTPUT
          else
            SQL_LIST=$(echo "$CHANGED_SQL" | tr '\n' ' ' | xargs)
            echo "sql_changed=true" >> $GITHUB_OUTPUT
            echo "sql_files=$SQL_LIST" >> $GITHUB_OUTPUT
          fi

      # 7. 변경된 SQL 없으면 스킵
      - name: Skip if no SQL changes
        if: steps.changed-sql.outputs.sql_changed == 'false'
        run: echo "✅ 마이그레이션 SQL 변경 없음. SchemaGuard 스킵."

      # 8. SchemaGuard 실행
      - name: Run SchemaGuard
        if: steps.changed-sql.outputs.sql_changed == 'true'
        env:
          SCHEMAGUARD_LANG: en
        run: |
          mkdir -p $GITHUB_WORKSPACE/build/reports

          for SQL_FILE in ${{ steps.changed-sql.outputs.sql_files }}; do
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "🔍 분석 중: $SQL_FILE"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            SQL_BASENAME=$(basename "$SQL_FILE" .sql)
            REPORT_PATH="$GITHUB_WORKSPACE/build/reports/schemaguard-${SQL_BASENAME}.json"

            cd $GITHUB_WORKSPACE/schemaguard
            ./gradlew run --args="\
              --source $GITHUB_WORKSPACE/src/main/java \
              --migration $GITHUB_WORKSPACE/$SQL_FILE \
              --report-path $REPORT_PATH"
            cd $GITHUB_WORKSPACE
          done

      # 9. hasHighRisk 기반으로 병합 차단 판단 + Step Summary 출력
      - name: Check for HIGH risk
        if: steps.changed-sql.outputs.sql_changed == 'true'
        run: |
          HIGH_FOUND=false
          SUMMARY_BODY=""

          for REPORT in $GITHUB_WORKSPACE/build/reports/schemaguard-*.json; do
            echo "📄 리포트 확인: $REPORT"

            SUMMARY_BODY+=$(python3 -c "
          import json, sys

          with open('$REPORT') as f:
              data = json.load(f)

          summary = data.get('summary', {})
          apis    = data.get('affectedApis', [])

          high   = summary.get('high', 0)
          medium = summary.get('medium', 0)
          low    = summary.get('low', 0)
          has_high = summary.get('hasHighRisk', False)

          overall = '🔴 HIGH' if has_high else ('🟡 MEDIUM' if medium > 0 else '🟢 LOW')

          lines = []
          lines.append('## 🛡️ SchemaGuard Report')
          lines.append('')
          lines.append('### 📌 요약')
          lines.append('')
          lines.append('| 항목 | 값 |')
          lines.append('|---|---|')
          lines.append(f'| 전체 위험도 | {overall} |')
          lines.append(f'| 🔴 HIGH     | {high}건 |')
          lines.append(f'| 🟡 MEDIUM   | {medium}건 |')
          lines.append(f'| 🟢 LOW      | {low}건 |')
          lines.append(f'| 영향 API 수 | {len(apis)}개 |')
          lines.append('')

          if apis:
              lines.append('### 🔥 영향받는 API 목록')
              lines.append('')
              lines.append('| # | API | 위험도 | 변경 유형 | 테이블.컬럼 |')
              lines.append('|---|-----|--------|-----------|------------|')

              for i, api in enumerate(apis, 1):
                  risk  = api.get('riskLevel', '-')
                  badge = '🔴' if risk == 'HIGH' else ('🟡' if risk == 'MEDIUM' else '🟢')
                  cause = api.get('cause', {})
                  change_type = cause.get('type', '-')
                  col  = cause.get('column') or '-'
                  tbl  = cause.get('table') or '-'
                  lines.append(f'| {i} | \`{api.get(\"apiId\",\"-\")}\` | {badge} {risk} | {change_type} | {tbl}.{col} |')

              lines.append('')
              lines.append('### 🛠️ 상세 영향 경로')
              lines.append('')

              for api in apis:
                  risk  = api.get('riskLevel', '-')
                  badge = '🔴' if risk == 'HIGH' else ('🟡' if risk == 'MEDIUM' else '🟢')
                  lines.append(f'<details>')
                  lines.append(f'<summary>{badge} <strong>{api.get(\"apiId\", \"-\")}</strong> ({risk})</summary>')
                  lines.append('')
                  cause = api.get('cause', {})
                  lines.append(f'**변경 원인:** {cause.get(\"type\",\"-\")} on {cause.get(\"table\",\"-\")}.{cause.get(\"column\",\"-\")}')
                  lines.append('')
                  lines.append('**영향 경로:**')
                  lines.append('')
                  lines.append('\`\`\`')
                  for node in api.get('impactPath', []):
                      node_type = node.get('type', '')
                      name      = node.get('name', '')
                      fp        = node.get('filePath', '')
                      line      = node.get('line')
                      loc = f'  ({fp}:{line})' if line else (f'  ({fp})' if fp else '')
                      lines.append(f'  -> [{node_type}] {name}{loc}')
                  lines.append('\`\`\`')
                  lines.append('')
                  lines.append('</details>')
                  lines.append('')

          print('\n'.join(lines))
          ")

            # hasHighRisk 체크
            HAS_HIGH=$(python3 -c "
          import json
          with open('$REPORT') as f:
              data = json.load(f)
          print(data.get('summary', {}).get('hasHighRisk', False))
          ")

            if [ "$HAS_HIGH" = "True" ]; then
              HIGH_FOUND=true
            fi
          done

          # Step Summary 작성
          echo "$SUMMARY_BODY" >> $GITHUB_STEP_SUMMARY

          if [ "$HIGH_FOUND" = "true" ]; then
            # 실패 배너도 Summary에 추가
            echo "" >> $GITHUB_STEP_SUMMARY
            echo "---" >> $GITHUB_STEP_SUMMARY
            echo "## ❌ 병합 차단됨" >> $GITHUB_STEP_SUMMARY
            echo "" >> $GITHUB_STEP_SUMMARY
            echo "> 🚨 **HIGH 위험도 스키마 변경이 감지되었습니다.**" >> $GITHUB_STEP_SUMMARY
            echo "> 영향받는 코드를 수정한 뒤 PR을 다시 올려주세요." >> $GITHUB_STEP_SUMMARY

            echo "🚨 HIGH 위험도 감지 → 병합 차단"
            exit 1
          else
            echo "" >> $GITHUB_STEP_SUMMARY
            echo "---" >> $GITHUB_STEP_SUMMARY
            echo "## ✅ 병합 가능" >> $GITHUB_STEP_SUMMARY
            echo "> HIGH 위험도 없음. 안전하게 병합할 수 있습니다." >> $GITHUB_STEP_SUMMARY

            echo "✅ HIGH 위험도 없음. 병합 가능합니다."
          fi

      # 10. 리포트 Artifact 업로드 (성공/실패 무관하게 항상 실행)
      - name: Upload SchemaGuard reports
        if: always() && steps.changed-sql.outputs.sql_changed == 'true'
        uses: actions/upload-artifact@v4
        with:
          name: schemaguard-reports
          path: ${{ github.workspace }}/build/reports/schemaguard-*.json
          retention-days: 30
```


---

### 4) CI/CD 연동 확인

Workflow 파일을 추가한 뒤 main 브랜치 또는 PR에 변경사항을 push하면 GitHub Actions가 자동으로 실행됩니다.

실행 흐름은 다음과 같습니다.

```text
PR 생성 또는 main 브랜치 push
→ GitHub Actions 실행
→ SchemaGuard 저장소 clone
→ SQL 마이그레이션 파일 분석
→ Java 코드 영향 범위 분석
→ JSON 리포트 생성
→ 위험도에 따라 PR 병합 가능 여부 판단
```

---

### 5) HIGH 위험도 탐지 시 동작

SchemaGuard 분석 결과 HIGH 위험도 변경이 탐지되면 GitHub Actions 실행을 실패 처리할 수 있습니다.

이를 통해 위험한 DB 스키마 변경이 main 브랜치에 병합되기 전에 자동으로 차단됩니다.

예시 상황:

```sql
ALTER TABLE users DROP COLUMN email;
```

위와 같이 기존 Java 코드에서 사용 중인 컬럼이 삭제되는 경우, SchemaGuard는 해당 컬럼과 연결된 Entity, Repository, Service, Controller, API 경로를 추적합니다.

HIGH 위험도가 탐지되면 GitHub Actions는 실패 상태가 되며, 개발자는 병합 전에 영향을 받는 코드를 수정할 수 있습니다.

---

### 6) 분석 리포트 확인

GitHub Actions 실행이 완료되면 Artifact에서 SchemaGuard 분석 리포트를 다운로드할 수 있습니다.

리포트 경로 예시:

```text
build/reports/schemaguard-report.json
```

리포트에는 다음 정보가 포함됩니다.

```text
- 감지된 DB 스키마 변경 유형
- 변경 대상 테이블 및 컬럼
- 위험도
- 영향받는 Entity, Repository, Service, Controller
- 영향받는 API 엔드포인트
- 수정 가이드
```
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
├── settings.gradle
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│
├── lib/
│   ├── build.gradle
│   │
│   └── src/
│       └── main/
│           └── java/
│               └── schemaguard/
│                   │
│                   ├── SchemaGuardMain.java
│                   │
│                   ├── cli/
│                   │   └── SchemaGuardCLI.java
│                   │
│                   ├── model/
│                   │   ├── SchemaChange.java
│                   │   ├── ChangeType.java
│                   │   ├── RiskLevel.java
│                   │   ├── Node.java
│                   │   ├── Edge.java
│                   │   ├── EntityMapping.java
│                   │   ├── FkMapping.java
│                   │   ├── RepositoryInfo.java
│                   │   ├── ServiceInfo.java
│                   │   ├── ControllerInfo.java
│                   │   ├── AffectedApi.java
│                   │   └── ImpactResult.java
│                   │
│                   ├── parser/
│                   │   ├── SqlMigrationParser.java
│                   │   ├── EntityParser.java
│                   │   ├── RepositoryParser.java
│                   │   ├── ServiceParser.java
│                   │   └── ControllerParser.java
│                   │
│                   ├── graph/
│                   │   └── DependencyGraphBuilder.java
│                   │
│                   ├── analyzer/
│                   │   └── ImpactAnalyzer.java
│                   │
│                   └── report/
│                       ├── ReportGenerator.java
│                       ├── ConsoleReporter.java
│                       └── JsonReporter.java

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
- Repository 계층 (`JpaRepository` 상속, 쿼리 메서드, `@Query`)
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

- **CI/CD 연동**: GitHub Actions에서 PR 단계 자동 실행, HIGH 위험 시 병합 차단
- **IDE 플러그인**: IntelliJ 플러그인으로 실시간 경고 표시
- **JPQL / Native Query 심층 분석**: 쿼리 내부 컬럼 참조 완전 추적
- **시각화**: 의존성 그래프 HTML 리포트 출력
- **다양한 프레임워크 지원**: MyBatis, QueryDSL 등