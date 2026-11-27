# 공용 장비 예약

예약 정책을 한 곳에 두면 새 진입점을 추가하거나 저장 구현을 바꿀 때 업무 코드까지 수정해야 할까? 공용 장비 예약을 예시로, 포트와 어댑터가 이 변경의 범위를 어디까지 줄이는지 검증한다. Java 21, Spring Boot 4.2.0, PostgreSQL 17을 사용한다.

## 왜 헥사고날 아키텍처인가

- **진입점 추가.** 사용자는 REST로 요청하고 운영자는 CLI로 예약을 처리한다. HTTP 상태 코드와 터미널 출력은 각 어댑터가 맡고, 두 진입점은 같은 입력 포트로 예약 시간과 충돌 규칙을 실행한다.
- **저장 교체.** 업무 흐름에 필요한 저장 계약은 `tryReserve`가 예약 확정 또는 시간 충돌을 원자적으로 알려주는 것이다. 인메모리 구현은 코어 테스트를 구동하고, 운영에서는 PostgreSQL이 트랜잭션과 배제 제약으로 실제 동시 요청을 처리한다. 기술이 달라도 유스케이스가 받는 결과는 같다.
- **의존 방향.** Gradle `core`는 도메인과 포트, 유스케이스를 가진다. `app`은 `core`에 의존하고 Spring, REST·CLI, JDBC와 조립 코드를 가진다. `core`의 운영 코드는 JDK 기본 모듈에만 의존한다. 두 모듈은 한 애플리케이션으로 배포한다.

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

## 헥사고날의 장점과 검증 기준

예약·취소 시나리오 하나를 여러 경계에서 실행해 아래 장점을 확인한다. 소스, 테스트 결과, 실제 변경 이력을 근거로 남긴다.

| 장점 | 확인할 증거 |
| --- | --- |
| 1. 코어의 기술 독립성 | `app`에서 `core`로 향하는 Gradle 의존 관계와 `jdeps` 결과를 확인한다. `core` JAR의 JDK 모듈 의존성은 `java.base`다. |
| 2. 저장 구현 변경의 영향 범위 | 인메모리에서 PostgreSQL로 연결 대상을 바꿀 때 유스케이스와 포트 코드에 생긴 변경을 검토한다. 두 어댑터에 같은 저장 계약 테스트를 적용해 장비별 겹침, 연속 예약, 다른 장비, 반복 취소와 재예약에서 같은 결과를 확인한다. |
| 3. 빠른 코어 테스트 | Spring과 DB 없이 `:core:test`를 실행하고 테스트 결과와 실행 시간을 기록한다. 시간 순서·범위·정밀도, 미등록 장비, 예약 충돌, 취소 후 재예약을 검증한다. |
| 4. 여러 진입점의 유스케이스 재사용 | REST로 예약하고 CLI로 겹치는 예약을 요청해 충돌을 확인한다. 기존 예약을 취소한 뒤 CLI 재예약이 성공하는지 확인한다. 두 진입점이 같은 입력 포트를 호출하는 코드도 함께 본다. |
| 5. 코어가 정의하는 외부 계약 | 코어의 `tryReserve` 시그니처와 결과 타입에서 원자적 확정과 시간 충돌의 의미를 확인한다. PostgreSQL 구현에서 해당 배제 제약으로 발생한 충돌만 시간 충돌로 매핑하며, 기본 키 오류는 실패로 전달되는지 검증한다. |
| 6. 업무 규칙과 기술적 보장의 개별 검증 | 코어 테스트로 어떤 구간이 충돌하는지 검증한다. PostgreSQL 통합 테스트에서는 독립 연결 두 개가 제한 시간 안에 실제 경합하는 것을 관찰하고 확정 1건·충돌 1건·활성 예약 1건을 확인한다. 시차와 마이크로초 단위 시각도 DB에 저장한 뒤 읽어 비교한다. |
| 7. 단계적·병렬 구현 | 코어와 포트 계약을 검증한 기준 커밋을 남긴다. 입력·저장 어댑터의 작업 범위, 개별 검증과 통합 변경 내역을 비교해 같은 계약을 바탕으로 작업을 나눌 수 있었는지 확인한다. |

