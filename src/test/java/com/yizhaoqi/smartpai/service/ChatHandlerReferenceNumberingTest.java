package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatHandlerReferenceNumberingTest {
    @Test
    void multipleSearchesKeepEarlierReferencesAndUseDistinctNumbers() {
        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        ChatHandler handler = new ChatHandler(null, null, null, null, null, state,
                null, null, null, null, null);
        SearchResult first = new SearchResult("first-md5", 1, "first text", 0.9, "first.txt");
        SearchResult second = new SearchResult("second-md5", 2, "second text", 0.8, "second.txt");

        String firstContent = handler.appendReferencesFromSearchTool("generation", "question",
                result(first));
        String secondContent = handler.appendReferencesFromSearchTool("generation", "question",
                result(second));

        assertTrue(firstContent.contains("[1] first.txt"));
        assertTrue(secondContent.contains("[2] second.txt"));
        assertEquals("first-md5", handler.getReferenceDetail("generation", 1).fileMd5());
        assertEquals("second-md5", handler.getReferenceDetail("generation", 2).fileMd5());
        verify(state, times(2)).updateReferenceMappings(eq("generation"), any());
    }

    private static AgentToolRegistry.ToolExecutionResult result(SearchResult result) {
        return new AgentToolRegistry.ToolExecutionResult("search_knowledge", true,
                AgentToolRegistry.formatSearchResults(List.of(result)), Map.of("results", List.of(result)));
    }
}
