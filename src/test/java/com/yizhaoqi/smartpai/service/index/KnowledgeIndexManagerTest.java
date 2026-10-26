package com.yizhaoqi.smartpai.service.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void templateDeclaresChunkContextWithIkAnalyzer() throws Exception {
        JsonNode chunkContext = objectMapper.readTree(manager.buildMappingJson(2048, Map.of()))
                .path("mappings").path("properties").path("chunkContext");
        assertEquals("text", chunkContext.path("type").asText());
        assertEquals("ik_max_word", chunkContext.path("analyzer").asText(),
                "与 textContent 分词器一致，combined_fields 才能把两个字段当成一个字段算 BM25");
        assertEquals("ik_smart", chunkContext.path("search_analyzer").asText());
    }

    @Test
    void onlyMissingNonVectorFieldsAreAppendedToExistingIndex() throws Exception {
        // 版本化之前建的线上索引实际字段集合：旧模板有 isPublic，没有版本/构建字段和文件名
        Set<String> oldIndexFields = Set.of("fileMd5", "chunkId", "pageNumber", "anchorText", "textContent",
                "vector", "modelVersion", "userId", "orgTag", "isPublic");

        var missing = manager.missingTemplateFields(oldIndexFields);

        Set<String> missingFields = new java.util.HashSet<>();
        missing.fieldNames().forEachRemaining(missingFields::add);
        assertEquals(Set.of("documentId", "versionId", "buildId", "versionNo", "buildNo",
                "docType", "jurisdiction", "jurisdictionCode", "issuer", "applicableInstitutions",
                "effectiveAt", "expiresAt", "repealed",
                "articlePath", "clauseNo",
                "chunkContext", "fileName", "public"), missingFields);
        assertFalse(missing.has("isPublic"), "公开标记按新契约改名为 public，旧名不再写入模板");
        assertEquals("boolean", missing.path("public").path("type").asText());
        assertEquals("keyword", missing.path("docType").path("type").asText(), "合规字段必须显式声明，不能走动态映射");
        assertEquals("keyword", missing.path("jurisdiction").path("type").asText());
        assertEquals("keyword", missing.path("jurisdictionCode").path("type").asText());
        assertEquals("keyword", missing.path("issuer").path("type").asText());
        assertEquals("keyword", missing.path("applicableInstitutions").path("type").asText());
        assertEquals("date", missing.path("effectiveAt").path("type").asText());
        assertEquals("date", missing.path("expiresAt").path("type").asText());
        assertEquals("boolean", missing.path("repealed").path("type").asText());
        assertEquals("keyword", missing.path("articlePath").path("type").asText(), "条款路径必须显式声明，不能走动态映射");
        assertEquals("keyword", missing.path("clauseNo").path("type").asText());
        assertFalse(manager.missingTemplateFields(Set.of()).has("vector"), "vector 维度随模型变化，不追加");
        var afterUpgrade = new java.util.HashSet<>(oldIndexFields);
        afterUpgrade.addAll(missingFields);
        assertTrue(manager.missingTemplateFields(afterUpgrade).isEmpty(), "重复调用是空操作");
    }

    @Test
    void refusesToDeleteAliasName() {
        assertThrows(IllegalArgumentException.class, () -> manager.deleteIndex(KnowledgeIndexNames.ALIAS));
    }
}
