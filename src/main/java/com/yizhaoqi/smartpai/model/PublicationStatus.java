package com.yizhaoqi.smartpai.model;

/**
 * 文档发布状态，与构建状态分开：构建成功不等于允许检索。
 */
public enum PublicationStatus {
    DRAFT,
    PUBLISHED,
    WITHDRAWN
}
