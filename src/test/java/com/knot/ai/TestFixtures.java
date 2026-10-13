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
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                "python3",
                "pnpm",
                "ffmpeg"
        );
    }
}
