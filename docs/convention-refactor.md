# AI 서버 컨벤션 정리

## 범위와 완료 조건

2026-10-13 기준 `cf2204c`의 AI 서버를 Knot 백엔드 컨벤션에 맞춘다. 기존 HTTP 계약과 모델 실행 동작을 회귀 테스트로 유지하며, 작업 단위 커밋으로 나눠 리뷰할 수 있게 한다.

## 수정 순서

1. 기존 Java·runner 테스트로 기준 동작을 확인하고, 상태 전이·인증·오류·취소에서 부족한 회귀 검증을 먼저 추가한다.
2. 작업의 상태 전이·멱등성·결과 만료 규칙은 Domain으로 옮긴다. Application은 단일 실행 슬롯과 스레드 생명주기를 조정한다. Domain에는 Application DTO·Thread·Spring 의존성을 두지 않는다.
3. 오류 코드·한글 메시지·응답 상태를 한 계약으로 관리한다. 외부 runner 오류 문자열은 Infrastructure 경계에서 알려진 코드로 변환한다.
4. HTTP Request·Response와 Application 입력·결과의 책임을 구분한다. 경로·필드·상태·인증 규칙은 유지하고 검증 메시지를 한글로 명시한다.
5. 모델 실행 어댑터의 프로세스 감독, 오디오 변환·길이 검사 등 독립적인 기술 책임을 분리한다. 취소·정리 완료 전 슬롯 해제 금지와 소유권 검사는 유지한다.
6. Java 작성 규칙과 테스트 형식을 정리하고 전체 검사·독립 리뷰를 수행한다. 코드 읽는 순서와 실행 흐름 문서를 갱신한다.

## 보존할 동작

- 활성 전사 1개, 대기열 0, 보관 중인 성공 결과가 있으면 BUSY
- 동일 attempt의 입력 일치 검사, 결과 삭제·만료 후 재실행 방지, 메모리 상태의 재시작 경계
- 인증 필수, S3 호스트·객체 identity·크기 검증, 원문과 인증값 비노출
- 모델·runner·supervisor 계약과 종료·강제 종료·잔존 프로세스 정리
- 빈 발화 결과 거부를 포함한 기존 HTTP 계약. 정상 빈 전사 정책은 EC2 연결 단계에서 별도 조율

## 컨벤션 적용 장부

| 규칙 | 적용 범위 |
| --- | --- |
| STACK | Java 25·Spring Boot 4.1·Gradle 유지 |
| LIB | 새 의존성 없음. OpenAPI 라이브러리 도입은 이번 범위에서 제외 |
| LAYER | Domain 상태 규칙, Application 조정, Infrastructure I/O, Presentation HTTP |
| MODEL | Domain과 HTTP 응답 분리, 값·컬렉션 불변성 |
| API | 기존 계약 보존, 코드 기반 오류, 한글 Validation, 내부 정보 비노출 |
| DB | 해당 없음. 기존 메모리 작업 관리 유지, DB·migration 추가 없음 |
| JAVA | 명시적 import·이름·상수·멤버 순서·120자·인자 줄바꿈·Spotless |
| TEST | Domain 직접 검증, Service 동시성, MVC 계약, subprocess·S3 stub 검증 |
| AI | 직접 코드 검토와 동작 검증, 비밀값 복사 금지 |
| DELIVERY | 사용자 요청에 따라 작업 단위 커밋과 main 푸시. GitHub Issue·PR·배포는 범위 밖 |

기준은 Knot `노션/knot/백엔드`의 00~08 원문과 승인된 Java/API 컨벤션이다. DB·FE-BE 전용 규칙은 AI 서버 역할에 맞는 범위만 적용한다. 새 아키텍처 프레임워크나 모든 클래스의 인터페이스화를 도입하지 않는다.

## 코드 읽는 순서

