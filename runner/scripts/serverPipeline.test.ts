import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { test } from 'node:test'
import { mkdtemp, readFile, writeFile, chmod, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import { buildWhisperArgs } from '../src/main/pipeline/whisper'
import { buildDiarizeArgs } from '../src/main/pipeline/diarize'
import { normalizeWavFile } from '../src/main/pipeline/normalize'
import {
  assembleResult,
  runServerPipeline,
  validateManifest,
  type ServerManifest
} from './serverPipeline'

const execute = promisify(execFile)

const raw = Buffer.from(
  JSON.stringify({ transcription: [{ offsets: { from: 0, to: 1000 }, text: '안녕' }] })
)
test('original Buffer parser preserves Korean and opaque raw string', () => {
  const result = assembleResult(raw, '0 -- 1 speaker_0', 1)
  assert.equal(result.rawWhisperJson, raw.toString('utf8'))
  assert.equal(result.utterances[0].text, '안녕')
})
test('raw STT alone cannot become a complete diarized result', () => {
  assert.throws(() => assembleResult(raw, ''), /DIARIZATION_EMPTY/)
})
test('manifest refuses relative paths and invalid speaker count', () => {
  assert.throws(() => validateManifest({}), /INVALID_MANIFEST/)
})
const wav = () => {
  const buffer = Buffer.alloc(32044)
  buffer.write('RIFF')
  buffer.writeUInt32LE(buffer.length - 8, 4)
  buffer.write('WAVE', 8)
  buffer.write('fmt ', 12)
  buffer.writeUInt32LE(16, 16)
  buffer.writeUInt16LE(1, 20)
  buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(16000, 24)
  buffer.writeUInt32LE(32000, 28)
  buffer.writeUInt16LE(2, 32)
  buffer.writeUInt16LE(16, 34)
  buffer.write('data', 36)
  buffer.writeUInt32LE(32000, 40)
  return buffer
}
test('native arguments preserve Korean, VAD and speaker clustering defaults', () => {
  assert.deepEqual(
    buildWhisperArgs({
      modelPath: '/models/whisper.bin',
      audioPath: '/job/audio.wav',
      outputPath: '/job/whisper',
      threads: 4,
      vadModelPath: '/models/vad.bin'
    }).filter((argument) => argument !== '--print-progress'),
    ['-m', '/models/whisper.bin', '-f', '/job/audio.wav', '-l', 'ko', '-t', '4',
      '--output-json-full', '-of', '/job/whisper', '--no-prints', '--vad', '--vad-model',
      '/models/vad.bin']
  )
  const parameters = {
    segmentationModelPath: '/models/segmentation.onnx',
    embeddingModelPath: '/models/embedding.onnx',
    audioPath: '/job/audio.wav',
    threads: 2
  }
  assert.deepEqual(buildDiarizeArgs(parameters), [
    '--clustering.cluster-threshold=0.8',
    '--segmentation.pyannote-model=/models/segmentation.onnx',
    '--embedding.model=/models/embedding.onnx',
    '--segmentation.num-threads=2', '--embedding.num-threads=2', '/job/audio.wav'
  ])
  assert.deepEqual(buildDiarizeArgs({ ...parameters, speakerCount: 3 }), [
    '--clustering.num-clusters=3', ...buildDiarizeArgs(parameters).slice(1)
  ])
})

for (const amplitude of [0, 1000]) {
  test(`WAV normalization preserves header and ${amplitude ? 'raises quiet speech' : 'keeps silence'}`, async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'server-normalize-'))
    try {
      const input = wav()
      for (let offset = 44; offset < input.length; offset += 2) {
        input.writeInt16LE((offset % 4 ? -1 : 1) * amplitude, offset)
      }
      const inputPath = path.join(directory, 'audio.wav')
      const outputPath = path.join(directory, 'normalized.wav')
      await writeFile(inputPath, input)
      await normalizeWavFile({ inputPath, outputPath })
      const output = await readFile(outputPath)
      assert.equal(output.length, input.length)
      assert.deepEqual(output.subarray(0, 44), input.subarray(0, 44))
      for (let offset = 44; offset < output.length; offset += 2) {
        assert.equal(output.readInt16LE(offset), amplitude ? (offset % 4 ? -3277 : 3277) : 0)
      }
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })
}
test('headless fake engines produce an atomic complete artifact', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'server-runner-'))
  try {
    const engine = path.join(directory, 'engine')
    await writeFile(
      engine,
      `#!/usr/bin/env python3\nimport sys,json\nif '-of' in sys.argv:\n p=sys.argv[sys.argv.index('-of')+1]+'.json'\n open(p,'w').write(json.dumps({'transcription':[{'offsets':{'from':0,'to':1000},'text':'안녕'}]},ensure_ascii=False))\nelse: print('0 -- 1 speaker_0')\n`
    )
    await chmod(engine, 0o700)
    const audioPath = path.join(directory, 'audio.wav')
    await writeFile(audioPath, wav())
    const manifest: ServerManifest = {
      audioPath,
      outputDirectory: directory,
      speakerCount: 1,
      profile: {
        whisperBinary: engine,
        diarizeBinary: engine,
        whisperModel: engine,
        vadModel: engine,
        segmentationModel: engine,
        embeddingModel: engine,
        sttThreads: 1,
        diarizeThreads: 1
      }
    }
    await runServerPipeline(validateManifest(manifest))
    const result = JSON.parse(await readFile(path.join(directory, 'result.json'), 'utf8'))
    assert.equal(result.schemaVersion, 1)
    assert.equal(result.utterances[0].text, '안녕')
    await assert.rejects(readFile(path.join(directory, 'result.json.tmp')))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
test('supervisor deadline terminates runner and SIGTERM-resistant native engines', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'server-timeout-'))
  try {
    const engine = path.join(directory, 'engine')
    await writeFile(
      engine,
      '#!/usr/bin/env python3\nimport signal,time,pathlib\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\npathlib.Path(__file__).with_name("resistant.ready").write_text("ready")\ntime.sleep(30)\n'
    )
    await chmod(engine, 0o700)
    const audioPath = path.join(directory, 'audio.wav')
    await writeFile(audioPath, wav())
    const manifest: ServerManifest = {
      audioPath,
      outputDirectory: directory,
      profile: {
        whisperBinary: engine,
        diarizeBinary: engine,
        whisperModel: engine,
        vadModel: engine,
        segmentationModel: engine,
        embeddingModel: engine,
        sttThreads: 1,
        diarizeThreads: 1
      }
    }
    const manifestPath = path.join(directory, 'manifest.json')
    const ownershipPath = path.join(directory, 'process-owner.json')
    await writeFile(manifestPath, JSON.stringify(manifest))
    const start = performance.now()
    await assert.rejects(
      execute('python3', [
        path.resolve('scripts/serverSupervisor.py'),
        '--parent-pid', String(process.pid),
        '--ownership', ownershipPath,
        '--cwd', directory,
        '--deadline-seconds', '2',
        '--', process.execPath,
        path.resolve('node_modules/tsx/dist/cli.mjs'),
        path.resolve('scripts/serverPipeline.ts'), '--manifest', manifestPath
      ], { timeout: 12000 }),
      (error: NodeJS.ErrnoException) => Number(error.code) === 124
    )
    assert.equal(await readFile(path.join(directory, 'resistant.ready'), 'utf8'), 'ready')
    const owner = JSON.parse(await readFile(ownershipPath, 'utf8'))
    assert.equal(owner.state, 'CLEANED')
    const { stdout } = await execute('ps', ['-axo', 'pid=,pgid=,stat='])
    assert.ok(!stdout.trim().split('\n').some((line) => {
      const [, group, state] = line.trim().split(/\s+/)
      return Number(group) === owner.pgid && !state.includes('Z')
    }), 'No live process may remain in the supervised group')
    assert.ok(performance.now() - start >= 2000)
    assert.ok(performance.now() - start < 10000)
    await assert.rejects(readFile(path.join(directory, 'result.json')))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
test('split Korean token bytes use original latin1 parser, opaque UTF8 remains unchanged', () => {
  const bytes = Buffer.from('안', 'utf8')
  const tokens = [...bytes].map((byte) =>
    Buffer.concat([
      Buffer.from('{"text":"'),
      Buffer.from([byte]),
      Buffer.from('","offsets":{"from":0,"to":1000}}')
    ])
  )
  const source = Buffer.concat([
    Buffer.from('{"transcription":[{"offsets":{"from":0,"to":1000},"text":"안","tokens":['),
    ...tokens.flatMap((token, index) => (index ? [Buffer.from(','), token] : [token])),
    Buffer.from(']}]}')
  ])
  const result = assembleResult(source, '0 -- 1 speaker_0', 1)
  assert.equal(result.utterances[0].text, '안')
  assert.equal(result.rawWhisperJson, source.toString('utf8'))
  assert.ok(result.rawWhisperJson.includes('\ufffd'))
})
test('provided speaker count preserves a minor speaker instead of absorbing it', () => {
  const source = Buffer.from(
    JSON.stringify({
      transcription: [
        { offsets: { from: 0, to: 15000 }, text: '주 화자' },
        { offsets: { from: 16000, to: 17000 }, text: '짧은 화자' }
      ]
    })
  )
  const diarization = '0 -- 15 speaker_0\n16 -- 17 speaker_1'
  assert.equal(
    new Set(assembleResult(source, diarization, 2).utterances.map((item) => item.speakerLabel))
      .size,
    2
  )
  assert.equal(
    new Set(assembleResult(source, diarization).utterances.map((item) => item.speakerLabel)).size,
    1
  )
})

for (const failedStage of ['STT_FAILED', 'DIARIZATION_FAILED'] as const) {
  test(`${failedStage} is distinct and never exposes a raw-only completed result`, async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'server-stage-failure-'))
    try {
      const engine = path.join(directory, 'engine')
      const failsStt = failedStage === 'STT_FAILED'
      await writeFile(
        engine,
        `#!/usr/bin/env python3\nimport sys,json\nstt='-of' in sys.argv\nif stt == ${failsStt ? 'True' : 'False'}: sys.exit(7)\nif stt:\n p=sys.argv[sys.argv.index('-of')+1]+'.json'\n open(p,'w').write(json.dumps({'transcription':[{'offsets':{'from':0,'to':1000},'text':'안녕'}]},ensure_ascii=False))\nelse: print('0 -- 1 speaker_0')\n`
      )
      await chmod(engine, 0o700)
      const audioPath = path.join(directory, 'audio.wav')
      await writeFile(audioPath, wav())
      const manifest: ServerManifest = {
        audioPath,
        outputDirectory: directory,
        profile: {
          whisperBinary: engine,
          diarizeBinary: engine,
          whisperModel: engine,
          vadModel: engine,
          segmentationModel: engine,
          embeddingModel: engine,
          sttThreads: 1,
          diarizeThreads: 1
        }
      }
      await assert.rejects(runServerPipeline(manifest), new RegExp(failedStage))
      await assert.rejects(readFile(path.join(directory, 'result.json')))
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })
}
