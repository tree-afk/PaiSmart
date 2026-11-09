package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 ES 文档序列化名与 mapping 字段名一致：新增版本字段显式写入 mapping，public 不再写成 isPublic
 */
class EsDocumentJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesVersionFieldsAndPublicFlag() throws Exception {
        EsDocument document = new EsDocument();
        document.setId("build-1_0");
        document.setDocumentId("doc-1");
        document.setVersionId("version-1");
        document.setBuildId("build-1");
        document.setVersionNo(2);
        document.setBuildNo(3);
        document.setDocType("REGULATION");
        document.setJurisdiction("全国");
        document.setIssuer("国家金融监督管理总局");
        document.setEffectiveAt("2026-10-01");
        document.setArticlePath("第二章 风险管理 > 第十二条");
        document.setClauseNo("第十二条第二款");
        document.setPublic(true);

        String json = objectMapper.writeValueAsString(document);

        assertThat(json).contains("\"public\":true");
        assertThat(json).contains("\"documentId\":\"doc-1\"");
        assertThat(json).contains("\"versionId\":\"version-1\"");
        assertThat(json).contains("\"buildId\":\"build-1\"");
        assertThat(json).contains("\"versionNo\":2");
        assertThat(json).contains("\"buildNo\":3");
        assertThat(json).contains("\"docType\":\"REGULATION\"");
        assertThat(json).contains("\"jurisdiction\":\"全国\"");
        assertThat(json).contains("\"issuer\":\"国家金融监督管理总局\"");
        assertThat(json).contains("\"effectiveAt\":\"2026-10-01\"");
        assertThat(json).contains("\"articlePath\":\"第二章 风险管理 > 第十二条\"");
        assertThat(json).contains("\"clauseNo\":\"第十二条第二款\"");
        assertThat(json).doesNotContain("\"isPublic\"");
    }

    @Test
    void mappingDeclaresVersionFieldsAndPublicName() throws Exception {
        String mappingJson = Files.readString(
                Path.of("src/main/resources/es-mappings/knowledge_base.json"), StandardCharsets.UTF_8);
        JsonNode properties = objectMapper.readTree(mappingJson).path("mappings").path("properties");

        assertThat(properties.path("documentId").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("versionId").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("buildId").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("versionNo").path("type").asText()).isEqualTo("integer");
        assertThat(properties.path("buildNo").path("type").asText()).isEqualTo("integer");
        assertThat(properties.path("docType").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("jurisdiction").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("issuer").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("effectiveAt").path("type").asText()).isEqualTo("date");
        assertThat(properties.path("articlePath").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("clauseNo").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("public").path("type").asText()).isEqualTo("boolean");
        assertThat(properties.has("isPublic")).isFalse();
    }
}
