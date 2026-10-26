package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgePermissionServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final KnowledgePermissionService service = new KnowledgePermissionService(userRepository);

    @Test
    void readsRoleFromDatabaseOnEveryRequest() {
        User user = user(KnowledgeRole.READER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThrows(CustomException.class, () -> service.requireFileManager("1"));
        assertEquals(KnowledgeRole.READER, service.roleOf("1"));

        user.setKnowledgeRole(KnowledgeRole.FILE_MANAGER);
        assertDoesNotThrow(() -> service.requireFileManager("1"));
    }

    @Test
    void roleManagerIsNotImplicitlyFileManager() {
        User user = user(KnowledgeRole.ROLE_MANAGER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertDoesNotThrow(() -> service.requireRoleManager("1"));
        assertThrows(CustomException.class, () -> service.requireFileManager("1"));
    }

    @Test
    void usernameFallbackWorksForLegacyRequesterIds() {
        User user = user(KnowledgeRole.FILE_MANAGER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertDoesNotThrow(() -> service.requireFileManager("alice"));
    }

    private User user(KnowledgeRole role) {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("test");
        user.setRole(User.Role.USER);
        user.setKnowledgeRole(role);
        return user;
    }
}