### 코어 기준 커밋

예약·취소 코어와 인메모리 저장소 계약 테스트는 `ac2c366`에서 검증했다. Java 21을 사용할 수 있는 PowerShell에서 아래 명령으로 실행한다.

```powershell
.\gradlew.bat :core:test :core:jar
& "$env:JAVA_HOME\bin\jdeps.exe" --print-module-deps .\core\build\libs\core.jar
```

별도 체크아웃에서 의존성을 캐시에 받은 뒤 오프라인으로 재실행했다. Spring과 PostgreSQL 없이 JUnit 21건이 통과했고 실패·건너뜀은 0건이었다. 테스트 스위트 실행 시간 합계는 0.088초였고, Wrapper 호출부터 빌드·테스트 종료까지 약 2.6초가 걸렸다. 코어 JAR의 JDK 모듈 의존성은 `java.base`였고 Gradle `runtimeClasspath`에는 외부 라이브러리가 없었다.

저장 계약 테스트 7건은 [공용 테스트 픽스처](core/src/testFixtures/java/io/github/cyson21/reservation/testing/ReservationStoreContract.java)에 있다. 인메모리와 PostgreSQL 어댑터의 테스트가 이 계약을 공유한다.

### REST·CLI·PostgreSQL 통합 검증

입력 어댑터와 PostgreSQL 어댑터를 별도 브랜치에서 작업하고 `1ccb24f`, `21cbad1`로 각각 통합했다. `14eb308`이 한 Spring Boot 애플리케이션에서 세 입력 포트와 JDBC 저장 어댑터를 연결한다. 코어 기준 `ac2c366` 이후 `core/` 전체의 Git diff는 비어 있다.

| 실행 | 확인 결과 |
| --- | --- |
| `:core:test` | 21건 통과. `jdeps` 결과는 `java.base`. |
| `:app:test` | REST·CLI 테스트 7건 통과. |
| `:app:postgresTest` | PostgreSQL 17.12에서 11건 통과. 인메모리에서 사용한 저장 계약 7건을 같은 테스트 코드로 재실행했다. 독립 JDBC 연결이 실제로 잠금을 기다리는 것을 관찰했고, 커밋 후 시간 충돌 1건과 활성 예약 1건을 확인했다. |
| 실행 JAR | REST로 예약하면 `201`, 겹치는 CLI 예약은 종료 코드 `4`와 `TIME_CONFLICT`, REST 취소는 `204`였다. REST 서버 종료 이후 같은 CLI가 동일 구간을 다시 예약했고, PostgreSQL에서 활성 1건·취소 1건과 정확한 마이크로초를 확인했다. |

실행한 테스트는 모두 실패·건너뜀 0건이었다.

### 설계 단계에서 확인한 동작

Windows의 PostgreSQL 17.12 임시 클러스터에서 제약과 SQL을 실행했다. 인접 구간, 다른 장비의 겹치는 구간, 반복 취소, 미등록 ID 취소, 취소 후 재예약, 기본 키 오류 전파를 확인했다. 독립 `psql` 연결에서 첫 예약을 커밋하기 전 겹치는 두 번째 예약이 잠금을 기다리는 것을 `pg_stat_activity`로 확인했다. 커밋 후 두 번째 삽입은 빈 결과를 반환했고 대상 장비의 활성 예약은 1건이었다. 이 실험은 설계 단계에서 PostgreSQL 제약과 SQL 동작을 확인한 결과다.

## 실행 방법

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

## 아키텍처 변경 범위 비교

어댑터 변경과 비교할 때는 `git diff ac2c366..HEAD -- core`를 실행한다. 코어 기준과 동일한 저장 계약으로 어댑터의 동작을 검증한 결과는 위 표에서 확인할 수 있다.
