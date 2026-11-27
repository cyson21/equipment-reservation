# 공용 장비 예약

같은 장비의 겹치는 예약 요청이 동시에 들어와도 한 건만 확정하는 서비스다. 예약 코어를 먼저 검증한 뒤 REST·CLI와 PostgreSQL을 연결하고, 포트와 어댑터 구조에서 실제 변경이 일어난 범위를 테스트와 커밋으로 확인했다. Java 21, Spring Boot 4.2.0, PostgreSQL 17을 사용한다.

## 왜 헥사고날 아키텍처인가

- **진입점 추가.** 사용자는 REST로 요청하고 운영자는 CLI로 예약을 처리한다. HTTP 상태 코드와 터미널 출력은 각 어댑터가 맡고, 두 진입점은 같은 입력 포트로 예약 시간과 충돌 규칙을 실행한다.
- **저장 교체.** 업무 흐름에 필요한 저장 계약은 `tryReserve`가 예약 확정 또는 시간 충돌을 원자적으로 알려주는 것이다. 인메모리 구현은 코어 테스트를 구동하고, 실행 앱에서는 PostgreSQL이 트랜잭션과 배제 제약으로 실제 동시 요청을 처리한다. 기술이 달라도 유스케이스가 받는 결과는 같다.
- **의존 방향.** Gradle `core`는 도메인과 포트, 유스케이스를 가진다. `app`은 `core`에 의존하고 Spring, REST·CLI, JDBC와 조립 코드를 가진다. `core`의 운영 코드는 JDK 기본 모듈에만 의존한다. 두 모듈은 하나의 실행 JAR로 패키징한다.

시간 충돌의 판단 기준을 정하는 일과 동시 요청에서 그 기준을 지키는 일은 각각 다른 경계에서 처리한다. 코어는 허용되는 시간 구간과 결과를 정의하고, PostgreSQL은 커밋 시점에도 그 결과가 유효하도록 보장한다. 포트와 변환 코드가 늘어나는 만큼, REST·CLI 연결과 DB 없이 실행되는 코어 테스트에서 이 경계의 가치가 드러나야 한다.

## 예약 규칙

- 장비 목록은 미리 등록한 장비에서 조회한다. 목록은 장비 ID 기준으로 정렬한다. 등록되지 않은 장비를 예약하려 하면 `EQUIPMENT_NOT_FOUND`를 반환한다.
- 예약 구간은 `[시작, 종료)`이고 시작 시각은 종료 시각보다 앞서야 한다. 같은 장비의 활성 예약과 겹치면 `TIME_CONFLICT`를 반환한다. 앞 예약의 종료 시각과 다음 예약의 시작 시각이 같으면 허용한다.
- REST와 CLI는 시차가 명시된 ISO 8601 시각을 받아 `Instant`로 변환한다. 코어의 지원 범위는 UTC 기준 0001년부터 9999년까지다. 마이크로초로 정확히 표현할 수 없는 시각은 입력 오류로 처리한다.
- 예약 취소는 레코드의 상태를 `CANCELLED`로 바꾼다. 기존 예약 ID는 반복 취소할 수 있고, 존재하지 않는 ID는 `RESERVATION_NOT_FOUND`를 반환한다. 취소가 완료된 시간대는 다시 예약할 수 있다.

## 포트와 어댑터

| 입력 포트 | 결과 | REST | CLI |
| --- | --- | --- | --- |
| `ListEquipment` | 장비 목록 | `GET /equipment` → `200` | `equipment list` |
| `ReserveEquipment` | 확정된 예약 ID, 미등록 장비, 시간 충돌 또는 입력 오류 | `POST /reservations` → `201` / `404` / `409` / `400` | `reservation create` |
| `CancelReservation` | 취소 성공, 미등록 예약 또는 입력 오류 | `DELETE /reservations/{id}` → `204` / `404` / `400` | `reservation cancel` |

