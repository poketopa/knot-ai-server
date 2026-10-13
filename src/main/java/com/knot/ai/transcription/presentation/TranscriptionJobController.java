package com.knot.ai.transcription.presentation;

import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.dto.result.JobSnapshot;
import com.knot.ai.transcription.presentation.dto.request.CreateJobRequest;
import com.knot.ai.transcription.presentation.dto.response.HealthResponse;
import com.knot.ai.transcription.presentation.dto.response.TranscriptionJobResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TranscriptionJobController {
    private final TranscriptionJobService service;

    public TranscriptionJobController(TranscriptionJobService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> getHealth() {
        boolean ready = service.isReady();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(
                        new HealthResponse(
                                ready ? "UP" : "DOWN",
                                service.getProfileId()
                        )
                );
    }

    @PostMapping("/api/v1/transcription-jobs")
    public ResponseEntity<TranscriptionJobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        JobSnapshot result = service.create(request.toCommand());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/transcription-jobs/" + result.attemptId()))
                .body(TranscriptionJobResponse.from(result));
    }

    @GetMapping("/api/v1/transcription-jobs/{attemptId}")
    public TranscriptionJobResponse get(@PathVariable UUID attemptId) {
        return TranscriptionJobResponse.from(service.get(attemptId));
    }

    @DeleteMapping("/api/v1/transcription-jobs/{attemptId}")
    public ResponseEntity<Void> delete(@PathVariable UUID attemptId) {
        service.delete(attemptId);
        return ResponseEntity.noContent()
                .build();
    }
}
