package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.ComplianceMetadataPatch;
import com.yizhaoqi.smartpai.model.ComplianceValidityStatus;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.reliability.FileProcessingOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 版本服务的业务规则：合并后在同一事务内建文档/版本/构建并写 outbox，
 * 新版本继承当前版本权限并拒绝重复内容，legacy 懒迁移幂等且不复制，
 * retry/reindex 只对当前 active 版本增加 build_no、不改 version_no。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DocumentVersionService.class, DocumentBuildService.class})
class DocumentVersionServiceTest {

    @Autowired
    private DocumentVersionService versionService;

    @Autowired
    private BusinessDocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Autowired
    private DocumentBuildRepository buildRepository;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentBuildService buildService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private FileProcessingOutboxService fileProcessingOutboxService;

    @MockitoBean
    private ElasticsearchService elasticsearchService;

    @MockitoBean
    private ActiveRetrievalScopeService activeRetrievalScopeService;

    @Test
    void firstDocumentCreatesDocumentVersionAndFirstBuildWithUploadPermission() {
        FileUpload upload = saveUpload("md5-first", "owner-1", "first.pdf", "TEAM_A", true);

        DocumentVersionService.VersionBuild result =
                versionService.createAndEnqueue(upload, "owner-1", "https://example.com/first", null, null);

        assertEquals("TEAM_A", result.orgTag());
        BusinessDocument document = documentRepository.findById(result.documentId()).orElseThrow();
        assertEquals("owner-1", document.getOwnerUserId());
        assertEquals("first.pdf", document.getTitle());
        assertNotNull(document.getUpdatedAt());
        assertNull(document.getActiveVersionId());
        assertNull(document.getActiveBuildId());

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals(1, version.getVersionNo());
        assertEquals("md5-first", version.getFileMd5());
        assertEquals(upload.getId(), version.getFileUploadId());
        assertEquals("first.pdf", version.getFileName());
        assertEquals("TEAM_A", version.getOrgTag());
        assertTrue(version.isPublic());
        assertFalse(version.isLegacyRepresentation());
        assertEquals(1, result.versionNo());

        DocumentBuild build = buildRepository.findById(result.buildId()).orElseThrow();
        assertEquals(version.getVersionId(), build.getVersionId());
        assertEquals(1, build.getBuildNo());
        assertEquals(DocumentBuildStatus.PENDING, build.getStatus());
        assertEquals(1, result.buildNo());
        assertEquals("md5-first", result.fileMd5());
        assertEquals("first.pdf", result.fileName());
        assertTrue(result.isPublic());

        FileProcessingTask task = captureTask(upload);
        assertEquals("md5-first", task.getFileMd5());
        assertEquals("https://example.com/first", task.getFilePath());
        assertEquals("first.pdf", task.getFileName());
        assertEquals("owner-1", task.getUserId());
        assertEquals("TEAM_A", task.getOrgTag());
        assertTrue(task.isPublic());
        assertEquals(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, task.getTaskType());
        assertEquals("owner-1", task.getRequesterId());
        assertEquals(result.documentId(), task.getDocumentId());
        assertEquals(result.versionId(), task.getVersionId());
        assertEquals(result.buildId(), task.getBuildId());
        assertEquals(FileProcessingTask.BUILD_KIND_NEW_DOCUMENT, task.getBuildKind());
        assertEquals(1, task.getVersionNo());
        assertEquals(1, task.getBuildNo());
    }

    @Test
    void newVersionIncrementsVersionNoAndInheritsActiveVersionPermission() {
        BusinessDocument document = saveDocumentWithActiveVersion("owner-1", 1, "md5-old", "ACTIVE_TEAM", true);
        FileUpload upload = saveUpload("md5-new", "owner-1", "new.pdf", "UPLOADER_TEAM", false);

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                upload, "owner-1", "https://example.com/new", document.getDocumentId(), null);

        assertEquals(2, result.versionNo());
        assertEquals(1, result.buildNo());
        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals("ACTIVE_TEAM", version.getOrgTag(), "权限继承当前 active 版本，而不是本次上传参数");
        assertTrue(version.isPublic());
        assertEquals(upload.getId(), version.getFileUploadId());
        assertEquals("md5-new", version.getFileMd5());
        assertEquals("new.pdf", version.getFileName());

        DocumentBuild build = buildRepository.findById(result.buildId()).orElseThrow();
        assertEquals(1, build.getBuildNo());
        assertEquals(DocumentBuildStatus.PENDING, build.getStatus());

        // 新版本创建不影响旧 active 指针
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(document.getActiveVersionId(), unchanged.getActiveVersionId());
        assertNull(unchanged.getActiveBuildId());

