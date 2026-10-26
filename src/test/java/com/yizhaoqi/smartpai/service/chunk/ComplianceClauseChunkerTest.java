package com.yizhaoqi.smartpai.service.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条款切分规则：章/节/条/款路径、无标记回退、阿拉伯数字以及长片段拆分继承。
 */
class ComplianceClauseChunkerTest {

    private final ComplianceClauseChunker chunker = new ComplianceClauseChunker();

    @Test
    void splitsChapterSectionArticleAndClauseWithPaths() {
        String text = """
                第一章 总则

                第一条 为规范公司合规管理，制定本办法。

                第二章 风险管理
                第一节 信用风险

                第十二条 银行应当建立信用风险管理制度。
                第二款 银行应当每季度评估信用风险。
                """;

        List<ComplianceClauseChunker.ClauseUnit> units = chunker.split(text);

        assertThat(units).hasSize(3);

        ComplianceClauseChunker.ClauseUnit article = units.get(1);
        assertThat(article.articlePath()).isEqualTo("第二章 风险管理 > 第一节 信用风险 > 第十二条");
        assertThat(article.clauseNo()).isEqualTo("第十二条");
        assertThat(article.text()).contains("第十二条 银行应当建立信用风险管理制度。");

        ComplianceClauseChunker.ClauseUnit clause = units.get(2);
        assertThat(clause.articlePath()).isEqualTo("第二章 风险管理 > 第一节 信用风险 > 第十二条");
        assertThat(clause.clauseNo()).isEqualTo("第十二条第二款");
        assertThat(clause.text()).isEqualTo("第二款 银行应当每季度评估信用风险。");
    }

    @Test
    void chapterPathIsKeptWhenArticleFollowsDirectly() {
        List<ComplianceClauseChunker.ClauseUnit> units =
                chunker.split("第三章 风险管理\n\n第十二条 银行应当建立制度。\n");

        assertThat(units).hasSize(1);
        assertThat(units.get(0).articlePath()).isEqualTo("第三章 风险管理 > 第十二条");
        assertThat(units.get(0).clauseNo()).isEqualTo("第十二条");
        assertThat(units.get(0).text()).contains("第三章 风险管理");
    }

