package org.kamranzafar.docman.repository.opensearch;

import org.kamranzafar.docman.model.Document;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentIndexRepository extends ElasticsearchRepository<Document, String> {
}
