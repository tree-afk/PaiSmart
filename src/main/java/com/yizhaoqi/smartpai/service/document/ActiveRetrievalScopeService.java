package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;

/**
 * 检索范围快照服务：从业务文档与内容版本仓储汇总 active build 和已退休的 legacy md5，
 * 在 TTL 内复用，避免每次检索都查库；构建切换后调用 {@link #invalidateAndBroadcast()}
 * 让所有实例通过 Redis 频道一起失效。
 */
@Service
public class ActiveRetrievalScopeService implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(ActiveRetrievalScopeService.class);

    public static final String CHANNEL = "paismart:active-scope";

    private final BusinessDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final StringRedisTemplate redisTemplate;
    private final long ttlNanos;

    private volatile Snapshot snapshot;

    public ActiveRetrievalScopeService(BusinessDocumentRepository documentRepository,
                                       DocumentVersionRepository versionRepository,
                                       StringRedisTemplate redisTemplate,
                                       @Value("${document.version.scope-ttl-seconds:5}") long ttlSeconds) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.redisTemplate = redisTemplate;
        this.ttlNanos = Math.max(ttlSeconds, 1L) * 1_000_000_000L;
    }

    public ActiveRetrievalScope current() {
        Snapshot current = snapshot;
        long now = System.nanoTime();
        if (current != null && now - current.loadedAtNanos() < ttlNanos) {
            return current.scope();
        }
        synchronized (this) {
            current = snapshot;
            now = System.nanoTime();
            if (current != null && now - current.loadedAtNanos() < ttlNanos) {
                return current.scope();
            }
            try {
                ActiveRetrievalScope loaded = new ActiveRetrievalScope(
                        new LinkedHashSet<>(documentRepository.findActiveBuildIds()),
                        new LinkedHashSet<>());
                snapshot = new Snapshot(loaded, now);
                return loaded;
            } catch (RuntimeException e) {
                Snapshot lastSuccessful = snapshot;
                if (lastSuccessful != null) {
                    // 重新加载失败时沿用最近一次成功快照，保证旧 active 仍可检索（规格 6.1）。
                    // 同时刷新快照时间戳：旧范围在 TTL 内直接复用，避免每次调用都重新查询仓储。
                    logger.warn("加载检索范围失败，沿用最近一次成功快照并在 TTL 内不再重试", e);
                    snapshot = new Snapshot(lastSuccessful.scope(), System.nanoTime());
                    return lastSuccessful.scope();
                }
                // 从未成功加载过（或刚被失效清空），失败关闭，避免把全量数据暴露给检索
                throw e;
            }
        }
    }

    public void invalidate() {
        snapshot = null;
    }

    public void invalidateAndBroadcast() {
        invalidate();
        try {
            redisTemplate.convertAndSend(CHANNEL, "refresh");
        } catch (Exception e) {
            logger.warn("广播检索范围失效消息失败，其他实例将在缓存过期后自行刷新", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        invalidate();
    }

    private record Snapshot(ActiveRetrievalScope scope, long loadedAtNanos) {
    }
}
