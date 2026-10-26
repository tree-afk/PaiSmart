package com.yizhaoqi.smartpai.model;

import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在 H2 上实际建表并执行新增的三层模型写入，验证唯一约束与检索范围查询按预期生效
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DocumentVersionModelTest {

    @Autowired
    private BusinessDocumentRepository businessDocumentRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private DocumentBuildRepository documentBuildRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void businessDocumentIsPersistedAndReadBack() {
        BusinessDocument document = document("doc-1", "build-a", null);
        businessDocumentRepository.saveAndFlush(document);

        BusinessDocument loaded = businessDocumentRepository.findById("doc-1").orElseThrow();
        assertThat(loaded.getOwnerUserId()).isEqualTo("user-1");
        assertThat(loaded.getTitle()).isEqualTo("制度手册.pdf");
        assertThat(loaded.getActiveBuildId()).isEqualTo("build-a");
        assertThat(loaded.getDeletedAt()).isNull();
    }

    @Test
    void complianceMetadataIsPersistedAndLegacyVersionsKeepNulls() {
        DocumentVersion regulated = saveVersion("version-reg", "doc-reg", 1, "md5-reg", 11L);
        regulated.setDocType("REGULATION");
        regulated.setJurisdiction("全国");
        regulated.setIssuer("国家金融监督管理总局");
        regulated.setEffectiveAt(LocalDate.of(2026, 10, 1));
        documentVersionRepository.saveAndFlush(regulated);

        DocumentVersion loaded = documentVersionRepository.findById("version-reg").orElseThrow();
        assertThat(loaded.getDocType()).isEqualTo("REGULATION");
        assertThat(loaded.getJurisdiction()).isEqualTo("全国");
        assertThat(loaded.getIssuer()).isEqualTo("国家金融监督管理总局");
        assertThat(loaded.getEffectiveAt()).isEqualTo(LocalDate.of(2026, 10, 1));

        DocumentVersion legacy = saveVersion("version-legacy", "doc-legacy", 1, "md5-legacy", 12L);
        assertThat(legacy.getDocType()).isNull();
        assertThat(legacy.getJurisdiction()).isNull();
        assertThat(legacy.getIssuer()).isNull();
        assertThat(legacy.getEffectiveAt()).isNull();
    }

    @Test
    void duplicateVersionNoWithinDocumentIsRejected() {
        saveVersion("version-1", "doc-1", 1, "md5-1", 1L);

        assertThatThrownBy(() -> saveVersion("version-2", "doc-1", 1, "md5-2", 2L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateFileMd5WithinDocumentIsRejected() {
        saveVersion("version-1", "doc-1", 1, "md5-1", 1L);

        assertThatThrownBy(() -> saveVersion("version-2", "doc-1", 2, "md5-1", 2L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateFileUploadIdAcrossVersionsIsRejected() {
        saveVersion("version-1", "doc-1", 1, "md5-1", 1L);

        assertThatThrownBy(() -> saveVersion("version-2", "doc-2", 1, "md5-2", 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateBuildNoWithinVersionIsRejected() {
        saveBuild("build-1", "version-1", 1, DocumentBuildStatus.PENDING);

        assertThatThrownBy(() -> saveBuild("build-2", "version-1", 1, DocumentBuildStatus.PENDING))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyVectorsWithNullBuildIdMayRepeatButSameBuildChunkMayNot() {
        // 旧数据没有 build_id，同一文件同一分块允许重复存在，保持 legacy 分支可检索
        saveVector(null, "md5-legacy", 1);
        saveVector(null, "md5-legacy", 1);
        assertThat(documentVectorRepository.count()).isEqualTo(2);

        // 新 build 内同一分块只能有一行，Kafka 重复投递时插入失败而不是悄悄重复
        saveVector("build-1", "md5-new", 1);
        assertThatThrownBy(() -> saveVector("build-1", "md5-new", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void vectorClauseMetadataIsPersistedAndNullableForLegacyChunks() {
        DocumentVector regulated = saveVector("build-1", "md5-regulated", 1);
        regulated.setArticlePath("第二章 风险管理 > 第十二条");
        regulated.setClauseNo("第十二条第二款");
        documentVectorRepository.saveAndFlush(regulated);

        DocumentVector loaded = documentVectorRepository.findById(regulated.getVectorId()).orElseThrow();
        assertThat(loaded.getArticlePath()).isEqualTo("第二章 风险管理 > 第十二条");
        assertThat(loaded.getClauseNo()).isEqualTo("第十二条第二款");

        DocumentVector legacy = saveVector(null, "md5-legacy", 9);
        assertThat(legacy.getArticlePath()).isNull();
        assertThat(legacy.getClauseNo()).isNull();
    }

    @Test
    void activeBuildIdsExcludeDeletedDocumentsAndDocumentsWithoutActiveBuild() {
        businessDocumentRepository.saveAndFlush(document("doc-active", "build-live", null));
        businessDocumentRepository.saveAndFlush(document("doc-deleted", "build-deleted", now));
        businessDocumentRepository.saveAndFlush(document("doc-empty", null, null));
        BusinessDocument draft = document("doc-draft", "build-draft", null);
        draft.setPublicationStatus(com.yizhaoqi.smartpai.model.PublicationStatus.DRAFT);
        businessDocumentRepository.saveAndFlush(draft);

        assertThat(businessDocumentRepository.findActiveBuildIds()).containsExactly("build-live");
    }

    @Test
    void latestBuildIsChosenByVersionNoThenBuildNo() {
        saveVersion("version-1", "doc-1", 1, "md5-1", 1L);
        saveVersion("version-2", "doc-1", 2, "md5-2", 2L);
        saveBuild("build-v1-1", "version-1", 1, DocumentBuildStatus.SUPERSEDED);
        saveBuild("build-v1-2", "version-1", 2, DocumentBuildStatus.SUPERSEDED);
        saveBuild("build-v2-1", "version-2", 1, DocumentBuildStatus.FAILED);

        assertThat(documentBuildRepository
                .findFirstByVersionDocumentIdOrderByVersionVersionNoDescBuildNoDesc("doc-1")
                .orElseThrow().getBuildId())
                .isEqualTo("build-v2-1");
    }

    private BusinessDocument document(String documentId, String activeBuildId, LocalDateTime deletedAt) {
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(documentId);
        document.setOwnerUserId("user-1");
        document.setTitle("制度手册.pdf");
        document.setActiveVersionId("version-1");
        document.setActiveBuildId(activeBuildId);
        document.setPublicationStatus(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED);
        document.setDeletedAt(deletedAt);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    private DocumentVersion saveVersion(String versionId, String documentId, int versionNo, String fileMd5, Long fileUploadId) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setFileMd5(fileMd5);
        version.setFileUploadId(fileUploadId);
        version.setFileName("制度手册.pdf");
        version.setOrgTag("dept-a");
        version.setCreatedAt(now);
        return documentVersionRepository.saveAndFlush(version);
    }

    private DocumentBuild saveBuild(String buildId, String versionId, int buildNo, DocumentBuildStatus status) {
        DocumentBuild build = new DocumentBuild();
        build.setBuildId(buildId);
        build.setVersionId(versionId);
        build.setBuildNo(buildNo);
        build.setStatus(status);
        build.setCreatedAt(now);
        return documentBuildRepository.saveAndFlush(build);
    }

    private DocumentVector saveVector(String buildId, String fileMd5, int chunkId) {
        DocumentVector vector = new DocumentVector();
        vector.setBuildId(buildId);
        vector.setFileMd5(fileMd5);
        vector.setChunkId(chunkId);
        vector.setTextContent("文本内容");
        vector.setUserId("user-1");
        return documentVectorRepository.saveAndFlush(vector);
    }
}
