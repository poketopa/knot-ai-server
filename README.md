# Knot AI Server

S3에 저장된 녹음을 내려받아 Whisper 전사와 화자 분리를 실행하는 서버입니다. Spring Boot가 HTTP 작업과 실행 상태를 관리하고, TypeScript runner가 native 모델을 호출합니다. 결과를 DB에 저장하는 책임은 호출하는 앱 서버에 있습니다.

기존 Knot 맥미니 구현을 별도 저장소로 분리했습니다. 서버와 runner의 소스·테스트를 포함하며, 모델·native 바이너리·녹음·운영 설정은 별도로 준비합니다.

## 코드 읽는 순서

| 파일 | 책임 |
| --- | --- |
| [TranscriptionJobController](src/main/java/com/knot/ai/transcription/presentation/TranscriptionJobController.java) | 작업 생성·조회·삭제 HTTP API |
| [TranscriptionJobService](src/main/java/com/knot/ai/transcription/application/TranscriptionJobService.java) | 단일 실행 슬롯, 중복 요청, 취소, 결과 만료 |
| [HeadlessTranscriptionEngine](src/main/java/com/knot/ai/transcription/infrastructure/HeadlessTranscriptionEngine.java) | 다운로드·오디오 변환·runner 실행과 정리 |
| [S3AudioDownloader](src/main/java/com/knot/ai/transcription/infrastructure/S3AudioDownloader.java) | S3 URL 검증과 고정된 객체 다운로드 |
| [serverPipeline.ts](runner/scripts/serverPipeline.ts) | 음량 정규화, 전사, 화자 분리, 발화 병합 |
| [serverSupervisor.py](runner/scripts/serverSupervisor.py) | 부모 JVM 감시와 소유한 프로세스 그룹 정리 |

API 입력·상태·제약은 [HTTP 계약](docs/http-contract.md), 코드 출처는 [소스 구성](docs/source-layout.md), 분리 시 확인한 범위는 [검증 기록](docs/verification.md)에 있습니다.

## 설치와 기본 검증

필요한 도구는 Java 25, Node.js 22 이상, pnpm 10.33.0, Python 3입니다. Gradle은 저장소의 Wrapper를 사용합니다. 기본 테스트는 macOS에서 검증하며 실제 S3·모델 없이 실행합니다. 프로세스 감독에는 POSIX 프로세스 그룹과 `ps`가 필요합니다.

저장소 루트에서 실행합니다. runner 설치 시 `tsx`를 포함한 개발 의존성도 필요합니다.

```sh
pnpm --dir runner install --frozen-lockfile
pnpm --dir runner typecheck
pnpm --dir runner test
./gradlew check bootJar
```

Gradle 검증에는 HTTP·동시성·S3 stub·실제 subprocess 감독 테스트가 포함됩니다. 실제 모델 HTTP 테스트는 설정을 제공한 경우에만 실행합니다.

## 실제 모델로 실행

FFmpeg·ffprobe, `whisper-cli`, sherpa-onnx 화자 분리 실행 파일과 프로필에서 지정한 모델 파일을 먼저 준비합니다. 자동 다운로드나 설치는 하지 않습니다. 모델 출처는 [외부 실행 도구와 모델](THIRD_PARTY_NOTICES.md)을 참고합니다.

1. [프로필 예시](examples/profile.example.json)를 저장소 루트의 `profile.json`으로 복사하고 모델·바이너리 경로와 프로필 ID를 실제 값으로 바꿉니다. 경로는 모두 절대 경로여야 합니다.
2. [.env.example](.env.example)을 `.env`로 복사하고 인증 토큰·S3 버킷·허용 호스트·객체 접두사를 설정합니다. 앱 서버에도 같은 토큰을 전달합니다.
3. 저장소 루트에서 환경 변수를 읽고 JAR를 실행합니다. Spring은 `.env`를 자동으로 읽지 않습니다.

```sh
set -a
. ./.env
set +a
java -jar build/libs/knot-ai-server-0.0.1-SNAPSHOT.jar
```

`AI_DESKTOP_DIRECTORY`는 기존 환경 변수 이름을 유지한 것으로, 이 저장소에서는 **`runner/`의 절대 경로**를 지정합니다. `.env.example`은 저장소 루트에서 읽는 것을 전제로 이 경로를 설정합니다. `AI_WORK_DIRECTORY`는 작업용 새 디렉터리로 지정합니다.

기본 서버 주소는 `127.0.0.1:18765`입니다. `GET /health`에도 `Authorization: Bearer <AI_APP_TOKEN>`이 필요하며, 준비되면 `status: UP`과 프로필 ID를 반환합니다.

## 실제 모델 HTTP 테스트

아래 테스트는 실제 Spring HTTP·FFmpeg·runner·모델을 실행하고, S3 전송만 stub으로 대체합니다. `AI_REAL_AUDIO`에는 테스트용 WebM 파일을 지정합니다.

```sh
AI_REAL_PIPELINE=true \
AI_REAL_PROFILE=/absolute/path/profile.json \
AI_REAL_AUDIO=/absolute/path/test.webm \
./gradlew test --tests '*RealPipelineHttpTest' --rerun-tasks
```

실제 S3·앱 서버 DB·배포 환경과 전사 품질은 별도 검증 대상입니다. 작업 상태는 메모리에 있으므로 재시작을 넘는 중복 실행 방지를 보장하지 않습니다.
