package com.knot.ai.transcription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.TranscriptionRequest;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.TranscriptResult;
import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class HeadlessTranscriptionEngineTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private HeadlessTranscriptionEngine engine;
    private Path ffmpeg;
    private Path pnpm;
    @BeforeEach
    void prepare() throws Exception {
        Path runner = directory.resolve("runner");
        Path scripts = runner.resolve("scripts");
        Files.createDirectories(scripts);
        Files.copy(
                Path.of("runner/scripts/serverSupervisor.py"),
                scripts.resolve("serverSupervisor.py")
        );
        Path asset = script(
                "asset",
                "import sys\n"
        );
        ffmpeg = script(
                "ffmpeg",
                "import sys,wave\nw=wave.open(sys.argv[-1],'wb')\nw.setnchannels(1)\n"
                        + "w.setsampwidth(2)\nw.setframerate(16000)\nw.writeframes(bytes(32000))\nw.close()\n"
        );
        pnpm = script(
                "pnpm",
                "import sys,json,pathlib,os\n" + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n"
                        + "p=pathlib.Path(m['outputDirectory'])\n"
                        + "r={'schemaVersion':1,'rawWhisperJson':'{\\\"transcription\\\":[]}',"
                        + "'utterances':[{'ord':0,'speakerLabel':'화자 1','startSec':0,'endSec':1,'text':'안녕'}]}\n"
                        + "(p/'result.tmp').write_text(json.dumps(r))\nos.replace(p/'result.tmp',p/'result.json')\n"
        );
        JsonMapper mapper = JsonMapper.builder()
                .build();
        Path profile = directory.resolve("profile.json");
        mapper.writeValue(
                profile.toFile(),
                Map.of(
                        "whisperBinary",
                        asset.toString(),
                        "diarizeBinary",
                        asset.toString(),
                        "whisperModel",
                        asset.toString(),
                        "vadModel",
                        asset.toString(),
                        "segmentationModel",
                        asset.toString(),
                        "embeddingModel",
                        asset.toString(),
                        "sttThreads",
                        1,
                        "diarizeThreads",
                        1
                )
        );
        server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        server.createContext(
                "/recordings/audio.webm",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Content-Type",
                                    "audio/webm"
                            );
                    exchange.getResponseHeaders()
                            .add(
                                    "ETag",
                                    "\"fixed\""
                            );
                    exchange.sendResponseHeaders(
                            200,
                            3
                    );
                    exchange.getResponseBody()
                            .write(new byte[]{1, 2, 3});
                    exchange.close();
                }
        );
        server.start();
        AiServerProperties properties = new AiServerProperties(
                "token",
                directory.resolve("jobs"),
                profile,
                runner,
                Set.of("test-bucket.s3.ap-northeast-2.amazonaws.com"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                "python3",
                pnpm.toString(),
                ffmpeg.toString()
        );
        S3AudioDownloader downloader = new S3AudioDownloader(
                properties,
                uri -> (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + server.getAddress()
                                .getPort() + uri.getPath()
                )
                        .toURL()
                        .openConnection()
        );
        engine = new HeadlessTranscriptionEngine(
                properties,
                downloader,
                mapper
        );
    }

    @AfterEach
    void close() {
        server.stop(0);
    }

    @Test
    @DisplayName("감독 프로세스로 변환과 runner를 실행하고 완성 결과만 공개한다")
    void transcribe_success_supervisedPipeline() throws Exception {
        // given
        TranscriptionRequest command = command();
        // when
        TranscriptResult result = engine.transcribe(command);
        // then
        assertEquals(
                "안녕",
                result.utterances()
                        .getFirst()
                        .text()
        );
        engine.cleanup(command.attemptId());
        assertFalse(
                Files.exists(
                        directory.resolve("jobs")
                                .resolve(
                                        command.attemptId()
                                                .toString()
                                )
                )
        );
    }

    @Test
    @DisplayName("runner가 원본만 출력하면 작업 성공으로 처리하지 않는다")
    void transcribe_failure_rawOnly() throws Exception {
        // given
        Files.writeString(
                pnpm,
                "#!/usr/bin/env python3\nimport sys,pathlib,json\n"
                        + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n"
                        + "(pathlib.Path(m['outputDirectory'])/'result.json').write_text('{\\\"schemaVersion\\\":1,"
                        + "\\\"rawWhisperJson\\\":\\\"{}\\\",\\\"utterances\\\":[]}')\n"
        );
        TranscriptionRequest command = command();
        // when
        Exception exception = assertThrows(
                Exception.class,
                () -> engine.transcribe(command)
        );
        // then
        assertNotNull(exception);
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("디코딩 실행 제한 시간이 지나면 단계별 timeout을 반환한다")
    void transcribe_failure_decodeTimeout() throws Exception {
        // given
        Files.writeString(
                ffmpeg,
                "#!/usr/bin/env python3\nimport time\ntime.sleep(30)\n"
        );
        TranscriptionRequest command = command();
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "DECODE_TIMEOUT",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("실제 PCM이 2시간을 넘으면 추론 전에 거부한다")
    void transcribe_failure_decodedDurationExceeded() throws Exception {
        // given
        Path decoder = directory.resolve("ffmpeg");
        Files.writeString(
                decoder,
                "#!/usr/bin/env python3\nimport sys,struct\n" + "size=230400002\nf=open(sys.argv[-1],'wb')\n"
                        + "f.write(struct.pack('<4sI4s4sIHHIIHH4sI',b'RIFF',size+36,b'WAVE',b'fmt ',"
                        + "16,1,1,16000,32000,2,16,b'data',size))\nf.seek(size+43)\nf.write(bytes(1))\nf.close()\n"
        );
        TranscriptionRequest command = command();
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "AUDIO_DURATION_EXCEEDED",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner가 알려진 진단 코드를 남기면 허용된 작업 오류 코드로 반환한다")
    void transcribe_failure_knownRunnerDiagnostic() throws Exception {
        // given
        diagnosticRunner("STT_FAILED");
        TranscriptionRequest command = command();
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "STT_FAILED",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner의 CANCELLED 진단은 서버 오류 코드 CANCELED로 보정한다")
    void transcribe_failure_cancelledRunnerDiagnostic() throws Exception {
        // given
        diagnosticRunner("CANCELLED");
        TranscriptionRequest command = command();
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "CANCELED",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    @Test
    @DisplayName("runner의 알 수 없는 진단은 추론 실패로 숨긴다")
    void transcribe_failure_unknownRunnerDiagnostic() throws Exception {
        // given
        diagnosticRunner("SECRET_PATH_LEAK");
        TranscriptionRequest command = command();
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> engine.transcribe(command)
        );
        // then
        assertEquals(
                "INFERENCE_FAILED",
                exception.getCode()
        );
        engine.cleanup(command.attemptId());
    }

    private void diagnosticRunner(String code) throws Exception {
        Files.writeString(
                pnpm,
                "#!/usr/bin/env python3\nimport sys,pathlib,json\n"
                        + "m=json.loads(pathlib.Path(sys.argv[-1]).read_text())\n"
                        + "pathlib.Path(m['outputDirectory'],'diagnostic.json').write_text(json.dumps({'code':'" + code
                        + "'}))\nsys.exit(1)\n"
        );
    }

    private Path script(
            String name,
            String body
    ) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(
                path,
                "#!/usr/bin/env python3\n" + body
        );
        Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString("rwx------")
        );
        return path;
    }

    private TranscriptionRequest command() {
        return new TranscriptionRequest(
                UUID.randomUUID(),
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm",
                null
        );
    }
}
