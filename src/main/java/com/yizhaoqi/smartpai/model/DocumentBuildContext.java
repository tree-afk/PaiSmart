package com.yizhaoqi.smartpai.model;

public record DocumentBuildContext(String documentId, String versionId, String buildId,
                                   Integer versionNo, Integer buildNo, ComplianceMetadata compliance) {

    /**
     * 不带合规元数据的构造入口：未版本化升级前的调用方与测试使用，元数据为空
     */
    public DocumentBuildContext(String documentId, String versionId, String buildId,
                                Integer versionNo, Integer buildNo) {
        this(documentId, versionId, buildId, versionNo, buildNo, ComplianceMetadata.empty());
    }
}
