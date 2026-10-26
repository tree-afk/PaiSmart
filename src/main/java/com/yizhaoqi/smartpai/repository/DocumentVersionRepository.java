package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, String> {
    List<DocumentVersion> findByDocumentIdOrderByVersionNoDesc(String documentId);

    Optional<DocumentVersion> findByDocumentIdAndFileMd5(String documentId, String fileMd5);

    List<DocumentVersion> findByDocumentIdAndLegacyRepresentationTrue(String documentId);

    /**
     * 上传记录对应的内容版本；file_upload_id 上有唯一键，每个上传记录最多一个版本。
     */
    Optional<DocumentVersion> findFirstByFileUploadIdOrderByVersionNoDesc(Long fileUploadId);

    Optional<DocumentVersion> findFirstByFileMd5OrderByCreatedAtDesc(String fileMd5);

    /**
     * 同一 MD5 可能被多个业务文档/版本复用；原文读取要逐个版本判断，
     * 不能只看最新一条，否则最新草稿会遮住较早的已发布副本。
     */
    List<DocumentVersion> findByFileMd5OrderByCreatedAtDesc(String fileMd5);

    @Query("select v.fileMd5 from DocumentVersion v where v.legacyRetired = true")
    List<String> findRetiredLegacyMd5s();
}
