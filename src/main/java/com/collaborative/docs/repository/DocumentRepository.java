package com.collaborative.docs.repository;

import com.collaborative.docs.model.CollaborativeDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentRepository extends ReactiveMongoRepository<CollaborativeDocument, String> {
}
