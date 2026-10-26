package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账户知识库角色变更。所有变更先在事务内锁定全部 ROLE_MANAGER 行，再在锁内重新判断
 * 是否还有第二名管理者；只加 @Transactional 不能阻止两个并发降级各自统计到两人。
 */
@Service
public class KnowledgeRoleService {

    private final UserRepository userRepository;
    private final KnowledgePermissionService permissionService;
    private final KnowledgeAuditService auditService;

    public KnowledgeRoleService(UserRepository userRepository,
                                KnowledgePermissionService permissionService,
                                KnowledgeAuditService auditService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional
    public User updateRole(String requesterId, Long userId, KnowledgeRole targetRole) {
        // 先锁管理者集合，再做授权判断：并发降级等待锁后才能看到真实的管理者人数，
        // 也不允许已被并发降级的账户继续提交角色变更。
        List<User> lockedManagers = userRepository.findByKnowledgeRoleForUpdate(KnowledgeRole.ROLE_MANAGER);
        permissionService.requireRoleManager(requesterId);

        User target = lockedManagers.stream()
                .filter(user -> user.getId().equals(userId))
                .findFirst()
                .orElseGet(() -> userRepository.findForUpdate(userId)
                        .orElseThrow(() -> new CustomException("账户不存在", HttpStatus.NOT_FOUND)));

        KnowledgeRole before = target.getKnowledgeRole();
        if (before == KnowledgeRole.ROLE_MANAGER && targetRole != KnowledgeRole.ROLE_MANAGER
                && lockedManagers.size() <= 1) {
            throw new CustomException("不能降级最后一位角色管理者", HttpStatus.CONFLICT);
        }
        if (before == targetRole) {
            return target;
        }

        target.setKnowledgeRole(targetRole);
        userRepository.save(target);
        auditService.record(requesterId, "USER", String.valueOf(userId), "ASSIGN_ROLE",
                before.name(), targetRole.name());
        return target;
    }
}
