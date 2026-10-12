import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtemp, readFile, writeFile, chmod, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  assembleResult,
  runServerPipeline,
  validateManifest,
  type ServerManifest
} from './serverPipeline'

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
      schemaVersion: 1,
      audioPath,
      outputDirectory: directory,
      speakerCount: 1,
      timeoutMs: 5000,
      profile: {
        id: 'test',
        whisperBinary: engine,
        diarizeBinary: engine,
        whisperModel: engine,
        vadModel: engine,
        segmentationModel: engine,
        embeddingModel: engine,
        sttThreads: 1,
        diarizeThreads: 1,
        parallel: true
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
test('timeout escalates termination of a SIGTERM-resistant engine', async () => {
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
      schemaVersion: 1,
      audioPath,
      outputDirectory: directory,
      timeoutMs: 1000,
      profile: {
        id: 'test',
        whisperBinary: engine,
        diarizeBinary: engine,
        whisperModel: engine,
        vadModel: engine,
        segmentationModel: engine,
        embeddingModel: engine,
        sttThreads: 1,
        diarizeThreads: 1,
        parallel: true
      }
    }
    const start = performance.now()
    await assert.rejects(runServerPipeline(manifest), /INFERENCE_TIMEOUT/)
    assert.equal(await readFile(path.join(directory, 'resistant.ready'), 'utf8'), 'ready')
    assert.ok(performance.now() - start >= 1700)
    assert.ok(performance.now() - start < 5000)
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
        schemaVersion: 1,
        audioPath,
        outputDirectory: directory,
        timeoutMs: 5000,
        profile: {
          id: 'stage-test',
          whisperBinary: engine,
          diarizeBinary: engine,
          whisperModel: engine,
          vadModel: engine,
          segmentationModel: engine,
          embeddingModel: engine,
          sttThreads: 1,
          diarizeThreads: 1,
          parallel: false
        }
      }
      await assert.rejects(runServerPipeline(manifest), new RegExp(failedStage))
      await assert.rejects(readFile(path.join(directory, 'result.json')))
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })
}
