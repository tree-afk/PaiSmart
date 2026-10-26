package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.resilience.ModelCallGuard;
import com.yizhaoqi.smartpai.client.resilience.ModelWebClientFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ModelCallProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LlmProviderRouterPromptTest {

    @Test
    void reactPromptDescribesPlanCoverageAndClarification() {
        String system = reactSystemPrompt();

        assertTrue(system.contains("plan_question"));
        assertTrue(system.contains("subQuestionIndex"));
        assertTrue(system.contains("ask_clarification"));
        assertTrue(system.contains("部分子问题没有证据时"));
    }

    @Test
    void reactPromptRequiresStructuredEvidenceReportBeforeFinalAnswer() {
        String system = reactSystemPrompt();

        assertTrue(system.contains("report_evidence"));
        assertTrue(system.contains("fileMd5#chunkId"));
        assertTrue(system.contains("每个子问题恰好"));
        assertTrue(system.contains("SUPPORTED"));
        assertTrue(system.contains("PARTIAL"));
        assertTrue(system.contains("MISSING_KNOWLEDGE"));
        assertTrue(system.contains("MISSING_USER_CONDITION"));
        assertTrue(system.contains("不得在没有证据时声称 SUPPORTED"));
    }

    @Test
    void reactPromptContainsComplianceAnswerPolicy() {
        String system = reactSystemPrompt();

        assertTrue(system.contains("合规问答（银行/保险）"));
        assertTrue(system.contains("不是效力等级"), "文档类型不能直接当效力等级");
        assertTrue(system.contains("禁止仅凭"), "禁止仅凭类型排序判定冲突");
        assertTrue(system.contains("不要只检索监管规定"), "除非用户要求，否则要覆盖内部制度和产品条款");
        assertTrue(system.contains("列出每份资料的发布机构、适用地区/适用机构、生效/失效/废止状态、具体条款和差异"));
        assertTrue(system.contains("待核实/需法务确认"));

        assertTrue(system.contains("issuer 只表示“谁发布”"));
        assertTrue(system.contains("appliesTo"));
        assertTrue(system.contains("不得用发布机构反推资料适用于哪些机构"));
        assertTrue(system.contains("地方 + 全国适用"));

        assertTrue(system.contains("地区/机构/文档类型"));
        assertTrue(system.contains("search_knowledge 必须填写 jurisdictions/docTypes"));
        assertTrue(system.contains("不要自行猜测地区"));
        assertTrue(system.contains("不要猜测地区或机构"));
        assertTrue(system.contains("地区、机构或生效版本不明确且会改变结论时，先调用 ask_clarification"));
        assertTrue(system.contains("不要直接给结论"));

        assertTrue(system.contains("UNCONFIRMED"), "必须保留效力待确认状态");
        assertTrue(system.contains("生效日期未标注，效力待确认"));
        assertTrue(system.contains("不得直接断言现行有效"));
        assertTrue(system.contains("只支持当前可检索版本（检索层已按生效日期、失效日期和废止标记过滤）"));
        assertTrue(system.contains("历史版本"));
        assertTrue(system.contains("旧政策"));
        assertTrue(system.contains("当前只支持现行可检索版本"));
        assertTrue(system.contains("不要用旧资料冒充现行版本"));

        assertTrue(system.contains("generate_summary 必须沿用同一次检索的地区/文档类型条件"),
                "摘要必须沿用检索过滤条件");
        assertTrue(system.contains("回答必须引用到文件 + articlePath/clauseNo"));
        assertTrue(system.contains("没有条款依据时拒答或只给部分回答"));
        assertTrue(system.contains("缺失的是资料还是用户条件"));
        assertTrue(system.contains("MISSING_KNOWLEDGE / MISSING_USER_CONDITION"));

        assertTrue(system.contains("只回答「制度/条款/流程/适用条件」类问题"));
        assertTrue(system.contains("不做客户级授信、核保、理赔裁定"));
        assertTrue(system.contains("不输出法律意见"));
        assertTrue(system.contains("需要客户、保单、工单等业务数据时"));
        assertTrue(system.contains("不要编造事实或结论"));
    }

    /**
     * Step 4 只追加合规回答策略，不得放宽 WP-1 的计划/证据规则和 Step 2 的合规过滤规则。
     */
    @Test
    void reactPromptKeepsWp1PlanEvidenceAndStep2ComplianceFilterRules() {
        String system = reactSystemPrompt();

        assertTrue(system.contains("plan_question"), "WP-1 复合问题计划规则");
        assertTrue(system.contains("逐项调用 search_knowledge"));
        assertTrue(system.contains("subQuestionIndex"));
        assertTrue(system.contains("每个子问题恰好报告一次证据充分性判断"));
        assertTrue(system.contains("fileMd5#chunkId"));
        assertTrue(system.contains("证据必须来自本次真实检索并去重"));
        assertTrue(system.contains("不得在没有证据时声称 SUPPORTED"));
        assertTrue(system.contains("部分子问题没有证据时"));
        assertTrue(system.contains("ask_clarification"), "WP-1 澄清规则");

        assertTrue(system.contains("不要只检索监管规定"), "覆盖内部制度和产品条款，而不是按层级只查高等级资料");
        assertTrue(system.contains("jurisdictions/docTypes"), "Step 2 合规过滤参数");
        assertTrue(system.contains("只支持当前可检索版本"), "Step 2 生效/失效/废止过滤口径");
        assertTrue(!system.contains("监管规定优先于内部制度/产品条款"), "不能把分类直接写成效力判断");
    }

    private static String reactSystemPrompt() {
        LlmProviderRouter router = new LlmProviderRouter(
                new AiProperties(),
                mock(RateLimitService.class),
                mock(UsageQuotaService.class),
                mock(ModelProviderConfigService.class),
                new ObjectMapper(),
                mock(ModelWebClientFactory.class),
                mock(ModelCallGuard.class),
                new ModelCallProperties());

        List<Map<String, Object>> messages = router.buildReActMessages("合规问题", "", List.of(), "");
        return String.valueOf(messages.get(0).get("content"));
    }
}
