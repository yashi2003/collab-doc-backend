package com.collaborative.docs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The collaborative document entity.
 * Stores the current full text content and metadata.
 * The @Version field enables optimistic locking — crucial for concurrent edits.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "documents")
public class CollaborativeDocument {

    @Id
    private String id;

    private String title;
    private String content;             // current full text of the document
    private long currentVersion;        // latest operation version applied

    @Builder.Default
    private List<String> collaborators = new ArrayList<>();   // user IDs currently editing

    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long mongoVersion;          // optimistic locking version (Spring Data)
}
