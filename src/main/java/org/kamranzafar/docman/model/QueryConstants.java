package org.kamranzafar.docman.model;

public interface QueryConstants {
    String PARENT_DOCUMENT_ID_METADATA_KEY = "parent_document_id";
    String QUERY_COLLAPSE_FIELD = "metadata." + PARENT_DOCUMENT_ID_METADATA_KEY + ".keyword";
    String QUERY_SOURCE_INCLUDE = "metadata";
    String QUERY_METADATA_FIELD_PREFIX = "metadata.";
    int QUERY_MAX_FILTERS = 10;
}
