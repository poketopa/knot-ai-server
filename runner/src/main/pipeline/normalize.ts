import { readFile, writeFile } from 'node:fs/promises'
import { frameSamplesOf, gainDbFor, gainOf, speechRmsDbOf } from '@meeting-stt/core/normalize'

/**
 * STT 전에 WAV 음량을 맞추는 RMS 게인 정규화.
 * whisper.cpp는 입력 음량을 정규화하지 않아 원거리 마이크 녹음(발화 -44 dBFS)에서 수십 초를 통째로 놓친다.
 * ffmpeg를 동봉하지 않으므로 순수 TS로 구현한다 (docs/phase1-results.md, SKILL.md 결정 표).
 * 목표 음량·게인 상한·프레임 길이 같은 값은 브라우저 앱과 같아야 하므로 `@meeting-stt/core/normalize`에 있다.
 */

const INT16_MAX = 32767
const BYTES_PER_SAMPLE = 2
const RIFF_HEADER_BYTES = 12
const CHUNK_HEADER_BYTES = 8
const PCM_FORMAT_TAG = 1
const SUPPORTED_BITS_PER_SAMPLE = 16

interface WavChunk {
  id: string
  dataOffset: number
  size: number
}

/** RIFF 청크 목록. ffmpeg가 만든 WAV는 `LIST` 청크가 있어 헤더가 44바이트로 고정되지 않는다 */
const listChunks = (buffer: Buffer) => {
  const chunks: WavChunk[] = []
  let offset = RIFF_HEADER_BYTES

  while (offset + CHUNK_HEADER_BYTES <= buffer.length) {
    const size = buffer.readUInt32LE(offset + 4)
    chunks.push({ id: buffer.toString('ascii', offset, offset + 4), dataOffset: offset + 8, size })
    offset += CHUNK_HEADER_BYTES + size + (size % 2)
  }

  return chunks
}

interface WavPcm {
  /** `data` 청크 본문 앞까지의 바이트 (fmt, LIST 등 그대로 보존) */
  headerBytes: number
  sampleRate: number
  pcm: Int16Array
}

/** 16bit PCM WAV에서 헤더 길이·샘플레이트·샘플 배열을 꺼낸다 */
export const readWavPcm = (buffer: Buffer): WavPcm => {
  const chunks = listChunks(buffer)
  const fmt = chunks.find((chunk) => chunk.id === 'fmt ')
  const data = chunks.find((chunk) => chunk.id === 'data')
  if (!fmt || !data) throw new Error('WAV 헤더에 fmt 또는 data 청크가 없습니다')

  const formatTag = buffer.readUInt16LE(fmt.dataOffset)
  const bitsPerSample = buffer.readUInt16LE(fmt.dataOffset + 14)
  if (formatTag !== PCM_FORMAT_TAG || bitsPerSample !== SUPPORTED_BITS_PER_SAMPLE) {
    throw new Error('16bit PCM WAV만 정규화할 수 있습니다')
  }

  const size = Math.min(data.size, buffer.length - data.dataOffset)
  const pcm = new Int16Array(
    buffer.buffer.slice(
      buffer.byteOffset + data.dataOffset,
      buffer.byteOffset + data.dataOffset + size
    )
  )

  return { headerBytes: data.dataOffset, sampleRate: buffer.readUInt32LE(fmt.dataOffset + 4), pcm }
}

/** 발화 구간의 대표 음량(dBFS). 무음뿐이면 -Infinity */
export const measureSpeechRmsDb = ({ pcm, sampleRate }: Pick<WavPcm, 'pcm' | 'sampleRate'>) => {
  const frameSamples = frameSamplesOf({ sampleRate })
  const frameRms: number[] = []

  for (let start = 0; start + frameSamples <= pcm.length; start += frameSamples) {
    let sumSquares = 0
    for (let i = start; i < start + frameSamples; i += 1) {
      const sample = pcm[i] / INT16_MAX
      sumSquares += sample * sample
    }
    frameRms.push(Math.sqrt(sumSquares / frameSamples))
  }

  return speechRmsDbOf({ frameRms })
}

/** 게인을 적용하고 int16 범위를 넘는 샘플은 하드 클립한다 */
export const applyGain = ({ pcm, gainDb }: { pcm: Int16Array; gainDb: number }) => {
  const gain = gainOf(gainDb)
  const output = new Int16Array(pcm.length)
  let clippedCount = 0

  for (let i = 0; i < pcm.length; i += 1) {
    const scaled = pcm[i] * gain
    if (scaled > INT16_MAX || scaled < -INT16_MAX) clippedCount += 1
    output[i] = Math.max(-INT16_MAX, Math.min(INT16_MAX, Math.round(scaled)))
  }

  return { pcm: output, clippedCount }
}

export interface NormalizeResult {
  speechRmsDb: number
  gainDb: number
  clippedRatio: number
}

/** WAV 버퍼 전체를 정규화한 새 버퍼와 측정값을 돌려준다. 헤더는 그대로 두고 PCM만 바꾼다 */
export const normalizeWavBuffer = (buffer: Buffer) => {
  const { headerBytes, sampleRate, pcm } = readWavPcm(buffer)
  const speechRmsDb = measureSpeechRmsDb({ pcm, sampleRate })
  const gainDb = gainDbFor(speechRmsDb)
  const { pcm: normalized, clippedCount } = applyGain({ pcm, gainDb })

  const body = Buffer.from(
    normalized.buffer,
    normalized.byteOffset,
    normalized.length * BYTES_PER_SAMPLE
  )
  const result: NormalizeResult = {
    speechRmsDb,
    gainDb,
    clippedRatio: pcm.length === 0 ? 0 : clippedCount / pcm.length
  }

  return { buffer: Buffer.concat([buffer.subarray(0, headerBytes), body]), result }
}

interface NormalizeWavFileParams {
  inputPath: string
  outputPath: string
}

/** 녹음 WAV를 읽어 정규화본을 outputPath에 쓴다. whisper·diarization은 이 파일을 읽는다 */
export const normalizeWavFile = async ({ inputPath, outputPath }: NormalizeWavFileParams) => {
  const { buffer, result } = normalizeWavBuffer(await readFile(inputPath))
  await writeFile(outputPath, buffer)
  return result
}
