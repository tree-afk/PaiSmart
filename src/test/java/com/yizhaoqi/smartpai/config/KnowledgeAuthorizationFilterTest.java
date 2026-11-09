package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAuthorizationFilterTest {

    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final KnowledgeAuthorizationFilter filter =
            new KnowledgeAuthorizationFilter(jwtUtils, userRepository);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void userCannotUpload() throws Exception {
        stubUser("2", User.Role.USER);

        MockHttpServletRequest request = request("POST", "/api/v1/upload/merge");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void adminCanUpload() throws Exception {
        stubUser("1", User.Role.ADMIN);

        MockHttpServletRequest request = request("POST", "/api/v1/upload/merge");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals("1", request.getAttribute("userId"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void userCannotCallHybridSearch() throws Exception {
        stubUser("2", User.Role.USER);

        MockHttpServletRequest request = request("GET", "/api/v1/search/hybrid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void userCanChatAndPreviewPublishedDocument() throws Exception {
        stubUser("2", User.Role.USER);

        MockHttpServletRequest chat = request("POST", "/api/v1/chat/message");
        MockHttpServletResponse chatResponse = new MockHttpServletResponse();
        filter.doFilter(chat, chatResponse, chain);
        verify(chain).doFilter(chat, chatResponse);

        FilterChain previewChain = mock(FilterChain.class);
        MockHttpServletRequest preview = request("GET", "/api/v1/documents/preview");
        MockHttpServletResponse previewResponse = new MockHttpServletResponse();
        filter.doFilter(preview, previewResponse, previewChain);
        verify(previewChain).doFilter(preview, previewResponse);
    }

    @Test
    void unknownUserIsRejected() throws Exception {
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("99");
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        MockHttpServletRequest request = request("POST", "/api/v1/chat/message");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    private void stubUser(String userId, User.Role role) {
        User user = new User();
        user.setId(Long.parseLong(userId));
        user.setRole(role);
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn(userId);
        when(userRepository.findById(Long.parseLong(userId))).thenReturn(Optional.of(user));
        when(userRepository.findByUsername(any())).thenReturn(Optional.of(user));
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer token");
        return request;
    }
}
