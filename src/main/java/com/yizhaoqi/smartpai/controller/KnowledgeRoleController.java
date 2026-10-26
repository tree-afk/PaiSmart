package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.KnowledgeRole;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.KnowledgePermissionService;
import com.yizhaoqi.smartpai.service.KnowledgeRoleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定三角色的账户管理：只允许角色管理者查看和分配 knowledgeRole。
 */
@RestController
@RequestMapping("/api/v1/knowledge-roles")
public class KnowledgeRoleController {

    private final UserRepository userRepository;
    private final KnowledgePermissionService permissionService;
    private final KnowledgeRoleService roleService;

    public KnowledgeRoleController(UserRepository userRepository,
                                   KnowledgePermissionService permissionService,
                                   KnowledgeRoleService roleService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.roleService = roleService;
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(@RequestAttribute("userId") String requesterId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        try {
            permissionService.requireRoleManager(requesterId);
            int pageNumber = Math.max(page, 1);
            int pageSize = Math.min(Math.max(size, 1), 100);
            PageRequest pageable = PageRequest.of(pageNumber - 1, pageSize, Sort.by(Sort.Direction.ASC, "id"));
            Page<User> users = keyword == null || keyword.isBlank()
                    ? userRepository.findAll(pageable)
                    : userRepository.findByUsernameContainingIgnoreCase(keyword.trim(), pageable);
            List<Map<String, Object>> rows = users.getContent().stream().map(this::toView).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content", rows);
            data.put("totalElements", users.getTotalElements());
            data.put("number", pageNumber);
            data.put("size", pageSize);
            return ResponseEntity.ok(Map.of("code", 200, "message", "获取账户角色成功", "data", data));
        } catch (CustomException e) {
            return error(e);
        }
    }

    @PutMapping("/users/{userId}")
    public ResponseEntity<?> updateRole(@RequestAttribute("userId") String requesterId,
                                        @PathVariable Long userId,
                                        @RequestBody UpdateKnowledgeRoleRequest request) {
        try {
            KnowledgeRole targetRole = KnowledgeRole.fromCode(request.knowledgeRole());
            if (targetRole == null) {
                throw new CustomException("knowledgeRole 只能是 READER / FILE_MANAGER / ROLE_MANAGER",
                        HttpStatus.BAD_REQUEST);
            }
            User target = roleService.updateRole(requesterId, userId, targetRole);
            return ResponseEntity.ok(Map.of("code", 200, "message", "角色更新成功", "data", toView(target)));
        } catch (CustomException e) {
            return error(e);
        }
    }

    private Map<String, Object> toView(User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("knowledgeRole", user.getKnowledgeRole());
        view.put("knowledgeRoleLabel", user.getKnowledgeRole().label());
        return view;
    }

    private ResponseEntity<?> error(CustomException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
    }

    record UpdateKnowledgeRoleRequest(String knowledgeRole) {
    }
}