1. `transcription/presentation/TranscriptionJobController`: 요청을 Command로 변환하고 Application 결과를 HTTP Response로 반환한다.
2. `transcription/application/TranscriptionJobService`: 단일 실행 슬롯, worker thread, 주기적 만료 정리를 조정한다. Command와 Snapshot은 각각 `application/dto/command`, `application/dto/result`에 둔다.
3. `transcription/domain/TranscriptionJob`: 상태 전이, 동일 입력 판정, 결과 만료와 제거 조건을 맡는다. 실행 중인 스레드와 Command는 Application 내부 `RunningJob`이 소유한다.
4. `transcription/infrastructure/HeadlessTranscriptionEngine`: 다운로드·오디오 검사·변환·추론·결과 읽기 순서를 조정한다. `AudioInspector`는 오디오 검사를, `ProcessSupervisor`는 프로세스 실행·종료·소유권 정리를 맡는다.
5. `global/exception`과 `transcription/domain/JobErrorCode`: 공통 오류 응답과 전사 오류 코드를 확인한다.

리뷰는 API/DTO → Domain/Service → Infrastructure 순으로 나눠 볼 수 있다. 각 영역의 회귀 테스트는 같은 패키지의 `src/test/java`에 있다. 별도 DB, 가짜 Repository, 기술 helper용 인터페이스는 추가하지 않았다. 기존 `TranscriptionEngine`은 외부 모델 실행 경계로 유지했다.

## 동작상 확인할 변경

- runner 진단의 `CANCELLED`는 서버가 쓰는 `CANCELED`로 통일했다. 알려지지 않은 진단 문자열은 그대로 반환하지 않고 `INFERENCE_FAILED`로 변환한다.
- null 발화를 포함한 결과는 `NullPointerException` 대신 `INVALID_RESULT`로 거부한다. 개별 발화의 불변식은 `Utterance` 생성 시 확인한다.
- 누락된 Validation 메시지를 한글로 명시했다. 생성 202·삭제 204, 응답 필드와 상태 이름, 원본 전사 JSON 문자열, Bearer 인증 실패 응답은 유지했다.

## 검증 기록

2026-10-13 로컬 검증 결과다.

| 검사 | 결과 |
| --- | --- |
| `./gradlew check bootJar --rerun-tasks` | 성공. 후속 포맷·Validation 문구 수정 후 `check bootJar`도 성공 |
| Java 테스트 | 53개 통과, 실제 모델 테스트 1개 생략, 실패 0개 |
| `pnpm --dir runner typecheck` | 성공 |
| `pnpm --dir runner test` | Node 9개·Python 5개 통과 |
| `git diff --check` | 성공 |
| 컨벤션 원문 00~08 hash 검사 | 통과 |

작업 취소 후 cleanup 완료 전 슬롯 유지, 취소 후 늦은 성공 무시, 결과 TTL, 멱등성 충돌, HTTP 응답 형태, S3 요청 검증, runner 오류 코드 변환을 확인했다. JVM 종료 후 자식 프로세스 회수 테스트도 포함한다.

독립 코드 리뷰에서도 수정이 필요한 회귀 문제는 발견하지 못했다. Service·Controller·Engine·Downloader 핵심 테스트를 별도로 실행해 통과했다. 이 결과는 실제 모델이나 운영 연결의 성공을 보장하지 않는다.

앱 서버용 컨벤션 검사에는 AI 서버에 해당하지 않는 DB 필수 항목 4개와 `Map<UUID, RunningJob>` 단일 필드를 복수 선언으로 읽는 오탐 1개가 남는다. `verify_backend.py --full`은 Knot workspace 밖의 독립 AI 저장소를 탐색하지 못해 중단되므로 Gradle을 직접 실행했다. 자동 검사 전체 통과를 뜻하지 않는다.

실제 모델·실제 S3·EC2 연결은 검증하지 않았다. 빈 전사 처리 정책과 EC2의 전사 요청·결과 저장·삭제 재시도는 후속 연결 작업에서 조율한다.
