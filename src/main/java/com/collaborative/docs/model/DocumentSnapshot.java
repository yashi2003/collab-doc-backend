package com.collaborative.docs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A periodic snapshot of the full document content.
 * Used to speed up document reconstruction — instead of replaying ALL ops from version 0,
 * we find the nearest snapshot and replay only ops after it.
 *
 * Interview talking point: "Without snapshots, reconstructing a document with 10,000 edits
 * means replaying 10,000 operations. With snapshots every 50 ops, we replay at most 50."
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "document_snapshots")
public class DocumentSnapshot {

    @Id
    private String id;

    @Indexed
    private String documentId;

    private String content;         // full document text at this point
    private long version;           // the operation version this snapshot represents

    private Instant createdAt;
}
