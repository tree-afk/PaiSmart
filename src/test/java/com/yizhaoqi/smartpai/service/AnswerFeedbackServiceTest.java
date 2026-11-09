package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.model.AnswerFeedback;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.repository.AnswerFeedbackRepository;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerFeedbackServiceTest {

    @TempDir
    Path tempDir;

    private final AnswerFeedbackRepository feedbackRepository = mock(AnswerFeedbackRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnswerFeedbackService service;

    @BeforeEach
    void setUp() {
        RagEvalProperties properties = new RagEvalProperties();
        properties.setDatasetDir(tempDir.toString());
        service = new AnswerFeedbackService(feedbackRepository, conversationRepository, objectMapper, properties);
        when(feedbackRepository.save(any(AnswerFeedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void recordSnapshotsLatestConversation() {
        Conversation conversation = new Conversation();
        conversation.setQuestion("端口是多少");
        conversation.setAnswer("8080");
        when(conversationRepository.findFirstByConversationIdOrderByTimestampDesc("c1")).thenReturn(conversation);

        AnswerFeedback feedback = service.record("7", "c1", "g1", "bad", "回答不对");

        assertEquals("BAD", feedback.getRating());
        assertEquals("端口是多少", feedback.getQuestion());
        assertEquals("8080", feedback.getAnswer());
        assertEquals(AnswerFeedback.STATUS_NEW, feedback.getStatus());
    }

    @Test
    void convertWritesJsonlAndMarksConverted() throws Exception {
        AnswerFeedback feedback = new AnswerFeedback();
        feedback.setId(5L);
        feedback.setQuestion("端口是多少");
        feedback.setAnswer("8080");
        feedback.setStatus(AnswerFeedback.STATUS_NEW);
        when(feedbackRepository.findById(5L)).thenReturn(Optional.of(feedback));

        AnswerFeedbackService.ConvertResult result = service.convert(5L, "端口是 8080",
                List.of("md5"), List.of("config.pdf"), List.of("8080"), "admin");

        assertTrue(Files.exists(Path.of(result.file())));
        String line = Files.readString(Path.of(result.file())).trim();
        JsonNode node = objectMapper.readTree(line);
        assertEquals("badcase-5", node.get("id").asText());
        assertEquals("端口是 8080", node.get("referenceAnswer").asText());
        assertEquals("config.pdf", node.get("expectedFileNames").get(0).asText());
        assertEquals(AnswerFeedback.STATUS_CONVERTED, feedback.getStatus());
        assertEquals(line, feedback.getEvalCaseJson());
    }

    @Test
    void exportOnlyReturnsStoredEvalCaseLines() {
        AnswerFeedback first = new AnswerFeedback();
        first.setEvalCaseJson("{\"id\":\"badcase-1\"}");
        AnswerFeedback second = new AnswerFeedback();
        second.setEvalCaseJson("{\"id\":\"badcase-2\"}");
        when(feedbackRepository.findByStatusOrderByCreatedAtAsc(AnswerFeedback.STATUS_CONVERTED))
                .thenReturn(List.of(first, second));

        String exported = service.export(null);

        assertTrue(exported.contains("badcase-1"));
        assertTrue(exported.contains("badcase-2"));
    }
}
