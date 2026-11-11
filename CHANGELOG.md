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

- **문법 오류 파일이 실패로 보고되지 않던 문제:** `SourceScanner`가 `StaticJavaParser` 대신 스캔마다 `JavaParser` 인스턴스를 쓰도록 바꾸면서 생긴 문제입니다. 인스턴스는 문법 오류에도 예외 없이 부분 결과를 돌려줍니다. `isSuccessful()`을 확인해 실패로 보고하게 했습니다. 기존 `SourceScannerTest`와 결정성 스냅샷(파싱 실패 파일 포함)이 이 문제를 잡아 줬습니다.
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
- `MigrationParser`는 `MODIFY`, `CHANGE`, `ALTER ... TYPE`에서 새 컬럼 정의를 함께 담습니다. 예: `VARCHAR(200) NOT NULL` → `SchemaChange.ColumnDef`

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

1. `SourceScanner.collectJavaFiles`가 경로(`/` 정규화) 기준으로 정렬된 목록을 돌려줍니다.
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
