package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.service.KnowledgePermissionService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 知识库请求级权限：文件管理和角色管理必须以数据库中的 knowledge_role 为准，
 * 旧 User.Role.ADMIN 不能绕过；所有已登录角色仍然可以问答和查看自己的历史。
 */
@Component
public class KnowledgeAuthorizationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final KnowledgePermissionService permissionService;

    public KnowledgeAuthorizationFilter(JwtUtils jwtUtils, KnowledgePermissionService permissionService) {
        this.jwtUtils = jwtUtils;
        this.permissionService = permissionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/")
                || path.equals("/api/v1/users/register")
                || path.equals("/api/v1/users/login")
                || path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/internal/")
                || path.startsWith("/api/v1/test/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = jwtUtils.extractUserIdFromToken(token);
        if (userId == null || userId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (request.getRequestURI().startsWith("/api/v1/knowledge-roles")) {
                permissionService.requireRoleManager(userId);
            } else if (requiresFileManager(request)) {
                permissionService.requireFileManager(userId);
            } else {
                permissionService.requireKnowledgeAccess(userId);
            }
            request.setAttribute("userId", userId);
        } catch (CustomException e) {
            writeError(response, e.getStatus().value(), e.getMessage());
            return;
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号不存在或未登录");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresFileManager(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/upload/") || path.equals("/api/v1/parse")) {
            return true;
        }
        if (path.equals("/api/v1/documents/accessible") || path.equals("/api/v1/documents/uploads")) {
            return true;
        }
        if (path.matches("^/api/v1/documents/[^/]+/(metadata|withdraw|restore-publication)/?$")) {
            return true;
        }
        if ("DELETE".equalsIgnoreCase(method) && path.matches("^/api/v1/documents/[^/]+/?$")) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && (
                path.matches("^/api/v1/documents/[^/]+/reindex/?$")
                        || path.matches("^/api/v1/documents/[^/]+/vectorization/retry/?$")
                        || path.matches("^/api/v1/documents/[^/]+/versions/[^/]+/builds/?$"))) {
            return true;
        }
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\""
                + (message == null ? "" : message.replace("\"", "\\\"")) + "\"}");
    }
}
