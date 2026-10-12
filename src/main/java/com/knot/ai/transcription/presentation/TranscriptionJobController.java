package com.knot.ai.transcription.presentation;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.JobSnapshot;
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
    public ResponseEntity<JobSnapshot> create(@Valid @RequestBody CreateJobRequest request) {
        JobSnapshot result = service.create(request.toCommand());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/transcription-jobs/" + result.attemptId()))
                .body(result);
    }
    @GetMapping("/api/v1/transcription-jobs/{attemptId}")
    public JobSnapshot get(@PathVariable UUID attemptId) {
        return service.get(attemptId);
    }
    @DeleteMapping("/api/v1/transcription-jobs/{attemptId}")
    public ResponseEntity<Void> delete(@PathVariable UUID attemptId) {
        service.delete(attemptId);
        return ResponseEntity.noContent()
                .build();
    }
    public record HealthResponse(
            String status,
            String profileId
    ) {
    }
}
