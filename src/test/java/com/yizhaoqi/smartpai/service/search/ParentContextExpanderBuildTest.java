package com.yizhaoqi.smartpai.service.search;

import com.yizhaoqi.smartpai.config.ParentContextProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 父块扩展按构建批次读取邻居：buildId 存在时只取同一 build 的分块，
 * legacy 命中（无 buildId）回退到 fileMd5 查询；不同 build 的命中不能共用父块。
 */
class ParentContextExpanderBuildTest {

    private final ParentContextProperties properties = new ParentContextProperties();
    private final DocumentVectorRepository repository = mock(DocumentVectorRepository.class);
    private ParentContextExpander expander;

    @BeforeEach
    void setUp() {
        properties.setNeighbourChunks(1);
        properties.setMaxCharsPerHit(1000);
        properties.setMaxTotalChars(5000);
        expander = new ParentContextExpander(properties, repository);
    }

    @Test
    void hitWithBuildIdReadsNeighboursFromTheSameBuildOnly() {
        when(repository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-1"), anyInt(), anyInt()))
                .thenAnswer(invocation -> window("build-1", "md5-new", invocation.getArgument(1), invocation.getArgument(2)));

        SearchResult result = expander.expand(List.of(hit("md5-new", "build-1", 3))).get(0);

        assertEquals("第2块。\n\n第3块。\n\n第4块。", result.getParentContext());
        assertEquals("build-1#2-4", result.getParentKey(), "父块标识以 buildId 打头，不同构建不会重名");
        verify(repository).findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc("build-1", 2, 4);
        verify(repository, never()).findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(anyString(), anyInt(), anyInt());
    }

    @Test
    void legacyHitWithoutBuildIdFallsBackToFileMd5Query() {
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("md5-old"), anyInt(), anyInt()))
                .thenAnswer(invocation -> legacyWindow("md5-old", invocation.getArgument(1), invocation.getArgument(2)));

        SearchResult result = expander.expand(List.of(hit("md5-old", null, 3))).get(0);

        assertEquals("第2块。\n\n第3块。\n\n第4块。", result.getParentContext());
        assertEquals("md5-old#2-4", result.getParentKey());
        verify(repository).findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc("md5-old", 2, 4);
        verify(repository, never()).findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(anyString(), anyInt(), anyInt());
    }

    @Test
    void hitsInTheSameBuildShareOneParentBlock() {
        when(repository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-1"), anyInt(), anyInt()))
                .thenAnswer(invocation -> window("build-1", "md5-new", invocation.getArgument(1), invocation.getArgument(2)));

        List<SearchResult> expanded = expander.expand(List.of(
                hit("md5-new", "build-1", 3), hit("md5-new", "build-1", 4)));

        assertEquals("build-1#2-4", expanded.get(0).getParentKey());
        assertEquals("build-1#2-4", expanded.get(1).getParentKey());
        assertEquals(expanded.get(0).getParentContext(), expanded.get(1).getParentContext());
        verify(repository, times(1)).findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-1"), anyInt(), anyInt());
    }

    @Test
    void hitFromAnotherBuildDoesNotReuseParentBlock() {
        when(repository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-1"), anyInt(), anyInt()))
                .thenAnswer(invocation -> window("build-1", "md5-new", invocation.getArgument(1), invocation.getArgument(2)));
        when(repository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-2"), anyInt(), anyInt()))
                .thenAnswer(invocation -> window("build-2", "md5-new", invocation.getArgument(1), invocation.getArgument(2)));

        List<SearchResult> expanded = expander.expand(List.of(
                hit("md5-new", "build-1", 3), hit("md5-new", "build-2", 4)));

        assertEquals("build-1#2-4", expanded.get(0).getParentKey());
        assertEquals("build-2#3-5", expanded.get(1).getParentKey(), "另一个构建即使分块区间重叠也另建父块");
        assertNotEquals(expanded.get(0).getParentContext(), expanded.get(1).getParentContext());
        verify(repository).findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc("build-1", 2, 4);
        verify(repository).findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc("build-2", 3, 5);
    }

    @Test
    void legacyAndBuildHitsWithSameFileMd5DoNotShareParentBlock() {
        when(repository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-1"), anyInt(), anyInt()))
                .thenAnswer(invocation -> window("build-1", "md5-same", invocation.getArgument(1), invocation.getArgument(2)));
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq("md5-same"), anyInt(), anyInt()))
                .thenAnswer(invocation -> legacyWindow("md5-same", invocation.getArgument(1), invocation.getArgument(2)));
        // build 窗口与 legacy 窗口同文本，仍必须按 key 区分
        SearchResult buildHit = hit("md5-same", "build-1", 3);
        SearchResult legacyHit = hit("md5-same", null, 4);

        List<SearchResult> expanded = expander.expand(List.of(buildHit, legacyHit));

        assertEquals("build-1#2-4", expanded.get(0).getParentKey());
        assertEquals("md5-same#3-5", expanded.get(1).getParentKey());
        verify(repository).findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc("build-1", 2, 4);
        verify(repository).findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc("md5-same", 3, 5);
    }

    @Test
    void buildRepositoryFailureFallsBackToSmallChunk() {
        when(repository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(eq("build-1"), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        SearchResult result = expander.expand(List.of(hit("md5-new", "build-1", 3))).get(0);

        assertNull(result.getParentContext());
        assertNull(result.getParentKey());
    }

    private static List<DocumentVector> window(String buildId, String fileMd5, int from, int to) {
        List<DocumentVector> chunks = new ArrayList<>();
        for (int id = Math.max(1, from); id <= to; id++) {
            chunks.add(chunk(buildId, fileMd5, id));
        }
        return chunks;
    }

    private static List<DocumentVector> legacyWindow(String fileMd5, int from, int to) {
        List<DocumentVector> chunks = new ArrayList<>();
        for (int id = Math.max(1, from); id <= to; id++) {
            chunks.add(chunk(null, fileMd5, id));
        }
        return chunks;
    }

    private static DocumentVector chunk(String buildId, String fileMd5, int id) {
        DocumentVector vector = new DocumentVector();
        vector.setBuildId(buildId);
        vector.setFileMd5(fileMd5);
        vector.setChunkId(id);
        vector.setTextContent("第" + id + "块。");
        return vector;
    }

    private static SearchResult hit(String fileMd5, String buildId, int chunkId) {
        SearchResult result = new SearchResult(fileMd5, chunkId, "第" + chunkId + "块。", 0.9, "7", "org", false, "doc.pdf",
                null, "anchor-" + chunkId, "HYBRID", null);
        result.setBuildId(buildId);
        return result;
    }
}
