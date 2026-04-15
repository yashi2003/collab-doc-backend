package com.collaborative.docs.service;

import com.collaborative.docs.dto.EditRequest;
import com.collaborative.docs.dto.EditResponse;
import com.collaborative.docs.engine.OTEngine;
import com.collaborative.docs.model.CollaborativeDocument;
import com.collaborative.docs.model.OperationLog;
import com.collaborative.docs.model.OperationType;
import com.collaborative.docs.model.TextOperation;
import com.collaborative.docs.repository.DocumentRepository;
import com.collaborative.docs.repository.OperationLogRepository;
import com.collaborative.docs.repository.SnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private OperationLogRepository operationLogRepository;

    @Mock
    private SnapshotRepository snapshotRepository;

    @Spy
    private OTEngine otEngine = new OTEngine();

    @InjectMocks
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(documentService, "nodeId", "test-node");
        ReflectionTestUtils.setField(documentService, "snapshotInterval", 50);
    }

    @Test
    @DisplayName("Create a new document")
    void createDocument() {
        when(documentRepository.save(any(CollaborativeDocument.class)))
                .thenAnswer(inv -> {
                    CollaborativeDocument doc = inv.getArgument(0);
                    doc.setId("doc-123");
                    return Mono.just(doc);
                });

        StepVerifier.create(documentService.createDocument("Test Doc", "user-1"))
                .assertNext(doc -> {
                    assertEquals("doc-123", doc.getId());
                    assertEquals("Test Doc", doc.getTitle());
                    assertEquals("", doc.getContent());
                    assertEquals(0, doc.getCurrentVersion());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Process a simple insert edit with no concurrent operations")
    void processSimpleInsert() {
        CollaborativeDocument doc = CollaborativeDocument.builder()
                .id("doc-123").content("Hello").currentVersion(0)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        when(documentRepository.findById("doc-123")).thenReturn(Mono.just(doc));
        when(operationLogRepository.findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(anyString(), anyLong()))
                .thenReturn(Flux.empty());
        when(operationLogRepository.save(any(OperationLog.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(documentRepository.save(any(CollaborativeDocument.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        EditRequest request = EditRequest.builder()
                .documentId("doc-123")
                .userId("user-1")
                .sessionId("session-1")
                .operation(TextOperation.builder()
                        .type(OperationType.INSERT).position(5).text(" World").length(6).build())
                .baseVersion(0)
                .build();

        StepVerifier.create(documentService.processEdit(request))
                .assertNext(response -> {
                    assertEquals("doc-123", response.getDocumentId());
                    assertEquals(1, response.getVersion());
                    assertEquals(true, response.isAcknowledged());
                })
                .verifyComplete();

        // Verify the document content was updated
        assertEquals("Hello World", doc.getContent());
    }

    @Test
    @DisplayName("Process edit with concurrent operations requires OT transform")
    void processEditWithConcurrentOps() {
        // Document was "ABC", now at version 1 after someone inserted "X" at pos 0
        CollaborativeDocument doc = CollaborativeDocument.builder()
                .id("doc-123").content("XABC").currentVersion(1)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        // The concurrent operation that happened: INSERT("X", pos=0) at version 1
        OperationLog concurrentOp = OperationLog.builder()
                .documentId("doc-123")
                .operation(TextOperation.builder()
                        .type(OperationType.INSERT).position(0).text("X").length(1).build())
                .version(1)
                .build();

        when(documentRepository.findById("doc-123")).thenReturn(Mono.just(doc));
        when(operationLogRepository.findByDocumentIdAndVersionGreaterThanOrderByVersionAsc("doc-123", 0))
                .thenReturn(Flux.just(concurrentOp));
        when(operationLogRepository.save(any(OperationLog.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(documentRepository.save(any(CollaborativeDocument.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        // Client is at version 0, wants to insert "Y" at position 2 (between B and C in "ABC")
        EditRequest request = EditRequest.builder()
                .documentId("doc-123")
                .userId("user-2")
                .sessionId("session-2")
                .operation(TextOperation.builder()
                        .type(OperationType.INSERT).position(2).text("Y").length(1).build())
                .baseVersion(0)
                .build();

        StepVerifier.create(documentService.processEdit(request))
                .assertNext(response -> {
                    assertEquals(2, response.getVersion());
                    // The operation should have been transformed: pos 2 -> pos 3
                    // because the concurrent INSERT at pos 0 shifted everything right by 1
                    assertEquals(3, response.getOperation().getPosition());
                })
                .verifyComplete();

        // Document should now be "XABYC"
        assertEquals("XABYC", doc.getContent());
    }
}
