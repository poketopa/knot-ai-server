package com.knot.ai.transcription.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import com.knot.ai.TestFixtures;
import com.knot.ai.transcription.application.CreateJobCommand.AudioInput;
import com.knot.ai.transcription.domain.JobException;
class S3AudioDownloaderTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private S3AudioDownloader downloader;
    private String status = "normal";
    @BeforeEach
    void prepare() throws Exception {
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
                    if (status.equals("redirect")) {
                        exchange.getResponseHeaders()
                                .add(
                                        "Location",
                                        "http://127.0.0.1/private"
                                );
                        exchange.sendResponseHeaders(
                                302,
                                -1
                        );
                    } else if (status.equals("changed")) {
                        exchange.sendResponseHeaders(
                                412,
                                -1
                        );
                    } else {
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
                    }
                    exchange.close();
                }
        );
        server.start();
        downloader = new S3AudioDownloader(
                TestFixtures.properties(directory),
                uri -> (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + server.getAddress()
                                .getPort() + uri.getPath()
                )
                        .toURL()
                        .openConnection()
        );
    }
    @AfterEach
    void close() {
        server.stop(0);
    }
    @Test
    @DisplayName("고정된 객체를 조건부 GET으로 내려받는다")
    void download_success_fixedValidator() throws Exception {
        // given
        Path target = directory.resolve("input.webm");
        // when
        downloader.download(
                input("https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm"),
                target
        );
        // then
        assertArrayEquals(
                new byte[]{1, 2, 3},
                Files.readAllBytes(target)
        );
    }
    @Test
    @DisplayName("S3 응답의 리다이렉트는 따라가지 않는다")
    void download_failure_redirect() {
        // given
        status = "redirect";
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> downloader.download(
                        input("https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm"),
                        directory.resolve("input.webm")
                )
        );
        // then
        assertEquals(
                "DOWNLOAD_FAILED",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("조건부 GET의 객체 변경은 구분된 실패로 전달한다")
    void download_failure_inputChanged() {
        // given
        status = "changed";
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> downloader.download(
                        input("https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm"),
                        directory.resolve("input.webm")
                )
        );
        // then
        assertEquals(
                "INPUT_CHANGED",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("허용하지 않은 호스트에는 연결하지 않는다")
    void validate_failure_arbitraryHost() {
        // given
        AudioInput input = input("https://169.254.169.254/recordings/audio.webm");
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> downloader.validate(input)
        );
        // then
        assertEquals(
                "INVALID_DOWNLOAD_URL",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("요청 파일과 URL 경로가 다르면 거부한다")
    void validate_failure_mismatchedKey() {
        // given
        AudioInput input = input("https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/other.webm");
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> downloader.validate(input)
        );
        // then
        assertEquals(
                "INVALID_DOWNLOAD_URL",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("버전 없는 GET에는 고정 ETag 헤더가 필요하다")
    void validate_failure_missingIfMatch() {
        // given
        AudioInput input = new AudioInput(
                "test-bucket",
                "recordings/audio.webm",
                null,
                "\"fixed\"",
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm",
                Map.of(),
                3,
                "audio/webm"
        );
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> downloader.validate(input)
        );
        // then
        assertEquals(
                "MISSING_OBJECT_VALIDATOR",
                exception.getCode()
        );
    }
    private AudioInput input(String url) {
        return new AudioInput(
                "test-bucket",
                "recordings/audio.webm",
                null,
                "\"fixed\"",
                url,
                Map.of(
                        "If-Match",
                        "\"fixed\""
                ),
                3,
                "audio/webm"
        );
    }
}
