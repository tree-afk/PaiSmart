package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionRegistry.class);

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GenerationSession> generationSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    private record GenerationSession(String userId, Set<WebSocketSession> sessions) {}

    public ChatSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerSession(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregisterSession(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (key, current) -> current == session ? null : current);
        generationSessions.values().forEach(generation -> generation.sessions().remove(session));
    }

    public WebSocketSession getCurrentSession(String userId) {
        return sessions.get(userId);
    }

    public void bindGeneration(String userId, String generationId, WebSocketSession session) {
        Set<WebSocketSession> subscribers = ConcurrentHashMap.newKeySet();
        subscribers.add(session);
        generationSessions.put(generationId, new GenerationSession(userId, subscribers));
    }

    /** Reconnects subscribe without taking the stream away from another open page. */
    public void resumeGeneration(String userId, String generationId, WebSocketSession session) {
        generationSessions.computeIfPresent(generationId, (key, current) -> {
            if (current.userId().equals(userId)) current.sessions().add(session);
            return current;
        });
    }

    public void releaseGeneration(String generationId) {
        generationSessions.remove(generationId);
    }

    public void sendJsonToUser(String userId, Map<String, ?> payload) {
        Object generationId = payload.get("generationId");
        if (generationId instanceof String id && !id.isBlank()) {
            GenerationSession target = generationSessions.get(id);
            if (target != null && target.userId().equals(userId)) {
                target.sessions().forEach(session -> sendJsonToSession(session, payload));
            }
            // Never redirect a generation to another tab, even if its original socket closed.
            return;
        }
        sendJsonToSession(sessions.get(userId), payload);
    }

    public void sendJsonToSession(WebSocketSession session, Map<String, ?> payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            logger.error("发送 WebSocket 消息失败，会话ID: {}", session.getId(), e);
        }
    }
}
