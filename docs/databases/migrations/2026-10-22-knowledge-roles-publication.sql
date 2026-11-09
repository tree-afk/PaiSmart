-- 知识库三角色 + 单库发布状态（2026-10-22）
-- 适用于已有 MySQL 库。执行前备份。
--
-- 本脚本可在 Hibernate ddl-auto=update 已经预建列的情况下执行：
-- 先按 information_schema 判断列是否存在，再统一修正默认值，避免
-- “字段已存在”或 “Field 'xxx' doesn't have a default value”。
--
-- 1. users.knowledge_role：READER / FILE_MANAGER / ROLE_MANAGER，默认 READER；
--    旧 ADMIN 账户迁移为 ROLE_MANAGER，旧 Role.USER/ADMIN 继续保留给其他管理功能。
-- 2. business_document.publication_status：历史业务文档一律 DRAFT，需文件管理者确认后发布；
--    metadata_revision_pending 标记元数据修订构建进行中。
-- 3. knowledge_audit_log：角色变更和文件发布操作的轻量追加记录。
--
-- 注意：脚本会补齐默认值并修正历史空值；已经上线后请勿重复执行，
-- 否则被手动降级为 READER 的旧 ADMIN 会被再次提升。

SET @db = DATABASE();

-- users.knowledge_role
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = @db AND table_name = 'users' AND column_name = 'knowledge_role') = 0,
  'ALTER TABLE users ADD COLUMN knowledge_role VARCHAR(32) NOT NULL DEFAULT ''READER'' COMMENT ''知识库角色：READER/FILE_MANAGER/ROLE_MANAGER'' AFTER role',
  'SELECT 1');
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

ALTER TABLE users
  MODIFY COLUMN knowledge_role VARCHAR(32) NOT NULL DEFAULT 'READER'
    COMMENT '知识库角色：READER/FILE_MANAGER/ROLE_MANAGER';

UPDATE users SET knowledge_role = 'READER'
WHERE knowledge_role IS NULL OR knowledge_role NOT IN ('READER', 'FILE_MANAGER', 'ROLE_MANAGER');

UPDATE users SET knowledge_role = 'ROLE_MANAGER'
WHERE role = 'ADMIN' AND knowledge_role = 'READER';

-- business_document.publication_status
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = @db AND table_name = 'business_document' AND column_name = 'publication_status') = 0,
  'ALTER TABLE business_document ADD COLUMN publication_status VARCHAR(16) NOT NULL DEFAULT ''DRAFT'' COMMENT ''发布状态：DRAFT/PUBLISHED/WITHDRAWN'' AFTER active_build_id',
  'SELECT 1');
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

ALTER TABLE business_document
  MODIFY COLUMN publication_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
    COMMENT '发布状态：DRAFT/PUBLISHED/WITHDRAWN';

-- 历史文档默认不发布；同时修正 Hibernate 预建列可能留下的空字符串
UPDATE business_document SET publication_status = 'DRAFT'
WHERE publication_status IS NULL OR publication_status NOT IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN');

-- business_document.metadata_revision_pending
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = @db AND table_name = 'business_document' AND column_name = 'metadata_revision_pending') = 0,
  'ALTER TABLE business_document ADD COLUMN metadata_revision_pending BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''元数据修订构建进行中'' AFTER publication_status',
  'SELECT 1');
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

ALTER TABLE business_document
  MODIFY COLUMN metadata_revision_pending BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT '元数据修订构建进行中';

UPDATE business_document SET metadata_revision_pending = FALSE WHERE metadata_revision_pending IS NULL;

-- business_document.metadata_revision_build_id：本次修订绑定的构建；只有该构建成功才能解除限制
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = @db AND table_name = 'business_document' AND column_name = 'metadata_revision_build_id') = 0,
  'ALTER TABLE business_document ADD COLUMN metadata_revision_build_id VARCHAR(36) DEFAULT NULL COMMENT ''本次元数据修订绑定的构建ID'' AFTER metadata_revision_pending',
  'SELECT 1');
PREPARE migrate_stmt FROM @sql;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

CREATE TABLE IF NOT EXISTS knowledge_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_user_id VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  action VARCHAR(64) NOT NULL,
  before_value VARCHAR(255) DEFAULT NULL,
  after_value VARCHAR(255) DEFAULT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_knowledge_audit_target (target_type, target_id),
  KEY idx_knowledge_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库角色与发布操作记录';

-- 若库内没有旧 ADMIN，迁移后可能没有 ROLE_MANAGER。此时先由部署人员通过
-- AdminUserInitializer 重建管理员，或在数据库里手工指定一位账户，脚本不擅自提升任意用户。
