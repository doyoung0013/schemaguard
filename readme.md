# 🛡️ SchemaGuard

> **Static Analysis Tool for Detecting Potential Error Points Caused by Database Schema Changes in Spring Boot-Based Backends**
>
> Spring Boot + JPA 기반 백엔드에서 DB 스키마 변경으로 인해 발생할 수 있는 런타임 오류 지점을 **배포 전에 사전 탐지**하는 정적 분석 CLI 도구

---

## 📌 프로젝트 소개

SchemaGuard는 SQL 마이그레이션 파일과 Java 소스 코드를 함께 분석하여,  
DB 스키마 변경으로 인해 영향을 받을 수 있는 API 엔드포인트를 사전에 찾아주는 도구입니다.

예를 들어 `users.email` 컬럼을 삭제했을 때,  
해당 컬럼과 연결된 Entity, Repository, Service, Controller, API까지 추적하여  
어떤 API가 런타임 오류를 일으킬 수 있는지 리포트로 출력합니다.

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
--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json
```

옵션 설명:

| 옵션 | 설명 |
|---|---|
| `--source` | 검사 대상 Java 소스 코드 경로 |
| `--migration` | 검사할 SQL 마이그레이션 파일 경로 |
| `--report-path` | JSON 리포트를 저장할 경로 |

---

### 방법 2) CLI로 실행

기본 형식:

```bash
./gradlew run --args="--source 검사대상Java경로 --migration 검사대상MigrationSQL경로 --report-path 리포트저장경로"
```

실행 예시:

```bash
./gradlew run --args="--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json"
```

---

## 🌐 영어 리포트로 실행하기

일부 터미널에서 UTF-8 또는 한글 출력이 깨질 수 있습니다.

이 경우 `SCHEMAGUARD_LANG=en` 옵션을 앞에 붙이면 영어 리포트로 출력할 수 있습니다.

```bash
SCHEMAGUARD_LANG=en ./gradlew run --args="--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json"
```

Windows PowerShell에서는 다음과 같이 실행할 수 있습니다.

```powershell
$env:SCHEMAGUARD_LANG="en"
./gradlew run --args="--source D:/graduateproject/community/src/main/java --migration D:/graduateproject/community/src/main/resources/db/migration/V2__drop_email.sql --report-path D:/graduateproject/community/build/reports/schemaguard-report.json"
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
│                   │   ├── MigrationParser.java
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
| 컬럼 추가 | 🟢 LOW | 기존 코드에 영향 없음 |

---

## 🔄 처리 흐름

```
SQL 마이그레이션 파일                Java 소스 코드
       │                                  │
       ▼                                  ▼
 MigrationParser                   EntityParser
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