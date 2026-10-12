# 소스 구성과 분리 범위

Knot 맥미니 AI 서버의 기존 구현을 개인 저장소에서 읽고 검증할 수 있도록 분리했습니다. Spring 서버의 운영 Java 코드는 분리 시 수정하지 않았습니다. Java 테스트 3개는 supervisor와 실제 runner를 이 저장소의 `runner/`에서 찾도록 경로를 바꿨습니다.

| 현재 경로 | 기존 프로토타입 경로 |
| --- | --- |
| 저장소 루트의 Gradle·`src/`·`config/` | `meeting-stt-prototype/apps/ai-server/` |
| `runner/scripts/` | `meeting-stt-prototype/apps/desktop/scripts/`의 serverPipeline·serverSupervisor와 해당 테스트 |
| `runner/src/main/pipeline/` | `meeting-stt-prototype/apps/desktop/src/main/pipeline/`의 whisper·diarize·normalize |
| `runner/src/core/` | `meeting-stt-prototype/packages/core/src/`의 merge·normalize·types |

TypeScript·Python 코드는 원본을 보존했습니다. `runner/tsconfig.json`은 기존 `@meeting-stt/core/*`와 `@shared/types`를 로컬 core 파일로 연결합니다. Electron 앱이나 별도 workspace 설치가 필요하지 않습니다.

전사 parser·음량 정규화·발화 병합 코드는 [meeting-stt](https://github.com/geongyu09/meeting-stt) 기반 프로토타입에서 가져왔습니다. 독립 서버·runner·supervisor는 Knot 로컬 구현입니다. 외부 도구·모델은 [출처 문서](../THIRD_PARTY_NOTICES.md)에 구분했습니다.

현재 저장소는 AI HTTP 서버 범위입니다. 앱 서버의 녹음 권한 검사·S3 URL 발급·DB 결과 저장과 운영용 SSH·LaunchAgent 설정은 포함하지 않습니다. 프로필·환경 변수 예시에는 실제 운영값을 사용하지 않습니다.
