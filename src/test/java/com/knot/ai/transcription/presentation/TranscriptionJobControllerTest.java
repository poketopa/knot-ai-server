package com.knot.ai.transcription.presentation;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.knot.ai.TestFixtures;
import com.knot.ai.global.*;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.dto.result.JobSnapshot;
import com.knot.ai.transcription.domain.JobStatus;
import com.knot.ai.transcription.domain.JobErrorCode;
import com.knot.ai.transcription.domain.JobException;
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
    @DisplayName("인증 없는 요청은 작업 정보를 노출하지 않는다")
    void getHealth_failure_noAuthentication() throws Exception {
        // given
        // when
        ResultActions response = mvc.perform(get("/health"));
        // then
        response.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }
    @Test
    @DisplayName("인증된 health 응답은 실제 실행 프로필을 반환한다")
    void getHealth_success() throws Exception {
        // given
        when(service.isReady()).thenReturn(true);
        when(service.getProfileId()).thenReturn("profile");
        // when
        ResultActions response = mvc.perform(
                get("/health").header(
                        "Authorization",
                        "Bearer test-token"
                )
        );
        // then
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value("profile"));
    }
    @Test
    @DisplayName("형식이 잘못된 생성 요청은 한글 검증 오류를 반환한다")
    void create_failure_invalidRequest() throws Exception {
        // given
        String body = "{}";
        // when
        ResultActions response = mvc.perform(
                post("/api/v1/transcription-jobs").header(
                        "Authorization",
                        "Bearer test-token"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
        // then
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
        verifyNoInteractions(service);
    }
    @Test
    @DisplayName("수락된 작업은 attempt ID로 조회 가능한 위치를 반환한다")
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
        String body = createBody(attemptId);
        // when
        ResultActions response = mvc.perform(
                post("/api/v1/transcription-jobs").header(
                        "Authorization",
                        "Bearer test-token"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
        // then
        response.andExpect(status().isAccepted())
                .andExpect(
                        header().string(
                                "Location",
                                "/api/v1/transcription-jobs/" + attemptId
                        )
                )
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()));
    }
    @Test
    @DisplayName("알 수 없는 작업은 내부 오류 없이 찾을 수 없음으로 응답한다")
    void get_failure_unknownJob() throws Exception {
        // given
        UUID attemptId = UUID.randomUUID();
        when(service.get(attemptId)).thenThrow(new JobException(JobErrorCode.JOB_NOT_FOUND));
        // when
        ResultActions response = mvc.perform(
                get("/api/v1/transcription-jobs/" + attemptId).header(
                        "Authorization",
                        "Bearer test-token"
                )
        );
        // then
        response.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }
    @Test
    @DisplayName("잘못된 HTTP 방식은 내부 예외 없이 거부한다")
    void getHealth_failure_invalidMethod() throws Exception {
        // given
        // when
        ResultActions response = mvc.perform(
                post("/health").header(
                        "Authorization",
                        "Bearer test-token"
                )
        );
        // then
        response.andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(service);
    }
    private String createBody(UUID attemptId) {
        return """
                {"attemptId":"%s","profileId":"profile","requestValidUntil":"2026-10-08T01:00:00Z",
                  "input":{"bucket":"bucket","key":"recordings/a","etag":"etag","downloadUrl":"https://s3",
                  "contentLength":1,"contentType":"audio/webm"},"speakerCount":null}
                """.formatted(attemptId);
    }

}
