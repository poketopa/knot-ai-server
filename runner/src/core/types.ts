/**
 * Whisper 전사와 화자 구간을 병합할 때 사용하는 중간 산출물 타입.
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

/** 병합이 끝난 발화 */
export interface MergedUtterance {
  ord: number
  speakerLabel: string
  startSec: number
  endSec: number
  text: string
}