- `domain`은 예약 후보와 `TimeSlot`을 표현한다. `TimeSlot`은 시각 범위와 두 구간의 겹침을 정의한다. 저장 어댑터는 확정된 예약의 활성·취소 상태를 보관한다.
- `application`은 입력 포트와 유스케이스, 출력 포트를 소유한다. `EquipmentCatalog`는 장비 목록과 존재 여부를 제공한다. `ReservationStore.tryReserve`는 확정 또는 시간 충돌을 원자적으로 반환하고, `cancel`은 기존 예약 취소 또는 미등록을 반환한다.
- `adapter.in`의 REST와 CLI는 입력 형식과 결과 표현을 맡는다. `adapter.out`의 PostgreSQL 구현은 JDBC와 SQL을 담당한다. 인메모리 구현은 코어 유스케이스 테스트에서 같은 출력 포트를 사용한다.
- `ReservationApplication`은 Spring 의존성 조립을 맡는다. REST 서버와 CLI는 각각 웹 모드와 비웹 모드로 실행하며 같은 PostgreSQL 데이터베이스를 사용한다.

## PostgreSQL의 동시 예약 처리

예약 ID는 UUID, 장비 ID는 `bigint`, 예약 시각은 `timestamptz`로 저장한다. JDBC 어댑터에서 `Instant`를 UTC `OffsetDateTime`으로 변환한다. 외래 키와 시작·종료 순서 검사를 둔다. `btree_gist`와 아래 배제 제약은 DB 마이그레이션이 적용한다. 배포 환경에서 마이그레이션을 실행하는 역할에는 해당 확장을 설치할 권한이 필요하다.

```sql
CONSTRAINT reservation_no_active_overlap EXCLUDE USING gist (
    equipment_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
) WHERE (status = 'ACTIVE')
```

장비 존재를 확인한 뒤 한 번의 `INSERT`로 예약한다. `ON CONFLICT ON CONSTRAINT reservation_no_active_overlap DO NOTHING RETURNING id`의 결과가 비어 있으면 시간 충돌로 처리한다. 기본 키나 외래 키 등 다른 DB 오류는 그대로 실패 처리한다. 실행 앱에서는 외부 트랜잭션 없이 JDBC의 기본 자동 커밋으로 이 문장을 실행한다. 따라서 성공 응답은 해당 쓰기가 커밋된 뒤 반환된다.

취소는 예약 ID로 `UPDATE … RETURNING id`를 한 번 실행한다. 갱신 대상은 취소 상태인 행도 포함한다. 취소와 예약이 동시에 들어오면 예약 시점에 활성 예약이 남아 있어 시간 충돌을 받을 수 있다. 취소가 완료된 뒤에는 같은 구간을 재예약할 수 있다.

