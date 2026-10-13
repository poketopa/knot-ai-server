import { readFile, writeFile } from 'node:fs/promises'

/**
 * STT 전에 WAV 음량을 맞추는 RMS 게인 정규화.
 * whisper.cpp는 입력 음량을 정규화하지 않아 원거리 마이크 녹음(발화 -44 dBFS)에서 수십 초를 통째로 놓친다.
 */

const INT16_MAX = 32767
const BYTES_PER_SAMPLE = 2
const RIFF_HEADER_BYTES = 12
const CHUNK_HEADER_BYTES = 8
const PCM_FORMAT_TAG = 1
const SUPPORTED_BITS_PER_SAMPLE = 16
const FRAME_MS = 50
const SPEECH_RMS_PERCENTILE = 0.9
const TARGET_SPEECH_RMS_DBFS = -20
const MAX_GAIN_DB = 30
const MS_PER_SEC = 1000

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
const readWavPcm = (buffer: Buffer): WavPcm => {
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
const measureSpeechRmsDb = ({ pcm, sampleRate }: Pick<WavPcm, 'pcm' | 'sampleRate'>) => {
  const frameSamples = Math.max(1, Math.round((sampleRate * FRAME_MS) / MS_PER_SEC))
  const frameRms: number[] = []

  for (let start = 0; start + frameSamples <= pcm.length; start += frameSamples) {
    let sumSquares = 0
    for (let i = start; i < start + frameSamples; i += 1) {
      const sample = pcm[i] / INT16_MAX
      sumSquares += sample * sample
    }
    frameRms.push(Math.sqrt(sumSquares / frameSamples))
  }

  if (frameRms.length === 0) return -Infinity
  frameRms.sort((left, right) => left - right)
  const index = Math.min(frameRms.length - 1, Math.floor(frameRms.length * SPEECH_RMS_PERCENTILE))
  return 20 * Math.log10(frameRms[index])
}

/** 게인을 적용하고 int16 범위를 넘는 샘플은 하드 클립한다 */
const applyGain = ({ pcm, gainDb }: { pcm: Int16Array; gainDb: number }) => {
  const gain = 10 ** (gainDb / 20)
  const output = new Int16Array(pcm.length)

  for (let i = 0; i < pcm.length; i += 1) {
    const scaled = pcm[i] * gain
    output[i] = Math.max(-INT16_MAX, Math.min(INT16_MAX, Math.round(scaled)))
  }

  return output
}

/** 헤더를 보존하고 정규화한 PCM을 붙인다. */
const normalizeWavBuffer = (buffer: Buffer) => {
  const { headerBytes, sampleRate, pcm } = readWavPcm(buffer)
  const speechRmsDb = measureSpeechRmsDb({ pcm, sampleRate })
  const gainDb = Number.isFinite(speechRmsDb)
    ? Math.min(TARGET_SPEECH_RMS_DBFS - speechRmsDb, MAX_GAIN_DB)
    : 0
  const normalized = applyGain({ pcm, gainDb })

  const body = Buffer.from(
    normalized.buffer,
    normalized.byteOffset,
    normalized.length * BYTES_PER_SAMPLE
  )
  return Buffer.concat([buffer.subarray(0, headerBytes), body])
}

interface NormalizeWavFileParams {
  inputPath: string
  outputPath: string
}

/** 녹음 WAV를 읽어 정규화본을 outputPath에 쓴다. whisper·diarization은 이 파일을 읽는다 */
export const normalizeWavFile = async ({ inputPath, outputPath }: NormalizeWavFileParams) => {
  await writeFile(outputPath, normalizeWavBuffer(await readFile(inputPath)))
}
