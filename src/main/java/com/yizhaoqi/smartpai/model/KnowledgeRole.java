package com.yizhaoqi.smartpai.model;

import java.util.Locale;

/**
 * 知识库角色：所有账户共用同一个共享知识库，角色只决定管理与问答入口，不决定文档可见范围。
 */
public enum KnowledgeRole {

    READER("阅读者"),
    FILE_MANAGER("文件管理者"),
    ROLE_MANAGER("角色管理者");

    private final String label;

    KnowledgeRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static KnowledgeRole fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (KnowledgeRole role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        return null;
    }
}
