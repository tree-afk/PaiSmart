package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.DocumentBuildContext;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Task 4：解析入库时把 buildId 写到 document_vectors；旧调用不写（保持 NULL，走 legacy 检索分支）。
 */
class ParseServiceBuildTest {

    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final ParseService parseService = new ParseService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(parseService, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(parseService, "chunkSize", 1000);
        ReflectionTestUtils.setField(parseService, "overlapSize", 0);
        ReflectionTestUtils.setField(parseService, "minChunkSize", 1);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 1048576);
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
    }

    @Test
    void parseWithBuildContextWritesBuildIdOnEveryChunk() throws Exception {
        parseService.parseAndSave("md5", stream(), "u1", "org", false,
                new DocumentBuildContext("d1", "v1", "b1", 2, 1));

        DocumentVector saved = capturedChunk();
        assertEquals("b1", saved.getBuildId());
        assertEquals("md5", saved.getFileMd5());
        assertEquals("u1", saved.getUserId());
        assertEquals("org", saved.getOrgTag());
    }

    @Test
    void legacyParseLeavesBuildIdNull() throws Exception {
        parseService.parseAndSave("md5", stream(), "u1", "org", false);

        assertNull(capturedChunk().getBuildId());
    }

    @Test
    void legacyTwoArgumentParseStillParses() throws Exception {
        parseService.parseAndSave("md5", stream());

        DocumentVector saved = capturedChunk();
        assertNull(saved.getBuildId());
        assertEquals("unknown", saved.getUserId());
    }

    @Test
    void complianceContextWritesArticlePathAndClauseNo() throws Exception {
        parseService.parseAndSave("md5", complianceStream(), "u1", "org", false,
                new DocumentBuildContext("d1", "v1", "b1", 2, 1,
                        new ComplianceMetadata("REGULATION", "全国", "国家金融监督管理总局",
                                LocalDate.of(2026, 10, 1))));

        List<DocumentVector> saved = capturedChunks();
        assertTrue(saved.stream().anyMatch(vector ->
                "第二章 风险管理 > 第一节 信用风险 > 第十二条".equals(vector.getArticlePath())
                        && "第十二条".equals(vector.getClauseNo())),
                "条正文分块应带上条款路径和条款号");
        assertTrue(saved.stream().anyMatch(vector ->
                "第十二条第二款".equals(vector.getClauseNo())),
                "款标记应生成带款号的条款号");
        assertTrue(saved.stream().allMatch(vector -> vector.getArticlePath() != null),
                "合规文档的每个分块都应带条款路径");
    }

    @Test
    void nonComplianceContextLeavesClauseMetadataNull() throws Exception {
        parseService.parseAndSave("md5", complianceStream(), "u1", "org", false,
                new DocumentBuildContext("d1", "v1", "b1", 2, 1, ComplianceMetadata.empty()));

        List<DocumentVector> saved = capturedChunks();
        assertTrue(saved.stream().allMatch(vector ->
                        vector.getArticlePath() == null && vector.getClauseNo() == null),
                "未标注合规类型的文档保持原分块行为，不写条款元数据");
    }

    @Test
    void pdfPageBoundaryKeepsClauseAndOriginalPageNumber() {
        DocumentBuildContext context = new DocumentBuildContext("d1", "v1", "b1", 2, 1,
                new ComplianceMetadata("REGULATION", "全国", "监管总局",
                        LocalDate.of(2026, 10, 1)));

        parseService.savePdfPages("md5", List.of(
                new ParseService.LiteParsePage(1, "第一章 总则\n第一条 第一页内容"),
                new ParseService.LiteParsePage(2, "第二条 第二页内容\n")
        ), "u1", "org", false, context);

        List<DocumentVector> saved = capturedChunks();
        DocumentVector first = saved.stream()
                .filter(vector -> vector.getTextContent().contains("第一条"))
                .findFirst().orElseThrow();
        assertEquals(1, first.getPageNumber());
        assertEquals("第一条", first.getClauseNo());
        assertFalse(first.getTextContent().contains("第二条"), "第一页末尾不能把第二页首条拼进同一块");

        DocumentVector second = saved.stream()
                .filter(vector -> vector.getTextContent().contains("第二条"))
                .findFirst().orElseThrow();
        assertEquals(2, second.getPageNumber());
        assertEquals("第二条", second.getClauseNo());
    }

    @Test
    void longTailAtStreamEndIsSplitByChunkSizeAndKeepsClauseContext() {
        ReflectionTestUtils.setField(parseService, "chunkSize", 100);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 10000);
        DocumentBuildContext context = new DocumentBuildContext("d1", "v1", "b1", 2, 1,
                new ComplianceMetadata("REGULATION", "全国", "监管总局",
                        LocalDate.of(2026, 10, 1)));
        ParseService.StreamingContentHandler handler = parseService.new StreamingContentHandler(
                "md5", "u1", "org", false, context);
        String longTail = "第一条 " + "银行应当建立合规管理制度。".repeat(60);

        handler.characters(longTail.toCharArray(), 0, longTail.length());
        handler.endDocument();

        List<DocumentVector> saved = capturedChunks();
        assertTrue(saved.size() > 1, "文档末尾的长条款也必须拆成多个子切片");
        assertTrue(saved.stream().allMatch(vector -> vector.getTextContent().length() <= 100),
                "结束收尾片段也必须遵守 chunkSize");
        assertTrue(saved.stream().allMatch(vector -> "第一条".equals(vector.getClauseNo())
                        && "第一条".equals(vector.getArticlePath())),
                "拆分后的收尾片段仍应继承原条款元数据");
    }

    @Test
    void tikaDocumentLongTailAlsoRespectsChunkSize() throws Exception {
        ReflectionTestUtils.setField(parseService, "chunkSize", 100);
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 10000);
        DocumentBuildContext context = new DocumentBuildContext("d1", "v1", "b1", 2, 1,
                new ComplianceMetadata("REGULATION", "全国", "监管总局",
                        LocalDate.of(2026, 10, 1)));
        String longTail = "第一条 " + "银行应当建立合规管理制度。".repeat(60);

        parseService.parseAndSave("md5", new ByteArrayInputStream(longTail.getBytes(StandardCharsets.UTF_8)),
                "u1", "org", false, context);

        List<DocumentVector> saved = capturedChunks();
        assertTrue(saved.size() > 1, "Tika 文档末尾的长文本也必须拆成多个子切片");
        assertTrue(saved.stream().allMatch(vector -> vector.getTextContent().length() <= 100),
                "Tika 文档的结束收尾片段也必须遵守 chunkSize");
        assertTrue(saved.stream().allMatch(vector -> "第一条".equals(vector.getClauseNo())),
                "Tika 文档收尾片段的条款元数据不能丢失");
    }

    @Test
    void longArticleKeepsClauseContextAcrossStreamingBuffers() throws Exception {
        ReflectionTestUtils.setField(parseService, "parentChunkSize", 60);
        DocumentBuildContext context = new DocumentBuildContext("d1", "v1", "b1", 2, 1,
                new ComplianceMetadata("REGULATION", "全国", "监管总局",
                        LocalDate.of(2026, 10, 1)));
        ParseService.StreamingContentHandler handler = parseService.new StreamingContentHandler(
                "md5", "u1", "org", false, context);

        String first = "第二章 风险管理\n第十二条 银行应当建立信用风险管理制度。\n"
                + "银行应当持续评估信用风险。".repeat(5) + "\n";
        String second = "续写内容：这一部分必须仍然属于第十二条。\n";
        handler.characters(first.toCharArray(), 0, first.length());
        handler.characters(second.toCharArray(), 0, second.length());
        handler.endDocument();

        List<DocumentVector> saved = capturedChunks();
        assertTrue(saved.size() > 1, "父块大小应把长条款拆成多个缓冲区");
        assertTrue(saved.stream()
                        .filter(vector -> vector.getTextContent().contains("续写内容"))
                        .allMatch(vector -> "第二章 风险管理 > 第十二条".equals(vector.getArticlePath())
                                && "第十二条".equals(vector.getClauseNo())),
                "跨缓冲区后的续写片段仍应带第十二条路径");
    }

    private DocumentVector capturedChunk() {
        return capturedChunks().get(0);
    }

    private List<DocumentVector> capturedChunks() {
        ArgumentCaptor<DocumentVector> captor = ArgumentCaptor.forClass(DocumentVector.class);
        verify(documentVectorRepository, atLeastOnce()).save(captor.capture());
        List<DocumentVector> saved = captor.getAllValues();
        assertFalse(saved.isEmpty(), "解析出的分块内容不应为空");
        assertTrue(saved.stream().noneMatch(vector ->
                        vector.getTextContent() == null || vector.getTextContent().isBlank()),
                "解析出的分块内容不应为空");
        return saved;
    }

    private static ByteArrayInputStream stream() {
        String text = "第一段内容。这是用于验证按构建写入的文本。\n\n第二段内容。解析后的分块需要带上构建标识。";
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream complianceStream() {
        String text = """
                第一章 总则

                第一条 为规范公司合规管理，制定本办法。

                第二章 风险管理
                第一节 信用风险

                第十二条 银行应当建立信用风险管理制度。
                第二款 银行应当每季度评估信用风险。
                """;
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