        FileProcessingTask task = captureTask(upload);
        assertEquals(FileProcessingTask.BUILD_KIND_NEW_VERSION, task.getBuildKind());
        assertEquals("ACTIVE_TEAM", task.getOrgTag(), "任务权限取版本快照");
        assertEquals(document.getDocumentId(), task.getDocumentId());
        assertEquals(2, task.getVersionNo());
    }

    @Test
    void firstDocumentStoresValidityAndApplicabilityAndCarriesThemToTask() {
        FileUpload upload = saveUpload("md5-validity", "owner-1", "validity.pdf", "TEAM_A", false);
        ComplianceMetadata metadata = new ComplianceMetadata(
                "REGULATION", "上海市", "监管总局", LocalDate.of(2026, 1, 1),
                "商业银行、保险公司", LocalDate.of(2026, 12, 31), false);

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null, metadata);

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals("上海", version.getJurisdiction(), "地区显示值统一去掉行政区后缀");
        assertEquals("上海", version.getJurisdictionCode());
        assertEquals("商业银行、保险公司", version.getApplicableInstitutions());
        assertEquals(LocalDate.of(2026, 12, 31), version.getExpiresAt());
        assertFalse(version.isRepealed());

        FileProcessingTask task = captureTask(upload);
        assertEquals("上海", task.getJurisdiction());
        assertEquals("商业银行、保险公司", task.getApplicableInstitutions());
        assertEquals("2026-12-31", task.getExpiresAt());
        assertFalse(task.isRepealed());
    }

    @Test
    void metadataRevisionUpdatesVersionPausesRetrievalAndPublishesOnActivation() {
        FileUpload upload = saveUpload("md5-metadata-revision", "owner-1", "metadata.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null,
                new ComplianceMetadata("REGULATION", "全国", "旧机构", LocalDate.of(2026, 1, 1)));
        BusinessDocument document = documentRepository.findById(created.documentId()).orElseThrow();
        document.setPublicationStatus(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED);
        documentRepository.save(document);

        ComplianceMetadata corrected = new ComplianceMetadata(
                "INTERNAL_POLICY", "上海", "新机构", LocalDate.of(2026, 2, 1),
                "上海分行", null, false);
        DocumentVersionService.VersionBuild revision =
                versionService.updateMetadataAndRebuild(created.documentId(), corrected, "owner-1");

        DocumentVersion version = versionRepository.findById(created.versionId()).orElseThrow();
        assertEquals("INTERNAL_POLICY", version.getDocType());
        assertEquals("上海", version.getJurisdiction());
        assertEquals("上海分行", version.getApplicableInstitutions());
        BusinessDocument paused = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.DRAFT, paused.getPublicationStatus());
        assertTrue(paused.isMetadataRevisionPending());
        assertEquals(revision.buildId(), paused.getMetadataRevisionBuildId());

        buildService.markProcessing(revision.buildId());
        buildService.markReady(revision.buildId(), 1);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(revision.buildId()));

        BusinessDocument resumed = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED, resumed.getPublicationStatus());
        assertFalse(resumed.isMetadataRevisionPending());
        assertNull(resumed.getMetadataRevisionBuildId());
        assertEquals(revision.buildId(), resumed.getActiveBuildId());
    }

    @Test
    void metadataRevisionKeepsUnspecifiedFieldsAndAllowsExplicitClear() {
        FileUpload upload = saveUpload("md5-meta-partial", "owner-1", "partial.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null,
                new ComplianceMetadata("REGULATION", "全国", "旧机构",
                        LocalDate.of(2026, 1, 1), "旧适用机构", LocalDate.of(2026, 12, 31), true));

        ComplianceMetadataPatch partial = ComplianceMetadataPatch.builder()
                .issuer("新机构")
                .applicableInstitutions("")
                .build();
        versionService.updateMetadataAndRebuild(created.documentId(), partial, "owner-1");

        DocumentVersion updated = versionRepository.findById(created.versionId()).orElseThrow();
        assertEquals("新机构", updated.getIssuer());
        assertEquals(LocalDate.of(2026, 1, 1), updated.getEffectiveAt(), "未提交的生效日期不能被清空");
        assertEquals(LocalDate.of(2026, 12, 31), updated.getExpiresAt(), "未提交的失效日期不能被清空");
        assertTrue(updated.isRepealed(), "未提交的废止标记不能被重置");
        assertNull(updated.getApplicableInstitutions(), "明确提交空值应清空适用机构");
    }

    @Test
    void futureEffectiveReadyRevisionCanBeReplacedAndOldBuildIsCancelled() {
        FileUpload upload = saveUpload("md5-meta-future-fix", "owner-1", "future-fix.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null,
                new ComplianceMetadata("REGULATION", "全国", "机构", LocalDate.of(2026, 1, 1)));
        activate(created.buildId());

        ComplianceMetadata future = new ComplianceMetadata(
                "REGULATION", "全国", "机构", LocalDate.of(2027, 1, 1));
        DocumentVersionService.VersionBuild revision =
                versionService.updateMetadataAndRebuild(created.documentId(), future, "owner-1");
        buildService.markProcessing(revision.buildId());
        buildService.markReady(revision.buildId(), 1);
        assertEquals(DocumentBuildService.BuildActivation.PENDING_EFFECTIVE,
                buildService.activate(revision.buildId()));

        ComplianceMetadataPatch corrected = ComplianceMetadataPatch.builder()
                .effectiveAt(LocalDate.of(2026, 6, 1))
                .build();
        DocumentVersionService.VersionBuild replacement =
                versionService.updateMetadataAndRebuild(created.documentId(), corrected, "owner-1");

        BusinessDocument pending = documentRepository.findById(created.documentId()).orElseThrow();
        assertTrue(pending.isMetadataRevisionPending());
        assertEquals(replacement.buildId(), pending.getMetadataRevisionBuildId());
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.DRAFT, pending.getPublicationStatus());

        assertEquals(DocumentBuildService.BuildActivation.CANCELLED,
                buildService.activate(revision.buildId()), "被替换的旧 READY 修订应取消");
        assertEquals(DocumentBuildStatus.CANCELLED,
                buildRepository.findById(revision.buildId()).orElseThrow().getStatus());
        assertEquals(created.buildId(),
                documentRepository.findById(created.documentId()).orElseThrow().getActiveBuildId());

        buildService.markProcessing(replacement.buildId());
        buildService.markReady(replacement.buildId(), 1);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE,
                buildService.activate(replacement.buildId()));

        BusinessDocument resumed = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED, resumed.getPublicationStatus());
        assertFalse(resumed.isMetadataRevisionPending());
        assertNull(resumed.getMetadataRevisionBuildId());
        assertEquals(replacement.buildId(), resumed.getActiveBuildId());
    }

    @Test
    void olderReadyBuildIsCancelledWhileMetadataRevisionPending() {
        FileUpload upload = saveUpload("md5-meta-block", "owner-1", "block.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null,
                new ComplianceMetadata("REGULATION", "全国", "机构", LocalDate.of(2026, 1, 1)));
        activate(created.buildId());

        DocumentVersionService.VersionBuild older =
                versionService.createBuildForActiveVersion(created.versionId(), "owner-1",
                        FileProcessingTask.BUILD_KIND_REINDEX);
        buildService.markProcessing(older.buildId());
        buildService.markReady(older.buildId(), 1);

        ComplianceMetadata corrected = new ComplianceMetadata(
                "INTERNAL_POLICY", "上海", "新机构", LocalDate.of(2026, 2, 1));
        DocumentVersionService.VersionBuild revision =
                versionService.updateMetadataAndRebuild(created.documentId(), corrected, "owner-1");

        assertEquals(DocumentBuildService.BuildActivation.CANCELLED,
                buildService.activate(older.buildId()),
                "同版本旧 READY 构建已被新修订替换，应取消而不是继续占用恢复队列");

        BusinessDocument paused = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.DRAFT, paused.getPublicationStatus());
        assertTrue(paused.isMetadataRevisionPending());
        assertEquals(revision.buildId(), paused.getMetadataRevisionBuildId());
        assertEquals(created.buildId(), paused.getActiveBuildId());
        assertEquals(DocumentBuildStatus.CANCELLED,
                buildRepository.findById(older.buildId()).orElseThrow().getStatus());

        buildService.markProcessing(revision.buildId());
        buildService.markReady(revision.buildId(), 1);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(revision.buildId()));

        BusinessDocument resumed = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED, resumed.getPublicationStatus());
        assertFalse(resumed.isMetadataRevisionPending());
        assertNull(resumed.getMetadataRevisionBuildId());
        assertEquals(revision.buildId(), resumed.getActiveBuildId());
    }

    @Test
    void failedMetadataRevisionStaysRestrictedAndRetryCanPublish() {
        FileUpload upload = saveUpload("md5-meta-fail", "owner-1", "fail.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null,
                new ComplianceMetadata("REGULATION", "全国", "机构", LocalDate.of(2026, 1, 1)));
        activate(created.buildId());

        DocumentVersionService.VersionBuild unrelated =
                versionService.createBuildForActiveVersion(created.versionId(), "owner-1",
                        FileProcessingTask.BUILD_KIND_REINDEX);
        buildService.markProcessing(unrelated.buildId());
        buildService.markReady(unrelated.buildId(), 1);

        ComplianceMetadata corrected = new ComplianceMetadata(
                "INTERNAL_POLICY", "上海", "新机构", LocalDate.of(2026, 2, 1));
        DocumentVersionService.VersionBuild revision =
                versionService.updateMetadataAndRebuild(created.documentId(), corrected, "owner-1");

        buildService.markFailed(unrelated.buildId(), "unrelated failure");
        BusinessDocument afterUnrelatedFailure = documentRepository.findById(created.documentId()).orElseThrow();
        assertTrue(afterUnrelatedFailure.isMetadataRevisionPending(), "其他构建失败不能解除修订限制");
        assertEquals(revision.buildId(), afterUnrelatedFailure.getMetadataRevisionBuildId());

        buildService.markFailed(revision.buildId(), "revision failure");
        BusinessDocument failed = documentRepository.findById(created.documentId()).orElseThrow();
        assertTrue(failed.isMetadataRevisionPending(), "修订构建失败后仍保持受限期");
        assertEquals(revision.buildId(), failed.getMetadataRevisionBuildId());
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.DRAFT, failed.getPublicationStatus());
        assertEquals(created.buildId(), failed.getActiveBuildId());

        DocumentVersionService.VersionBuild retry =
                versionService.createBuildForActiveVersion(created.versionId(), "owner-1",
                        FileProcessingTask.BUILD_KIND_REINDEX);
        BusinessDocument pendingRetry = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(retry.buildId(), pendingRetry.getMetadataRevisionBuildId());
        assertTrue(pendingRetry.isMetadataRevisionPending());

        buildService.markProcessing(retry.buildId());
        buildService.markReady(retry.buildId(), 1);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(retry.buildId()));

        BusinessDocument resumed = documentRepository.findById(created.documentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED, resumed.getPublicationStatus());
        assertFalse(resumed.isMetadataRevisionPending());
        assertNull(resumed.getMetadataRevisionBuildId());
        assertEquals(retry.buildId(), resumed.getActiveBuildId());
    }

    private void activate(String buildId) {
        buildService.markProcessing(buildId);
        buildService.markReady(buildId, 1);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(buildId));
    }

    @Test
    void createBuildForActiveVersionReturnsVersionComplianceMetadata() {
        FileUpload upload = saveUpload("md5-reindex-meta", "owner-1", "reindex.pdf", "TEAM_A", false);
        ComplianceMetadata metadata = new ComplianceMetadata(
                "INTERNAL_POLICY", "北京", "某银行", LocalDate.of(2026, 4, 1));
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null, metadata);

        DocumentVersionService.VersionBuild reindex = versionService.createBuildForActiveVersion(
                created.versionId(), "owner-1", FileProcessingTask.BUILD_KIND_REINDEX);

        assertEquals(2, reindex.buildNo());
        assertEquals(metadata, reindex.compliance(), "重建构建沿用版本已落库的合规元数据");
    }

    @Test
    void firstDocumentStoresComplianceMetadataAndCarriesItToTask() {
        FileUpload upload = saveUpload("md5-compliance", "owner-1", "compliance.pdf", "TEAM_A", false);
        ComplianceMetadata metadata = new ComplianceMetadata("regulation", "全国", "国家金融监督管理总局",
                LocalDate.of(2026, 10, 1));

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null, metadata);

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals("REGULATION", version.getDocType(), "类型代码统一规范化为枚举名");
        assertEquals("全国", version.getJurisdiction());
        assertEquals("国家金融监督管理总局", version.getIssuer());
        assertEquals(LocalDate.of(2026, 10, 1), version.getEffectiveAt());
        assertEquals(metadata, result.compliance());

        FileProcessingTask task = captureTask(upload);
        assertEquals("REGULATION", task.getDocType());
        assertEquals("全国", task.getJurisdiction());
        assertEquals("国家金融监督管理总局", task.getIssuer());
        assertEquals("2026-10-01", task.getEffectiveAt());
        assertEquals(metadata, task.complianceMetadata());
    }

    @Test
    void firstDocumentWithoutComplianceMetadataKeepsValidityUnconfirmed() {
        FileUpload upload = saveUpload("md5-no-compliance", "owner-1", "plain.pdf", "TEAM_A", false);

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null);

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertNull(version.getDocType());
        assertNull(version.getJurisdiction());
        assertNull(version.getIssuer());
        assertNull(version.getEffectiveAt(), "缺少生效日期时保持为空，表示效力待确认");
        assertNull(result.compliance().docType(), "未传元数据时分类字段保持为空");
        assertEquals(ComplianceValidityStatus.UNCONFIRMED,
                result.compliance().validityStatus(LocalDate.now()));

        FileProcessingTask task = captureTask(upload);
        assertNull(task.getDocType());
        assertNull(task.getEffectiveAt(), "任务也不能按上传日期伪造生效日期");
    }

    @Test
    void newVersionInheritsComplianceFieldsFromActiveVersionWhenRequestOmitsThem() {
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion active = saveVersion(document.getDocumentId(), 1, "md5-old", false, "TEAM_A", false,
                nextFixtureUploadId(),
                new ComplianceMetadata("INTERNAL_POLICY", "北京", "某银行", LocalDate.of(2025, 1, 1)));
        document.setActiveVersionId(active.getVersionId());
        documentRepository.save(document);
        FileUpload upload = saveUpload("md5-new", "owner-1", "new.pdf", "TEAM_A", false);

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                upload, "owner-1", "url", document.getDocumentId(), null);

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals("INTERNAL_POLICY", version.getDocType());
        assertEquals("北京", version.getJurisdiction());
        assertEquals("某银行", version.getIssuer());
        assertNull(version.getEffectiveAt(), "生效日期不继承旧版本，也不按上传日期补齐");
        assertEquals("INTERNAL_POLICY", result.compliance().docType());

        FileProcessingTask task = captureTask(upload);
        assertEquals("INTERNAL_POLICY", task.getDocType());
        assertEquals("北京", task.getJurisdiction());
        assertEquals("某银行", task.getIssuer());
    }

    @Test
    void newVersionRequestOverridesProvidedComplianceFieldsAndKeepsInheritedOnes() {
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion active = saveVersion(document.getDocumentId(), 1, "md5-old", false, "TEAM_A", false,
                nextFixtureUploadId(),
                new ComplianceMetadata("INTERNAL_POLICY", "北京", "某银行", LocalDate.of(2025, 1, 1)));
        document.setActiveVersionId(active.getVersionId());
        documentRepository.save(document);
        FileUpload upload = saveUpload("md5-new", "owner-1", "new.pdf", "TEAM_A", false);
        ComplianceMetadata requested = new ComplianceMetadata(
                "PRODUCT_TERMS", null, "某保险公司", LocalDate.of(2026, 6, 1));

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                upload, "owner-1", "url", document.getDocumentId(), null, requested);

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals("PRODUCT_TERMS", version.getDocType());
        assertEquals("北京", version.getJurisdiction(), "未填的适用地区继续继承");
        assertEquals("某保险公司", version.getIssuer());
        assertEquals(LocalDate.of(2026, 6, 1), version.getEffectiveAt());
    }

    @Test
    void explicitVersionBuildTaskCarriesVersionComplianceMetadata() {
        FileUpload upload = saveUpload("md5-explicit", "owner-1", "explicit.pdf", "TEAM_A", false);
        ComplianceMetadata metadata = new ComplianceMetadata(
                "GUIDELINE", "上海", "某银行", LocalDate.of(2026, 3, 1));
        DocumentVersionService.VersionBuild created = versionService.createAndEnqueue(
                upload, "owner-1", "url", null, null, metadata);
        clearInvocations(fileProcessingOutboxService);

        DocumentVersionService.VersionBuild result = versionService.createBuildAndEnqueueForVersion(
                created.documentId(), created.versionId(), "owner-1");

        ArgumentCaptor<FileProcessingTask> captor = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(fileProcessingOutboxService).enqueueVectorization(eq(upload), captor.capture(), eq(true));
        FileProcessingTask task = captor.getValue();
        assertEquals("GUIDELINE", task.getDocType());
        assertEquals("上海", task.getJurisdiction());
        assertEquals("某银行", task.getIssuer());
        assertEquals("2026-03-01", task.getEffectiveAt());
        assertEquals(metadata, result.compliance());
    }

    @Test
    void sequentialNewVersionsGetStrictlyIncreasingVersionNumbers() {
        BusinessDocument document = saveDocumentWithActiveVersion("owner-1", 1, "md5-v1", "TEAM_A", false);
        FileUpload second = saveUpload("md5-v2", "owner-1", "v2.pdf", "TEAM_A", false);
        FileUpload third = saveUpload("md5-v3", "owner-1", "v3.pdf", "TEAM_A", false);

        DocumentVersionService.VersionBuild v2 = versionService.createAndEnqueue(
                second, "owner-1", "https://example.com/v2", document.getDocumentId(), null);
        DocumentVersionService.VersionBuild v3 = versionService.createAndEnqueue(
                third, "owner-1", "https://example.com/v3", document.getDocumentId(), null);

        assertEquals(2, v2.versionNo());
        assertEquals(3, v3.versionNo());
        List<DocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId());
        assertEquals(List.of(3, 2, 1), versions.stream().map(DocumentVersion::getVersionNo).toList());
    }

    @Test
    void newVersionRejectsContentThatAlreadyExistsInAnyVersion() {
        BusinessDocument document = saveDocumentWithActiveVersion("owner-1", 1, "md5-old", "TEAM_A", false);
        DocumentVersion current = saveVersion(document.getDocumentId(), 2, "md5-current", false,
                "TEAM_A", false, nextFixtureUploadId());
        document.setActiveVersionId(current.getVersionId());
        documentRepository.save(document);
        String documentId = document.getDocumentId();

        // 同内容此前已作为旧版本存在：即使不是当前版本也必须拒绝
        FileUpload duplicate = saveUpload("md5-old", "owner-1", "old.pdf", "TEAM_A", false);

        CustomException error = assertThrows(CustomException.class,
                () -> versionService.createAndEnqueue(duplicate, "owner-1", "url", documentId, null));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("内容与现有版本相同"));
        assertEquals(2, versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId).size());
        verify(fileProcessingOutboxService, never()).enqueueVectorization(any(), any(), eq(true));
    }

    @Test
    void newVersionRejectsForeignOwner() {
        BusinessDocument document = saveDocumentWithActiveVersion("owner-1", 1, "md5-old", "TEAM_A", false);
        FileUpload upload = saveUpload("md5-new", "owner-2", "new.pdf", "TEAM_A", false);

        CustomException error = assertThrows(CustomException.class,
                () -> versionService.createAndEnqueue(upload, "owner-2", "url", document.getDocumentId(), null));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals(1, versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId()).size());
    }

    @Test
    void newVersionRejectsDeletedOrMissingDocument() {
        BusinessDocument deleted = saveDocumentWithActiveVersion("owner-1", 1, "md5-old", "TEAM_A", false);
        deleted.setDeletedAt(LocalDateTime.now());
        documentRepository.save(deleted);
        FileUpload upload = saveUpload("md5-new", "owner-1", "new.pdf", "TEAM_A", false);

        CustomException deletedError = assertThrows(CustomException.class,
                () -> versionService.createAndEnqueue(upload, "owner-1", "url", deleted.getDocumentId(), null));
        assertEquals(HttpStatus.NOT_FOUND, deletedError.getStatus());

        CustomException missingError = assertThrows(CustomException.class,
                () -> versionService.createAndEnqueue(upload, "owner-1", "url", "missing-document", null));
        assertEquals(HttpStatus.NOT_FOUND, missingError.getStatus());
    }

    @Test
    void legacyTargetPromotesOldFileThenCreatesVersionTwoInSameCall() {
        FileUpload legacyUpload = saveUpload("md5-legacy", "owner-1", "legacy.pdf", "OLD_TEAM", true);
        FileUpload newUpload = saveUpload("md5-new", "owner-1", "new.pdf", "UPLOADER_TEAM", false);

        DocumentVersionService.VersionBuild result = versionService.createAndEnqueue(
                newUpload, "owner-1", "https://example.com/new", null, "md5-legacy");

        BusinessDocument document = documentRepository.findById(result.documentId()).orElseThrow();
        assertEquals("owner-1", document.getOwnerUserId());
        assertEquals("legacy.pdf", document.getTitle());
        assertNull(document.getActiveBuildId());

        List<DocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId());
        assertEquals(List.of(2, 1), versions.stream().map(DocumentVersion::getVersionNo).toList());
        DocumentVersion legacyVersion = versions.get(1);
        assertNotEquals(result.versionId(), document.getActiveVersionId(),
                "legacy 提升后新版本尚未切换，active 仍是 legacy v1");
        assertEquals(legacyVersion.getVersionId(), document.getActiveVersionId());
        assertTrue(legacyVersion.isLegacyRepresentation());
        assertEquals(legacyUpload.getId(), legacyVersion.getFileUploadId());
        DocumentVersion newVersion = versions.get(0);
        assertFalse(newVersion.isLegacyRepresentation());
        assertEquals(newUpload.getId(), newVersion.getFileUploadId());
        assertEquals("OLD_TEAM", newVersion.getOrgTag(), "新版本继承 legacy 旧数据权限");
        assertTrue(newVersion.isPublic());

        FileProcessingTask task = captureTask(newUpload);
        assertEquals(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, task.getTaskType());
        assertEquals(FileProcessingTask.BUILD_KIND_NEW_VERSION, task.getBuildKind());
        assertEquals("OLD_TEAM", task.getOrgTag());
        assertTrue(task.isPublic());
        assertEquals(2, task.getVersionNo());
        assertEquals(1, task.getBuildNo());
    }

    @Test
    void promoteLegacyCreatesLegacyVersionAndKeepsActiveBuildEmpty() {
        FileUpload upload = saveUpload("md5-legacy", "owner-1", "legacy.pdf", "OLD_TEAM", false);

        DocumentVersionService.VersionBuild result = versionService.promoteLegacy(upload, "owner-1");

        assertNotNull(result.documentId());
        assertNotNull(result.versionId());
        assertNull(result.buildId());
        assertNull(result.buildNo());
        assertEquals(1, result.versionNo());
        BusinessDocument document = documentRepository.findById(result.documentId()).orElseThrow();
        assertEquals("owner-1", document.getOwnerUserId());
        assertEquals("legacy.pdf", document.getTitle());
        assertEquals(result.versionId(), document.getActiveVersionId());
        assertNull(document.getActiveBuildId());

        DocumentVersion version = versionRepository.findById(result.versionId()).orElseThrow();
        assertEquals(1, version.getVersionNo());
        assertTrue(version.isLegacyRepresentation());
        assertFalse(version.isLegacyRetired());
        assertEquals("OLD_TEAM", version.getOrgTag());
        assertEquals(upload.getId(), version.getFileUploadId());
        assertNull(version.getDocType(), "legacy 懒迁移不推断合规元数据");
        assertNull(version.getJurisdiction());
        assertNull(version.getIssuer());
        assertNull(version.getEffectiveAt());
        assertTrue(buildRepository.findByVersionIdOrderByBuildNoDesc(version.getVersionId()).isEmpty());
        verifyNoInteractions(elasticsearchService);
    }

    @Test
    void promoteLegacyTwiceForTheSameUploadReusesDocumentAndVersion() {
        FileUpload upload = saveUpload("md5-legacy", "owner-1", "legacy.pdf", "OLD_TEAM", false);

        DocumentVersionService.VersionBuild first = versionService.promoteLegacy(upload, "owner-1");
        DocumentVersionService.VersionBuild second = versionService.promoteLegacy(upload, "owner-1");

        assertEquals(first.documentId(), second.documentId());
        assertEquals(first.versionId(), second.versionId());
        assertEquals(1, documentRepository.count());
        assertEquals(1, versionRepository.count());
        assertTrue(buildRepository.findAll().isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentLegacyPromotionOfSameUploadReturnsSingleDocumentAndVersion() throws Exception {
        cleanupCommittedRows();
        FileUpload upload = saveUpload("md5-legacy", "owner-1", "legacy.pdf", "OLD_TEAM", false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<DocumentVersionService.VersionBuild> promotion = () -> {
                start.await();
                return versionService.promoteLegacy(upload, "owner-1");
            };
            Future<DocumentVersionService.VersionBuild> first = executor.submit(promotion);
            Future<DocumentVersionService.VersionBuild> second = executor.submit(promotion);
            start.countDown();

            DocumentVersionService.VersionBuild firstResult = first.get(10, TimeUnit.SECONDS);
            DocumentVersionService.VersionBuild secondResult = second.get(10, TimeUnit.SECONDS);

            assertEquals(firstResult.documentId(), secondResult.documentId(), "并发登记必须复用同一业务文档");
            assertEquals(firstResult.versionId(), secondResult.versionId(), "并发登记必须复用同一内容版本");
            assertEquals(1, documentRepository.count());
            assertEquals(1, versionRepository.count());
        } finally {
            executor.shutdownNow();
            cleanupCommittedRows();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByIdForUpdateLocksUploadRowUntilTransactionCommits() throws Exception {
        cleanupCommittedRows();
        FileUpload upload = saveUpload("md5-lock", "owner-1", "lock.pdf", "TEAM_A", false);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Throwable> contenderError = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            attempting.countDown();
            try {
                transactionTemplate.executeWithoutResult(status ->
                        fileUploadRepository.findByIdForUpdate(upload.getId()).orElseThrow());
                acquired.countDown();
            } catch (Throwable error) {
                contenderError.set(error);
            }
        }, "lock-contender");

        try {
            transactionTemplate.executeWithoutResult(status -> {
                fileUploadRepository.findByIdForUpdate(upload.getId()).orElseThrow();
                contender.start();
                awaitQuietly(attempting);
                assertFalse(awaitQuietly(acquired, 300, TimeUnit.MILLISECONDS),
                        "第二个事务在首个事务提交前必须被行锁阻塞");
            });
            assertTrue(acquired.await(5, TimeUnit.SECONDS), "首个事务提交后第二个事务应获得行锁");
            assertNull(contenderError.get());
        } finally {
            contender.join(5000);
            cleanupCommittedRows();
        }
    }

    @Test
    void fileUploadIdUniqueKeyRemainsBackstopForVersionRegistration() {
        FileUpload upload = saveUpload("md5-backstop", "owner-1", "legacy.pdf", "TEAM_A", false);
        DocumentVersion first = new DocumentVersion();
        first.setVersionId("version-a");
        first.setDocumentId("document-a");
        first.setVersionNo(1);
        first.setFileMd5("md5-a");
        first.setFileUploadId(upload.getId());
        first.setFileName("a.pdf");
        first.setCreatedAt(LocalDateTime.now());
        versionRepository.saveAndFlush(first);

        DocumentVersion duplicate = new DocumentVersion();
        duplicate.setVersionId("version-b");
        duplicate.setDocumentId("document-b");
        duplicate.setVersionNo(1);
        duplicate.setFileMd5("md5-b");
        duplicate.setFileUploadId(upload.getId());
        duplicate.setFileName("b.pdf");
        duplicate.setCreatedAt(LocalDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> versionRepository.saveAndFlush(duplicate));
    }

    @Test
    void createAndEnqueueTwiceForSameUploadReturnsExistingVersionWithoutSecondTask() {
        FileUpload upload = saveUpload("md5-repeat", "owner-1", "repeat.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild first =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);
        DocumentVersionService.VersionBuild second =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);

        assertEquals(first.documentId(), second.documentId());
        assertEquals(first.versionId(), second.versionId());
        assertEquals(first.buildId(), second.buildId());
        assertEquals(1, documentRepository.count());
        assertEquals(1, versionRepository.count());
        assertEquals(1, buildRepository.count());
        verify(fileProcessingOutboxService, times(1))
                .enqueueVectorization(any(FileUpload.class), any(FileProcessingTask.class), eq(true));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRecoveryOfCompletedUploadCreatesSingleVersionBuildAndTask() throws Exception {
        cleanupCommittedRows();
        FileUpload upload = saveUpload("md5-recover", "owner-1", "recover.pdf", "TEAM_A", false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<DocumentVersionService.VersionBuild> recovery = () -> {
                start.await();
                return versionService.createAndEnqueue(upload, "owner-1", "url", null, null);
            };
            Future<DocumentVersionService.VersionBuild> first = executor.submit(recovery);
            Future<DocumentVersionService.VersionBuild> second = executor.submit(recovery);
            start.countDown();

            DocumentVersionService.VersionBuild firstResult = first.get(10, TimeUnit.SECONDS);
            DocumentVersionService.VersionBuild secondResult = second.get(10, TimeUnit.SECONDS);

            assertEquals(firstResult.documentId(), secondResult.documentId());
            assertEquals(firstResult.versionId(), secondResult.versionId());
            assertEquals(firstResult.buildId(), secondResult.buildId());
            assertEquals(1, documentRepository.count());
            assertEquals(1, versionRepository.count());
            assertEquals(1, buildRepository.count());
            verify(fileProcessingOutboxService, times(1))
                    .enqueueVectorization(any(FileUpload.class), any(FileProcessingTask.class), eq(true));
        } finally {
            executor.shutdownNow();
            cleanupCommittedRows();
        }
    }

    @Test
    void reindexBuildIncrementsBuildNoWithoutChangingVersionNo() {
        FileUpload upload = saveUpload("md5-reindex", "owner-1", "doc.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild first =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);
        LocalDateTime oldUpdatedAt = LocalDateTime.now().minusDays(1);
        BusinessDocument document = documentRepository.findById(first.documentId()).orElseThrow();
        document.setUpdatedAt(oldUpdatedAt);
        documentRepository.save(document);

        DocumentVersionService.VersionBuild second = versionService.createBuildForActiveVersion(
                first.versionId(), "owner-1", FileProcessingTask.BUILD_KIND_REINDEX);
        DocumentVersionService.VersionBuild third = versionService.createBuildForActiveVersion(
                first.versionId(), "owner-1", FileProcessingTask.BUILD_KIND_REINDEX);

        assertEquals(1, second.versionNo());
        assertEquals(2, second.buildNo());
        assertEquals(1, third.versionNo());
        assertEquals(3, third.buildNo());
        assertNotEquals(first.buildId(), second.buildId());

        DocumentVersion version = versionRepository.findById(first.versionId()).orElseThrow();
        assertEquals(1, version.getVersionNo(), "重建不改变内容版本号");
        DocumentBuild build = buildRepository.findById(second.buildId()).orElseThrow();
        assertEquals(DocumentBuildStatus.PENDING, build.getStatus());
        assertEquals(second.buildNo(), build.getBuildNo());

        // 构建在完成切换前不应移动 active 指针
        BusinessDocument unchanged = documentRepository.findById(first.documentId()).orElseThrow();
        assertNull(unchanged.getActiveVersionId());
        assertNull(unchanged.getActiveBuildId());
        assertTrue(unchanged.getUpdatedAt().isAfter(oldUpdatedAt), "创建构建应刷新 business_document.updated_at");
    }

    @Test
    void retryAllowsFailedLatestVersionButRejectsOlderHistoryVersion() {
        BusinessDocument document = saveDocument("owner-1");
        FileUpload upload1 = saveUpload("md5-v1", "owner-1", "v1.pdf", "OLD_TEAM", false);
        DocumentVersion v1 = saveVersion(document.getDocumentId(), 1, "md5-v1", false,
                "OLD_TEAM", false, upload1.getId());
        FileUpload upload2 = saveUpload("md5-v2", "owner-1", "v2.pdf", "NEW_TEAM", true);
        DocumentVersion v2 = saveVersion(document.getDocumentId(), 2, "md5-v2", false, "NEW_TEAM", true, upload2.getId());
        document.setActiveVersionId(v1.getVersionId());
        documentRepository.save(document);

        // v2 构建失败而 v1 仍 active：重试 v2 允许，且不移动 active 指针
        DocumentVersionService.VersionBuild retryV2 = versionService.createBuildForActiveVersion(
                v2.getVersionId(), "owner-1", FileProcessingTask.BUILD_KIND_REINDEX);
        assertEquals(2, retryV2.versionNo());
        assertEquals(1, retryV2.buildNo());
        assertEquals(v1.getVersionId(), documentRepository.findById(document.getDocumentId())
                .orElseThrow().getActiveVersionId(), "重试 v2 前 active 仍是 v1");
        assertEquals(1, buildRepository.findByVersionIdOrderByBuildNoDesc(v2.getVersionId()).size());

        // v2 切换为 active 后，更早的 v1 既不是 active 也不是最新版本，重试必须拒绝
        BusinessDocument switchV2 = documentRepository.findById(document.getDocumentId()).orElseThrow();
        switchV2.setActiveVersionId(v2.getVersionId());
        documentRepository.save(switchV2);

        CustomException error = assertThrows(CustomException.class, () -> versionService.createBuildForActiveVersion(
                v1.getVersionId(), "owner-1", FileProcessingTask.BUILD_KIND_REINDEX));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("当前版本"));
        assertTrue(buildRepository.findByVersionIdOrderByBuildNoDesc(v1.getVersionId()).isEmpty(),
                "被拒绝的旧版本不能再产生构建");
    }

    @Test
    void fileManagerCanRetryAnotherUsersVersionWhileReaderIsDenied() {
        User manager = saveUser("manager-1", User.Role.USER);
        manager.setKnowledgeRole(com.yizhaoqi.smartpai.model.KnowledgeRole.FILE_MANAGER);
        userRepository.save(manager);
        saveUser("plain-user", User.Role.USER);
        FileUpload upload = saveUpload("md5-foreign", "owner-1", "foreign.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);

        DocumentVersionService.VersionBuild adminBuild = versionService.createBuildForActiveVersion(
                created.versionId(), "manager-1", FileProcessingTask.BUILD_KIND_REINDEX);
        assertEquals(2, adminBuild.buildNo());

        saveUser("old-admin", User.Role.ADMIN);
        CustomException oldAdminDenied = assertThrows(CustomException.class, () -> versionService
                .createBuildForActiveVersion(created.versionId(), "old-admin", FileProcessingTask.BUILD_KIND_REINDEX));
        assertEquals(HttpStatus.FORBIDDEN, oldAdminDenied.getStatus(),
                "旧 ADMIN 不能绕过 knowledge_role 做文件管理");

        CustomException denied = assertThrows(CustomException.class, () -> versionService
                .createBuildForActiveVersion(created.versionId(), "plain-user", FileProcessingTask.BUILD_KIND_REINDEX));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
    }

    @Test
    void explicitVersionRouteAllowsLatestVersionEvenWhenOlderVersionIsActive() {
        BusinessDocument document = saveDocument("owner-1");
        FileUpload upload1 = saveUpload("md5-v1", "owner-1", "v1.pdf", "OLD_TEAM", false);
        DocumentVersion v1 = saveVersion(document.getDocumentId(), 1, "md5-v1", false,
                "OLD_TEAM", false, upload1.getId());
        FileUpload upload2 = saveUpload("md5-v2", "owner-1", "v2.pdf", "NEW_TEAM", true);
        DocumentVersion v2 = saveVersion(document.getDocumentId(), 2, "md5-v2", false, "NEW_TEAM", true, upload2.getId());
        document.setActiveVersionId(v1.getVersionId());
        documentRepository.save(document);

        DocumentVersionService.VersionBuild result = versionService.createBuildAndEnqueueForVersion(
                document.getDocumentId(), v2.getVersionId(), "owner-1");

        assertEquals(v2.getVersionId(), result.versionId());
        assertEquals(2, result.versionNo());
        assertEquals(1, result.buildNo());
        assertEquals(v1.getVersionId(), documentRepository.findById(document.getDocumentId())
                .orElseThrow().getActiveVersionId(), "显式重建 v2 不应提前移动 active 指针");

        ArgumentCaptor<FileProcessingTask> captor = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(fileProcessingOutboxService).enqueueVectorization(eq(upload2), captor.capture(), eq(true));
        assertEquals(FileProcessingTask.BUILD_KIND_REINDEX, captor.getValue().getBuildKind());
        assertEquals(2, captor.getValue().getVersionNo());
    }

    @Test
    void createBuildForActiveVersionRequiresOwnerAndCompletedUpload() {
        FileUpload upload = saveUpload("md5-reindex", "owner-1", "doc.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);

        CustomException foreign = assertThrows(CustomException.class, () -> versionService.createBuildForActiveVersion(
                created.versionId(), "intruder", FileProcessingTask.BUILD_KIND_REINDEX));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatus());

        FileUpload uploading = saveUploadWithStatus("md5-uploading", "owner-1", "up.pdf", FileUpload.STATUS_UPLOADING);
        DocumentVersion uploadingVersion = saveVersion("doc-uploading", 1, "md5-uploading", false,
                "TEAM_A", false, uploading.getId());
        BusinessDocument uploadingDocument = new BusinessDocument();
        uploadingDocument.setDocumentId("doc-uploading");
        uploadingDocument.setOwnerUserId("owner-1");
        uploadingDocument.setTitle("up.pdf");
        uploadingDocument.setCreatedAt(LocalDateTime.now());
        uploadingDocument.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(uploadingDocument);

        CustomException notCompleted = assertThrows(CustomException.class, () -> versionService.createBuildForActiveVersion(
                uploadingVersion.getVersionId(), "owner-1", FileProcessingTask.BUILD_KIND_REINDEX));
        assertEquals(HttpStatus.CONFLICT, notCompleted.getStatus());
    }

    @Test
    void createBuildAndEnqueueForVersionCreatesTaskForActiveVersion() {
        FileUpload upload = saveUpload("md5-route", "owner-1", "route.pdf", "ROUTE_TEAM", false);
        DocumentVersionService.VersionBuild created =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);
        clearInvocations(fileProcessingOutboxService);

        DocumentVersionService.VersionBuild result = versionService.createBuildAndEnqueueForVersion(
                created.documentId(), created.versionId(), "owner-1");

        assertEquals(created.versionId(), result.versionId());
        assertEquals(1, result.versionNo());
        assertEquals(2, result.buildNo());

        ArgumentCaptor<FileProcessingTask> captor = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(fileProcessingOutboxService).enqueueVectorization(eq(upload), captor.capture(), eq(true));
        FileProcessingTask task = captor.getValue();
        assertEquals(FileProcessingTask.TASK_TYPE_REINDEX, task.getTaskType());
        assertEquals(FileProcessingTask.BUILD_KIND_REINDEX, task.getBuildKind());
        assertEquals("owner-1", task.getRequesterId());
        assertEquals(created.documentId(), task.getDocumentId());
        assertEquals(created.versionId(), task.getVersionId());
        assertEquals(result.buildId(), task.getBuildId());
        assertEquals("md5-route", task.getFileMd5());
        assertEquals("route.pdf", task.getFileName());
        assertEquals("ROUTE_TEAM", task.getOrgTag());
        assertEquals(1, task.getVersionNo());
        assertEquals(2, task.getBuildNo());
    }

    @Test
    void createBuildAndEnqueueForVersionRejectsForeignDocumentAndVersionMismatch() {
        FileUpload upload = saveUpload("md5-route", "owner-1", "route.pdf", "ROUTE_TEAM", false);
        DocumentVersionService.VersionBuild created =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);

        CustomException foreign = assertThrows(CustomException.class, () -> versionService
                .createBuildAndEnqueueForVersion(created.documentId(), created.versionId(), "intruder"));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatus());

        CustomException mismatch = assertThrows(CustomException.class, () -> versionService
                .createBuildAndEnqueueForVersion("other-document", created.versionId(), "owner-1"));
        assertEquals(HttpStatus.NOT_FOUND, mismatch.getStatus());
    }

    @Test
    void validateUploadTargetRejectsDuplicateContentBeforeMerge() {
        BusinessDocument document = saveDocumentWithActiveVersion("owner-1", 1, "md5-old", "TEAM_A", false);
        FileUpload upload = saveUpload("md5-old", "owner-1", "old.pdf", "TEAM_A", false);

        CustomException error = assertThrows(CustomException.class, () -> versionService
                .validateUploadTarget(upload, "owner-1", document.getDocumentId(), null));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("内容与现有版本相同"));
    }

    @Test
    void validateUploadTargetRejectsSharedLegacyContentAndIncompleteLegacyFile() {
        FileUpload newUpload = saveUpload("md5-new", "owner-1", "new.pdf", "TEAM_A", false);
        saveUpload("md5-shared", "owner-1", "a.pdf", "TEAM_A", false);
        saveUpload("md5-shared", "user-b", "b.pdf", "TEAM_A", false);

        CustomException shared = assertThrows(CustomException.class, () -> versionService
                .validateUploadTarget(newUpload, "owner-1", null, "md5-shared"));
        assertEquals(HttpStatus.CONFLICT, shared.getStatus());
        assertTrue(shared.getMessage().contains("共享"));

        saveUploadWithStatus("md5-pending", "owner-1", "pending.pdf", FileUpload.STATUS_UPLOADING);
        CustomException incomplete = assertThrows(CustomException.class, () -> versionService
                .validateUploadTarget(newUpload, "owner-1", null, "md5-pending"));
        assertEquals(HttpStatus.CONFLICT, incomplete.getStatus());
        assertTrue(incomplete.getMessage().contains("尚未完成"));
    }

    @Test
    void validateUploadTargetRejectsDuplicateOfLegacyDocumentVersionBeforeMerge() {
        FileUpload legacyUpload = saveUpload("md5-legacy", "owner-1", "legacy.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild promoted = versionService.promoteLegacy(legacyUpload, "owner-1");
        saveVersion(promoted.documentId(), 2, "md5-v2", false, "TEAM_A", false, nextFixtureUploadId());
        FileUpload duplicateUpload = saveUploadWithStatus("md5-v2", "owner-1", "v2.pdf", "TEAM_A", false,
                FileUpload.STATUS_UPLOADING);

        CustomException error = assertThrows(CustomException.class, () -> versionService
                .validateUploadTarget(duplicateUpload, "owner-1", null, "md5-legacy"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("内容与现有版本相同"));
        assertEquals(FileUpload.STATUS_UPLOADING,
                fileUploadRepository.findById(duplicateUpload.getId()).orElseThrow().getStatus(),
                "预校验不改变上传状态，避免留下反复 409 的 COMPLETED 记录");
    }

    @Test
    void validateUploadTargetRejectsForeignOwnerAndDeletedDocument() {
        BusinessDocument foreignDocument = saveDocumentWithActiveVersion("owner-2", 1, "md5-old", "TEAM_A", false);
        FileUpload upload = saveUpload("md5-new", "owner-1", "new.pdf", "TEAM_A", false);

        CustomException foreign = assertThrows(CustomException.class, () -> versionService
                .validateUploadTarget(upload, "owner-1", foreignDocument.getDocumentId(), null));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatus());

        BusinessDocument deleted = saveDocumentWithActiveVersion("owner-1", 1, "md5-deleted", "TEAM_A", false);
        deleted.setDeletedAt(LocalDateTime.now());
        documentRepository.save(deleted);
        CustomException deletedError = assertThrows(CustomException.class, () -> versionService
                .validateUploadTarget(upload, "owner-1", deleted.getDocumentId(), null));
        assertEquals(HttpStatus.NOT_FOUND, deletedError.getStatus());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createAndEnqueueRollsBackVersionAndBuildWhenOutboxFails() {
        FileUpload upload = saveUpload("md5-rollback", "owner-1", "rollback.pdf", "TEAM_A", false);
        try {
            doThrow(new IllegalStateException("outbox 写入失败")).when(fileProcessingOutboxService)
                    .enqueueVectorization(any(FileUpload.class), any(FileProcessingTask.class), eq(true));

            assertThrows(IllegalStateException.class, () -> versionService.createAndEnqueue(
                    upload, "owner-1", "url", null, null));

            assertTrue(documentRepository.findAll().isEmpty(), "outbox 失败时业务文档必须回滚");
            assertTrue(versionRepository.findAll().isEmpty(), "outbox 失败时内容版本必须回滚");
            assertTrue(buildRepository.findAll().isEmpty(), "outbox 失败时构建必须回滚");
        } finally {
            fileUploadRepository.deleteAll();
        }
    }

    @Test
    void findByFileUploadIdReturnsLatestVersionAndBuild() {
        FileUpload upload = saveUpload("md5-lookup", "owner-1", "lookup.pdf", "TEAM_A", false);
        DocumentVersionService.VersionBuild created =
                versionService.createAndEnqueue(upload, "owner-1", "url", null, null);

        DocumentVersionService.VersionBuild found = versionService.findByFileUploadId(upload.getId()).orElseThrow();

        assertEquals(created.documentId(), found.documentId());
        assertEquals(created.versionId(), found.versionId());
        assertEquals(created.buildId(), found.buildId());
        assertEquals(1, found.versionNo());
        assertEquals(1, found.buildNo());
    }

    private User saveUser(String username, User.Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("test");
        user.setRole(role);
        return userRepository.save(user);
    }

    private static boolean awaitQuietly(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发线程被中断", e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        assertTrue(awaitQuietly(latch, 5, TimeUnit.SECONDS), "工作线程未能及时启动");
    }

    /**
     * NOT_SUPPORTED 的并发用例会提交数据；显式清理，避免影响同一 H2 库上的其他测试。
     */
    private void cleanupCommittedRows() {
        buildRepository.deleteAll();
        versionRepository.deleteAll();
        documentRepository.deleteAll();
        fileUploadRepository.deleteAll();
    }

    private FileProcessingTask captureTask(FileUpload upload) {
        ArgumentCaptor<FileProcessingTask> captor = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(fileProcessingOutboxService).enqueueVectorization(eq(upload), captor.capture(), eq(true));
        return captor.getValue();
    }

    private FileUpload saveUpload(String md5, String userId, String fileName, String orgTag, boolean isPublic) {
        return saveUploadWithStatus(md5, userId, fileName, orgTag, isPublic, FileUpload.STATUS_COMPLETED);
    }

    private FileUpload saveUploadWithStatus(String md5, String userId, String fileName, int status) {
        return saveUploadWithStatus(md5, userId, fileName, "TEAM_A", false, status);
    }

    private FileUpload saveUploadWithStatus(String md5, String userId, String fileName, String orgTag,
                                            boolean isPublic, int status) {
        FileUpload upload = new FileUpload();
        upload.setFileMd5(md5);
        upload.setFileName(fileName);
        upload.setUserId(userId);
        upload.setOrgTag(orgTag);
        upload.setPublic(isPublic);
        upload.setStatus(status);
        upload.setTotalSize(1024L);
        return fileUploadRepository.save(upload);
    }

    private static final java.util.concurrent.atomic.AtomicLong FIXTURE_UPLOAD_ID =
            new java.util.concurrent.atomic.AtomicLong(-1000);

    private Long nextFixtureUploadId() {
        return FIXTURE_UPLOAD_ID.decrementAndGet();
    }

    private BusinessDocument saveDocument(String ownerUserId) {
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setOwnerUserId(ownerUserId);
        document.setTitle("文档");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        return documentRepository.save(document);
    }

    private BusinessDocument saveDocumentWithActiveVersion(String ownerUserId, int versionNo, String md5,
                                                           String orgTag, boolean isPublic) {
        BusinessDocument document = saveDocument(ownerUserId);

        DocumentVersion version = saveVersion(document.getDocumentId(), versionNo, md5, false, orgTag, isPublic,
                nextFixtureUploadId());
        document.setActiveVersionId(version.getVersionId());
        documentRepository.save(document);
        return document;
    }

    private DocumentVersion saveVersion(String documentId, int versionNo, String md5, boolean legacy,
                                        String orgTag, boolean isPublic, Long fileUploadId) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setFileMd5(md5);
        version.setFileUploadId(fileUploadId);
        version.setFileName("v" + versionNo + ".pdf");
        version.setOrgTag(orgTag);
        version.setPublic(isPublic);
        version.setLegacyRepresentation(legacy);
        version.setCreatedAt(LocalDateTime.now());
        return versionRepository.save(version);
    }

    private DocumentVersion saveVersion(String documentId, int versionNo, String md5, boolean legacy,
                                        String orgTag, boolean isPublic, Long fileUploadId,
                                        ComplianceMetadata metadata) {
        DocumentVersion version = saveVersion(documentId, versionNo, md5, legacy, orgTag, isPublic, fileUploadId);
        version.setDocType(metadata.docType());
        version.setJurisdiction(metadata.jurisdiction());
        version.setIssuer(metadata.issuer());
        version.setEffectiveAt(metadata.effectiveAt());
        return versionRepository.save(version);
    }

}
