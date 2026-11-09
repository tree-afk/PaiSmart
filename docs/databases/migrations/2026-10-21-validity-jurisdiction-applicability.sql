-- 合规效力与适用范围升级（2026-10-21）
-- 适用于已执行 2026-10-20-document-versioning.sql 与 2026-10-21-document-compliance-metadata.sql 的 MySQL 库。
--
-- 变更内容：
--   1. jurisdiction_code：地区编码，NATIONAL 表示全国；地方名称去掉省/市/自治区后缀。
--   2. applicable_institutions：资料适用机构/主体，与 issuer（发布机构）分开。
--   3. expires_at / repealed：失效日期与废止标记；缺失表示未标注，不再把上传日期当生效日期。
--
-- 执行时机：启动新应用版本之前执行。application*.yml 默认 ddl-auto=update，
-- 若应用已先启动并由 Hibernate 补齐列，本脚本会报 Duplicate column name，属预期，无需重跑。
-- 执行前请备份；本脚本不可重复执行。

ALTER TABLE document_version
  ADD COLUMN jurisdiction_code VARCHAR(64) DEFAULT NULL COMMENT '归一化地区编码：NATIONAL/北京/上海' AFTER jurisdiction,
  ADD COLUMN applicable_institutions VARCHAR(512) DEFAULT NULL COMMENT '资料适用机构/主体' AFTER issuer,
  ADD COLUMN expires_at DATE DEFAULT NULL COMMENT '版本失效日期；缺失表示未标注失效' AFTER effective_at,
  ADD COLUMN repealed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已废止' AFTER expires_at;

-- 历史数据回填地区编码；显示值保留原文，不覆盖发布时写法。
UPDATE document_version
SET jurisdiction_code = CASE
    WHEN jurisdiction IN ('全国', '中国', '中华人民共和国', '国家', '全国适用', '全国范围') THEN 'NATIONAL'
    WHEN jurisdiction LIKE '%特别行政区' THEN LEFT(jurisdiction, CHAR_LENGTH(jurisdiction) - 5)
    WHEN jurisdiction LIKE '%自治区' THEN LEFT(jurisdiction, CHAR_LENGTH(jurisdiction) - 3)
    WHEN jurisdiction LIKE '%省' THEN LEFT(jurisdiction, CHAR_LENGTH(jurisdiction) - 1)
    WHEN jurisdiction LIKE '%市' AND CHAR_LENGTH(jurisdiction) > 2 THEN LEFT(jurisdiction, CHAR_LENGTH(jurisdiction) - 1)
    ELSE jurisdiction
  END
WHERE jurisdiction IS NOT NULL
  AND jurisdiction <> ''
  AND jurisdiction_code IS NULL;
