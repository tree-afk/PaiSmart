package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Blob;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(
        name = "document_vectors",
        // 同一 build 的同一分块只能有一行：Kafka 重复投递时即使清理遗漏，也会在插入时失败而不是悄悄重复；
        // 旧数据 build_id 为空，唯一键对 NULL 不生效（MySQL/H2 均视 NULL 互不相等），legacy 重复分块保持可检索
        uniqueConstraints = @UniqueConstraint(name = "uk_document_vectors_build_chunk", columnNames = {"build_id", "chunk_id"}),
        // 新唯一键以 build_id 打头，file_md5 不再有可用前缀索引；legacy 分支仍按 file_md5 查询/删除，单独补普通索引
        indexes = @Index(name = "idx_document_vectors_file_md5", columnList = "file_md5")
)
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(name = "build_id", length = 36)
    private String buildId;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "chunk_id", nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    /**
     * 条款路径，如 "第三章 风险管理 > 第十二条"；仅合规文档的条款级切分写入，历史分块为空
     */
    @Column(name = "article_path", length = 512)
    private String articlePath;

    /**
     * 条款号，如 "第十二条" 或 "第十二条第二款"；仅合规文档的条款级切分写入
     */
    @Column(name = "clause_no", length = 128)
    private String clauseNo;

    /**
     * 上下文检索（Contextual Retrieval）为该分块生成的一两句上下文说明，只参与 embedding 和 BM25，
     * 不作为原文展示或引用。为空表示未开启、尚未生成或生成失败（按原文入索引）。
     */
    @Column(name = "chunk_context", columnDefinition = "TEXT")
    private String chunkContext;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;
}
