package com.yizhaoqi.smartpai.client;

import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeepSeekClientSummaryPromptTest {

    private final DeepSeekClient client = new DeepSeekClient(
            "http://localhost:1",
            "",
            "test-model",
            new AiProperties(),
            mock(UsageQuotaService.class),
            mock(ModelProviderConfigService.class));

    @Test
    void summaryPromptCarriesComplianceMetadataValidityAndGlobalReferenceNumbers() {
        SearchResult result = new SearchResult("md5-a", 7, "上海地区商业银行应当每季度评估信用风险。", 0.9, "上海制度.pdf");
        result.setDocType("REGULATION");
        result.setJurisdiction("上海");
        result.setJurisdictionCode("上海");
        result.setIssuer("上海监管局");
        result.setApplicableInstitutions("上海地区商业银行");
        result.setEffectiveAt("2026-01-01");
        result.setExpiresAt("2026-12-31");
        result.setRepealed(false);
        result.setValidityStatus("UNCONFIRMED");
        result.setValidityLabel("效力待确认");
        result.setArticlePath("第二章 风险管理 > 第十二条");
        result.setClauseNo("第十二条第二款");

        List<Map<String, String>> messages = client.buildSummaryMessages("上海信用风险", List.of(result), 6);
        String system = messages.get(0).get("content");
        String user = messages.get(1).get("content");

        assertThat(system).contains("不是效力等级", "UNCONFIRMED", "appliesTo", "待核实/需法务确认");
        assertThat(user)
                .contains("[6] 文件：上海制度.pdf")
                .contains("docType=REGULATION")
                .contains("jurisdiction=上海")
                .contains("issuer=上海监管局")
                .contains("appliesTo=上海地区商业银行")
                .contains("effectiveAt=2026-01-01")
                .contains("expiresAt=2026-12-31")
                .contains("validity=UNCONFIRMED(效力待确认)")
                .contains("article=第二章 风险管理 > 第十二条")
                .contains("clause=第十二条第二款");
        assertThat(user).doesNotContain("[1] 文件：上海制度.pdf");
    }
}
