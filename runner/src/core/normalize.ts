/**
 * 음량 정규화의 공식과 상수. STT 전에 음량을 맞추지 않으면 원거리 마이크 녹음(발화 -44 dBFS)에서
 * Whisper가 수십 초를 한두 단어로 뭉갠다 (`docs/phase1-results.md`).
 *
 * 프레임 RMS를 세는 루프는 데이터 타입이 달라 각 앱에 둔다 — 데스크탑은 WAV의 `Int16Array`,
 * 웹은 디코딩된 `Float32Array`를 제자리에서 고친다. 71분이면 배열 하나가 273MB라
 * 한 함수로 합치면 루프가 다형이 되고 복사본이 생긴다. 공유하는 것은 아래 상수와 계산뿐이다.
 */

/** RMS를 재는 프레임 길이 */
const FRAME_MS = 50
/** 프레임 RMS 분포에서 이 분위수를 "발화 음량"으로 본다 (무음·잡음 프레임을 배제) */
const SPEECH_RMS_PERCENTILE = 0.9
/** 발화 음량을 맞출 목표치 */
const TARGET_SPEECH_RMS_DBFS = -20
/** 게인 상한. 이보다 작은 소리는 잡음이 함께 커질 뿐이라 의미가 없다 */
const MAX_GAIN_DB = 30
const MS_PER_SEC = 1000

/** 진폭비(0~1)를 dBFS로 */
export const toDb = (linear: number) => 20 * Math.log10(linear)

/** 한 프레임에 들어가는 샘플 수. 양쪽 앱이 같은 프레임 길이로 재야 같은 dBFS가 나온다 */
export const frameSamplesOf = ({ sampleRate }: { sampleRate: number }) =>
  Math.max(1, Math.round((sampleRate * FRAME_MS) / MS_PER_SEC))

/**
 * 프레임 RMS 목록에서 발화 구간의 대표 음량(dBFS)을 고른다. 프레임이 없으면 -Infinity.
 * 호출자는 `frameSamplesOf`로 나눈 프레임마다 RMS(풀스케일 1 기준)를 계산해 넘긴다.
 */
export const speechRmsDbOf = ({ frameRms }: { frameRms: number[] }) => {
  if (frameRms.length === 0) return -Infinity

  const sorted = frameRms.toSorted((a, b) => a - b)
  const index = Math.min(sorted.length - 1, Math.floor(sorted.length * SPEECH_RMS_PERCENTILE))

  return toDb(sorted[index])
}

/** 발화 음량을 목표치로 올리는(또는 내리는) 게인. 무음이면 0 */
export const gainDbFor = (speechRmsDb: number) =>
  Number.isFinite(speechRmsDb) ? Math.min(TARGET_SPEECH_RMS_DBFS - speechRmsDb, MAX_GAIN_DB) : 0

/** dB 게인을 곱할 배수로 */
export const gainOf = (gainDb: number) => 10 ** (gainDb / 20)
