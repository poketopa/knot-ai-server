package com.knot.ai.transcription.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.knot.ai.AiServerApplication;
import com.knot.ai.global.AiServerProperties;
@EnabledIfEnvironmentVariable(named = "AI_REAL_PIPELINE", matches = "true")
class RealPipelineHttpTest {
    private static HttpServer storage;
    @Test
    @DisplayName("실제 한국어 녹음을 HTTP API와 설치된 모델로 전사한 뒤 삭제한다")
    void transcribe_success_realKoreanHttpPipeline() throws Exception {
        // given
        Path audio = Path.of(System.getenv("AI_REAL_AUDIO"));
        Path profile = Path.of(System.getenv("AI_REAL_PROFILE"));
        Path desktop = Path.of("runner")
                .toAbsolutePath()
                .normalize();
        Path work = Files.createTempDirectory("knot-ai-real-http-");
        byte[] bytes = Files.readAllBytes(audio);
        storage = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0
                ),
                0
        );
        storage.createContext(
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
                                    "\"fixture\""
                            );
                    exchange.sendResponseHeaders(
                            200,
                            bytes.length
                    );
                    exchange.getResponseBody()
                            .write(bytes);
                    exchange.close();
                }
        );
        storage.start();
        Map<String, Object> settings = settings(
                work,
                profile,
                desktop
        );
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                AiServerApplication.class,
                TestConfiguration.class
        ).run(
                settings.entrySet()
                        .stream()
                        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                        .toArray(String[]::new)
        );
        int port = ((ServletWebServerApplicationContext) context).getWebServer()
                .getPort();
        URI origin = URI.create("http://127.0.0.1:" + port);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        String profileId = mapper.readTree(profile.toFile())
                .path("id")
                .asString();
        UUID attemptId = UUID.randomUUID();
        Map<String, Object> input = Map.of(
                "bucket",
                "test-bucket",
                "key",
                "recordings/audio.webm",
                "etag",
                "\"fixture\"",
                "downloadUrl",
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/recordings/audio.webm?fixture=1",
                "requiredHeaders",
                Map.of(
                        "If-Match",
                        "\"fixture\""
                ),
                "contentLength",
                bytes.length,
                "contentType",
                "audio/webm"
        );
        String body = mapper.writeValueAsString(
                Map.of(
                        "attemptId",
                        attemptId,
                        "profileId",
                        profileId,
                        "requestValidUntil",
                        Instant.now()
                                .plusSeconds(1800)
                                .toString(),
                        "input",
                        input
                )
        );
        try {
            // when
            JsonNode result = submitAndPoll(
                    client,
                    origin,
                    mapper,
                    attemptId,
                    body
            );
            // then
            assertEquals(
                    "SUCCEEDED",
                    result.path("status")
                            .asString()
            );
            JsonNode transcript = result.path("result");
            assertEquals(
                    1,
                    transcript.path("schemaVersion")
                            .asInt()
            );
            assertTrue(
                    mapper.readTree(
                            transcript.path("rawWhisperJson")
                                    .asString()
                    )
                            .isObject()
            );
            assertTrue(
                    transcript.path("utterances")
                            .isArray()
            );
            assertTrue(
                    transcript.path("utterances")
                            .size() > 0
            );
            assertTrue(
                    transcript.path("utterances")
                            .get(0)
                            .path("text")
                            .asString()
                            .matches("(?s).*[가-힣].*")
            );
            HttpResponse<String> deletion = client.send(
                    request(origin.resolve("/api/v1/transcription-jobs/" + attemptId)).DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(
                    204,
                    deletion.statusCode()
            );
            JsonNode expired = mapper.readTree(
                    client.send(
                            request(origin.resolve("/api/v1/transcription-jobs/" + attemptId)).GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    )
                            .body()
            );
            assertEquals(
                    "EXPIRED",
                    expired.path("status")
                            .asString()
            );
        } finally {
            context.close();
            storage.stop(0);
            try (java.util.stream.Stream<Path> files = Files.walk(work)) {
                for (Path file : files.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.delete(file);
                }
            }
        }
    }
    private JsonNode submitAndPoll(
            HttpClient client,
            URI origin,
            JsonMapper mapper,
            UUID attemptId,
            String body
    ) throws Exception {
        HttpResponse<String> accepted = client.send(
                request(origin.resolve("/api/v1/transcription-jobs")).header(
                        "Content-Type",
                        "application/json"
                )
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(
                202,
                accepted.statusCode()
        );
        long deadline = System.nanoTime() + Duration.ofMinutes(10)
                .toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(
                    request(origin.resolve("/api/v1/transcription-jobs/" + attemptId)).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(
                    200,
                    response.statusCode()
            );
            JsonNode result = mapper.readTree(response.body());
            if (!result.path("status")
                    .asString()
                    .equals("PROCESSING")) {
                return result;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("실제 모델 실행이 제한 시간 안에 완료되지 않았습니다");
    }
    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header(
                        "Authorization",
                        "Bearer test-token"
                );
    }
    private Map<String, Object> settings(
            Path work,
            Path profile,
            Path desktop
    ) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(
                "server.address",
                "127.0.0.1"
        );
        settings.put(
                "server.port",
                0
        );
        settings.put(
                "ai.token",
                "test-token"
        );
        settings.put(
                "ai.work-directory",
                work.toString()
        );
        settings.put(
                "ai.profile-file",
                profile.toString()
        );
        settings.put(
                "ai.desktop-directory",
                desktop.toString()
        );
        settings.put(
                "ai.allowed-s3-hosts",
                "test-bucket.s3.ap-northeast-2.amazonaws.com"
        );
        settings.put(
                "ai.bucket",
                "test-bucket"
        );
        settings.put(
                "ai.key-prefix",
                "recordings/"
        );
        settings.put(
                "ai.inference-timeout",
                "10m"
        );
        settings.put(
                "logging.level.root",
                "WARN"
        );
        settings.put(
                "spring.main.banner-mode",
                "off"
        );
        return settings;
    }
    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        @Primary
        S3AudioDownloader testDownloader(AiServerProperties properties) {
            return new S3AudioDownloader(
                    properties,
                    uri -> (HttpURLConnection) URI.create(
                            "http://127.0.0.1:" + storage.getAddress()
                                    .getPort() + uri.getPath()
                    )
                            .toURL()
                            .openConnection()
            );
        }
    }
}
