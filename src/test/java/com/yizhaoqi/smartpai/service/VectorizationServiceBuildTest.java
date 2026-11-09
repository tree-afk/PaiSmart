package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.DocumentBuildContext;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.contextual.ChunkContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 4：按 build 向量化时读分块、写 ES 的 _id 和版本字段都必须以 build 为单位；
 * 旧路径（context 为 null）继续用 fileMd5。
 */
class VectorizationServiceBuildTest {

    private static final ModelProviderConfigService.ActiveProviderView PROVIDER = new ModelProviderConfigService.ActiveProviderView(
            "aliyun", "阿里云", "openai-compatible", "https://dashscope", "text-embedding-v4", "k", 2048);
    private static final String MODEL_VERSION = "aliyun:text-embedding-v4:2048";
    private static final ComplianceMetadata COMPLIANCE = new ComplianceMetadata(
            "REGULATION", "全国", "国家金融监督管理总局", LocalDate.of(2026, 10, 1));
    private static final DocumentBuildContext CONTEXT =
            new DocumentBuildContext("d1", "v1", "b1", 2, 3, COMPLIANCE);

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final ElasticsearchService elasticsearchService = mock(ElasticsearchService.class);
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
    private final ChunkContextService chunkContextService = mock(ChunkContextService.class);
    private final VectorizationService service = new VectorizationService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(service, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(service, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(service, "modelProviderConfigService", providerConfig);
        ReflectionTestUtils.setField(service, "chunkContextService", chunkContextService);
        ReflectionTestUtils.setField(service, "batchSize", 10);
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(PROVIDER);
        when(embeddingClient.embedWithProvider(anyList(), eq("u1"), eq(EmbeddingClient.UsageType.UPLOAD), eq(PROVIDER)))
                .thenAnswer(invocation -> {
                    List<?> texts = invocation.getArgument(0);
                    return new EmbeddingClient.EmbeddingUsageResult(
                            texts.stream().map(t -> new float[]{1f}).toList(), texts.size() * 5, MODEL_VERSION);
                });
    }

    @Test
    void buildContextReadsChunksByBuildIdAndWritesBuildMetadata() {
        stubBuildChunks();

        VectorizationService.VectorizationUsageResult result =
                service.vectorizeWithUsage("md5", "u1", "org", true, "u1", CONTEXT, () -> { });

        assertEquals(2, result.actualChunkCount());
        verify(documentVectorRepository).findByBuildIdOrderByChunkIdAsc(eq("b1"), eq(PageRequest.of(0, 10)));
        verify(documentVectorRepository, never()).findByFileMd5OrderByChunkIdAsc(any(), any());

        List<EsDocument> docs = capturedDocuments();
        assertEquals(2, docs.size());
        assertEquals("b1_1", docs.get(0).getId());
        assertEquals("b1_2", docs.get(1).getId());
        for (EsDocument doc : docs) {
            assertEquals("md5", doc.getFileMd5());
            assertEquals("d1", doc.getDocumentId());
            assertEquals("v1", doc.getVersionId());
            assertEquals("b1", doc.getBuildId());
            assertEquals(2, doc.getVersionNo());
            assertEquals(3, doc.getBuildNo());
            assertEquals("REGULATION", doc.getDocType());
            assertEquals("全国", doc.getJurisdiction());
            assertEquals("国家金融监督管理总局", doc.getIssuer());
            assertEquals("2026-10-01", doc.getEffectiveAt());
            assertEquals("第二章 风险管理 > 第十二条", doc.getArticlePath());
            assertEquals(doc.getChunkId() == 1 ? "第十二条" : "第十二条第二款", doc.getClauseNo());
        }
        assertEquals(1, docs.get(0).getChunkId());
        assertEquals(2, docs.get(1).getChunkId());
    }

    @Test
    void nullContextKeepsFileMd5QueryAndLegacyId() {
        stubLegacyChunks();

        service.vectorizeWithUsage("md5", "u1", "org", true, "u1", null, () -> { });

        verify(documentVectorRepository).findByFileMd5OrderByChunkIdAsc(eq("md5"), any(Pageable.class));
        verify(documentVectorRepository, never()).findByBuildIdOrderByChunkIdAsc(any(), any());

        List<EsDocument> docs = capturedDocuments();
        assertEquals("md5_1", docs.get(0).getId());
        assertEquals("md5_2", docs.get(1).getId());
        assertEquals(null, docs.get(0).getBuildId());
        assertEquals(null, docs.get(0).getDocumentId());
        assertEquals(null, docs.get(0).getVersionId());
        assertEquals(null, docs.get(0).getDocType());
        assertEquals(null, docs.get(0).getEffectiveAt());
        assertEquals(null, docs.get(0).getArticlePath());
        assertEquals(null, docs.get(0).getClauseNo());
    }

    @Test
    void legacyOverloadStillUsesFileMd5() {
        stubLegacyChunks();

        VectorizationService.VectorizationUsageResult result = service.vectorizeWithUsage("md5", "u1", "org", true, "u1");

        assertEquals(2, result.actualChunkCount());
        verify(documentVectorRepository).findByFileMd5OrderByChunkIdAsc(eq("md5"), any(Pageable.class));
        assertEquals("md5_1", capturedDocuments().get(0).getId());
    }

    private void stubBuildChunks() {
        when(documentVectorRepository.findByBuildIdOrderByChunkIdAsc(eq("b1"), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            int from = (int) pageable.getOffset();
            List<DocumentVector> content = IntStream.range(Math.min(from, 2), Math.min(from + pageable.getPageSize(), 2))
                    .mapToObj(index -> {
                        DocumentVector vector = chunk(index + 1);
                        vector.setArticlePath("第二章 风险管理 > 第十二条");
                        vector.setClauseNo(index == 0 ? "第十二条" : "第十二条第二款");
                        return vector;
                    })
                    .toList();
            return new SliceImpl<>(content, pageable, false);
        });
    }

    private void stubLegacyChunks() {
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc(eq("md5"), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            int from = (int) pageable.getOffset();
            List<DocumentVector> content = IntStream.range(Math.min(from, 2), Math.min(from + pageable.getPageSize(), 2))
                    .mapToObj(index -> chunk(index + 1))
                    .toList();
            return new SliceImpl<>(content, pageable, false);
        });
    }

    @SuppressWarnings("unchecked")
    private List<EsDocument> capturedDocuments() {
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(batches.capture());
        return batches.getValue();
    }

    private static DocumentVector chunk(int chunkId) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5("md5");
        vector.setBuildId("b1");
        vector.setChunkId(chunkId);
        vector.setTextContent("text-" + chunkId);
        return vector;
    }
}
