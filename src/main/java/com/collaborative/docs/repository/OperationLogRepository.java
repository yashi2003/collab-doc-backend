package com.collaborative.docs.repository;

import com.collaborative.docs.model.OperationLog;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OperationLogRepository extends ReactiveMongoRepository<OperationLog, String> {

    /**
     * Get all operations for a document after a given version, ordered by version ascending.
     * Used to transform incoming operations against all concurrent ops.
     */
    Flux<OperationLog> findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(
            String documentId, long version);

    /**
     * Get all operations for a document from a version onwards.
     * Used for replaying ops from a snapshot.
     */
    Flux<OperationLog> findByDocumentIdAndVersionGreaterThanEqualOrderByVersionAsc(
            String documentId, long version);

    /**
     * Get the latest operation version for a document.
     */
    Mono<OperationLog> findTopByDocumentIdOrderByVersionDesc(String documentId);
}
