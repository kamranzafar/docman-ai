package org.kamranzafar.docman.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;

import java.util.Map;
import java.util.UUID;

@org.springframework.data.elasticsearch.annotations.Document(indexName = "document_index")
@org.springframework.data.mongodb.core.mapping.Document(collection = "documents")
@Data
public class Document {
    @Id
    UUID id;
    String name;
    String location;
    String contentType;
    String status;
    @Transient
    byte[] data;
    Map<String, String> metadata;
}
