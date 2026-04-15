package com.collaborative.docs.dto;

import com.collaborative.docs.model.TextOperation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message sent by the client over WebSocket when the user makes an edit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditRequest {

    private String documentId;
    private String userId;
    private String sessionId;
    private TextOperation operation;
    private long baseVersion;       // the document version the client had when making this edit
}
