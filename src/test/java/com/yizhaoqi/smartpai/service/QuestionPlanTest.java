package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionPlanTest {

    @Test
    void normalizeRejectsTooFewTooManyBlankAndDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("只有一个")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("一", "二", "三", "四")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("问题", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("问题", "问题")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions("不是数组"));
    }

    @Test
    void recordSearchDedupesByFileMd5AndChunkId() {
        QuestionPlan plan = threeQuestionPlan();

        assertEquals(2, plan.recordSearch(1, List.of(
                result("md5-a", 1),
                result("md5-a", 1),
                result("md5-a", 2))));
        assertEquals(2, plan.recordSearch(1, List.of(result("md5-a", 2))));
        assertEquals(3, plan.recordSearch(1, List.of(result("md5-a", 2), result("md5-a", 3))));
        assertEquals(0, plan.recordSearch(2, List.of()));
        assertEquals(1, plan.recordSearch(3, List.of(result("md5-b", 5))));
    }

    @Test
    void unassessedFooterIsRetrievalRecordWithDedupedCounts() {
        QuestionPlan plan = threeQuestionPlan();
        plan.recordSearch(1, List.of(result("md5-a", 1), result("md5-a", 1), result("md5-a", 2)));
        plan.recordSearch(2, List.of());

        String footer = plan.buildEvidenceFooter();

        assertTrue(footer.startsWith("\n\n---\n"));
        assertTrue(footer.contains("**子问题检索记录（未做证据充分性判断）**"));
        assertTrue(footer.contains("子问题 1（住宿标准）：已检索，去重证据 2 条"));
        assertTrue(footer.contains("子问题 2（超标审批）：已检索，去重证据 0 条"));
        assertTrue(footer.contains("子问题 3（报销流程）：未检索"));
        assertFalse(footer.contains("已支撑"));
        assertFalse(footer.contains("**子问题证据充分性"));
    }

    @Test
    void assessedFooterUsesModelStatusAndDedupedCounts() {
        QuestionPlan plan = twoQuestionPlan();
        plan.recordSearch(1, List.of(result("md5-a", 1), result("md5-a", 1)));
        plan.recordSearch(2, List.of());

        plan.recordAssessments(List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of(),
                        "reason", "库内没有审批制度")));

        String footer = plan.buildEvidenceFooter();

        assertTrue(footer.contains("**子问题证据充分性（模型判断 + 代码校验）**"));
        assertTrue(footer.contains("子问题 1（住宿标准）：已支撑，去重证据 1 条"));
        assertTrue(footer.contains("子问题 2（超标审批）：资料未覆盖，去重证据 0 条（说明：库内没有审批制度）"));
        assertFalse(footer.contains("**子问题检索记录"));
    }

    @Test
    void assessedFooterLabelsPartialAndMissingUserCondition() {
        QuestionPlan plan = twoQuestionPlan();
        plan.recordSearch(1, List.of(result("md5-a", 1)));

        plan.recordAssessments(List.of(
                Map.of("index", 1, "status", "PARTIAL", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_USER_CONDITION", "evidence", List.of())));

        String footer = plan.buildEvidenceFooter();

        assertTrue(footer.contains("子问题 1（住宿标准）：部分支撑，去重证据 1 条"));
        assertTrue(footer.contains("子问题 2（超标审批）：缺少用户条件，去重证据 0 条"));
    }

    @Test
    void recordAssessmentsRejectsMissingDuplicateAndOutOfRangeIndexes() {
        QuestionPlan missing = searchedTwoQuestionPlan();
        assertThrows(IllegalArgumentException.class, () -> missing.recordAssessments(List.of(
                Map.of("index", 1, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));

        QuestionPlan duplicate = searchedTwoQuestionPlan();
        assertThrows(IllegalArgumentException.class, () -> duplicate.recordAssessments(List.of(
                Map.of("index", 1, "status", "MISSING_KNOWLEDGE", "evidence", List.of()),
                Map.of("index", 1, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));

        QuestionPlan outOfRange = searchedTwoQuestionPlan();
        assertThrows(IllegalArgumentException.class, () -> outOfRange.recordAssessments(List.of(
                Map.of("index", 1, "status", "MISSING_KNOWLEDGE", "evidence", List.of()),
                Map.of("index", 3, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));
    }

    @Test
    void recordAssessmentsRejectsSupportedWithoutEvidence() {
        QuestionPlan plan = searchedTwoQuestionPlan();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> plan.recordAssessments(List.of(
                        Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of()),
                        Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));

        assertTrue(exception.getMessage().contains("SUPPORTED"));
    }

    @Test
    void recordAssessmentsRejectsEvidenceThatWasNotRetrieved() {
        QuestionPlan plan = searchedTwoQuestionPlan();
        plan.recordSearch(1, List.of(result("md5-a", 1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> plan.recordAssessments(List.of(
                        Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#99")),
                        Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));

        assertTrue(exception.getMessage().contains("md5-a#99"));
    }

    @Test
    void invalidAssessmentsDoNotReplaceTheRetrievalRecord() {
        QuestionPlan plan = searchedTwoQuestionPlan();
        plan.recordSearch(1, List.of(result("md5-a", 1)));

        assertThrows(IllegalArgumentException.class, () -> plan.recordAssessments(List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#99")),
                Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));

        String footer = plan.buildEvidenceFooter();
        assertTrue(footer.contains("**子问题检索记录（未做证据充分性判断）**"));
        assertFalse(footer.contains("**子问题证据充分性"));
    }

    @Test
    void missingStatusesMayCarryEmptyEvidenceButUnknownKeysAreStillChecked() {
        QuestionPlan plan = searchedTwoQuestionPlan();
        plan.recordAssessments(List.of(
                Map.of("index", 1, "status", "MISSING_KNOWLEDGE", "evidence", List.of()),
                Map.of("index", 2, "status", "MISSING_USER_CONDITION", "evidence", List.of())));

        assertTrue(plan.buildEvidenceFooter().contains("资料未覆盖"));
        assertTrue(plan.buildEvidenceFooter().contains("缺少用户条件"));

        QuestionPlan withKey = searchedTwoQuestionPlan();
        assertThrows(IllegalArgumentException.class, () -> withKey.recordAssessments(List.of(
                Map.of("index", 1, "status", "MISSING_KNOWLEDGE", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));
    }

    @Test
    void missingKnowledgeRequiresTheSubQuestionToBeSearched() {
        QuestionPlan plan = twoQuestionPlan();
        plan.recordSearch(1, List.of(result("md5-a", 1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> plan.recordAssessments(List.of(
                        Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#1")),
                        Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()))));

        assertTrue(exception.getMessage().contains("子问题 2"));
        assertTrue(exception.getMessage().contains("MISSING_KNOWLEDGE"));
        assertFalse(plan.hasAssessments(), "未检索的子问题不能落 MISSING_KNOWLEDGE 判断");

        plan.recordSearch(2, List.of());
        plan.recordAssessments(List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of())));

        assertTrue(plan.hasAssessments());
        assertTrue(plan.buildEvidenceFooter().contains("资料未覆盖"));
    }

    @Test
    void missingUserConditionIsAllowedWithoutAnySearch() {
        QuestionPlan plan = twoQuestionPlan();

        plan.recordAssessments(List.of(
                Map.of("index", 1, "status", "MISSING_USER_CONDITION", "evidence", List.of()),
                Map.of("index", 2, "status", "MISSING_USER_CONDITION", "evidence", List.of())));

        String footer = plan.buildEvidenceFooter();
        assertTrue(footer.contains("子问题 1（住宿标准）：缺少用户条件，去重证据 0 条"));
        assertTrue(footer.contains("子问题 2（超标审批）：缺少用户条件，去重证据 0 条"));
    }

    @Test
    void validateIndexRejectsNullZeroAndOutOfRange() {
        QuestionPlan plan = QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("A", "B")));
        assertThrows(IllegalArgumentException.class, () -> plan.validateIndex(null));
        assertThrows(IllegalArgumentException.class, () -> plan.validateIndex(0));
        assertThrows(IllegalArgumentException.class, () -> plan.validateIndex(3));
        assertEquals(1, QuestionPlan.parseIndex("1"));
    }

    @Test
    void footerIsAppendedOnlyOnce() {
        QuestionPlan plan = QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("A", "B")));
        assertFalse(plan.isFooterAppended());
        plan.markFooterAppended();
        assertTrue(plan.isFooterAppended());
    }

    private static QuestionPlan twoQuestionPlan() {
        return QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("住宿标准", "超标审批")));
    }

    private static QuestionPlan threeQuestionPlan() {
        return QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("住宿标准", "超标审批", "报销流程")));
    }

    /**
     * 两个子问题都已检索（零命中也算检索），便于单独验证索引/状态/证据校验
     */
    private static QuestionPlan searchedTwoQuestionPlan() {
        QuestionPlan plan = twoQuestionPlan();
        plan.recordSearch(1, List.of());
        plan.recordSearch(2, List.of());
        return plan;
    }

    private static SearchResult result(String fileMd5, int chunkId) {
        return new SearchResult(fileMd5, chunkId, "片段内容", 0.9, "policy.pdf");
    }
}
