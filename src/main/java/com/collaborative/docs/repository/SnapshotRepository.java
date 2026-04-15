package com.collaborative.docs.repository;

import com.collaborative.docs.model.DocumentSnapshot;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface SnapshotRepository extends ReactiveMongoRepository<DocumentSnapshot, String> {

    /**
     * Find the most recent snapshot for a document.
     * Used during document reconstruction to avoid replaying all ops from version 0.
     */
    Mono<DocumentSnapshot> findTopByDocumentIdOrderByVersionDesc(String documentId);
}
