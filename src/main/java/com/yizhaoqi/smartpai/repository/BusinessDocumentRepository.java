package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BusinessDocumentRepository extends JpaRepository<BusinessDocument, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BusinessDocument d where d.documentId = :documentId")
    Optional<BusinessDocument> findForUpdate(@Param("documentId") String documentId);

    @Query("select d.activeBuildId from BusinessDocument d " +
            "where d.deletedAt is null and d.activeBuildId is not null " +
            "and d.publicationStatus = com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED")
    List<String> findActiveBuildIds();

    long countByDeletedAtIsNullAndPublicationStatus(PublicationStatus publicationStatus);

    Optional<BusinessDocument> findFirstByDeletedAtIsNullAndPublicationStatusOrderByUpdatedAtDesc(
            PublicationStatus publicationStatus);

    List<BusinessDocument> findByDeletedAtIsNull();
}
