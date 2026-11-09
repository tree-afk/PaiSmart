package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.resilience.ModelCallGuard;
import com.yizhaoqi.smartpai.client.resilience.ModelCallRateLimiter;
import com.yizhaoqi.smartpai.client.resilience.ModelWebClientFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ModelCallProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上游在返回任何内容前拒绝请求（400）时，这次调用不应按估算 token 扣费。
 */
class LlmProviderRouterFailureSettlementTest {

    private HttpServer model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.stop(0);
        }
    }

    @Test
    void rejectedCallSettlesZeroTokens() throws Exception {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", exchange -> {
            byte[] body = "{\"error\":{\"code\":\"Arrearage\",\"message\":\"Access denied\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        model.start();

        ModelCallProperties properties = new ModelCallProperties();
        properties.getRetry().setInitialBackoffMs(1);
        properties.getRetry().setMaxBackoffMs(2);

        ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getLlmFailoverChain(any())).thenReturn(List.of(
                new ModelProviderConfigService.ActiveProviderView("deepseek", "deepseek", ModelProviderConfigService.API_STYLE_OPENAI,
                        "http://127.0.0.1:" + model.getAddress().getPort() + "/v1",
                        "deepseek-chat", "test-key", null)));

        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.reserveLlmUsage(anyString(), anyInt(), anyInt())).thenReturn(
                UsageQuotaService.TokenReservationBundle.of("llm", "1", List.of(
                        new UsageQuotaService.TokenReservation("llm", "1", "", "", 500, 500, 0, false, true))));
        UsageQuotaService usageQuotaService = mock(UsageQuotaService.class);

        LlmProviderRouter router = new LlmProviderRouter(new AiProperties(), rateLimitService, usageQuotaService,
                providerConfig, new ObjectMapper(), new ModelWebClientFactory(properties),
                new ModelCallGuard(properties, mock(ModelCallRateLimiter.class)), properties);

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        router.streamReActTurn("1", List.of(Map.of("role", "user", "content", "你好")), List.of(), 100,
                chunk -> { },
                failure -> {
                    error.set(failure);
                    done.countDown();
                },
                turn -> done.countDown());

        assertTrue(done.await(10, TimeUnit.SECONDS), "上游 400 应立即回调错误");
        assertNotNull(error.get());
        verify(usageQuotaService).settleReservation(any(UsageQuotaService.TokenReservationBundle.class), eq(0));
        verify(usageQuotaService, never()).settleReservation(any(UsageQuotaService.TokenReservationBundle.class),
                org.mockito.ArgumentMatchers.intThat(tokens -> tokens > 0));
    }
}
