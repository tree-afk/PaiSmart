package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.ComplianceMetadataPatch;
import com.yizhaoqi.smartpai.service.KnowledgeAuditService;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentControllerMetadataPatchTest {

    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final KnowledgeAuditService auditService = mock(KnowledgeAuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentController();
        ReflectionTestUtils.setField(controller, "documentVersionService", versionService);
        ReflectionTestUtils.setField(controller, "knowledgeAuditService", auditService);
    }

    @Test
    void partialMetadataPatchKeepsMissingFieldsAndClearsExplicitBlank() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {"issuer":"新机构","applicableInstitutions":""}
                """);
        when(versionService.updateMetadataAndRebuild(eq("doc-1"), any(ComplianceMetadataPatch.class), eq("user-1")))
                .thenReturn(new DocumentVersionService.VersionBuild(
                        "doc-1", "v1", "b1", 1, 1, "DEFAULT", true, "md5", "file.pdf"));

        var response = controller.updateMetadata("doc-1", "user-1", body);

        assertEquals(202, response.getStatusCode().value());
        ArgumentCaptor<ComplianceMetadataPatch> captor = ArgumentCaptor.forClass(ComplianceMetadataPatch.class);
        verify(versionService).updateMetadataAndRebuild(eq("doc-1"), captor.capture(), eq("user-1"));
        ComplianceMetadata current = new ComplianceMetadata("REGULATION", "全国", "旧机构",
                LocalDate.of(2026, 1, 1), "旧适用机构", LocalDate.of(2026, 12, 31), true);
        ComplianceMetadata merged = captor.getValue().applyTo(current);
        assertEquals("新机构", merged.issuer());
        assertNull(merged.applicableInstitutions(), "显式空字符串应清空适用机构");
        assertEquals(LocalDate.of(2026, 1, 1), merged.effectiveAt(), "未提交的生效日期保持不变");
        assertEquals(LocalDate.of(2026, 12, 31), merged.expiresAt(), "未提交的失效日期保持不变");
        assertTrue(merged.repealed(), "未提交的废止标记保持不变");
    }
}
