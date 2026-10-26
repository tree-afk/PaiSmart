package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.KnowledgePermissionService;
import com.yizhaoqi.smartpai.service.KnowledgeRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRoleControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final KnowledgePermissionService permissionService = mock(KnowledgePermissionService.class);
    private final KnowledgeRoleService roleService = mock(KnowledgeRoleService.class);
    private final KnowledgeRoleController controller =
            new KnowledgeRoleController(userRepository, permissionService, roleService);

    private User target;

    @BeforeEach
    void setUp() {
        target = new User();
        target.setId(2L);
        target.setUsername("bob");
        target.setRole(User.Role.USER);
        target.setKnowledgeRole(KnowledgeRole.READER);
    }

    @Test
    void roleManagerCanAssignFixedRole() {
        target.setKnowledgeRole(KnowledgeRole.FILE_MANAGER);
        when(roleService.updateRole("1", 2L, KnowledgeRole.FILE_MANAGER)).thenReturn(target);

        var response = controller.updateRole("1", 2L,
                new KnowledgeRoleController.UpdateKnowledgeRoleRequest("FILE_MANAGER"));

        assertEquals(200, response.getStatusCode().value());
        verify(roleService).updateRole("1", 2L, KnowledgeRole.FILE_MANAGER);
    }

    @Test
    void rejectsUnknownRoleCodeBeforeCallingService() {
        var response = controller.updateRole("1", 2L,
                new KnowledgeRoleController.UpdateKnowledgeRoleRequest("SUPER_ADMIN"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        verifyNoInteractions(roleService);
    }

    @Test
    void propagatesLastManagerConflictFromService() {
        when(roleService.updateRole("1", 2L, KnowledgeRole.READER))
                .thenThrow(new CustomException("不能降级最后一位角色管理者", HttpStatus.CONFLICT));

        var response = controller.updateRole("1", 2L,
                new KnowledgeRoleController.UpdateKnowledgeRoleRequest("READER"));

        assertEquals(HttpStatus.CONFLICT.value(), response.getStatusCode().value());
    }

    @Test
    void nonRoleManagerIsDeniedByService() {
        doThrow(new CustomException("没有角色管理权限", HttpStatus.FORBIDDEN))
                .when(roleService).updateRole("reader", 2L, KnowledgeRole.READER);

        var response = controller.updateRole("reader", 2L,
                new KnowledgeRoleController.UpdateKnowledgeRoleRequest("READER"));

        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatusCode().value());
        verify(userRepository, never()).findById(any());
    }
}
