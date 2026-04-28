# 🛡️ SchemaGuard

> **Static Analysis Tool for Detecting Potential Error Points Caused by Database Schema Changes in Spring Boot-Based Backends**
>
> Spring Boot + JPA 기반 백엔드에서 DB 스키마 변경으로 인해 발생할 수 있는 런타임 오류 지점을 **배포 전에 사전 탐지**하는 정적 분석 CLI 도구

---

## 📌 프로젝트 소개

실제 서비스에서는 DB 스키마가 변경되더라도 **컴파일 단계에서는 문제가 드러나지 않고**, 배포 이후 런타임 시점에 API가 깨지는 장애가 자주 발생한다.

SchemaGuard는 이 문제를 해결하기 위해 SQL 마이그레이션 파일과 Java 소스 코드를 함께 분석하여, **DB 컬럼 변경으로부터 영향받는 API 엔드포인트를 자동으로 역추적**한다.


---

## 🎯 프로젝트 목표

- DB 스키마 변경(컬럼 삭제, 컬럼명 변경, 타입 변경 등)으로 인한 런타임 오류 **사전 탐지**
- Entity → Repository → Service → Controller → API 엔드포인트까지의 **의존성 그래프 자동 구성**
- 변경된 스키마가 영향을 미치는 API를 **역방향 그래프 탐색(BFS)** 으로 식별
- 위험도(HIGH / MEDIUM / LOW) 기반 **경고 리포트** 생성
- **GitHub Actions CI/CD** 파이프라인 연동을 통한 PR 단계 자동 차단

---

## 🏗️ 전체 저장소 구조

이 저장소는 두 개의 독립 프로젝트로 구성된다.

```
graduateproject/
├── schemaguard/                  ← 📌 정적 분석 도구 (이 프로젝트, Java CLI)
└── community/        ← 🧪 분석 대상 테스트용 Spring Boot 웹
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
│       ├── main/
│       │   └── java/
│       │       └── com/
│       │           └── schemaguard/
│       │               │
│       │               ├── SchemaGuardMain.java        
│       │               │
│       │               ├── cli/
│       │               │   └── SchemaGuardCLI.java
│       │               │
│       │               ├── model/
│       │               │   ├── SchemaChange.java
│       │               │   ├── ChangeType.java
│       │               │   ├── RiskLevel.java
│       │               │   ├── Node.java
│       │               │   ├── NodeType.java
│       │               │   ├── Edge.java
│       │               │   ├── RelationType.java
│       │               │   ├── EntityMapping.java
│       │               │   ├── RepositoryInfo.java
│       │               │   ├── ServiceInfo.java
│       │               │   ├── ControllerInfo.java
│       │               │   ├── AffectedApi.java
│       │               │   └── ImpactResult.java
│       │               │
│       │               ├── parser/
│       │               │   ├── MigrationParser.java
│       │               │   ├── EntityParser.java
│       │               │   ├── RepositoryParser.java
│       │               │   ├── ServiceParser.java
│       │               │   └── ControllerParser.java
│       │               │
│       │               ├── graph/
│       │               │   └── DependencyGraphBuilder.java
│       │               │
│       │               ├── analyzer/
│       │               │   └── ImpactAnalyzer.java
│       │               │
│       │               └── report/
│       │                   ├── ReportGenerator.java
│       │                   ├── ConsoleReporter.java
│       │                   └── JsonReporter.java
│       │
│       └── test/
│           └── java/
│               └── com/
│                   └── schemaguard/
│                       ├── parser/
│                       │   ├── MigrationParserTest.java
│                       │   ├── EntityParserTest.java
│                       │   ├── RepositoryParserTest.java
│                       │   ├── ServiceParserTest.java
│                       │   └── ControllerParserTest.java
│                       ├── graph/
│                       │   └── DependencyGraphBuilderTest.java
│                       ├── analyzer/
│                       │   └── ImpactAnalyzerTest.java
│                       └── fixtures/
│                           ├── java/
│                           │   ├── User.java
│                           │   ├── UserRepository.java
│                           │   ├── UserService.java
│                           │   └── UserController.java
│                           └── sql/
│                               ├── V1__init.sql
│                               └── V2__drop_email.sql
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

> Spring Boot **미사용** — 웹 서버 불필요, CLI 단독 실행 도구

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

## 📋 리포트 출력 예시

```
========================================
  SchemaGuard Analysis Report
========================================

[HIGH] DROP_COLUMN detected: users.email

Affected APIs (2):

  ❌ GET /users/{id}
     Risk   : HIGH
     Path   : users.email
              → User.email (field)
              → UserRepository.findById (repository)
              → UserService.getUser (service)
              → UserController.getUser (controller)

  ❌ POST /auth/login
     Risk   : HIGH
     Path   : users.email
              → UserRepository.findByEmail (repository)
              → AuthService.login (service)
              → AuthController.login (controller)

========================================
  Total HIGH : 2
  Total MEDIUM: 0
  Total LOW   : 0
========================================
```

---

## 🚀 실행 방법

### 빌드

```bash
./gradlew build
```

### 실행 (콘솔 출력)

```bash
java -jar build/libs/schemaguard.jar \
  --source ../community/src/main/java \
  --migration ../community/src/main/resources/db/migration/V2__drop_email.sql
```

### 실행 (JSON 출력)

```bash
java -jar build/libs/schemaguard.jar \
  --source ../community/src/main/java \
  --migration ./V2__drop_email.sql \
  --output json \
  --report-path ./report.json
```

---

## 🧪 실험 시나리오

`community`에 정의된 아래 6가지 변경 시나리오를 기준으로 탐지 정확도를 검증한다.

| 시나리오 | 변경 내용 | 위험도 |
|---|---|---|
| 1 | `Post.content` 컬럼 삭제 | HIGH |
| 2 | `User.email` → `login_email` 컬럼명 변경 | HIGH |
| 3 | `User.status` VARCHAR → INT 타입 변경 | HIGH |
| 4 | `Category` 테이블 삭제 | HIGH |
| 5 | `Post.author_id` FK 제거 | HIGH |
| 6 | `Comment.content` NOT NULL 추가, `User.email` UNIQUE 추가 | MEDIUM |

---

## 🗓️ 개발 단계

| 단계 | 내용 | 핵심 클래스 |
|---|---|---|
| 1주차 | 프로젝트 세팅 + 모델 클래스 정의 + MigrationParser | `SchemaChange`, `MigrationParser` |
| 2주차 | EntityParser → 필드-컬럼 매핑 검증 | `EntityMapping`, `EntityParser` |
| 3주차 | RepositoryParser + ServiceParser + ControllerParser | `RepositoryInfo`, `ServiceParser` |
| 4주차 | 그래프 구성 + BFS 영향 분석 | `DependencyGraphBuilder`, `ImpactAnalyzer` |
| 5주차 | 리포트 생성 + CLI 완성 + 시나리오 검증 | `ConsoleReporter`, `SchemaGuardCLI` |
| 6주차 | GitHub Actions 연동 + 최종 정리 | `.github/workflows/schemaguard.yml` |

---

## 📦 향후 확장

- **CI/CD 연동**: GitHub Actions에서 PR 단계 자동 실행, HIGH 위험 시 병합 차단
- **IDE 플러그인**: IntelliJ 플러그인으로 실시간 경고 표시
- **JPQL / Native Query 심층 분석**: 쿼리 내부 컬럼 참조 완전 추적
- **시각화**: 의존성 그래프 HTML 리포트 출력
- **다양한 프레임워크 지원**: MyBatis, QueryDSL 등