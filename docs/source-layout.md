# 소스 구성과 실행 흐름

Spring은 HTTP 접수와 현재 작업 하나를 관리합니다. 실제 모델 실행은 TypeScript runner가 맡고, Python supervisor가 프로세스 종료를 감독합니다. EC2의 녹음 권한 검사·S3 URL 발급·DB 저장과 SSH·LaunchAgent 설정은 이 저장소 밖의 책임입니다.

## Java 계층

| 계층 | 책임 |
| --- | --- |
| Presentation | 요청 검증, 접수·조회·health의 HTTP 상태와 헤더 |
| Application | 현재 `JobSnapshot`, 단일 worker, 접수와 정리 순서 |
| Domain | 전사 결과·발화의 불변식, 전사 오류 코드 |
| Infrastructure | S3 다운로드, PCM 변환·검사, runner·supervisor 호출 |
| Global | 설정, Bearer 인증, 오류 응답 |

Controller와 Service는 `TranscriptionRequest`와 `JobSnapshot`을 그대로 주고받습니다. 같은 필드의 DTO·Command·Response 변환 계층은 두지 않습니다. Service는 잠금 안에서 현재 작업과 worker를 확인하고, 정리까지 끝난 후 완료 상태를 공개합니다.

`TranscriptionEngine`은 모델 실행이라는 외부 I/O 경계이자 Service 테스트의 대체 지점입니다. 구현체 `HeadlessTranscriptionEngine`이 다운로드 → PCM 변환·검사 → runner 실행 → 결과 읽기를 연결합니다. `S3AudioDownloader`, `AudioInspector`, `ProcessSupervisor`는 해당 기술 책임만 맡으며 각 helper의 인터페이스는 만들지 않습니다.

## Runner와 감독 프로세스

`runner/scripts/serverPipeline.ts`는 음량 정규화 후 Whisper와 화자 분리를 병렬 실행하고 결과를 합칩니다. 한글 바이트 복원, VAD 시간 보정, RMS 정규화와 화자별 발화 병합 로직을 유지합니다. 모델 경로와 스레드 수는 서버의 프로필 파일에서 읽으며 요청마다 프로필을 고르지 않습니다.

Java의 `ProcessSupervisor`가 Python `serverSupervisor.py`를 실행합니다. Python은 실행 제한 시간과 부모 JVM을 감시합니다. 프로세스 소유권 기록은 재시작 때 이전 모델을 정리하기 위한 것이며 작업 결과를 복구하는 저장소가 아닙니다. TypeScript는 종료 신호, 한쪽 모델 실패와 출력 상한 초과 시 나머지 자식을 정리합니다.

현재 HTTP 계약은 [전사 작업 HTTP 계약](http-contract.md), 구조를 줄인 이유는 [최소화 리뷰 안내](convention-refactor.md)에 있습니다.

## 소스 출처

기존 Knot 맥미니 프로토타입에서 서버와 필요한 runner 파일을 분리한 뒤, 독립 실행에 맞게 수정했습니다. Electron 앱이나 별도 workspace 설치는 필요하지 않습니다.

| 현재 경로 | 기존 프로토타입 경로 |
| --- | --- |
| 저장소 루트의 Gradle·`src/`·`config/` | `meeting-stt-prototype/apps/ai-server/` |
| `runner/scripts/` | `meeting-stt-prototype/apps/desktop/scripts/`의 서버 실행·감독 코드 |
| `runner/src/main/pipeline/` | `meeting-stt-prototype/apps/desktop/src/main/pipeline/`의 whisper·diarize·normalize |
| `runner/src/core/merge.ts`, `types.ts` | `meeting-stt-prototype/packages/core/src/`의 merge·types |

전사 parser·음량 정규화·발화 병합은 [meeting-stt](https://github.com/geongyu09/meeting-stt) 기반 프로토타입에서 가져왔습니다. 기존 core의 음량 정규화 함수는 현재 `runner/src/main/pipeline/normalize.ts`에 합쳤습니다. 독립 서버·runner·supervisor는 Knot 로컬 구현입니다. 외부 도구·모델 출처는 [별도 문서](../THIRD_PARTY_NOTICES.md)에 기록했습니다.
