# 소스 구성과 분리 범위

Knot 맥미니 AI 서버의 기존 구현을 개인 저장소에서 읽고 검증할 수 있도록 분리했습니다. 2026-10-12 분리 당시 운영 Java 코드는 유지했고, Java 테스트 3개는 이 저장소의 `runner/`를 찾도록 경로를 바꿨습니다. 2026-10-13에는 아래 계층 경계를 기준으로 Java 코드의 책임을 정리했습니다.

| 현재 경로 | 기존 프로토타입 경로 |
| --- | --- |
| 저장소 루트의 Gradle·`src/`·`config/` | `meeting-stt-prototype/apps/ai-server/` |
| `runner/scripts/` | `meeting-stt-prototype/apps/desktop/scripts/`의 serverPipeline·serverSupervisor와 해당 테스트 |
| `runner/src/main/pipeline/` | `meeting-stt-prototype/apps/desktop/src/main/pipeline/`의 whisper·diarize·normalize |
| `runner/src/core/` | `meeting-stt-prototype/packages/core/src/`의 merge·normalize·types |

TypeScript·Python 코드는 원본을 보존했습니다. `runner/tsconfig.json`은 기존 `@meeting-stt/core/*`와 `@shared/types`를 로컬 core 파일로 연결합니다. Electron 앱이나 별도 workspace 설치가 필요하지 않습니다.

전사 parser·음량 정규화·발화 병합 코드는 [meeting-stt](https://github.com/geongyu09/meeting-stt) 기반 프로토타입에서 가져왔습니다. 독립 서버·runner·supervisor는 Knot 로컬 구현입니다. 외부 도구·모델은 [출처 문서](../THIRD_PARTY_NOTICES.md)에 구분했습니다.

현재 저장소는 AI HTTP 서버 범위입니다. 앱 서버의 녹음 권한 검사·S3 URL 발급·DB 결과 저장과 운영용 SSH·LaunchAgent 설정은 포함하지 않습니다. 프로필·환경 변수 예시에는 실제 운영값을 사용하지 않습니다.

## 현재 Java 구조

| 계층 | 책임 |
| --- | --- |
| Presentation | 요청 형식 검사, Application 결과를 Response DTO로 변환, HTTP 상태·헤더 |
| Application | 작업 접수·조회·삭제, 단일 실행 슬롯, 스레드와 정리 순서 |
| Domain | 작업 identity, 상태 전이·만료, 전사 결과·발화의 불변식, 타입으로 정의한 오류 |
| Infrastructure | S3 전송, 오디오 검사, runner와 Python supervisor 실행 |
| Global | 설정, Bearer 인증, 공통 오류 응답 |

서비스는 `TranscriptionEngine` Port를 호출합니다. 구현체인 `HeadlessTranscriptionEngine`은 다운로드·오디오 처리·프로세스 감독을 조합합니다. 기술 helper마다 인터페이스를 추가하지 않았습니다.

작업의 상태와 결과는 메모리에 있고, 서비스가 같은 잠금 안에서 슬롯 확보와 상태 접근을 조정합니다. `TranscriptionJob`은 스레드나 Spring을 알지 않으며 현재 시각을 인자로 받아 규칙을 판단합니다. 모델 결과가 늦게 도착해도 취소·만료된 작업을 성공으로 되돌리지 않습니다.

`ProcessSupervisor`는 Python 감독 프로세스를 실행하는 Java 어댑터이고, 실제 프로세스 그룹 소유권과 부모 JVM 감시는 기존 `serverSupervisor.py`가 담당합니다. 결과가 성공이어도 후처리 정리가 끝나기 전에는 새 모델 실행을 시작하지 않습니다.