    @Test
    void noMarkersReturnsSingleUnitWithNullMetadata() {
        String text = "这是普通制度说明文字。\n\n第二段没有章、节、条、款标记。";

        List<ComplianceClauseChunker.ClauseUnit> units = chunker.split(text);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).text()).isEqualTo(text);
        assertThat(units.get(0).articlePath()).isNull();
        assertThat(units.get(0).clauseNo()).isNull();
    }

    @Test
    void supportsArabicNumerals() {
        String text = """
                第2章 风险管理
                第12条 银行应当建立信用风险管理制度。
                第3款 银行应当每季度评估。
                """;

        List<ComplianceClauseChunker.ClauseUnit> units = chunker.split(text);

        assertThat(units).hasSize(2);
        assertThat(units.get(0).articlePath()).isEqualTo("第2章 风险管理 > 第12条");
        assertThat(units.get(0).clauseNo()).isEqualTo("第12条");
        assertThat(units.get(1).articlePath()).isEqualTo("第2章 风险管理 > 第12条");
        assertThat(units.get(1).clauseNo()).isEqualTo("第12条第3款");
    }

    @Test
    void toleratesWhitespaceBeforeMarkersAndNormalizesHeading() {
        String text = "  第三章   风险管理\n    第十二条　银行应当建立制度。\n";

        List<ComplianceClauseChunker.ClauseUnit> units = chunker.split(text);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).articlePath()).isEqualTo("第三章 风险管理 > 第十二条");
        assertThat(units.get(0).clauseNo()).isEqualTo("第十二条");
    }

    @Test
    void longUnitSubChunksInheritArticlePathAndClauseNo() {
        String text = """
                第二章 风险管理
                第十二条 第一段内容。
                第二段内容。
                """;

        List<ComplianceClauseChunker.ClauseUnit> units = chunker.splitWith(text,
                value -> List.of(value.split("\n")));

        assertThat(units).hasSizeGreaterThan(1);
        assertThat(units).allSatisfy(unit -> {
            assertThat(unit.articlePath()).isEqualTo("第二章 风险管理 > 第十二条");
            assertThat(unit.clauseNo()).isEqualTo("第十二条");
        });
    }

    @Test
    void splitterReturningNoPiecesKeepsOriginalUnit() {
        List<ComplianceClauseChunker.ClauseUnit> units =
                chunker.splitWith("第十二条 银行应当建立制度。", value -> List.of());

        assertThat(units).hasSize(1);
        assertThat(units.get(0).text()).isEqualTo("第十二条 银行应当建立制度。");
        assertThat(units.get(0).clauseNo()).isEqualTo("第十二条");
    }

    @Test
    void sessionKeepsArticleAcrossBufferBoundary() {
        ComplianceClauseChunker.Session session = chunker.newSession();

        List<ComplianceClauseChunker.ClauseUnit> first = session.splitWith(
                "第二章 风险管理\n第十二条 银行应当建立信用风险管理制度。\n", value -> List.of(value));
        List<ComplianceClauseChunker.ClauseUnit> second = session.splitWith(
                "银行应当每季度评估信用风险。\n", value -> List.of(value));

        assertThat(first).hasSize(1);
        assertThat(first.get(0).clauseNo()).isEqualTo("第十二条");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).articlePath()).isEqualTo("第二章 风险管理 > 第十二条");
        assertThat(second.get(0).clauseNo()).isEqualTo("第十二条");
        assertThat(second.get(0).text()).contains("每季度评估");
    }

    @Test
    void sessionRecognizesMarkerSplitAcrossBufferBoundary() {
        ComplianceClauseChunker.Session session = chunker.newSession();

        List<ComplianceClauseChunker.ClauseUnit> first = session.split(
                "第二章 风险管理\n第十");
        List<ComplianceClauseChunker.ClauseUnit> second = session.split(
                "二条 银行应当建立信用风险管理制度。\n");

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(second.get(0).articlePath()).isEqualTo("第二章 风险管理 > 第十二条");
        assertThat(second.get(0).clauseNo()).isEqualTo("第十二条");
    }

    @Test
    void pageBreakKeepsClausesOnTheirOwnPages() {
        ComplianceClauseChunker.Session session = chunker.newSession();

        List<ComplianceClauseChunker.ClauseUnit> firstPage =
                session.split("第一章 总则\n第一条 第一页内容");
        List<ComplianceClauseChunker.ClauseUnit> firstTail = session.pageBreak();
        List<ComplianceClauseChunker.ClauseUnit> secondPage =
                session.split("第二条 第二页内容\n");

        assertThat(firstPage).isEmpty();
        assertThat(firstTail).hasSize(1);
        assertThat(firstTail.get(0).clauseNo()).isEqualTo("第一条");
        assertThat(firstTail.get(0).text()).doesNotContain("第二条");

        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).clauseNo()).isEqualTo("第二条");
        assertThat(secondPage.get(0).text()).contains("第二页内容");
    }

    @Test
    void finishEmitsDanglingChapterHeading() {
        ComplianceClauseChunker.Session session = chunker.newSession();

        assertThat(session.split("第二章 风险管理\n")).isEmpty();
        List<ComplianceClauseChunker.ClauseUnit> tail = session.finish();

        assertThat(tail).hasSize(1);
        assertThat(tail.get(0).articlePath()).isEqualTo("第二章 风险管理");
        assertThat(tail.get(0).text()).contains("第二章 风险管理");
    }

    @Test
    void chapterHeadingIsIncludedBeforePlainTextWithoutArticle() {
        List<ComplianceClauseChunker.ClauseUnit> units =
                chunker.split("第二章 风险管理\n这是没有条款号的总述。\n");

        assertThat(units).hasSize(1);
        assertThat(units.get(0).articlePath()).isEqualTo("第二章 风险管理");
        assertThat(units.get(0).text()).contains("第二章 风险管理").contains("这是没有条款号的总述");
    }
}
