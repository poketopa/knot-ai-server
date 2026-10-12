package com.knot.ai.transcription.infrastructure;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.CreateJobCommand.AudioInput;
import com.knot.ai.transcription.domain.JobException;
@Component
public class S3AudioDownloader {
    private final AiServerProperties properties;
    private final ConnectionFactory connections;
    @org.springframework.beans.factory.annotation.Autowired
    public S3AudioDownloader(AiServerProperties properties) {
        this(
                properties,
                uri -> (HttpURLConnection) uri.toURL()
                        .openConnection()
        );
    }
    S3AudioDownloader(
            AiServerProperties properties,
            ConnectionFactory connections
    ) {
        this.properties = properties;
        this.connections = connections;
    }
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }
    public URI validate(AudioInput input) {
        URI uri;
        try {
            uri = URI.create(input.downloadUrl());
        } catch (IllegalArgumentException exception) {
            throw new JobException(
                    "INVALID_DOWNLOAD_URL",
                    400
            );
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !properties.allowedS3Hosts()
                        .contains(uri.getHost())
                || !uri.getHost()
                        .matches("(?:[a-z0-9.-]+\\.)?s3(?:[.-][a-z0-9-]+)?\\.amazonaws\\.com")
                || !properties.bucket()
                        .equals(input.bucket())
                || !input.key()
                        .startsWith(properties.keyPrefix())
                || input.key()
                        .contains("..")
                || input.contentLength() > properties.maximumBytes() || input.contentLength() <= 0
                || !"audio/webm".equals(input.contentType()) || input.etag() == null || input.etag()
                        .isBlank()) {
            throw new JobException(
                    "INVALID_DOWNLOAD_URL",
                    400
            );
        }
        String expectedPath = uri.getHost()
                .startsWith(input.bucket() + ".") ? "/" + input.key() : "/" + input.bucket() + "/" + input.key();
        if (!expectedPath.equals(uri.getPath())) {
            throw new JobException(
                    "INVALID_DOWNLOAD_URL",
                    400
            );
        }
        for (Map.Entry<String, String> header : input.requiredHeaders()
                .entrySet()) {
            String name = header.getKey()
                    .toLowerCase(Locale.ROOT);
            if (!name.equals("host") && !name.equals("if-match")) {
                throw new JobException(
                        "INVALID_DOWNLOAD_HEADERS",
                        400
                );
            }
            if (header.getValue()
                    .contains("\r")
                    || header.getValue()
                            .contains("\n")) {
                throw new JobException(
                        "INVALID_DOWNLOAD_HEADERS",
                        400
                );
            }
            if (name.equals("host") && !header.getValue()
                    .equals(uri.getHost())) {
                throw new JobException(
                        "INVALID_DOWNLOAD_HEADERS",
                        400
                );
            }
        }
        String ifMatch = input.requiredHeaders()
                .entrySet()
                .stream()
                .filter(
                        header -> header.getKey()
                                .equalsIgnoreCase("if-match")
                )
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (input.versionId() == null || input.versionId()
                .equals("null")) {
            if (!input.etag()
                    .equals(ifMatch)) {
                throw new JobException(
                        "MISSING_OBJECT_VALIDATOR",
                        400
                );
            }
        } else {
            String query = uri.getRawQuery();
            boolean matches = query != null && java.util.Arrays.stream(query.split("&"))
                    .anyMatch(
                            part -> part.startsWith("versionId=") && input.versionId()
                                    .equals(
                                            URLDecoder.decode(
                                                    part.substring(10),
                                                    java.nio.charset.StandardCharsets.UTF_8
                                            )
                                    )
                    );
            if (!matches) {
                throw new JobException(
                        "MISSING_OBJECT_VALIDATOR",
                        400
                );
            }
        }
        return uri;
    }
    public void download(
            AudioInput input,
            Path target
    ) throws IOException {
        URI uri = validate(input);
        HttpURLConnection connection = connections.open(uri);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(toMillis(properties.connectTimeout()));
        connection.setReadTimeout(toMillis(properties.readTimeout()));
        input.requiredHeaders()
                .forEach(
                        (
                                name,
                                value
                        ) -> {
                            if (!name.equalsIgnoreCase("host")) {
                                connection.setRequestProperty(
                                        name,
                                        value
                                );
                            }
                        }
                );
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(
                connection::disconnect,
                properties.downloadTimeout()
                        .toMillis(),
                TimeUnit.MILLISECONDS
        );
        long deadline = System.nanoTime() + properties.downloadTimeout()
                .toNanos();
        try {
            int status = connection.getResponseCode();
            if (status == 412) {
                throw new JobException(
                        "INPUT_CHANGED",
                        409
                );
            }
            if (status != 200) {
                throw new JobException(
                        "DOWNLOAD_FAILED",
                        502
                );
            }
            if (!input.etag()
                    .equals(connection.getHeaderField("ETag"))
                    || (input.versionId() != null && !input.versionId()
                            .equals("null") && !input.versionId()
                                    .equals(connection.getHeaderField("x-amz-version-id")))) {
                throw new JobException(
                        "INPUT_CHANGED",
                        409
                );
            }
            if (!"audio/webm".equals(connection.getContentType())
                    || connection.getContentLengthLong() != input.contentLength()) {
                throw new JobException(
                        "INVALID_AUDIO_METADATA",
                        400
                );
            }
            try (InputStream source = connection.getInputStream();
                    OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[65536];
                long count = 0;
                int length;
                while ((length = source.read(buffer)) != -1) {
                    if (Thread.currentThread()
                            .isInterrupted()) {
                        throw new JobException(
                                "CANCELED",
                                409
                        );
                    }
                    if (System.nanoTime() >= deadline) {
                        throw new JobException(
                                "DOWNLOAD_TIMEOUT",
                                504
                        );
                    }
                    count += length;
                    if (count > input.contentLength() || count > properties.maximumBytes()) {
                        throw new JobException(
                                "AUDIO_TOO_LARGE",
                                400
                        );
                    }
                    output.write(
                            buffer,
                            0,
                            length
                    );
                }
                if (count != input.contentLength()) {
                    throw new JobException(
                            "INCOMPLETE_DOWNLOAD",
                            502
                    );
                }
            }
        } catch (IOException exception) {
            if (Thread.currentThread()
                    .isInterrupted()) {
                throw new JobException(
                        "CANCELED",
                        409
                );
            }
            if (System.nanoTime() >= deadline) {
                throw new JobException(
                        "DOWNLOAD_TIMEOUT",
                        504
                );
            }
            throw new JobException(
                    "DOWNLOAD_FAILED",
                    502
            );
        } finally {
            watchdog.shutdownNow();
            connection.disconnect();
        }
    }
    private int toMillis(Duration timeout) {
        return Math.toIntExact(
                Math.min(
                        timeout.toMillis(),
                        Integer.MAX_VALUE
                )
        );
    }
}
