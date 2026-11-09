package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索范围快照：active build 与已退休 legacy 版本从两个仓储汇总，TTL 内复用，失效后重新加载。
 */
class ActiveRetrievalScopeServiceTest {

    private final BusinessDocumentRepository documentRepository = mock(BusinessDocumentRepository.class);
    private final DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    // TTL 取 60 秒：本测试只验证失效，不依赖时间流逝
    private final ActiveRetrievalScopeService service =
            new ActiveRetrievalScopeService(documentRepository, versionRepository, redisTemplate, 60);

    @Test
    void currentLoadsOnlyActiveBuilds() {
        when(documentRepository.findActiveBuildIds()).thenReturn(List.of("b1"));

        ActiveRetrievalScope scope = service.current();

        assertEquals(Set.of("b1"), scope.activeBuildIds());
        assertEquals(Set.of(), scope.retiredLegacyMd5s(), "共享库不再加载 retired legacy md5");
        assertFalse(scope.isEmpty());
        assertTrue(new ActiveRetrievalScope(null, null).isEmpty(), "仓储返回 null 时按空集合处理");
    }

    @Test
    void currentIsCachedWithinTtl() {
        when(documentRepository.findActiveBuildIds()).thenReturn(List.of("b1"));

        assertEquals(Set.of("b1"), service.current().activeBuildIds());
        assertEquals(Set.of("b1"), service.current().activeBuildIds());

        verify(documentRepository, times(1)).findActiveBuildIds();
    }

    @Test
    void invalidateForcesNextCurrentToReload() {
        when(documentRepository.findActiveBuildIds()).thenReturn(List.of("b1"), List.of("b2"));
        assertEquals(Set.of("b1"), service.current().activeBuildIds());

        service.invalidate();

        assertEquals(Set.of("b2"), service.current().activeBuildIds(), "失效后重新从仓储加载");
        verify(documentRepository, times(2)).findActiveBuildIds();
    }

    @Test
    void invalidateAndBroadcastReloadsLocallyAndNotifiesOtherInstances() {
        when(documentRepository.findActiveBuildIds()).thenReturn(List.of("b1"), List.of("b2"));
        assertEquals(Set.of("b1"), service.current().activeBuildIds());

        service.invalidateAndBroadcast();

        verify(redisTemplate).convertAndSend(ActiveRetrievalScopeService.CHANNEL, "refresh");
        assertEquals(Set.of("b2"), service.current().activeBuildIds());
    }

    @Test
    void broadcastFailureStillInvalidatesLocalSnapshot() {
        when(documentRepository.findActiveBuildIds()).thenReturn(List.of("b1"), List.of("b2"));
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));
        assertEquals(Set.of("b1"), service.current().activeBuildIds());

        assertDoesNotThrow(service::invalidateAndBroadcast);

        assertEquals(Set.of("b2"), service.current().activeBuildIds(), "广播失败不影响本实例失效");
    }

    @Test
    void reloadFailureServesLastSnapshotAndSkipsRequeryWithinTtl() throws InterruptedException {
        when(documentRepository.findActiveBuildIds())
                .thenReturn(List.of("b1"))
                .thenThrow(new RuntimeException("db down"));
        // TTL 取 1 秒：先成功加载，等快照过期后让仓储刷新失败
        ActiveRetrievalScopeService shortTtlService =
                new ActiveRetrievalScopeService(documentRepository, versionRepository, redisTemplate, 1);

        ActiveRetrievalScope loaded = shortTtlService.current();
        assertEquals(Set.of("b1"), loaded.activeBuildIds());

        Thread.sleep(1200);

        ActiveRetrievalScope stale = shortTtlService.current();
        assertSame(loaded, stale, "刷新失败时沿用最近一次成功快照");
        // 失败后刷新了快照时间戳：TTL 内再次调用直接复用，不再查询仓储
        assertSame(loaded, shortTtlService.current());
        verify(documentRepository, times(2)).findActiveBuildIds();
    }

    @Test
    void reloadFailureAfterInvalidateWithoutSnapshotThrows() {
        when(documentRepository.findActiveBuildIds())
                .thenReturn(List.of("b1"))
                .thenThrow(new RuntimeException("db down"));
        assertEquals(Set.of("b1"), service.current().activeBuildIds());

        service.invalidate();

        RuntimeException error = assertThrows(RuntimeException.class, service::current);
        assertEquals("db down", error.getMessage());
    }

    @Test
    void messageFromOtherInstanceInvalidatesSnapshot() {
        when(documentRepository.findActiveBuildIds()).thenReturn(List.of("b1"), List.of("b2"));
        assertEquals(Set.of("b1"), service.current().activeBuildIds());

        service.onMessage(mock(Message.class), null);

        assertEquals(Set.of("b2"), service.current().activeBuildIds());
    }
}
