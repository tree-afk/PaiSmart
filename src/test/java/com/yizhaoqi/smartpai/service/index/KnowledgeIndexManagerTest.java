package com.yizhaoqi.smartpai.service.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class KnowledgeIndexManagerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KnowledgeIndexManager manager = new KnowledgeIndexManager(
            mock(ElasticsearchClient.class), new ClassPathResource("es-mappings/knowledge_base.json"), objectMapper);

    @Test
    void mappingUsesTargetDimensionsAndRecordsModelMeta() throws Exception {
        String json = manager.buildMappingJson(1024, Map.of(
                KnowledgeIndexManager.META_PROVIDER, "zhipu",
                KnowledgeIndexManager.META_MODEL_VERSION, "zhipu:embedding-3:1024"));

        JsonNode mappings = objectMapper.readTree(json).path("mappings");
        assertEquals(1024, mappings.path("properties").path("vector").path("dims").asInt());
        assertEquals("dense_vector", mappings.path("properties").path("vector").path("type").asText());
        assertEquals("zhipu:embedding-3:1024", mappings.path("_meta").path("modelVersion").asText());
        assertEquals("ik_max_word", mappings.path("properties").path("textContent").path("analyzer").asText());
    }

    @Test
    void refusesToDeleteAliasName() {
        assertThrows(IllegalArgumentException.class, () -> manager.deleteIndex(KnowledgeIndexNames.ALIAS));
    }
}
