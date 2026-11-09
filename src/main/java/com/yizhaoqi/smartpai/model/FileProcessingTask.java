package com.yizhaoqi.smartpai.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理任务类，用于Kafka消息传递
 */
@Data
@NoArgsConstructor
public class FileProcessingTask {
    public static final String TASK_TYPE_UPLOAD_PROCESS = "UPLOAD_PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    public static final String BUILD_KIND_NEW_DOCUMENT = "NEW_DOCUMENT";
    public static final String BUILD_KIND_NEW_VERSION = "NEW_VERSION";
    public static final String BUILD_KIND_REINDEX = "REINDEX";
    public static final String BUILD_KIND_LEGACY_PROMOTION = "LEGACY_PROMOTION";

    private String fileMd5; // 文件的 MD5 校验值
    private String filePath; // 文件存储路径
    private String fileName; // 文件名
    private String userId;   // 上传用户ID
    private String orgTag;   // 文件所属组织标签
    private boolean isPublic; // 文件是否公开
    private String taskType; // 任务类型
    private String requesterId; // 发起重试的用户
    // 版本化入库的构建信息；旧任务没有这些字段，反序列化为 null，继续按 fileMd5 走 legacy 路径
    private String documentId; // 所属业务文档
    private String versionId;  // 内容版本
    private String buildId;    // 构建批次
    private String buildKind;  // 构建来源：首传/新版本/重建/legacy 提升
    private Integer versionNo; // 内容版本号
    private Integer buildNo;   // 版本内构建批次号
    // 合规元数据；旧任务/旧 outbox JSON 没有这些字段，反序列化为 null，按未标注合规信息处理
    private String docType;      // 文档类型：REGULATION/INTERNAL_POLICY/PRODUCT_TERMS/GUIDELINE
    private String jurisdiction; // 适用地区
    private String issuer;       // 发布机构
    private String effectiveAt;  // 版本生效日期，ISO yyyy-MM-dd
    private String applicableInstitutions; // 资料适用机构/主体，与发布机构分开
    private String expiresAt;    // 版本失效日期，ISO yyyy-MM-dd
    private boolean repealed;    // 是否已废止

    /**
     * 版本化入库的完整构造函数（含合规元数据）；参数顺序与版本服务/恢复任务一致
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                              String orgTag, boolean isPublic, String taskType, String requesterId,
                              String documentId, String versionId, String buildId, String buildKind,
                              Integer versionNo, Integer buildNo,
                              String docType, String jurisdiction, String issuer, String effectiveAt) {
        this(fileMd5, filePath, fileName, userId, orgTag, isPublic, taskType, requesterId,
                documentId, versionId, buildId, buildKind, versionNo, buildNo,
                docType, jurisdiction, issuer, effectiveAt, null, null, false);
    }

    public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                              String orgTag, boolean isPublic, String taskType, String requesterId,
                              String documentId, String versionId, String buildId, String buildKind,
                              Integer versionNo, Integer buildNo,
                              String docType, String jurisdiction, String issuer, String effectiveAt,
                              String applicableInstitutions, String expiresAt, boolean repealed) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.taskType = taskType;
        this.requesterId = requesterId;
        this.documentId = documentId;
        this.versionId = versionId;
        this.buildId = buildId;
        this.buildKind = buildKind;
        this.versionNo = versionNo;
        this.buildNo = buildNo;
        this.docType = docType;
        this.jurisdiction = jurisdiction;
        this.issuer = issuer;
        this.effectiveAt = effectiveAt;
        this.applicableInstitutions = applicableInstitutions;
        this.expiresAt = expiresAt;
        this.repealed = repealed;
    }

    /**
     * 不含合规元数据的版本化构造函数；内部按空元数据转发，旧调用方保持可用
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                              String orgTag, boolean isPublic, String taskType, String requesterId,
                              String documentId, String versionId, String buildId, String buildKind,
                              Integer versionNo, Integer buildNo) {
        this(fileMd5, filePath, fileName, userId, orgTag, isPublic, taskType, requesterId,
                documentId, versionId, buildId, buildKind, versionNo, buildNo,
                null, null, null, null);
    }

    /**
     * 版本化之前的 8 参构造函数，构建信息全部为 null
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                              String orgTag, boolean isPublic, String taskType, String requesterId) {
        this(fileMd5, filePath, fileName, userId, orgTag, isPublic, taskType, requesterId,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 任务里携带的合规元数据；旧任务字段全空时返回空元数据而不是 null
     */
    public ComplianceMetadata complianceMetadata() {
        return new ComplianceMetadata(docType, jurisdiction, issuer,
                ComplianceMetadata.parseEffectiveAt(effectiveAt),
                applicableInstitutions,
                ComplianceMetadata.parseExpiresAt(expiresAt),
                repealed);
    }

    /**
     * 向后兼容的构造函数
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = null;
        this.orgTag = "DEFAULT";
        this.isPublic = false;
        this.taskType = TASK_TYPE_UPLOAD_PROCESS;
        this.requesterId = null;
    }
}
