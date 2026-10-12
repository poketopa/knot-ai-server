package com.knot.ai;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import com.knot.ai.global.AiServerProperties;
public class TestFixtures {
    public static AiServerProperties properties(Path directory) {
        return new AiServerProperties(
                "test-token",
                directory,
                directory.resolve("profile.json"),
                directory,
                Set.of("test-bucket.s3.ap-northeast-2.amazonaws.com"),
                "test-bucket",
                "recordings/",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMinutes(1),
                Duration.ofHours(1),
                524288000,
                268435456,
                1024,
                "python3",
                "pnpm",
                "ffmpeg",
                "ffprobe"
        );
    }
}
