package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 知识库角色检查：每次从数据库读取，不信任 JWT 中携带的角色，也不做角色缓存。
 */
@Service
public class KnowledgePermissionService {

    private final UserRepository userRepository;

    public KnowledgePermissionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireUser(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            throw new CustomException("未登录", HttpStatus.UNAUTHORIZED);
        }
        try {
            return userRepository.findById(Long.parseLong(requesterId))
                    .orElseGet(() -> userRepository.findByUsername(requesterId).orElseThrow(
                            () -> new CustomException("账号不存在", HttpStatus.UNAUTHORIZED)));
        } catch (NumberFormatException ignored) {
            return userRepository.findByUsername(requesterId)
                    .orElseThrow(() -> new CustomException("账号不存在", HttpStatus.UNAUTHORIZED));
        }
    }

    public KnowledgeRole roleOf(String requesterId) {
        return requireUser(requesterId).getKnowledgeRole();
    }

    public void requireKnowledgeAccess(String requesterId) {
        requireUser(requesterId);
    }

    public void requireFileManager(String requesterId) {
        if (roleOf(requesterId) != KnowledgeRole.FILE_MANAGER) {
            throw new CustomException("没有文件管理权限", HttpStatus.FORBIDDEN);
        }
    }

    public void requireRoleManager(String requesterId) {
        if (roleOf(requesterId) != KnowledgeRole.ROLE_MANAGER) {
            throw new CustomException("没有角色管理权限", HttpStatus.FORBIDDEN);
        }
    }

    public boolean isFileManager(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            return false;
        }
        try {
            return roleOf(requesterId) == KnowledgeRole.FILE_MANAGER;
        } catch (CustomException e) {
            return false;
        }
    }
}
