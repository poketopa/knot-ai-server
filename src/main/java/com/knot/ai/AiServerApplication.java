package com.knot.ai;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.knot.ai.global.AiServerProperties;
@SpringBootApplication
@EnableConfigurationProperties(AiServerProperties.class)
public class AiServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(
                AiServerApplication.class,
                args
        );
    }
}
