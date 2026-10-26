-- 文档版本化升级（三层模型 + document_vectors 按 build 去重）
-- 适用于已有数据的 MySQL 库，属于一次性迁移。
--
-- 执行时机（重要）：本脚本必须在启动新的应用版本之前执行完。
--   新版本实体的唯一键已是 (build_id, chunk_id)，且 application*.yml 默认 ddl-auto=update：
--   应用一旦先启动，Hibernate 会自动补上 build_id 列和新的唯一键，但不会删除旧唯一键，
--   之后再执行本脚本会因旧索引已不存在/结构已变更而中途失败（1091 或 Duplicate key name）。
--
--   若某套环境已经由 Hibernate ddl-auto=update 先行改过结构（build_id 已存在、旧唯一键可能还在），
--   本脚本不能直接重跑：需按实际索引状态手工修复（删除残留的旧唯一键、补齐 idx_document_vectors_file_md5），
--   并由后续任务在应用启动阶段加 guard 做可重复的检查与告警。
--
-- 其他约定：
--   1. document_vectors 的四个变更是同一条 ALTER 内的原子操作，旧唯一键、新唯一键、file_md5 普通索引一起生效。
--   2. 本脚本不可重复执行：列/索引已存在会报 Duplicate column name / Duplicate key name。
--   3. 执行前请先备份；旧版 LegacySchemaMigration 仍按旧索引名 uk_document_vectors_md5_chunk 检查，会持续告警，属预期。
-- 旧数据 build_id 保持 NULL，继续按 legacy 分支检索；新构建写入的分块必须带 build_id。

CREATE TABLE IF NOT EXISTS business_document (
  document_id VARCHAR(36) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  title VARCHAR(255) NOT NULL,
  active_version_id VARCHAR(36) DEFAULT NULL,
  active_build_id VARCHAR(36) DEFAULT NULL,
  deleted_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (document_id),
  KEY idx_business_document_owner (owner_user_id, deleted_at),
  KEY idx_business_document_active_build (active_build_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_version (
  version_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  version_no INT NOT NULL,
  file_md5 VARCHAR(32) NOT NULL,
  file_upload_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  org_tag VARCHAR(50) DEFAULT NULL,
  is_public BOOLEAN NOT NULL DEFAULT FALSE,
  legacy_representation BOOLEAN NOT NULL DEFAULT FALSE,
  legacy_retired BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (version_id),
  UNIQUE KEY uk_document_version_no (document_id, version_no),
  UNIQUE KEY uk_document_version_md5 (document_id, file_md5),
  UNIQUE KEY uk_document_version_upload (file_upload_id),
  KEY idx_document_version_md5 (file_md5),
  KEY idx_document_version_retired (legacy_retired)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_build (
  build_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  build_no INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  chunk_count INT DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  created_at DATETIME NOT NULL,
  started_at DATETIME DEFAULT NULL,
  completed_at DATETIME DEFAULT NULL,
  PRIMARY KEY (build_id),
  UNIQUE KEY uk_document_build_no (version_id, build_no),
  KEY idx_document_build_status (status),
  KEY idx_document_build_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 旧数据 build_id 为空，唯一键 (build_id, chunk_id) 对 NULL 不生效，legacy 重复分块不受影响；
-- 新唯一键以 build_id 打头，file_md5 失去前缀索引，同一条 ALTER 里补回普通索引供 legacy 查询使用
ALTER TABLE document_vectors
  ADD COLUMN build_id VARCHAR(36) DEFAULT NULL,
  DROP INDEX uk_document_vectors_md5_chunk,
  ADD UNIQUE KEY uk_document_vectors_build_chunk (build_id, chunk_id),
  ADD INDEX idx_document_vectors_file_md5 (file_md5);
