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
