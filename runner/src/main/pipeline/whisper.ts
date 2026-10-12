import type { SttSegment, SttWord } from '@shared/types'

const DEFAULT_LANGUAGE = 'ko'
const MS_PER_SEC = 1000
/** t_dtw는 10ms 단위 정수다 */
const MS_PER_DTW_UNIT = 10
const DTW_DISABLED = -1
const SPECIAL_TOKEN_PREFIX = '[_'
const PROGRESS_PATTERN = /progress\s*=\s*(\d+)%/

interface WhisperToken {
  text: string
  offsets: { from: number; to: number }
  /** -dtw 옵션을 켰을 때만 채워지는 정렬 시각 (10ms 단위), 아니면 -1 */
  t_dtw?: number
}

/** 파서 내부에서 쓰는 정규화된 토큰 — 시간은 ms, DTW가 있으면 그쪽을 쓴다 */
interface NormalizedToken {
  text: string
  from: number
  to: number
}

interface WhisperSegment {
  offsets: { from: number; to: number }
  text: string
  tokens?: WhisperToken[]
}

/**
 * whisper-cli의 --output-json-full 결과는 유효한 UTF-8이 아니다.
 * 토큰이 한글 한 글자를 바이트 단위로 쪼개기 때문에, 바이트를 보존하는 latin1로 읽고
 * 토큰을 이어붙인 뒤에 UTF-8로 되돌려야 한다 (references/pitfalls.md).
 */
const decodeUtf8 = (latin1Text: string) => Buffer.from(latin1Text, 'latin1').toString('utf-8')

const isSpecialToken = (token: WhisperToken) => token.text.startsWith(SPECIAL_TOKEN_PREFIX)

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null

const toWhisperSegments = (parsed: unknown) => {
  if (!isRecord(parsed) || !Array.isArray(parsed.transcription)) {
    throw new Error('whisper 결과 JSON에 transcription 배열이 없습니다')
  }

  return parsed.transcription as WhisperSegment[]
}

/**
 * -dtw를 켜면 토큰마다 정렬 시각 하나(t_dtw)가 붙는다. 끝 시각은 다음 토큰의 시작으로 보고,
 * 마지막 토큰만 원래 offsets의 끝을 쓴다. DTW가 없으면 offsets를 그대로 쓴다.
 */
const normalizeTokens = (tokens: WhisperToken[]): NormalizedToken[] => {
  const hasDtw = tokens.every((token) => (token.t_dtw ?? DTW_DISABLED) >= 0)
  if (!hasDtw) {
    return tokens.map((token) => ({
      text: token.text,
      from: token.offsets.from,
      to: token.offsets.to
    }))
  }

  return tokens.map((token, index) => {
    const from = (token.t_dtw as number) * MS_PER_DTW_UNIT
    const next = tokens[index + 1]

    // 마지막 토큰만 DTW 끝 시각이 없다. offsets.to는 DTW와 조금 어긋나므로 뒤로만 늘린다.
    return {
      text: token.text,
      from,
      to: Math.max(from, next ? (next.t_dtw as number) * MS_PER_DTW_UNIT : token.offsets.to)
    }
  })
}

/** 앞에 공백이 붙은 토큰이 새 단어의 시작이다 */
const groupTokensIntoWords = (tokens: NormalizedToken[]) =>
  tokens.reduce<NormalizedToken[][]>((words, token) => {
    const startsNewWord = words.length === 0 || token.text.startsWith(' ')
    if (startsNewWord) return [...words, [token]]

    return words.with(words.length - 1, [...words[words.length - 1], token])
  }, [])

interface RemapParams {
  value: number
  tokenFrom: number
  tokenTo: number
  segmentFrom: number
  segmentTo: number
}

/**
 * VAD를 켜면 세그먼트 시간만 원본 시간축으로 복원되고 토큰 시간은 압축된 시간축에 남는다.
 * 세그먼트의 from은 직전 세그먼트의 to를 그대로 쓴 값이라 믿을 수 없고, to만 실제 발화 끝을 가리킨다.
 * 그래서 **끝을 기준으로** 되돌려 토큰 구간 길이를 보존한다 (references/pitfalls.md).
 * 세그먼트 안에서 무음이 많이 제거돼 토큰 구간이 더 길어지면 선형 재매핑으로 되돌아간다.
 */
