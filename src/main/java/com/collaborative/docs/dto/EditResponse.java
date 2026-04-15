package com.collaborative.docs.dto;

import com.collaborative.docs.model.TextOperation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message broadcast to all clients after the server processes an edit.
 * Contains the TRANSFORMED operation (already adjusted for concurrency).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditResponse {

    private String documentId;
    private String userId;          // who made the edit (so the sender can ignore their own echo)
    private String sessionId;
    private TextOperation operation;
    private long version;           // the new server version after this operation
    private boolean acknowledged;   // true only for the original sender (ACK pattern)
}
