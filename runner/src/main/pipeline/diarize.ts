import type { SpeakerSegment } from '../../core/types'

/** `0.959 -- 5.178 speaker_01` 형태의 결과 줄 */
const SEGMENT_PATTERN = /^\s*(\d+(?:\.\d+)?)\s*--\s*(\d+(?:\.\d+)?)\s+(\S+)\s*$/

/**
 * 참석자 수를 모를 때 쓰는 군집 임계값. 값이 작을수록 화자를 많이 나눈다.
 * 모델 실행에는 0.8을 사용한다.
 */
const DEFAULT_CLUSTER_THRESHOLD = 0.8

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

interface BuildDiarizeArgsParams {
  segmentationModelPath: string
  embeddingModelPath: string
  audioPath: string
  threads: number
  /** 참석자 수를 알면 군집 개수를 고정하는 편이 정확하다 */
  speakerCount?: number
}

export const buildDiarizeArgs = ({
  segmentationModelPath,
  embeddingModelPath,
  audioPath,
  threads,
  speakerCount
}: BuildDiarizeArgsParams) => [
  ...(speakerCount
    ? [`--clustering.num-clusters=${speakerCount}`]
    : [`--clustering.cluster-threshold=${DEFAULT_CLUSTER_THRESHOLD}`]),
  `--segmentation.pyannote-model=${segmentationModelPath}`,
  `--embedding.model=${embeddingModelPath}`,
  `--segmentation.num-threads=${threads}`,
  `--embedding.num-threads=${threads}`,
  audioPath
]
