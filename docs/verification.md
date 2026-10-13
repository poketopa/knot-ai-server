# 검증 기록

## 2026-10-13 최소 구현 재설계

Java/Spring은 유지하고 HTTP 계약과 작업 보관 방식을 변경했다. 현재 구조와 삭제 이유는 [리뷰 안내](convention-refactor.md), 호출 방법은 [HTTP 계약](http-contract.md)에 있다.

| 검사 | 결과 |
| --- | --- |
| `./gradlew spotlessApply check bootJar` | 성공. Java 60개 통과, 실제 모델 테스트 1개 생략 |
| `pnpm --dir runner typecheck` | 성공 |
| `pnpm --dir runner test` | Node 12개·Python 5개 통과 |
| `git diff --check` | 성공 |
| Knot 컨벤션 00~08 원문 hash 검사 | 통과 |

검증한 동작은 현재 작업의 중복 접수, 동시 요청의 단일 실행, cleanup 전 슬롯 유지, 완료 결과 교체와 이전 ID의 404, 종료·정리 실패 후 접수 차단이다. S3 URL·리다이렉트·다운로드 크기·시간 제한과 실제 PCM 길이도 검사했다. runner 테스트는 한글 바이트 복원, VAD·화자 기본 인자, 음량 정규화와 Python deadline에 의한 강제 종료를 포함한다. JVM 강제 종료 후 native 프로세스 회수 테스트도 통과했다.

삭제한 TTL·DELETE·객체 identity·ffprobe 메타데이터 계약의 테스트는 새 계약 검증으로 교체하거나 폐기했다. 기존에 없던 runner 기본 인자·음량 정규화 회귀 테스트는 구현 변경 전에 통과를 확인했다. 독립 코드 리뷰에서 수정이 필요한 회귀는 발견하지 못했다.

`2118b02`와 비교하면 운영 Java·TypeScript·Python 소스는 35개 파일·3,144줄에서 24개 파일·2,362줄로 줄었다. 테스트·설정·문서는 집계에서 제외했다. 새 의존성은 없다.

전체 자동 컨벤션 검사는 성공으로 처리하지 않았다. DB를 사용하지 않는 AI 서버에 적용되지 않는 JPA·Flyway·PostgreSQL·Testcontainers 필수 진단 4개와 검사기의 Java 구문 처리 오탐 2개가 남는다. 오탐은 여러 줄 상수를 일반 필드로 읽는 `JAVA-FIELD-BLANK`, text block의 URL을 주석으로 읽는 `TEST-GWT`다. 해당 필드 구분과 테스트 Given–When–Then은 직접 확인했다. Gradle 컴파일·Spotless·테스트는 통과했다.

실제 모델·S3·EC2 연결과 운영 배포는 검증하지 않았다. 다운로드 deadline 검증은 연결 stub 기반이므로 실제 소켓이 멈춘 환경의 정확한 종료 시간을 입증하지 않는다.

## 이전 검증

같은 날 `2118b02`의 컨벤션 리팩터링에서는 Java 53개·Node 9개·Python 5개 테스트와 빌드가 통과했고, 실제 모델 테스트 1개는 생략했다. 아래는 2026-10-12 저장소 분리 당시의 기록이며 현재 HTTP 계약을 설명하지 않는다.

2026-10-12 macOS에서 서버 빌드와 기존 회귀 테스트를 실행했습니다. 운영 Java 코드와 runner의 TypeScript·Python 코드는 기존 구현을 보존했습니다.

| 명령 | 결과 |
| --- | --- |
| `pnpm --dir runner install --frozen-lockfile` | 독립 의존성 설치 성공 |
| `pnpm --dir runner typecheck` | TypeScript 검사 통과 |
| `pnpm --dir runner test` | Node 9건·Python 5건 통과 |
| `./gradlew check bootJar --rerun-tasks` | 포맷·컴파일·JAR 생성 성공, Java 30건 통과·실제 모델 1건 생략 |

Java 테스트는 인증·HTTP 입력·작업 동시성·S3 stub·결과 불변식·fake engine·부모 JVM 강제 종료 후 프로세스 회수를 확인합니다. Node 테스트는 실제 runner 코드에 fake native engine을 연결합니다. 실제 모델 HTTP 테스트는 `AI_REAL_PIPELINE=true`를 제공하는 별도 실행이며, 이번 분리 검증에서는 실행하지 않았습니다.

Corepack이 저장소 루트에서 다른 기본 pnpm 버전을 선택하면 runner의 버전 선언과 충돌합니다. 루트 `package.json`에도 runner와 같은 `pnpm@10.33.0`을 지정했고, README의 루트 기준 명령으로 설치·타입 검사·테스트를 다시 확인했습니다.

## 기존 Knot 컨벤션 적용 범위

| 규칙군 | 이번 작업의 적용 |
| --- | --- |
| STACK | 기존 Java 25·Boot 4.1·Gradle·Spotless 보존 |
| LIB | 기존 Java 의존성 보존, runner의 기존 tsx·TypeScript·Node 타입 버전 고정 |
| LAYER | 기존 presentation/application/domain/infrastructure와 엔진 Port 보존 |
| MODEL | 기존 작업·결과 모델과 불변식 보존 |
| API | 기존 Bearer·생성/조회/삭제·오류·시간 계약 보존 |
| DB | AI 서버에는 DB가 없어 JPA·Flyway·PostgreSQL·Testcontainers 해당 없음 |
| JAVA | 테스트 3개의 runner 경로 수정, Spotless 검사 통과 |
| TEST | 기존 테스트 보존, 외부 서비스는 stub/fake 사용 |
| AI | 실제 토큰·녹음·모델·운영 설정을 게시 파일에서 제외 |
| DELIVERY | 사용자가 지정한 개인 신규 레포의 초기 `main` 커밋, 팀 Issue·PR·Governance 범위 밖 |

팀 컨벤션 00~08 원문 hash 검사는 통과했습니다. 기존 Knot 앱 서버용 자동 검사에는 독립 AI 서버와 맞지 않는 진단 5건이 남습니다. JPA·Flyway·PostgreSQL·Testcontainers 누락 4건과 `Map<UUID, Job>`을 복수 필드 선언으로 읽는 `JAVA-FIELD-ONE` 1건입니다. 해당 줄은 단일 `jobs` 필드 선언입니다.

공통 검증 스크립트는 기본·전체 실행 모두 이 진단 때문에 Gradle 전에 중단됩니다. 따라서 Gradle 검증은 위 명령으로 직접 실행했습니다. 자동 검사 전체 통과나 팀 컨벤션의 모든 의미 규칙 준수를 주장하지 않습니다. API 컨벤션은 사용자 승인본을 참고했으며 기존 HTTP 동작을 수정하지 않았습니다.

실제 S3·앱 서버 DB·모델 품질·운영 배포 검증과 GitHub CI는 이번 코드 게시 범위에 포함하지 않았습니다. 기존 원본 구현의 검증 기록은 이 저장소에서 새로 실행한 결과와 구분합니다.
