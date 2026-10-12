# 독립 저장소 분리 검증

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
