package com.yizhaoqi.smartpai.entity;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Elasticsearch存储的文档实体类
 * 包含文档内容和权限信息
 */
@Data
public class EsDocument {

    private String id;             // 文档唯一标识
    private String documentId;     // 所属业务文档
    private String versionId;      // 内容版本
    private String buildId;        // 构建批次，检索时按它过滤 active build
    private Integer versionNo;     // 内容版本号
    private Integer buildNo;       // 版本内构建批次号
    private String docType;        // 合规文档类型：REGULATION/INTERNAL_POLICY/PRODUCT_TERMS/GUIDELINE
    private String jurisdiction;   // 适用地区显示值
    private String jurisdictionCode; // 归一化地区编码：NATIONAL/上海/北京
    private String issuer;         // 发布机构（只说明谁发布）
    private String applicableInstitutions; // 资料适用机构/主体
    private String effectiveAt;    // 版本生效日期，ISO yyyy-MM-dd 字符串，mapping 为 date
    private String expiresAt;      // 版本失效日期，ISO yyyy-MM-dd 字符串，mapping 为 date
    private boolean repealed;      // 是否已废止
    private String fileMd5;        // 文件指纹
    private Integer chunkId;       // 文本分块序号
    private String fileName;       // 文件名，单独参与关键词打分（标题性强的文档在对应提问下排得更前）
    private String textContent;    // 分块原文（展示、引用、证据片段只用这个字段）
    private String chunkContext;   // 上下文检索生成的分块说明，只参与 BM25 与 embedding，可为空
    private Integer pageNumber;    // PDF 页码
    private String anchorText;     // 页内定位锚点
    private String articlePath;    // 条款路径，如 "第三章 风险管理 > 第十二条"
    private String clauseNo;       // 条款号，如 "第十二条" 或 "第十二条第二款"
    private float[] vector;        // 向量数据（768维）
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    @JsonProperty("public")
    private boolean isPublic;      // 是否公开

    /**
     * 默认构造函数，用于Jackson反序列化
     */
    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion, 
                     String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, null, pageNumber, anchorText, vector, modelVersion, userId, orgTag, isPublic);
    }

    /**
     * 带上下文说明的构造函数：textContent 保存原文，chunkContext 单独存放
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content, String chunkContext,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.chunkContext = chunkContext;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }
    

}
