-- 条款级切分（Step 3）：document_vectors 增加条款路径和条款号。
-- 适用于已执行过 2026-10-20-document-versioning.sql 的 MySQL 库，属于一次性迁移。
--
-- 执行时机与说明：
--   1. 建议在启动新应用版本之前执行；application*.yml 默认 ddl-auto=update，
--      若应用已先启动并由 Hibernate 自动补齐了这两列，本脚本会报 Duplicate column name，属预期，无需重跑。
--   2. 脚本不可重复执行：列已存在会报 Duplicate column name。
--   3. 执行前请先备份；旧数据两列保持 NULL，继续按未做条款切分的历史分块处理。
--   4. 只有合规文档（buildContext.compliance.docType 非空）解析时写入；其余文档保持 NULL。

ALTER TABLE document_vectors
  ADD COLUMN article_path VARCHAR(512) DEFAULT NULL COMMENT '条款路径，如 第三章 风险管理 > 第十二条' AFTER anchor_text,
  ADD COLUMN clause_no VARCHAR(128) DEFAULT NULL COMMENT '条款号，如 第十二条 或 第十二条第二款' AFTER article_path;
