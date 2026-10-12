# 전사 작업 HTTP 계약

모든 경로는 `Authorization: Bearer <AI_APP_TOKEN>`을 요구합니다. 기본 주소는 `127.0.0.1:18765`입니다.

| 요청 | 응답 |
| --- | --- |
| `GET /health` | 준비되면 200 `{status:"UP",profileId}` |
| `POST /api/v1/transcription-jobs` | 202, Location 헤더, 작업 상태 |
| `GET /api/v1/transcription-jobs/{attemptId}` | 상태와 완료 결과 |
| `DELETE /api/v1/transcription-jobs/{attemptId}` | 204. 진행 중 작업 취소 또는 완료 결과 삭제 |

## 생성 본문

```json
{
  "attemptId": "00000000-0000-4000-8000-000000000001",
  "profileId": "local-whisper-diarization-v1",
  "requestValidUntil": "2099-01-01T00:00:00Z",
  "input": {
    "bucket": "example-bucket",
    "key": "recordings/example.webm",
    "versionId": null,
    "etag": "\"example-etag\"",
    "downloadUrl": "https://example-bucket.s3.ap-northeast-2.amazonaws.com/recordings/example.webm?X-Amz-Signature=EXAMPLE",
    "requiredHeaders": {"If-Match": "\"example-etag\""},
    "contentLength": 12345,
    "contentType": "audio/webm"
  },
  "speakerCount": null
}
```

위 값은 형식 예시입니다. 호출 시 `requestValidUntil`을 현재 시각 이후이면서 기본 24시간 이내로 설정하고, 실제 객체의 ETag·크기·presigned GET URL을 사용합니다. `speakerCount`가 없으면 화자 수를 자동 추정합니다.

versionId가 있으면 URL과 응답 version이 일치해야 합니다. 버전 없는 요청은 `If-Match`가 필수입니다. signed header의 `host`는 URL host와 일치할 때만 허용하고 HTTP 구현이 직접 생성합니다. 임의 호스트·경로 불일치·리다이렉트·별도 인증 헤더는 거부합니다. 다운로드 전에 HEAD를 다시 해 입력을 바꾸지 않습니다.

## 조회와 결과

조회 본문은 `{attemptId,status,failureCode,result}`입니다. 상태는 `PROCESSING`, `SUCCEEDED`, `FAILED`, `EXPIRED`이며 성공 이외의 `result`는 null입니다.

```json
{
  "schemaVersion": 1,
  "rawWhisperJson": "{\"transcription\":[]}",
  "utterances": [
    {"ord":0,"speakerLabel":"speaker_0","startSec":0,"endSec":1,"text":"안녕하세요"}
  ]
}
```

`result`의 `ord`는 0부터 연속됩니다. 원본은 UTF-8 문자열로 보존하고 JSON 객체로 재작성하지 않습니다. 원본만 있거나 유효한 발화가 없으면 성공으로 처리하지 않습니다.

## 중복·용량·만료

동시에 활성 작업 하나, 대기열 0입니다. 삭제되지 않은 성공 결과 하나가 남아 있어도 다음 작업에 409 `BUSY`를 반환합니다. 앱 서버가 DB 저장 후 DELETE하거나 결과 TTL이 지나면 다음 작업을 받습니다.

같은 attempt·객체 identity·화자 수·profile은 기존 작업을 반환합니다. URL·서명은 identity에 포함하지 않습니다. 같은 attempt에 다른 입력이면 409 `IDEMPOTENCY_CONFLICT`입니다. 삭제·TTL 후 같은 유효 요청에는 `EXPIRED`를 반환하고 재실행하지 않습니다. 종료 기록은 `requestValidUntil`까지 최대 1,024개를 유지하며 상한에서도 BUSY입니다.

상태는 메모리에 있습니다. 앱 서버는 재시작 후 unknown job을 실패로 정리하고 같은 attempt POST를 반복하지 않아야 합니다.

오디오 상한은 524,288,000바이트(500MiB), 디코딩한 길이는 7,200초입니다. 원본과 발화를 담은 단일 결과 파일 상한은 256MiB입니다. 기본 제한 시간은 connect 10초·read 30초·다운로드 15분·각 변환 15분·추론 12시간, 결과 보존 1시간, 요청 유효 기간 상한 24시간입니다. `application.yml`의 대응 환경 변수로 조정하며 제품 SLA를 뜻하지 않습니다.

## 실행과 정리

S3 다운로드 → ffprobe → 크기가 제한된 FFmpeg PCM16 mono 16kHz 변환 → 실제 PCM 길이 검사 → runner 순서로 실행합니다. MediaRecorder WebM의 `duration=N/A`는 PCM 길이로 검증합니다. 2시간을 초과한 오디오를 잘라 성공으로 처리하지 않습니다.

Python supervisor가 JVM 시작 identity를 감시하고 소유한 native 프로세스 그룹을 정리합니다. timeout·cancel 후 정리가 끝나기 전에는 실행 슬롯을 풀지 않습니다. 다음 시작 때 잔존 작업을 회수하며, 소유권을 증명할 수 없으면 시작 실패 또는 health 503으로 준비 상태를 닫습니다.

전용 work directory의 UUID 작업만 정리합니다. 토큰·서명 URL·녹음·발화 원문을 로그에 출력하지 않고, API에는 정적 한글 오류 메시지를 반환합니다.
