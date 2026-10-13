package com.knot.ai.transcription.infrastructure;

import com.knot.ai.global.AiServerProperties;
import com.knot.ai.transcription.application.dto.command.CreateJobCommand.AudioInput;
import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class S3AudioDownloader {
    private static final String AUDIO_CONTENT_TYPE = "audio/webm";
    private static final int COPY_BUFFER_BYTES = 65_536;

    private final AiServerProperties properties;
    private final ConnectionFactory connections;
    @Autowired
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

    public URI validate(AudioInput input) {
        URI uri;
        try {
            uri = URI.create(input.downloadUrl());
        } catch (IllegalArgumentException exception) {
            throw new JobException(JobErrorCode.INVALID_DOWNLOAD_URL);
        }
        validateObjectLocation(
                input,
                uri
        );
        validateHeaders(
                input,
                uri
        );
        validateObjectVersion(
                input,
                uri
        );
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
                throw new JobException(JobErrorCode.INPUT_CHANGED);
            }
            if (status != 200) {
                throw new JobException(JobErrorCode.DOWNLOAD_FAILED);
            }
            if (!input.etag()
                    .equals(connection.getHeaderField("ETag"))
                    || (input.versionId() != null && !input.versionId()
                            .equals("null") && !input.versionId()
                                    .equals(connection.getHeaderField("x-amz-version-id")))) {
                throw new JobException(JobErrorCode.INPUT_CHANGED);
            }
            if (!AUDIO_CONTENT_TYPE.equals(connection.getContentType())
                    || connection.getContentLengthLong() != input.contentLength()) {
                throw new JobException(JobErrorCode.INVALID_AUDIO_METADATA);
            }
            try (InputStream source = connection.getInputStream();
                    OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                long count = 0;
                int length;
                while ((length = source.read(buffer)) != -1) {
                    if (Thread.currentThread()
                            .isInterrupted()) {
                        throw new JobException(JobErrorCode.CANCELED);
                    }
                    if (System.nanoTime() >= deadline) {
                        throw new JobException(JobErrorCode.DOWNLOAD_TIMEOUT);
                    }
                    count += length;
                    if (count > input.contentLength() || count > properties.maximumBytes()) {
                        throw new JobException(JobErrorCode.AUDIO_TOO_LARGE);
                    }
                    output.write(
                            buffer,
                            0,
                            length
                    );
                }
                if (count != input.contentLength()) {
                    throw new JobException(JobErrorCode.INCOMPLETE_DOWNLOAD);
                }
            }
        } catch (IOException exception) {
            if (Thread.currentThread()
                    .isInterrupted()) {
                throw new JobException(JobErrorCode.CANCELED);
            }
            if (System.nanoTime() >= deadline) {
                throw new JobException(JobErrorCode.DOWNLOAD_TIMEOUT);
            }
            throw new JobException(JobErrorCode.DOWNLOAD_FAILED);
        } finally {
            watchdog.shutdownNow();
            connection.disconnect();
        }
    }

    private void validateObjectLocation(
            AudioInput input,
            URI uri
    ) {
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
                || !AUDIO_CONTENT_TYPE.equals(input.contentType()) || input.etag() == null || input.etag()
                        .isBlank()) {
            throw new JobException(JobErrorCode.INVALID_DOWNLOAD_URL);
        }
        String expectedPath = uri.getHost()
                .startsWith(input.bucket() + ".") ? "/" + input.key() : "/" + input.bucket() + "/" + input.key();
        if (!expectedPath.equals(uri.getPath())) {
            throw new JobException(JobErrorCode.INVALID_DOWNLOAD_URL);
        }
    }

    private void validateHeaders(
            AudioInput input,
            URI uri
    ) {
        for (Map.Entry<String, String> header : input.requiredHeaders()
                .entrySet()) {
            String name = header.getKey()
                    .toLowerCase(Locale.ROOT);
            if (!name.equals("host") && !name.equals("if-match")) {
                throw new JobException(JobErrorCode.INVALID_DOWNLOAD_HEADERS);
            }
            if (header.getValue()
                    .contains("\r")
                    || header.getValue()
                            .contains("\n")) {
                throw new JobException(JobErrorCode.INVALID_DOWNLOAD_HEADERS);
            }
            if (name.equals("host") && !header.getValue()
                    .equals(uri.getHost())) {
                throw new JobException(JobErrorCode.INVALID_DOWNLOAD_HEADERS);
            }
        }
    }

    private void validateObjectVersion(
            AudioInput input,
            URI uri
    ) {
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
                throw new JobException(JobErrorCode.MISSING_OBJECT_VALIDATOR);
            }
        } else {
            String query = uri.getRawQuery();
            boolean matches = query != null && Arrays.stream(query.split("&"))
                    .anyMatch(
                            part -> part.startsWith("versionId=") && input.versionId()
                                    .equals(
                                            URLDecoder.decode(
                                                    part.substring(10),
                                                    StandardCharsets.UTF_8
                                            )
                                    )
                    );
            if (!matches) {
                throw new JobException(JobErrorCode.MISSING_OBJECT_VALIDATOR);
            }
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

    interface ConnectionFactory {

        HttpURLConnection open(URI uri) throws IOException;
    }
}
