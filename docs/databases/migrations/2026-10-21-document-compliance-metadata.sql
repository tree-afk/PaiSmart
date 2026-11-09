-- 合规文档元数据升级（Step 1）：document_version 增加文档类型/适用地区/发布机构/生效日期。
-- 适用于已经执行过 2026-10-20-document-versioning.sql 的 MySQL 库，属于一次性迁移。
--
-- 执行时机与说明：
--   1. 建议在启动新应用版本之前执行；application*.yml 默认 ddl-auto=update，
--      若应用已先启动并由 Hibernate 自动补齐了这四列，本脚本会报 Duplicate column name，属预期，无需重跑。
--   2. 脚本不可重复执行：列已存在会报 Duplicate column name。
--   3. 执行前请先备份；旧数据四列保持 NULL，继续按未标注合规信息的历史版本处理。
--   4. 新版本上传未指定生效日期时由应用按上传日期补齐；doc_type 仅接受
--      REGULATION / INTERNAL_POLICY / PRODUCT_TERMS / GUIDELINE。

ALTER TABLE document_version
  ADD COLUMN doc_type VARCHAR(32) DEFAULT NULL COMMENT '合规文档类型：REGULATION/INTERNAL_POLICY/PRODUCT_TERMS/GUIDELINE' AFTER is_public,
  ADD COLUMN jurisdiction VARCHAR(100) DEFAULT NULL COMMENT '适用地区，如 全国/北京/上海' AFTER doc_type,
  ADD COLUMN issuer VARCHAR(255) DEFAULT NULL COMMENT '发布机构，如 国家金融监督管理总局/某银行/某保险公司' AFTER jurisdiction,
  ADD COLUMN effective_at DATE DEFAULT NULL COMMENT '版本生效日期，新版本未指定时默认上传日期' AFTER issuer;
