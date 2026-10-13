# Knot AI Server

맥미니에서 S3 녹음을 내려받아 Whisper 전사와 화자 분리를 실행합니다. Java/Spring이 현재 작업 하나를 관리하고, TypeScript runner가 native 모델을 호출합니다. EC2 앱 서버는 작업을 요청하고 결과를 조회해 DB에 저장합니다.

현재 상태와 마지막 결과는 메모리에만 보관합니다. 다음 작업을 받으면 이전 결과를 교체하고, 서버를 재시작하면 상태와 결과를 잃습니다. EC2는 **결과를 저장한 뒤 다음 작업을 요청**해야 합니다.

## 설치와 기본 검증

Java 25, Node.js 22 이상, pnpm 10.33.0, Python 3이 필요합니다. Gradle은 저장소의 Wrapper를 사용합니다. 프로세스 감독에는 POSIX 프로세스 그룹과 `ps`가 필요하며, 기본 테스트 대상은 macOS입니다.

저장소 루트에서 실행합니다. runner 실행에 `tsx`가 필요하므로 개발 의존성도 설치합니다.

```sh
pnpm --dir runner install --frozen-lockfile
pnpm --dir runner typecheck
pnpm --dir runner test
./gradlew check bootJar
```

기본 테스트는 실제 S3·모델 없이 HTTP 계약, 작업 동시성, 오디오 처리와 subprocess 정리를 확인합니다. 실제 모델 HTTP 테스트는 별도 설정을 제공한 경우에만 실행합니다. 실행 결과와 검증 범위는 [검증 기록](docs/verification.md)을 참고합니다.

## 실제 모델로 실행

FFmpeg, `whisper-cli`, sherpa-onnx 화자 분리 실행 파일과 모델 파일을 준비합니다. 이 저장소는 모델이나 native 실행 파일을 자동 설치하지 않습니다. [외부 실행 도구와 모델](THIRD_PARTY_NOTICES.md)에 출처를 기록했습니다.

1. [프로필 예시](examples/profile.example.json)를 저장소 루트의 `profile.json`으로 복사합니다. 모델·바이너리를 가리키는 절대 경로와 두 모델의 스레드 수를 설정합니다.
2. [.env.example](.env.example)을 `.env`로 복사합니다. 인증 토큰과 허용할 S3 호스트를 설정하고, EC2에도 같은 토큰을 전달합니다.
3. 저장소 루트에서 환경 변수를 읽고 JAR를 실행합니다. Spring은 `.env`를 자동으로 읽지 않습니다.

```sh
set -a
. ./.env
set +a
java -jar build/libs/knot-ai-server-0.0.1-SNAPSHOT.jar
```

`AI_RUNNER_DIRECTORY`에는 `runner/`의 절대 경로를, `AI_WORK_DIRECTORY`에는 이 서버 전용 작업 디렉터리를 지정합니다. `.env.example`은 저장소 루트에서 읽는 것을 전제로 경로를 설정합니다.

기본 주소는 `127.0.0.1:18765`입니다. 모든 API에 `Authorization: Bearer <AI_APP_TOKEN>`이 필요합니다. 준비 상태는 `GET /health`의 200 응답 `{"status":"UP"}`으로 확인합니다. 이 설정만으로 EC2와의 네트워크 연결이 구성되지는 않습니다.

## 요청과 코드 읽기

`POST /api/v1/transcription-jobs`에 `attemptId`, `downloadUrl`, 선택적인 `speakerCount`를 전달합니다. 반환된 Location으로 GET을 반복해 결과를 조회합니다. 요청 형식, 재시도와 결과 교체 규칙은 [HTTP 계약](docs/http-contract.md)에 있습니다.

| 읽는 순서 | 책임 |
| --- | --- |
| [TranscriptionJobController](src/main/java/com/knot/ai/transcription/presentation/TranscriptionJobController.java) | 접수·조회·health HTTP API |
| [TranscriptionJobService](src/main/java/com/knot/ai/transcription/application/TranscriptionJobService.java) | 현재 작업 하나, 실행 스레드와 정리 순서 |
| [HeadlessTranscriptionEngine](src/main/java/com/knot/ai/transcription/infrastructure/HeadlessTranscriptionEngine.java) | 다운로드 → PCM 변환·검사 → runner → 결과 읽기 |
| [serverPipeline.ts](runner/scripts/serverPipeline.ts) | 음량 정규화, 전사·화자 분리 병렬 실행, 발화 병합 |
| [serverSupervisor.py](runner/scripts/serverSupervisor.py) | 실행 제한 시간, 부모 JVM 감시와 소유 프로세스 정리 |

계층과 출처는 [소스 구성](docs/source-layout.md), 삭제한 구조와 남긴 책임은 [최소화 리뷰 안내](docs/convention-refactor.md)를 참고합니다.

## 실제 모델 HTTP 테스트

아래 테스트는 Spring HTTP·FFmpeg·runner·설치된 모델을 실행하고 S3 전송만 stub으로 대체합니다. `AI_REAL_AUDIO`에는 테스트용 한국어 WebM 녹음을 지정합니다.

```sh
AI_REAL_PIPELINE=true \
AI_REAL_PROFILE=/absolute/path/profile.json \
AI_REAL_AUDIO=/absolute/path/test.webm \
./gradlew test --tests '*RealPipelineHttpTest' --rerun-tasks
```

실제 S3·EC2 연결, DB 저장과 전사 품질은 별도 검증 대상입니다.
