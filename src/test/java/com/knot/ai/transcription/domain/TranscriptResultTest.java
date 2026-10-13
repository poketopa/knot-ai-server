package com.knot.ai.transcription.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    void createUtterance_failure_reversedTime() {
        // when
        JobException exception = assertThrows(
                JobException.class,
                () -> new TranscriptResult.Utterance(
                        0,
                        "화자 1",
                        2,
                        1,
                        "말"
                )
        );

        // then
        assertEquals(
                JobErrorCode.INVALID_RESULT,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("원문과 발화는 입력 목록이 바뀌어도 그대로 보존한다")
    void create_success_immutableUtterances() {
        // given
        String raw = "{\"transcription\":[],\"opaque\":\"한글\"}";
        ArrayList<TranscriptResult.Utterance> utterances = new ArrayList<>();
        utterances.add(
                new TranscriptResult.Utterance(
                        0,
                        "화자 1",
                        0,
                        1,
                        "말"
                )
        );

        // when
        TranscriptResult result = new TranscriptResult(
                1,
                raw,
                utterances
        );

        // then
        utterances.clear();
        assertEquals(
                raw,
                result.rawWhisperJson()
        );
        assertEquals(
                1,
                result.utterances()
                        .size()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.utterances()
                        .clear()
        );
    }

    @Test
    @DisplayName("시작 시각이 역순인 결과는 거부한다")
    void create_failure_unorderedUtterances() {
        // given
        List<TranscriptResult.Utterance> utterances = List.of(
                new TranscriptResult.Utterance(
                        0,
                        "화자 1",
                        2,
                        3,
                        "먼저"
                ),
                new TranscriptResult.Utterance(
                        1,
                        "화자 1",
                        0,
                        1,
                        "나중"
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
                JobErrorCode.INVALID_RESULT,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("연속되지 않은 발화 순서는 거부한다")
    void create_failure_nonContiguousOrder() {
        // given
        List<TranscriptResult.Utterance> utterances = List.of(
                new TranscriptResult.Utterance(
                        1,
                        "화자 1",
                        0,
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
                JobErrorCode.INVALID_RESULT,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("누락된 발화는 전사 결과 오류로 거부한다")
    void create_failure_nullUtterance() {
        // given
        List<TranscriptResult.Utterance> utterances = Arrays.asList((TranscriptResult.Utterance) null);

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
                JobErrorCode.INVALID_RESULT,
                exception.getErrorCode()
        );
    }
}
