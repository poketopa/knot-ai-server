# AI 서버 최소화 리뷰 안내

Java/Spring을 유지하면서 모델 실행과 전사 결과 전달에 필요한 코드만 남깁니다. EC2 연결 전이므로 HTTP 계약을 바꾸고, 현재 작업 하나를 메모리에만 보관하기로 사용자가 결정했습니다. 재시작 시 EC2가 재요청하며, 과거 작업 조회·결과 복구는 구현하지 않습니다.

## 삭제한 책임

| 삭제한 구조 | 현재 방식 |
| --- | --- |
| 작업 Map, Domain 작업 aggregate, identity 비교 | 현재 `JobSnapshot` 하나와 worker 하나 |
| 결과 TTL, 주기적 정리, 종료 기록, 요청 만료 | 다음 작업 접수 때 결과 교체 |
| DELETE, 취소·만료 상태 | POST 접수와 GET 조회, 세 가지 상태 |
| Request → Command → Response 복제 | `TranscriptionRequest`와 `JobSnapshot` 공유 |
| bucket·key·ETag·version·요청 헤더 계약 | presigned URL 하나. 불변 객체 발급은 EC2 책임 |
| 요청 profileId와 프로필 협상 | 서버에 설정된 모델 사용 |
| ffprobe 선검사 | 디코딩한 PCM의 실제 형식·길이 검사 |
| runner 병렬 옵션, 진행률 parser, 별도 timeout 타이머 | 항상 병렬 실행, Python이 제한 시간 감독 |
| 단일 구현용 공통 오류 인터페이스와 여러 파일의 오류 DTO | 전사 오류 enum과 작은 공통 오류 record |

EC2는 결과를 저장한 뒤 다음 작업을 보내야 합니다. 현재 ID의 재요청은 기존 결과를 반환하지만 교체된 ID에는 이 보장이 없습니다. 구체적인 응답과 재시도 조건은 [HTTP 계약](http-contract.md)을 기준으로 합니다.

## 남긴 책임

- `TranscriptionEngine`: 실제 모델 I/O를 대체해 Service의 동시성과 실패를 검증하는 경계입니다.
- 다운로드 제한과 PCM 검사: 잘못된 입력, 끝나지 않는 전송, 크거나 긴 녹음을 모델 실행 전에 거부합니다.
- Python supervisor와 소유권 기록: JVM이 사라져도 native 모델을 정리하고 다음 실행과의 중복을 막습니다. 메모리 상태만 쓰더라도 필요합니다.
- 결과 검증과 모델 처리 로직: 원본 JSON, 발화 순서·시간·텍스트의 일관성, 한글 복원·음량·화자 병합을 유지합니다.

파일 수를 줄이려고 서로 다른 I/O 책임까지 한 클래스에 합치지 않았습니다. 새로운 의존성은 추가하지 않습니다.

## 리뷰 순서

1. Controller의 작은 요청·응답 계약과 Service의 현재 ID·BUSY·결과 교체 규칙을 확인합니다.
2. Service가 cleanup 전에 다음 실행을 허용하지 않는지, 정리 실패·종료 뒤 접수를 막는지 확인합니다.
3. Downloader의 URL·바이트 제한, PCM 길이 검사와 supervisor의 소유 프로세스 정리를 확인합니다.
4. runner의 한글·시간·화자 처리와 실제 subprocess 테스트를 확인합니다.

관련 테스트는 Java 소스와 같은 패키지의 `src/test/java`, `runner/scripts/serverPipeline.test.ts`, `runner/scripts/serverSupervisor.test.py`에 있습니다. 실행한 검사와 미검증 범위는 [검증 기록](verification.md)에 모읍니다.

## 컨벤션 적용 범위

기준은 Knot 백엔드 00~08 원문과 승인된 Java/API 컨벤션입니다. AI 서버 역할과 사용자가 확정한 최소화 범위에 맞춰 적용합니다.

| 규칙 | 적용 |
| --- | --- |
| STACK·LIB | Java 25·Spring Boot·Gradle 유지, 새 의존성 없음 |
| LAYER·MODEL | HTTP·작업 조정·결과 불변식·I/O 경계 유지. 중복 입력·출력 DTO 생략 |
| API | 사용자 승인으로 계약 변경, Bearer 인증·오류 코드·한글 검증·원문 비노출 유지 |
| DB | 해당 없음. DB·Repository·migration 없음 |
| JAVA | 명시적 import·이름·상수·줄바꿈·Spotless 적용 |
| TEST | 결과 불변식, Service 동시성, MVC 계약, S3 stub과 subprocess 검증 |
| AI | 실제 diff와 검증 증거로 리뷰, 비밀값 복사 금지 |
| DELIVERY | 사용자 요청에 따라 작업 단위 커밋·푸시. Issue·PR·배포는 범위 밖 |
