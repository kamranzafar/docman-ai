package org.kamranzafar.docman.repository.mongo;

import org.kamranzafar.docman.model.Document;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;
public interface DocumentMetadataRepository extends MongoRepository<Document, UUID> {
}
