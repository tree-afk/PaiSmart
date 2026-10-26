package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.service.KnowledgePermissionService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAuthorizationFilterTest {

    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final KnowledgePermissionService permissionService = mock(KnowledgePermissionService.class);
    private final KnowledgeAuthorizationFilter filter =
            new KnowledgeAuthorizationFilter(jwtUtils, permissionService);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void readerCannotCallUploadEvenWithOldAdminRole() throws Exception {
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
        doThrow(new CustomException("没有文件管理权限", HttpStatus.FORBIDDEN))
                .when(permissionService).requireFileManager("1");

        MockHttpServletRequest request = request("POST", "/api/v1/upload/merge");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void readerCanUseChatAndHistory() throws Exception {
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");

        MockHttpServletRequest request = request("POST", "/api/v1/chat/message");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(permissionService).requireKnowledgeAccess("1");
        verify(chain).doFilter(request, response);
    }

    @Test
    void roleManagerPathRequiresRoleManagerNotOldAdmin() throws Exception {
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("1");
        doThrow(new CustomException("没有角色管理权限", HttpStatus.FORBIDDEN))
                .when(permissionService).requireRoleManager("1");

        MockHttpServletRequest request = request("GET", "/api/v1/knowledge-roles/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer token");
        return request;
    }
}