참고 문서 [Ports & Adapters 원문](https://alistair.cockburn.us/hexagonal-architecture) · [PostgreSQL 범위 타입과 배제 제약](https://www.postgresql.org/docs/17/rangetypes.html#RANGETYPES-CONSTRAINT) · [INSERT 문서](https://www.postgresql.org/docs/17/sql-insert.html#SQL-ON-CONFLICT)

## 헥사고날의 장점 일곱 가지와 검증 근거

| 장점 | 확인할 코드 | 테스트·관찰 결과 | 커밋 |
| --- | --- | --- | --- |
| 1. 코어의 기술 독립성 | [코어 빌드][core-build], [앱의 `core` 의존성][app-build] | 코어 `runtimeClasspath`는 외부 의존성 0개. 빌드한 코어 JAR의 `jdeps` 결과는 `java.base`만 출력. | `f102a40`, `2f3c7b2` |
| 2. 저장 구현 변경의 영향 범위 | [인메모리 저장소][memory-store], [JDBC 저장 어댑터][jdbc-store] | 두 구현이 같은 [저장 계약 테스트][store-contract] 7건을 통과. `ac2c366`부터 어댑터를 통합한 `14eb308`까지 `core/` diff가 비어 있음. | `ac2c366`, `21cbad1`, `14eb308` |
| 3. 빠른 코어 테스트 | [예약 유스케이스][service], [인메모리 저장소][memory-store] | [코어 테스트][service-test]로 충돌·취소·재예약을 검증. 자동 재현에서 PostgreSQL 시작 전에 `:core:test` 21건 통과. | `ac2c366`, `9820e87` |
| 4. 여러 진입점의 유스케이스 재사용 | [REST 컨트롤러][rest], [CLI][cli], [의존성 조립][bootstrap] | [입력 어댑터 테스트][input-test]와 [실행 JAR 데모][demo]에서 REST 예약 → CLI 충돌 → REST 취소 → REST 종료 후 CLI 재예약 성공을 확인. | `1ccb24f`, `14eb308`, `9820e87` |
| 5. 코어가 정의하는 외부 계약 | [`ReservationStore.tryReserve`][store-port], [충돌 처리 SQL][jdbc-store] | 코어가 원자적 확정·시간 충돌을 요구한다. [JDBC 테스트][jdbc-test]에서 시간 충돌의 결과 변환과 기본 키 오류 전파를 확인. | `ac2c366`, `21cbad1` |
| 6. 업무 규칙과 기술적 보장의 개별 검증 | [`TimeSlot`][slot], [PostgreSQL 배제 제약][migration] | [시간 구간 테스트][slot-test]가 `[시작, 종료)`를 검증. [DB 경합 테스트][contention]가 독립 연결의 잠금 대기를 관찰하고 확정 1건·충돌 1건·활성 예약 1건을 확인. | `ac2c366`, `21cbad1`, `33a8d8a` |
| 7. 단계적·병렬 구현 | [입력 어댑터][inputs], [저장 어댑터][outputs], [최종 조립][bootstrap] | 공통 빌드 커밋에서 분리된 두 작업이 서로 다른 경로를 변경했다. 통합 후 입력 7건과 PostgreSQL 11건이 모두 통과했고 기준 코어의 diff는 비어 있음. | `ac2c366`, `2f3c7b2`, `1ccb24f`, `21cbad1`, `14eb308` |

### 설계 선택과 작업 분담

예약 확정은 저장 포트의 한 번의 호출로 처리했다. 코어는 어떤 시간대가 유효하고 어떤 결과를 받아야 하는지 정의한다. PostgreSQL은 배제 제약으로 동시 요청에도 그 계약을 지킨다. 포트와 변환 코드가 추가되지만, 입력과 저장 구현을 따로 검증하고 코어 변경 범위를 Git으로 확인할 수 있었다.

사용자가 헥사고날의 장점 일곱 가지를 실제 증거로 보여주는 범위를 정했다. 코어와 계약을 먼저 커밋한 뒤 두 AI 에이전트가 입력과 저장 어댑터를 각각 구현했고, 메인 에이전트가 통합과 실행 검증을 맡았다. 원본 입력·저장 커밋 `84f15b6`, `e4c0904`의 공통 부모는 `2f3c7b2`다. 표의 `1ccb24f`, `21cbad1`은 `main`에 들어간 커밋이다.

### 새 체크아웃 재현 결과

`86171c3`를 새 로컬 클론에서 검증했다. Windows 11, Java 21.0.2, PostgreSQL 17.12를 사용했고 이 실행은 기존 Gradle 배포·의존성 캐시를 사용했다. 코어 21건, 입력 어댑터 7건, PostgreSQL 11건이 통과했다. 실패·건너뜀은 모두 0건이었다. 코어 테스트 스위트 시간 합계는 0.095초였고, 컴파일·테스트·데모 종료까지 43.29초가 걸렸다. 두 수치는 이 실행에서 관찰한 값이다.

실행 JAR은 REST 예약 `201`, CLI 중복 예약 종료 코드 `4`와 `TIME_CONFLICT`, REST 취소 `204`, REST 종료 후 CLI 재예약 `0`을 반환했다. PostgreSQL에는 처음 예약이 취소 상태로 남았고 새 예약 한 건의 시작·종료 마이크로초가 그대로 저장됐다. 클론에서 `git diff ac2c366..HEAD -- core`도 비어 있었다.

트랜잭션 검증 범위는 실행 앱의 기본 JDBC 자동 커밋과 독립 연결 간 경합이다.

## 실행 방법

### 자동 재현

Windows PowerShell 7.4 이상에서 `JAVA_HOME`을 Java 21 JDK에 맞춘 뒤 실행한다. `-PostgresBin`에는 PostgreSQL 17의 `initdb.exe`, `pg_ctl.exe`, `psql.exe`가 들어 있는 폴더를 지정한다. 처음 실행할 때 Gradle Wrapper와 라이브러리를 내려받는다.

```powershell
.\scripts\verify-demo.ps1 -PostgresBin 'C:\path\to\pgsql\bin'
```

[재현 스크립트][demo]는 코어 테스트를 먼저 실행한 뒤 루프백 임시 PostgreSQL에 테스트용 DB와 데모용 DB를 각각 만든다. PostgreSQL 경합 테스트가 실제로 실행됐는지 검사하고, 빌드한 JAR로 REST·CLI를 실행한다. 종료할 때 실행한 서버와 임시 DB를 정리하며 부모 셸의 관련 환경 변수를 복원한다.

```powershell
git diff --exit-code ac2c366..HEAD -- core
```

### 개별 실행

Java 21과 PostgreSQL 17을 사용한다. PostgreSQL에 빈 `equipment_reservation` 데이터베이스를 만들고 접속 계정을 준비한다. Flyway가 처음 실행될 때 테이블과 초기 장비를 만들며, 마이그레이션 계정에 `btree_gist`를 설치할 권한이 필요하다.

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/equipment_reservation'
$env:SPRING_DATASOURCE_USERNAME = 'reservation'
.\gradlew.bat :app:bootJar
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar = '.\app\build\libs\equipment-reservation.jar'
& $java -jar $jar
```

접속에 암호가 필요하면 `SPRING_DATASOURCE_PASSWORD`를 환경 변수로 지정한다. REST 서버는 기본값으로 `127.0.0.1:8080`에 열린다. 아래 예제는 같은 데이터베이스 접속 설정과 `$java`, `$jar`를 지정한 별도 PowerShell에서 실행한다. CLI도 시작 시 DB 접속과 Flyway 검증을 수행한다.

```powershell
$body = @{
    equipmentId = 1
    startsAt = '2030-01-01T23:00:00.000001+09:00'
    endsAt = '2030-01-02T00:00:00.000001+09:00'
} | ConvertTo-Json
$reservation = Invoke-RestMethod 'http://127.0.0.1:8080/reservations' -Method Post `
    -ContentType 'application/json' -Body $body

& $java -jar $jar cli reservation create 1 '2030-01-01T14:15:00Z' '2030-01-01T14:20:00Z'
$LASTEXITCODE # 4. stderr에 TIME_CONFLICT

Invoke-RestMethod "http://127.0.0.1:8080/reservations/$($reservation.reservationId)" -Method Delete
& $java -jar $jar cli reservation create 1 '2030-01-01T14:00:00.000001Z' '2030-01-01T15:00:00.000001Z'
$LASTEXITCODE # 0. stdout에 새 예약 UUID
```

전체 JDBC 검증은 폐기 가능한 별도 데이터베이스를 사용한다. `TEST_POSTGRES_URL`, `TEST_POSTGRES_USER`, 필요한 경우 `TEST_POSTGRES_PASSWORD`를 지정한 뒤 `.\gradlew.bat :app:postgresTest`를 실행한다. 테스트가 임시 스키마를 만들고 삭제하며 `btree_gist`를 설치할 수 있다.

[core-build]: core/build.gradle
[app-build]: app/build.gradle
[service]: core/src/main/java/io/github/cyson21/reservation/application/ReservationService.java
[slot]: core/src/main/java/io/github/cyson21/reservation/domain/TimeSlot.java
[store-port]: core/src/main/java/io/github/cyson21/reservation/application/port/out/ReservationStore.java#L13
[memory-store]: core/src/testFixtures/java/io/github/cyson21/reservation/testing/InMemoryReservationStore.java
[store-contract]: core/src/testFixtures/java/io/github/cyson21/reservation/testing/ReservationStoreContract.java
[service-test]: core/src/test/java/io/github/cyson21/reservation/application/ReservationServiceTest.java
[slot-test]: core/src/test/java/io/github/cyson21/reservation/domain/TimeSlotTest.java
[rest]: app/src/main/java/io/github/cyson21/reservation/adapter/in/rest/ReservationController.java
[cli]: app/src/main/java/io/github/cyson21/reservation/adapter/in/cli/ReservationCli.java
[bootstrap]: app/src/main/java/io/github/cyson21/reservation/ReservationApplication.java
[jdbc-store]: app/src/main/java/io/github/cyson21/reservation/adapter/out/postgres/JdbcReservationStore.java
[input-test]: app/src/test/java/io/github/cyson21/reservation/adapter/in/ReservationInputsTest.java
[jdbc-test]: app/src/test/java/io/github/cyson21/reservation/adapter/out/postgres/JdbcReservationStoreTest.java
[contention]: app/src/test/java/io/github/cyson21/reservation/adapter/out/postgres/JdbcReservationStoreTest.java#L106
[migration]: app/src/main/resources/db/migration/V1__create_equipment_and_reservations.sql
[inputs]: app/src/main/java/io/github/cyson21/reservation/adapter/in
[outputs]: app/src/main/java/io/github/cyson21/reservation/adapter/out/postgres
[demo]: scripts/verify-demo.ps1
