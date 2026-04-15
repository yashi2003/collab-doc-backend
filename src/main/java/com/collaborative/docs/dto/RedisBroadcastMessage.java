package com.collaborative.docs.dto;

import com.collaborative.docs.model.TextOperation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message published to Redis Pub/Sub for cross-node broadcast.
 * Includes sourceNodeId so the receiving node can skip re-broadcast.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedisBroadcastMessage {

    private String documentId;
    private String userId;
    private String sessionId;
    private TextOperation operation;
    private long version;
    private String sourceNodeId;    // prevents infinite echo loops across nodes
}
