# 외부 실행 도구와 모델

이 저장소에는 native 실행 파일과 모델 파일을 포함하지 않습니다. 아래는 기존 Knot 프로토타입에서 사용한 도구·모델의 출처 기록입니다. 실제 사용할 파일과 배포 조건은 해당 upstream 문서를 확인합니다.

| 구성 | 기존 사용 버전 또는 파일 | 출처 |
| --- | --- | --- |
| Whisper 실행 파일 | whisper.cpp v1.8.4 | [whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/v1.8.4) |
| 화자 분리 실행 파일 | sherpa-onnx v1.13.6 | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.6) |
| Whisper 모델 | `ggml-large-v3-q5_0.bin` | [Whisper GGML](https://huggingface.co/ggerganov/whisper.cpp) |
| VAD 모델 | `ggml-silero-v5.1.2.bin` | [Whisper VAD](https://huggingface.co/ggml-org/whisper-vad) |
| Segmentation 모델 | pyannote segmentation 3.0 ONNX | [sherpa segmentation](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models), [upstream 모델](https://huggingface.co/pyannote/segmentation-3.0) |
| Embedding 모델 | 3D-Speaker ERes2Net 16k ONNX | [sherpa speaker models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models), [3D-Speaker](https://github.com/modelscope/3D-Speaker) |

FFmpeg는 오디오 변환에 사용하며 시스템에 별도로 설치합니다. 실행 파일과 필요한 동적 라이브러리는 현재 시스템에 맞는 빌드를 준비합니다.

TypeScript 의존성은 `runner/package.json`과 `runner/pnpm-lock.yaml`, Java 의존성은 `build.gradle`에서 확인합니다. 이 문서는 외부 도구나 모델에 저장소 코드의 라이선스를 새로 부여하지 않습니다.
