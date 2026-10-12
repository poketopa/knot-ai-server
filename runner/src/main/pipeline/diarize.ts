import type { SpeakerSegment } from '@shared/types'

/** `0.959 -- 5.178 speaker_01` 형태의 결과 줄 */
const SEGMENT_PATTERN = /^\s*(\d+(?:\.\d+)?)\s*--\s*(\d+(?:\.\d+)?)\s+(\S+)\s*$/
const PROGRESS_PATTERN = /^\s*progress\s+(\d+(?:\.\d+)?)%\s*$/

/**
 * 참석자 수를 모를 때 쓰는 군집 임계값. 값이 작을수록 화자를 많이 나눈다.
 * 실제 회의 녹음에서 0.6은 23명으로 과분할됐고 0.8부터 주요 화자 구성이 안정된다 (docs/phase1-results.md)
 */
export const DEFAULT_CLUSTER_THRESHOLD = 0.8

/**
 * 화자 분리 stdout을 화자 구간으로 바꾼다.
 * 설정 덤프·Started 같은 다른 줄은 버리고, 겹치는 구간은 그대로 남긴다
 * (겹쳐 말하기는 병합 단계에서 겹침이 큰 화자로 정리된다).
 */
export const parseDiarizeOutput = (stdout: string): SpeakerSegment[] =>
  stdout.split('\n').reduce<SpeakerSegment[]>((segments, line) => {
    const matched = line.match(SEGMENT_PATTERN)
    if (!matched) return segments

    return [
      ...segments,
      { start: Number(matched[1]), end: Number(matched[2]), speaker: matched[3] }
    ]
  }, [])

/** 진행률 줄에서 퍼센트를 읽는다. 포맷이 바뀌면 null */
export const parseDiarizeProgress = (line: string) => {
  const matched = line.match(PROGRESS_PATTERN)
  return matched ? Number(matched[1]) : null
}

interface BuildDiarizeArgsParams {
  segmentationModelPath: string
  embeddingModelPath: string
  audioPath: string
  threads: number
  /** 참석자 수를 알면 군집 개수를 고정하는 편이 정확하다 */
  speakerCount?: number
  clusterThreshold?: number
}

export const buildDiarizeArgs = ({
  segmentationModelPath,
  embeddingModelPath,
  audioPath,
  threads,
  speakerCount,
  clusterThreshold = DEFAULT_CLUSTER_THRESHOLD
}: BuildDiarizeArgsParams) => [
  ...(speakerCount
    ? [`--clustering.num-clusters=${speakerCount}`]
    : [`--clustering.cluster-threshold=${clusterThreshold}`]),
  `--segmentation.pyannote-model=${segmentationModelPath}`,
  `--embedding.model=${embeddingModelPath}`,
  `--segmentation.num-threads=${threads}`,
  `--embedding.num-threads=${threads}`,
  audioPath
]
