/**
 * 파이프라인 중간 산출물 타입. 데스크탑 앱(whisper.cpp + sherpa-onnx)과
 * 브라우저 앱(transformers.js)이 같은 모양으로 주고받아야 병합 로직을 공유할 수 있다.
 * 회의·설정 같은 제품 타입은 앱마다 다르므로 각 앱의 `src/shared/types.ts`에 둔다.
 * 모든 시간 값은 초(sec)다.
 */

export interface SttWord {
  start: number
  end: number
  text: string
}

export interface SttSegment {
  start: number
  end: number
  text: string
  words?: SttWord[]
}

export interface SpeakerSegment {
  start: number
  end: number
  speaker: string
}

/** 화자가 배정된 조각. 병합 전 단계 */
export interface SpeakerPiece {
  speaker: string
  start: number
  end: number
  text: string
}

/** 병합이 끝난 발화. 데스크탑은 여기에 DB 키(id, meetingId)를 더해 `Utterance`로 쓴다 */
export interface MergedUtterance {
  ord: number
  speakerLabel: string
  startSec: number
  endSec: number
  text: string
}
