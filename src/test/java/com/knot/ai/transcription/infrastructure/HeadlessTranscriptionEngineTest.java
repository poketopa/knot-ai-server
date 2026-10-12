package com.knot.ai.transcription.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.CreateJobCommand;
import com.knot.ai.transcription.domain.*;
class HeadlessTranscriptionEngineTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private HeadlessTranscriptionEngine engine;
    private Path probe;
    private Path pnpm;
    @BeforeEach
    void prepare() throws Exception {
        Path desktop = directory.resolve("desktop");
        Path scripts = desktop.resolve("scripts");
        Files.createDirectories(scripts);
        Files.copy(
                Path.of("runner/scripts/serverSupervisor.py"),
                scripts.resolve("serverSupervisor.py")
        );
        Path asset = script(
                "asset",
                "import sys\n"
        );
        probe = script(
                "probe",
                "import sys,pathlib\npathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text('1')\n"
        );
        Path ffmpeg = script(
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
                        "id",
                        "profile",
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
                        1,
                        "parallel",
                        false
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
                desktop,
                Set.of("test-bucket.s3.ap-northeast-2.amazonaws.com"),
                "test-bucket",
                "recordings/",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                Duration.ofHours(1),
                524288000,
                268435456,
                1024,
                "python3",
                pnpm.toString(),
                ffmpeg.toString(),
                probe.toString()
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
        CreateJobCommand command = command();
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
    @DisplayName("2시간을 초과한 파일은 추론 전에 거부한다")
    void transcribe_failure_durationExceeded() throws Exception {
        // given
        Files.writeString(
                probe,
                "#!/usr/bin/env python3\nimport sys,pathlib\n"
                        + "pathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text('7200.1')\n"
        );
        CreateJobCommand command = command();
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
        CreateJobCommand command = command();
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
    @DisplayName("길이 정보 없는 MediaRecorder WebM은 디코딩된 PCM 길이로 검증한다")
    void transcribe_success_missingContainerDuration() throws Exception {
        // given
        Files.writeString(
                probe,
                "#!/usr/bin/env python3\nimport sys,pathlib\n"
                        + "pathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text('N/A')\n"
        );
        CreateJobCommand command = command();
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
    }
    @Test
    @DisplayName("디코딩 실행 제한 시간이 지나면 단계별 timeout을 반환한다")
    void transcribe_failure_decodeTimeout() throws Exception {
        // given
        Files.writeString(
                probe,
                "#!/usr/bin/env python3\nimport time\ntime.sleep(30)\n"
        );
        CreateJobCommand command = command();
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
    @DisplayName("컨테이너 길이가 없어도 실제 PCM이 2시간을 넘으면 거부한다")
    void transcribe_failure_decodedDurationExceeded() throws Exception {
        // given
        Files.writeString(
                probe,
                "#!/usr/bin/env python3\nimport sys,pathlib\n"
                        + "pathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text('N/A')\n"
        );
        Path decoder = directory.resolve("ffmpeg");
        Files.writeString(
                decoder,
                "#!/usr/bin/env python3\nimport sys,struct\n" + "size=230400002\nf=open(sys.argv[-1],'wb')\n"
                        + "f.write(struct.pack('<4sI4s4sIHHIIHH4sI',b'RIFF',size+36,b'WAVE',b'fmt ',"
                        + "16,1,1,16000,32000,2,16,b'data',size))\nf.seek(size+43)\nf.write(bytes(1))\nf.close()\n"
        );
        CreateJobCommand command = command();
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
    private CreateJobCommand command() {
        return new CreateJobCommand(
                UUID.randomUUID(),
                "profile",
                Instant.now()
                        .plusSeconds(1800),
                new CreateJobCommand.AudioInput(
                        "test-bucket",
                        "recordings/audio.webm",
                        null,
                        "\"fixed\"",
                        "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm",
                        Map.of(
                                "If-Match",
                                "\"fixed\""
                        ),
                        3,
                        "audio/webm"
                ),
                null
        );
    }
}
