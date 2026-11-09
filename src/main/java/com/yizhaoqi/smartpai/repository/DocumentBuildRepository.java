package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DocumentBuildRepository extends JpaRepository<DocumentBuild, String> {
    Optional<DocumentBuild> findByBuildId(String buildId);

    /**
     * 悲观锁读取构建行，用于状态迁移与切换时“锁定后重新校验状态”，
     * 避免迟到的消息把已 ACTIVE / SUPERSEDED / CANCELLED 的构建改回其他状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DocumentBuild b where b.buildId = :buildId")
    Optional<DocumentBuild> findForUpdate(@Param("buildId") String buildId);

    List<DocumentBuild> findByVersionIdOrderByBuildNoDesc(String versionId);

    List<DocumentBuild> findByStatus(DocumentBuildStatus status);

    List<DocumentBuild> findByStatusAndCompletedAtBefore(DocumentBuildStatus status, LocalDateTime before);

    /**
     * 已到业务生效日、且未失效/废止的 READY 构建：构建就绪与业务生效分开，
     * 未来生效版本先保持 READY，由定时任务在生效日切换。
     */
    @Query("select b from DocumentBuild b, DocumentVersion v " +
            "where b.versionId = v.versionId and b.status = :status " +
            "and (v.effectiveAt is null or v.effectiveAt <= :asOf) " +
            "and (v.expiresAt is null or v.expiresAt >= :asOf) " +
            "and v.repealed = false")
    List<DocumentBuild> findReadyEffective(@Param("status") DocumentBuildStatus status,
                                            @Param("asOf") LocalDate asOf);

    /**
     * 某文档最近一次构建尝试（先比版本号，再比版本内批次号）。
     * DocumentBuild 只有标量 versionId、没有 version 关联，Spring Data 无法按
     * VersionDocumentId 逐级解析，这里用显式查询保留同名接口。
     */
    @Query(value = """
            SELECT b.* FROM document_build b
            JOIN document_version v ON v.version_id = b.version_id
            WHERE v.document_id = ?1
            ORDER BY v.version_no DESC, b.build_no DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<DocumentBuild> findFirstByVersionDocumentIdOrderByVersionVersionNoDescBuildNoDesc(String documentId);

    /**
     * 某个上传记录对应的最近一次构建尝试：先取该上传的最新内容版本，再取版本内最新批次。
     * 恢复任务据此按原构建重新投递，避免把同 md5 的重建任务退化成按 fileMd5 清理的 legacy 任务。
     */
    @Query("select b from DocumentBuild b, DocumentVersion v " +
            "where b.versionId = v.versionId and v.fileUploadId = :fileUploadId " +
            "order by v.versionNo desc, b.buildNo desc")
    List<DocumentBuild> findLatestForFileUpload(@Param("fileUploadId") Long fileUploadId, Pageable pageable);
}
