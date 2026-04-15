package com.collaborative.docs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A versioned operation record stored in MongoDB.
 * Each edit by any user becomes one OperationLog entry.
 * The (documentId + version) pair is unique — this is our operation log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "operation_log")
@CompoundIndex(name = "doc_version_idx", def = "{'documentId': 1, 'version': 1}", unique = true)
public class OperationLog {

    @Id
    private String id;

    private String documentId;
    private String userId;
    private String sessionId;

    private TextOperation operation;

    private long version;           // monotonically increasing per document
    private long baseVersion;       // the version the client was at when it generated this op

    private String sourceNodeId;    // which server node created this — prevents Redis echo loops

    private Instant timestamp;
}
