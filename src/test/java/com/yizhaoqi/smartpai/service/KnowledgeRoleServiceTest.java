package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRoleServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final KnowledgePermissionService permissionService = mock(KnowledgePermissionService.class);
    private final KnowledgeAuditService auditService = mock(KnowledgeAuditService.class);
    private final KnowledgeRoleService service =
            new KnowledgeRoleService(userRepository, permissionService, auditService);

    @Test
    void demotionLocksManagerRowsAndRejectsLastManager() {
        User onlyManager = user(1L, KnowledgeRole.ROLE_MANAGER);
        when(userRepository.findByKnowledgeRoleForUpdate(KnowledgeRole.ROLE_MANAGER))
                .thenReturn(List.of(onlyManager));

        CustomException error = assertThrows(CustomException.class,
                () -> service.updateRole("1", 1L, KnowledgeRole.READER));

        assertEquals(409, error.getStatus().value());
        assertEquals(KnowledgeRole.ROLE_MANAGER, onlyManager.getKnowledgeRole());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void demotionSucceedsWhenAnotherManagerRemainsAfterLocking() {
        User target = user(1L, KnowledgeRole.ROLE_MANAGER);
        User other = user(2L, KnowledgeRole.ROLE_MANAGER);
        when(userRepository.findByKnowledgeRoleForUpdate(KnowledgeRole.ROLE_MANAGER))
                .thenReturn(List.of(target, other));

        User updated = service.updateRole("1", 1L, KnowledgeRole.READER);

        assertEquals(KnowledgeRole.READER, updated.getKnowledgeRole());
        verify(userRepository).save(target);
        verify(auditService).record("1", "USER", "1", "ASSIGN_ROLE", "ROLE_MANAGER", "READER");
    }

    @Test
    void everyRoleUpdateLocksManagerSetBeforeChangingTarget() {
        User manager = user(1L, KnowledgeRole.ROLE_MANAGER);
        User target = user(2L, KnowledgeRole.READER);
        when(userRepository.findByKnowledgeRoleForUpdate(KnowledgeRole.ROLE_MANAGER))
                .thenReturn(List.of(manager));
        when(userRepository.findForUpdate(2L)).thenReturn(Optional.of(target));

        User updated = service.updateRole("1", 2L, KnowledgeRole.FILE_MANAGER);

        assertEquals(KnowledgeRole.FILE_MANAGER, updated.getKnowledgeRole());
        verify(userRepository).findByKnowledgeRoleForUpdate(KnowledgeRole.ROLE_MANAGER);
        verify(userRepository).findForUpdate(2L);
        verify(auditService).record("1", "USER", "2", "ASSIGN_ROLE", "READER", "FILE_MANAGER");
    }

    @Test
    void nonRoleManagerIsDeniedBeforeChangingAnything() {
        when(userRepository.findByKnowledgeRoleForUpdate(KnowledgeRole.ROLE_MANAGER))
                .thenReturn(List.of(user(1L, KnowledgeRole.ROLE_MANAGER)));
        doThrow(new CustomException("没有角色管理权限", org.springframework.http.HttpStatus.FORBIDDEN))
                .when(permissionService).requireRoleManager("reader");

        assertThrows(CustomException.class,
                () -> service.updateRole("reader", 2L, KnowledgeRole.READER));

        verify(userRepository, never()).findForUpdate(2L);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    private User user(Long id, KnowledgeRole role) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setPassword("x");
        user.setRole(User.Role.USER);
        user.setKnowledgeRole(role);
        return user;
    }
}
