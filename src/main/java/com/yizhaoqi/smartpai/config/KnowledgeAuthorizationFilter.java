package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证与文件管理权限：所有 /api/v1 请求校验登录用户；
 * 上传/解析/文件管理/检索只允许管理员，正文预览、下载和引用详情不在这里限制，
 * 由 DocumentAccessService 按“文档已发布”判断。
 */
@Component
public class KnowledgeAuthorizationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    public KnowledgeAuthorizationFilter(JwtUtils jwtUtils, UserRepository userRepository) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
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

        User user = findUser(userId);
        if (user == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "账号不存在或未登录");
            return;
        }

        if (requiresAdmin(request) && user.getRole() != User.Role.ADMIN) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return;
        }

        request.setAttribute("userId", userId);
        filterChain.doFilter(request, response);
    }

    private User findUser(String userId) {
        try {
            return userRepository.findById(Long.parseLong(userId)).orElse(null);
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId).orElse(null);
        }
    }

    /**
     * 内容管理面：上传、解析、文件列表、元数据与发布操作、直接检索都只对管理员开放。
     * 预览/下载/引用详情不在此列，它们由文档发布状态决定，普通用户可读已发布文档。
     */
    private boolean requiresAdmin(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/admin/")) {
            return true;
        }
        if (path.startsWith("/api/v1/upload/") || path.equals("/api/v1/parse")) {
            return true;
        }
        if (path.startsWith("/api/v1/search/")) {
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
