package com.collaborative.docs.controller;

import com.collaborative.docs.dto.CreateDocumentRequest;
import com.collaborative.docs.model.CollaborativeDocument;
import com.collaborative.docs.model.OperationLog;
import com.collaborative.docs.service.DocumentService;
import com.collaborative.docs.websocket.SessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST API for document management (non-WebSocket operations).
 *
 * Endpoints:
 *   POST   /api/documents           - Create a new document
 *   GET    /api/documents/{id}      - Get document by ID
 *   GET    /api/documents/{id}/ops  - Get operation history since a version
 *   GET    /api/documents/{id}/reconstruct - Reconstruct from op log (crash recovery)
 *   GET    /api/health              - Health check with session count
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final SessionRegistry sessionRegistry;

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CollaborativeDocument> createDocument(@RequestBody CreateDocumentRequest request) {
        return documentService.createDocument(request.getTitle(), request.getCreatedBy());
    }

    @GetMapping("/documents/{id}")
    public Mono<CollaborativeDocument> getDocument(@PathVariable String id) {
        return documentService.getDocument(id)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(id)));
    }

    @GetMapping("/documents/{id}/ops")
    public Flux<OperationLog> getOperations(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long sinceVersion) {
        return documentService.getOperationsSince(id, sinceVersion);
    }

    @GetMapping("/documents/{id}/reconstruct")
    public Mono<CollaborativeDocument> reconstructDocument(@PathVariable String id) {
        return documentService.reconstructDocument(id);
    }

    @GetMapping("/health")
    public Mono<HealthResponse> health() {
        return Mono.just(new HealthResponse(
                "UP",
                sessionRegistry.getTotalSessionCount()
        ));
    }

    record HealthResponse(String status, int activeSessions) {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class DocumentNotFoundException extends RuntimeException {
        DocumentNotFoundException(String id) {
            super("Document not found: " + id);
        }
    }
}