const remapToSegment = ({ value, tokenFrom, tokenTo, segmentFrom, segmentTo }: RemapParams) => {
  const tokenSpan = tokenTo - tokenFrom
  if (tokenSpan <= 0) return segmentFrom

  const segmentSpan = segmentTo - segmentFrom
  if (tokenSpan >= segmentSpan) {
    return segmentFrom + ((value - tokenFrom) / tokenSpan) * segmentSpan
  }

  return Math.max(segmentFrom, segmentTo - (tokenTo - value))
}

const buildWords = ({ segment, tokens }: { segment: WhisperSegment; tokens: WhisperToken[] }) => {
  const normalized = normalizeTokens(tokens)
  const tokenFrom = Math.min(...normalized.map((token) => token.from)) / MS_PER_SEC
  const tokenTo = Math.max(...normalized.map((token) => token.to)) / MS_PER_SEC
  const segmentFrom = segment.offsets.from / MS_PER_SEC
  const segmentTo = segment.offsets.to / MS_PER_SEC

  const toSec = (value: number) =>
    remapToSegment({ value: value / MS_PER_SEC, tokenFrom, tokenTo, segmentFrom, segmentTo })

  return groupTokensIntoWords(normalized).reduce<SttWord[]>((words, group) => {
    const text = decodeUtf8(group.map((token) => token.text).join('')).trim()
    if (!text) return words

    return [...words, { start: toSec(group[0].from), end: toSec(group[group.length - 1].to), text }]
  }, [])
}

/** whisper-cli가 남긴 JSON 파일 내용을 파이프라인 공용 타입으로 바꾼다 */
export const parseWhisperOutput = (raw: Buffer): SttSegment[] => {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw.toString('latin1'))
  } catch {
    throw new Error('whisper 결과 JSON을 읽지 못했습니다')
  }

  return toWhisperSegments(parsed).map((segment) => {
    const tokens = (segment.tokens ?? []).filter((token) => !isSpecialToken(token))
    const words = tokens.length ? buildWords({ segment, tokens }) : []

    return {
      start: segment.offsets.from / MS_PER_SEC,
      end: segment.offsets.to / MS_PER_SEC,
      text: words.length
        ? words.map((word) => word.text).join(' ')
        : decodeUtf8(segment.text).trim(),
      ...(words.length ? { words } : {})
    }
  })
}

/** 진행률 로그 한 줄에서 퍼센트를 읽는다. 포맷이 바뀌면 null (진행률만 숨기고 작업은 계속) */
export const parseWhisperProgress = (line: string) => {
  const matched = line.match(PROGRESS_PATTERN)
  return matched ? Number(matched[1]) : null
}

interface BuildWhisperArgsParams {
  modelPath: string
  audioPath: string
  /** 확장자를 뺀 출력 경로. whisper-cli가 .json을 붙인다 */
  outputPath: string
  threads: number
  vadModelPath?: string
  language?: string
  /**
   * DTW 토큰 타임스탬프. flash attention과 배타적이라 -nfa를 함께 넣어야 한다
   * (안 그러면 whisper-cli가 조용히 DTW를 끈다).
   */
  dtwPreset?: string
}

export const buildWhisperArgs = ({
  modelPath,
  audioPath,
  outputPath,
  threads,
  vadModelPath,
  language = DEFAULT_LANGUAGE,
  dtwPreset
}: BuildWhisperArgsParams) => [
  '-m',
  modelPath,
  '-f',
  audioPath,
  '-l',
  language,
  '-t',
  String(threads),
  '--output-json-full',
  '-of',
  outputPath,
  '--print-progress',
  '--no-prints',
  ...(vadModelPath ? ['--vad', '--vad-model', vadModelPath] : []),
  ...(dtwPreset ? ['--no-flash-attn', '-dtw', dtwPreset] : [])
]
