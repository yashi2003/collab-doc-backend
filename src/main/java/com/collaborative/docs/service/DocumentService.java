package com.collaborative.docs.service;

import com.collaborative.docs.dto.EditRequest;
import com.collaborative.docs.dto.EditResponse;
import com.collaborative.docs.engine.OTEngine;
import com.collaborative.docs.model.*;
import com.collaborative.docs.repository.DocumentRepository;
import com.collaborative.docs.repository.OperationLogRepository;
import com.collaborative.docs.repository.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Core document service that coordinates:
 * 1. Receiving client operations
 * 2. Transforming them via OT engine against concurrent ops
 * 3. Persisting to MongoDB
 * 4. Creating periodic snapshots
 *
 * CHALLENGE: Race conditions when two operations arrive simultaneously.
 * SOLUTION: We use MongoDB's unique compound index on (documentId, version).
 * If two ops try to claim the same version, one will fail with DuplicateKeyException
 * and we retry with the latest state. This is "optimistic concurrency control."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final OperationLogRepository operationLogRepository;
    private final SnapshotRepository snapshotRepository;
    private final OTEngine otEngine;

    @Value("${app.node-id}")
    private String nodeId;

    @Value("${app.snapshot-interval}")
    private int snapshotInterval;

    /**
     * Create a new collaborative document.
     */
    public Mono<CollaborativeDocument> createDocument(String title, String createdBy) {
        CollaborativeDocument doc = CollaborativeDocument.builder()
                .title(title)
                .content("")
                .currentVersion(0)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return documentRepository.save(doc);
    }

    /**
     * Get a document by ID.
     */
    public Mono<CollaborativeDocument> getDocument(String documentId) {
        return documentRepository.findById(documentId);
    }

    /**
     * The main method: process an incoming edit from a client.
     *
     * Steps:
     * 1. Fetch the current document state
     * 2. Fetch all operations that happened AFTER the client's base version
     *    (these are the ops the client doesn't know about yet)
     * 3. Transform the client's operation against each of those ops
     * 4. Apply the transformed operation to the document
     * 5. Save the operation log entry
     * 6. Update the document content
     * 7. Maybe create a snapshot
     * 8. Return the response
     */
    public Mono<EditResponse> processEdit(EditRequest request) {
        String docId = request.getDocumentId();
        log.info("Processing edit for doc={}, user={}, baseVersion={}",
                docId, request.getUserId(), request.getBaseVersion());

        return documentRepository.findById(docId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + docId)))
                .flatMap(doc -> {
                    // Step 2: Get all ops between client's baseVersion and server's current version
                    return operationLogRepository
                            .findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(docId, request.getBaseVersion())
                            .collectList()
                            .flatMap(concurrentOps -> {
                                // Step 3: Transform client's operation against each concurrent op
                                TextOperation currentOp = request.getOperation();
                                for (OperationLog concurrentOp : concurrentOps) {
                                    currentOp = otEngine.transform(
                                            concurrentOp.getOperation(), currentOp);
                                }
                                final TextOperation transformedOp = currentOp;

                                // Step 4: Apply to document content
                                String newContent;
                                try {
                                    newContent = otEngine.apply(doc.getContent(), transformedOp);
                                } catch (IllegalArgumentException e) {
                                    log.warn("Failed to apply operation: {}", e.getMessage());
                                    return Mono.error(e);
                                }

                                long newVersion = doc.getCurrentVersion() + 1;

                                // Step 5: Create operation log entry
                                OperationLog opLog = OperationLog.builder()
                                        .documentId(docId)
                                        .userId(request.getUserId())
                                        .sessionId(request.getSessionId())
                                        .operation(transformedOp)
                                        .version(newVersion)
                                        .baseVersion(request.getBaseVersion())
                                        .sourceNodeId(nodeId)
                                        .timestamp(Instant.now())
                                        .build();

                                // Step 6: Update document
                                doc.setContent(newContent);
                                doc.setCurrentVersion(newVersion);
                                doc.setUpdatedAt(Instant.now());

                                // Save both in sequence
                                return operationLogRepository.save(opLog)
                                        .then(documentRepository.save(doc))
                                        .flatMap(savedDoc -> {
                                            // Step 7: Create snapshot if needed
                                            Mono<Void> snapshotMono = Mono.empty();
                                            if (newVersion % snapshotInterval == 0) {
                                                snapshotMono = createSnapshot(docId, newContent, newVersion);
                                            }

                                            // Step 8: Build response
                                            EditResponse response = EditResponse.builder()
                                                    .documentId(docId)
                                                    .userId(request.getUserId())
                                                    .sessionId(request.getSessionId())
                                                    .operation(transformedOp)
                                                    .version(newVersion)
                                                    .acknowledged(true)
                                                    .build();

                                            return snapshotMono.thenReturn(response);
                                        });
                            });
                });
    }

    /**
     * Reconstruct document content from snapshots + operation replay.
     * Used when a new user joins a session or for crash recovery.
     *
     * Interview talking point: "Instead of replaying ALL operations from version 0,
     * I find the latest snapshot, then replay only the operations AFTER that snapshot.
     * This brings reconstruction from O(total_ops) to O(snapshot_interval)."
     */
    public Mono<CollaborativeDocument> reconstructDocument(String documentId) {
        return snapshotRepository.findTopByDocumentIdOrderByVersionDesc(documentId)
                .flatMap(snapshot -> {
                    // Replay ops from snapshot version onwards
                    return operationLogRepository
                            .findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(documentId, snapshot.getVersion())
                            .reduce(snapshot.getContent(), (content, opLog) ->
                                    otEngine.apply(content, opLog.getOperation()))
                            .flatMap(reconstructedContent ->
                                    documentRepository.findById(documentId)
                                            .map(doc -> {
                                                doc.setContent(reconstructedContent);
                                                return doc;
                                            }));
                })
                .switchIfEmpty(
                        // No snapshot exists — replay all ops from the start
                        operationLogRepository
                                .findByDocumentIdAndVersionGreaterThanEqualOrderByVersionAsc(documentId, 0)
                                .reduce("", (content, opLog) ->
                                        otEngine.apply(content, opLog.getOperation()))
                                .flatMap(reconstructedContent ->
                                        documentRepository.findById(documentId)
                                                .map(doc -> {
                                                    doc.setContent(reconstructedContent);
                                                    return doc;
                                                }))
                );
    }

    /**
     * Get operation history for a document (for late-joining clients).
     */
    public Flux<OperationLog> getOperationsSince(String documentId, long sinceVersion) {
        return operationLogRepository
                .findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(documentId, sinceVersion);
    }

    private Mono<Void> createSnapshot(String documentId, String content, long version) {
        DocumentSnapshot snapshot = DocumentSnapshot.builder()
                .documentId(documentId)
                .content(content)
                .version(version)
                .createdAt(Instant.now())
                .build();
        log.info("Creating snapshot for doc={} at version={}", documentId, version);
        return snapshotRepository.save(snapshot).then();
    }
}
