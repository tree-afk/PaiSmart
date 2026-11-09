package com.yizhaoqi.smartpai.model;

import java.util.Locale;

/**
 * 合规文档类型：银行/保险合规场景下区分监管规定、内部制度、产品条款和操作指引。
 * 持久化与消息传输统一使用枚举名（REGULATION 等），展示文案使用中文标签。
 */
public enum DocumentComplianceType {

    REGULATION("监管规定"),
    INTERNAL_POLICY("内部制度"),
    PRODUCT_TERMS("产品条款"),
    GUIDELINE("操作指引");

    private final String label;

    DocumentComplianceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 把请求里的类型代码解析成枚举；空白返回 null，未知值也返回 null，由调用方决定拒绝还是忽略
     */
    public static DocumentComplianceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (DocumentComplianceType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 规范化类型代码为枚举名；空白或未知值返回 null
     */
    public static String normalize(String code) {
        DocumentComplianceType type = fromCode(code);
        return type == null ? null : type.name();
    }

    /**
     * 全部枚举名，用于工具 schema 的 enum 列表
     */
    public static java.util.List<String> allowedCodes() {
        java.util.List<String> codes = new java.util.ArrayList<>(values().length);
        for (DocumentComplianceType type : values()) {
            codes.add(type.name());
        }
        return java.util.List.copyOf(codes);
    }

    /**
     * 逗号分隔的合法代码与中文标签，用于上传接口的参数错误提示
     */
    public static String allowedCodesWithLabels() {
        StringBuilder builder = new StringBuilder();
        for (DocumentComplianceType type : values()) {
            if (!builder.isEmpty()) {
                builder.append('、');
            }
            builder.append(type.name()).append('（').append(type.label).append('）');
        }
        return builder.toString();
    }
}
