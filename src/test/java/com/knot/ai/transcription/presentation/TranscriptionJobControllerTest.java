package com.knot.ai.transcription.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.ai.TestFixtures;
import com.knot.ai.global.AppAuthenticationFilter;
import com.knot.ai.global.GlobalExceptionHandler;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.dto.result.JobSnapshot;
import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
import com.knot.ai.transcription.domain.JobStatus;
import com.knot.ai.transcription.domain.TranscriptResult;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptionJobControllerTest {
    @TempDir
    Path directory;
    private MockMvc mvc;
    private TranscriptionJobService service;

    @BeforeEach
    void prepare() {
        service = mock(TranscriptionJobService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TranscriptionJobController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new AppAuthenticationFilter(TestFixtures.properties(directory)))
                .build();
    }

    @Test
    @DisplayName("인증 없는 요청은 기존 토큰 실패 응답 형식을 유지한다")
    void getHealth_failure_noAuthentication() throws Exception {
        // when
        ResultActions response = mvc.perform(get("/health"));

        // then
        response.andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"앱 인증이 필요합니다\"}"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("인증된 health 응답은 실제 실행 프로필을 반환한다")
    void getHealth_success() throws Exception {
        // given
        when(service.isReady()).thenReturn(true);
        when(service.getProfileId()).thenReturn("profile");

        // when
        ResultActions response = mvc.perform(authorized(get("/health")));

        // then
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.profileId").value("profile"));
    }

    @Test
    @DisplayName("수락된 작업은 attempt ID로 조회 가능한 위치와 기존 상태 필드를 반환한다")
    void create_success_accepted() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.create(any())).thenReturn(
                new JobSnapshot(
                        attemptId,
                        JobStatus.PROCESSING,
                        null,
                        null
                )
        );

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(attemptId))
        );

        // then
        response.andExpect(status().isAccepted())
                .andExpect(
                        header().string(
                                "Location",
                                "/api/v1/transcription-jobs/" + attemptId
                        )
                )
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    @DisplayName("완료 작업 조회 응답은 원본 전사 JSON을 객체로 풀지 않고 문자열로 보존한다")
    void get_success_succeededResult() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        String rawWhisperJson = "{\"transcription\":[{\"text\":\"안녕\"}]}";
        when(service.get(attemptId)).thenReturn(
                new JobSnapshot(
                        attemptId,
                        JobStatus.SUCCEEDED,
                        null,
                        new TranscriptResult(
                                1,
                                rawWhisperJson,
                                List.of(
                                        new TranscriptResult.Utterance(
                                                0,
                                                "화자 1",
                                                0,
                                                1,
                                                "안녕"
                                        )
                                )
                        )
                )
        );

        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.result.schemaVersion").value(1))
                .andExpect(jsonPath("$.result.rawWhisperJson").value(rawWhisperJson))
                .andExpect(jsonPath("$.result.utterances[0].ord").value(0))
                .andExpect(jsonPath("$.result.utterances[0].speakerLabel").value("화자 1"))
                .andExpect(jsonPath("$.result.utterances[0].startSec").value(0.0))
                .andExpect(jsonPath("$.result.utterances[0].endSec").value(1.0))
                .andExpect(jsonPath("$.result.utterances[0].text").value("안녕"));
    }

    @Test
    @DisplayName("삭제 요청은 작업 삭제 후 본문 없이 성공한다")
    void delete_success_noContent() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();

        // when
        ResultActions response = mvc.perform(authorized(delete("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).delete(attemptId);
    }

    @Test
    @DisplayName("형식이 잘못된 생성 요청은 한글 검증 오류를 반환한다")
    void create_failure_invalidRequest() throws Exception {
        // given
        String body = "{}";

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("중첩 입력의 contentType 누락은 기본 영어 메시지 없이 거부한다")
    void create_failure_missingNestedContentType() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyWithoutContentType(attemptId))
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("input.contentType"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("타입은 필수입니다"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("잘못된 UUID 경로 변수는 내부 예외명 없이 거부한다")
    void get_failure_invalidUuid() throws Exception {
        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/not-a-uuid")));

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"))
                .andExpect(content().string(not(containsString("MethodArgumentTypeMismatchException"))));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("깨진 JSON 본문은 내부 파서 메시지 없이 거부한다")
    void create_failure_invalidJsonBody() throws Exception {
        // given
        String body = "{";

        // when
        ResultActions response = mvc.perform(
                authorized(post("/api/v1/transcription-jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );

        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"))
                .andExpect(content().string(not(containsString("JSON parse error"))));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("알 수 없는 작업은 내부 오류 없이 찾을 수 없음으로 응답한다")
    void get_failure_unknownJob() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.get(attemptId)).thenThrow(new JobException(JobErrorCode.JOB_NOT_FOUND));

        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("전사 작업을 처리할 수 없습니다"));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 내부 메시지를 가리고 공통 오류로 응답한다")
    void get_failure_unexpectedErrorRedacted() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.get(attemptId)).thenThrow(new IllegalStateException("boom secret"));

        // when
        ResultActions response = mvc.perform(authorized(get("/api/v1/transcription-jobs/" + attemptId)));

        // then
        response.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("요청을 처리할 수 없습니다"))
                .andExpect(content().string(not(containsString("boom secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    @DisplayName("잘못된 HTTP 방식은 내부 예외 없이 거부한다")
    void getHealth_failure_invalidMethod() throws Exception {
        // when
        ResultActions response = mvc.perform(authorized(post("/health")));

        // then
        response.andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header(
                "Authorization",
                "Bearer test-token"
        );
    }

    private String createBody(UUID attemptId) {
        return """
                {"attemptId":"%s","profileId":"profile","requestValidUntil":"2026-10-08T01:00:00Z",
                  "input":{"bucket":"bucket","key":"recordings/a","etag":"etag","downloadUrl":"https://s3",
                  "contentLength":1,"contentType":"audio/webm"},"speakerCount":null}
                """.formatted(attemptId);
    }

    private String createBodyWithoutContentType(UUID attemptId) {
        return """
                {"attemptId":"%s","profileId":"profile","requestValidUntil":"2026-10-08T01:00:00Z",
                  "input":{"bucket":"bucket","key":"recordings/a","etag":"etag","downloadUrl":"https://s3",
                  "contentLength":1},"speakerCount":null}
                """.formatted(attemptId);
    }
}
