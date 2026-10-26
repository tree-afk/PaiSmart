package com.yizhaoqi.smartpai.client;

import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.utils.RequestDeadline;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DeepSeekClientSummaryBudgetTest {

    private static final List<SearchResult> RESULTS =
            List.of(new SearchResult("md5", 1, "制度片段", 0.9, "制度.pdf"));

    @Test
    void summaryUsesRemainingRequestBudgetInsteadOfFixedNinetySeconds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = hangingServer(release);
        try {
            DeepSeekClient client = client(server);
            AtomicLong elapsedMs = new AtomicLong();

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try (RequestDeadline.Scope ignored =
                             RequestDeadline.open(RequestDeadline.after(Duration.ofMillis(50)))) {
                    long started = System.nanoTime();
                    assertThrows(RuntimeException.class,
                            () -> client.summarize("u1", "主题", RESULTS, null, 1));
                    elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                }
            });

            assertTrue(elapsedMs.get() < 1000,
                    "摘要应受整轮请求剩余预算约束，实际耗时 " + elapsedMs.get() + "ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void summarySubscriptionIsRegisteredWithChatStopHandle() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<Runnable> cancelAction = new AtomicReference<>();
        HttpServer server = hangingServer(release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DeepSeekClient client = client(server);
            Future<?> summary = executor.submit(() -> assertThrows(RuntimeException.class,
                    () -> client.summarize("u1", "主题", RESULTS, null, 1, action -> {
                        cancelAction.set(action);
                        registered.countDown();
                    })));

            assertTrue(registered.await(2, TimeUnit.SECONDS), "摘要内部订阅应可被停止句柄登记");
            long started = System.nanoTime();
            cancelAction.get().run();
            summary.get(2, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 1000, "停止后摘要应尽快退出，实际耗时 " + elapsedMs + "ms");
        } finally {
            executor.shutdownNow();
            release.countDown();
            server.stop(0);
        }
    }

    private DeepSeekClient client(HttpServer server) {
        return new DeepSeekClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                "test-model",
                new AiProperties(),
                mock(UsageQuotaService.class),
                mock(ModelProviderConfigService.class));
    }

    private HttpServer hangingServer(CountDownLatch release) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 0);
            } catch (Exception ignored) {
                // 客户端取消或测试收尾
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }
}
