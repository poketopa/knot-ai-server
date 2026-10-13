package com.knot.ai.transcription.presentation;

import com.knot.ai.transcription.application.JobSnapshot;
import com.knot.ai.transcription.application.TranscriptionJobService;
import com.knot.ai.transcription.application.TranscriptionRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<Map<String, String>> getHealth() {
        boolean ready = service.isReady();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(
                        Map.of(
                                "status",
                                ready ? "UP" : "DOWN"
                        )
                );
    }

    @PostMapping("/api/v1/transcription-jobs")
    public ResponseEntity<JobSnapshot> create(@Valid @RequestBody TranscriptionRequest request) {
        JobSnapshot result = service.create(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/transcription-jobs/" + result.attemptId()))
                .body(result);
    }

    @GetMapping("/api/v1/transcription-jobs/{attemptId}")
    public JobSnapshot get(@PathVariable UUID attemptId) {
        return service.get(attemptId);
    }
}
