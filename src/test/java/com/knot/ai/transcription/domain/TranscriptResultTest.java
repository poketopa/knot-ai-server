package com.knot.ai.transcription.domain;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.*;
class TranscriptResultTest {
    @Test
    @DisplayName("원본만 있고 화자별 발화가 없으면 성공 결과로 인정하지 않는다")
    void create_failure_rawOnly() {
        // given
        String raw = "{}";
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> new TranscriptResult(
                        1,
                        raw,
                        List.of()
                )
        );
        // then
        assertEquals(
                "INVALID_RESULT",
                exception.getCode()
        );
    }
    @Test
    @DisplayName("역순 시간의 발화는 성공 결과로 인정하지 않는다")
    void create_failure_reversedTime() {
        // given
        List<TranscriptResult.Utterance> utterances = List.of(
                new TranscriptResult.Utterance(
                        0,
                        "화자 1",
                        2,
                        1,
                        "말"
                )
        );
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> new TranscriptResult(
                        1,
                        "{}",
                        utterances
                )
        );
        // then
        assertEquals(
                "INVALID_RESULT",
                exception.getCode()
        );
    }
}
