CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
                       username VARCHAR(255) NOT NULL UNIQUE COMMENT '用户名，唯一',
                       password VARCHAR(255) NOT NULL COMMENT '加密后的密码',
                       role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '旧版角色，保留用于兼容其他管理功能',
                       knowledge_role VARCHAR(32) NOT NULL DEFAULT 'READER' COMMENT '知识库角色：READER/FILE_MANAGER/ROLE_MANAGER',
                       org_tags VARCHAR(255) DEFAULT NULL COMMENT '用户所属组织标签，多个用逗号分隔',
                       primary_org VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户主组织标签',
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                       INDEX idx_username (username) COMMENT '用户名索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
CREATE TABLE organization_tags (
                                   tag_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '标签唯一标识',
                                   name VARCHAR(100) NOT NULL COMMENT '标签名称',
                                   description TEXT COMMENT '描述',
                                   parent_tag VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '父标签ID',
                                   upload_max_size_bytes BIGINT DEFAULT NULL COMMENT '非管理员上传文件大小上限，单位字节',
                                   created_by BIGINT NOT NULL COMMENT '创建者ID',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                   FOREIGN KEY (parent_tag) REFERENCES organization_tags(tag_id) ON DELETE SET NULL,
                                   FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织标签表';


CREATE TABLE file_upload (
                             id           BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键',
                             file_md5     VARCHAR(32)      NOT NULL COMMENT '文件 MD5',
                             file_name    VARCHAR(255)     NOT NULL COMMENT '文件名称',
                             total_size   BIGINT           NOT NULL COMMENT '文件大小',
                             status       TINYINT          NOT NULL DEFAULT 0 COMMENT '上传状态：0上传中 1已完成 2合并中',
                             user_id      VARCHAR(64)      NOT NULL COMMENT '用户 ID',
                             org_tag      VARCHAR(50)      DEFAULT NULL COMMENT '组织标签',
                             is_public    BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '是否公开',
                             estimated_embedding_tokens BIGINT DEFAULT NULL COMMENT '预估 embedding token 数',
                             estimated_chunk_count INT DEFAULT NULL COMMENT '预估切片数',
                             actual_embedding_tokens BIGINT DEFAULT NULL COMMENT '实际 embedding token 数',
                             actual_chunk_count INT DEFAULT NULL COMMENT '实际切片数',
                             vectorization_status VARCHAR(32) DEFAULT NULL COMMENT '向量化状态：PENDING / PROCESSING / COMPLETED / FAILED',
                             vectorization_error_message VARCHAR(1000) DEFAULT NULL COMMENT '向量化失败原因',
                             processing_started_at DATETIME(3) DEFAULT NULL COMMENT '本轮向量化被认领的时间，心跳和完成按它做条件更新',
                             heartbeat_at DATETIME(3) DEFAULT NULL COMMENT '当前阶段最近一次进展时间（分片到达 / 开始合并 / 完成一批分块）',
                             retry_count  INT              NOT NULL DEFAULT 0 COMMENT '卡住后被自动重新投递的次数',
                             created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             merged_at    TIMESTAMP        NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '合并时间',
                             PRIMARY KEY (id),
                             UNIQUE KEY uk_file_upload_md5_user (file_md5, user_id),
                             INDEX idx_user (user_id),
                             INDEX idx_org_tag (org_tag),
                             INDEX idx_vectorization_heartbeat (vectorization_status, heartbeat_at),
                             INDEX idx_status_heartbeat (status, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传记录';
CREATE TABLE chunk_info (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块记录唯一标识',
                            file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                            user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '上传用户ID，分片按用户隔离，默认值兼容旧版本回滚',
                            chunk_index INT NOT NULL COMMENT '分块序号',
                            chunk_md5 VARCHAR(32) NOT NULL COMMENT '分块的MD5值',
                            storage_path VARCHAR(255) NOT NULL COMMENT '分块在存储系统中的路径：chunks/{userId}/{fileMd5}/{chunkIndex}',
                            UNIQUE KEY uk_chunk_user_md5_index (user_id, file_md5, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分块信息表';

CREATE TABLE document_vectors (
                                  vector_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '向量记录唯一标识',
                                  build_id VARCHAR(36) DEFAULT NULL COMMENT '构建批次ID，旧数据为空',
                                  file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                                  chunk_id INT NOT NULL COMMENT '文本分块序号',
                                  text_content TEXT COMMENT '文本内容',
                                  page_number INT COMMENT 'PDF页码，用于引用定位',
                                  anchor_text VARCHAR(255) COMMENT '页内定位锚点文本',
                                  article_path VARCHAR(512) DEFAULT NULL COMMENT '条款路径，如 第三章 风险管理 > 第十二条',
                                  clause_no VARCHAR(128) DEFAULT NULL COMMENT '条款号，如 第十二条 或 第十二条第二款',
                                  model_version VARCHAR(32) COMMENT '向量模型版本',
                                  user_id VARCHAR(64) NOT NULL COMMENT '上传用户ID',
                                  org_tag VARCHAR(50) COMMENT '文件所属组织标签',
                                  is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '文件是否公开',
                                  UNIQUE KEY uk_document_vectors_build_chunk (build_id, chunk_id),
                                  INDEX idx_document_vectors_file_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档向量存储表';

CREATE TABLE file_processing_outbox (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                                        file_md5 VARCHAR(32) NOT NULL COMMENT '文件MD5',
                                        task_type VARCHAR(32) NOT NULL COMMENT '任务类型：UPLOAD_PROCESS / REINDEX',
                                        payload TEXT NOT NULL COMMENT 'FileProcessingTask JSON',
                                        status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING / SENT',
                                        attempts INT NOT NULL DEFAULT 0 COMMENT '投递次数',
                                        next_attempt_at DATETIME(3) NOT NULL COMMENT '下次可投递时间，认领后推迟到租约结束',
                                        last_error VARCHAR(1000) DEFAULT NULL COMMENT '最近一次投递失败原因',
                                        created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
                                        sent_at DATETIME(3) DEFAULT NULL COMMENT '投递成功时间',
                                        INDEX idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件处理任务投递 outbox';

CREATE TABLE file_processing_dead_letter (
                                             id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                                             file_md5 VARCHAR(32) DEFAULT NULL COMMENT '文件MD5',
                                             task_type VARCHAR(32) DEFAULT NULL COMMENT '任务类型',
                                             payload TEXT COMMENT '死信消息内容',
                                             error_message VARCHAR(1000) DEFAULT NULL COMMENT '最后一次处理异常',
                                             source_topic VARCHAR(255) NOT NULL COMMENT '死信主题',
                                             source_partition INT NOT NULL COMMENT '死信分区',
                                             source_offset BIGINT NOT NULL COMMENT '死信偏移量',
                                             created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时间',
                                             UNIQUE KEY uk_dead_letter_source (source_topic, source_partition, source_offset),
                                             INDEX idx_dead_letter_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件处理死信记录';

CREATE TABLE rate_limit_configs (
                                    config_key VARCHAR(64) PRIMARY KEY COMMENT '限流配置键',
                                    single_max INT DEFAULT NULL COMMENT '单窗口最大次数',
                                    single_window_seconds BIGINT DEFAULT NULL COMMENT '单窗口秒数',
                                    minute_max BIGINT DEFAULT NULL COMMENT '分钟窗口最大值',
                                    minute_window_seconds BIGINT DEFAULT NULL COMMENT '分钟窗口秒数',
                                    day_max BIGINT DEFAULT NULL COMMENT '日窗口最大值',
                                    day_window_seconds BIGINT DEFAULT NULL COMMENT '日窗口秒数',
                                    updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时限流配置表';

CREATE TABLE model_provider_configs (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '模型配置主键',
                                        config_scope VARCHAR(32) NOT NULL COMMENT '作用域：llm / embedding',
                                        provider_code VARCHAR(64) NOT NULL COMMENT 'provider 标识',
                                        display_name VARCHAR(128) NOT NULL COMMENT '展示名称',
                                        api_style VARCHAR(64) NOT NULL COMMENT '协议风格',
                                        api_base_url VARCHAR(512) NOT NULL COMMENT 'API 基础地址',
                                        model_name VARCHAR(255) NOT NULL COMMENT '模型名称',
                                        api_key_ciphertext VARCHAR(2048) DEFAULT NULL COMMENT '加密后的 API Key',
                                        embedding_dimension INT DEFAULT NULL COMMENT 'Embedding 维度',
                                        enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                        active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否当前激活',
                                        updated_by VARCHAR(255) NOT NULL COMMENT '最后更新人',
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                        UNIQUE KEY uk_model_provider_scope_code (config_scope, provider_code),
                                        KEY idx_model_provider_scope (config_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时模型 Provider 配置表';

-- 充值套餐表
CREATE TABLE recharge_packages (
                                   id INT AUTO_INCREMENT PRIMARY KEY COMMENT '套餐 ID（自增主键）',
                                   package_name VARCHAR(128) NOT NULL COMMENT '套餐名称',
                                   package_price BIGINT NOT NULL COMMENT '套餐价格，单位分',
                                   package_desc TEXT COMMENT '套餐描述',
                                   package_benefit TEXT COMMENT '套餐权益',
                                   llm_token INT NOT NULL COMMENT 'LLM token 数量',
                                   embedding_token INT NOT NULL COMMENT 'Embedding token 数量',
                                   sort_order INT NOT NULL DEFAULT 10 COMMENT '排序顺序（数字越小越靠前）',
                                   enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
                                   deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已删除',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值套餐表';

-- 充值订单表
CREATE TABLE recharge_orders (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单 ID',
                                 trade_no VARCHAR(128) NOT NULL UNIQUE COMMENT '业务单号（外部系统唯一）',
                                 user_id VARCHAR(64) NOT NULL COMMENT '用户 ID（关联 users 表）',
                                 package_id INT NOT NULL COMMENT '套餐 ID（如果是自定义充值，则为 0）',
                                 amount BIGINT NOT NULL COMMENT '订单金额，单位分',
                                 llm_token INT NOT NULL COMMENT 'LLM token 数量',
                                 embedding_token INT NOT NULL COMMENT 'Embedding token 数量',
                                 wx_transaction_id VARCHAR(64) NOT NULL COMMENT '微信交易流水号',
                                 status ENUM('NOT_PAY', 'PAYING', 'SUCCEED', 'FAIL', 'CANCELLED') NOT NULL DEFAULT 'NOT_PAY' COMMENT '订单状态',
                                 description VARCHAR(255) COMMENT '订单描述',
                                 pay_time TIMESTAMP NULL COMMENT '支付成功时间',
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 INDEX idx_trade_no (trade_no),
                                 INDEX idx_user_id (user_id),
                                 INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值订单表';

-- 初始化充值套餐数据
-- 说明：
-- 1. 保留 1 分钱内部基准套餐，用于自定义充值时按比例折算 Token，不对前台用户展示。
-- 2. 默认三档套餐基于 2026-03-20 的 DeepSeek / 阿里百炼官方价格做保守估算，兼顾吸引力和利润空间。
INSERT INTO recharge_packages (package_name, package_price, package_desc, package_benefit, llm_token, embedding_token, sort_order, enabled)
VALUES
    ('内部基准', 1, '自定义充值折算基准，不对外展示', 'LLM Token: 2,500\nEmbedding Token: 1,000', 2500, 1000, 999, TRUE),
    ('体验版', 990, '适合轻度体验、日常问答和少量知识库上传。', 'LLM Token：250 万\nEmbedding Token：100 万\n支持微信支付充值\n余额到账后可直接使用', 2500000, 1000000, 10, TRUE),
    ('进阶版', 1990, '适合持续问答、资料整理和中等规模知识库构建。', 'LLM Token：550 万\nEmbedding Token：250 万\n支持微信支付充值\n余额到账后可直接使用', 5500000, 2500000, 20, TRUE),
    ('旗舰版', 4990, '适合高频问答、团队共享资料和较大规模知识库场景。', 'LLM Token：1400 万\nEmbedding Token：600 万\n支持微信支付充值\n余额到账后可直接使用', 14000000, 6000000, 30, TRUE);


-- 创建用户 Token 变动记录表
CREATE TABLE IF NOT EXISTS `user_token_record` (
                               `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
                               `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
                               `record_date` DATE NOT NULL COMMENT '记录日期（按天统计）',
                               `token_type` VARCHAR(20) NOT NULL COMMENT 'Token 类型：LLM/EMBEDDING',
                               `change_type` VARCHAR(20) NOT NULL COMMENT '变动类型：INCREASE/CONSUME',
                                `request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '请求次数（一次充值或对话可能包含多次 API 请求）',
                               `amount` BIGINT NOT NULL COMMENT '变动数量',
                               `balance_before` BIGINT DEFAULT NULL COMMENT '变动前的余额',
                               `balance_after` BIGINT DEFAULT NULL COMMENT '变动后的余额',
                               `reason` VARCHAR(500) DEFAULT NULL COMMENT '变动原因描述',
                               `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注信息（订单号、对话 ID 等）',
                               `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               PRIMARY KEY (`id`),
                               INDEX `idx_user_date` (`user_id`, `record_date`),
                               INDEX `idx_record_date` (`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 Token 变动记录表';


CREATE TABLE IF NOT EXISTS `user_daily_chat_count` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
    `record_date` DATE NOT NULL COMMENT '记录日期',
    `chat_request_count` BIGINT(20) NOT NULL DEFAULT 0 COMMENT '对话请求次数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `record_date`) COMMENT '用户 + 日期唯一索引',
    INDEX `idx_record_date` (`record_date`) COMMENT '按日期查询索引'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日对话次数记录表';


CREATE TABLE IF NOT EXISTS `document_conflicts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `pair_key` VARCHAR(160) NOT NULL COMMENT '与方向无关的分块对标识，用于去重',
    `new_file_md5` VARCHAR(32) NOT NULL COMMENT '新文件 MD5',
    `new_chunk_id` INT NOT NULL COMMENT '新文件分块序号',
    `new_file_name` VARCHAR(255) DEFAULT NULL COMMENT '新文件名',
    `existing_file_md5` VARCHAR(32) NOT NULL COMMENT '已有文件 MD5',
    `existing_chunk_id` INT NOT NULL COMMENT '已有文件分块序号',
    `existing_file_name` VARCHAR(255) DEFAULT NULL COMMENT '已有文件名',
    `similarity` DOUBLE NOT NULL COMMENT '分块向量余弦相似度',
    `masked_similarity` DOUBLE DEFAULT NULL COMMENT '屏蔽数字/版本号/日期后的文本相似度',
    `new_snippet` VARCHAR(1000) DEFAULT NULL COMMENT '新分块摘要',
    `existing_snippet` VARCHAR(1000) DEFAULT NULL COMMENT '已有分块摘要',
    `new_values` VARCHAR(500) DEFAULT NULL COMMENT '只在新分块中出现的关键值',
    `existing_values` VARCHAR(500) DEFAULT NULL COMMENT '只在已有分块中出现的关键值',
    `llm_reason` VARCHAR(500) DEFAULT NULL COMMENT 'LLM 二次确认理由',
    `status` VARCHAR(20) NOT NULL COMMENT 'PENDING/KEEP_NEW/KEEP_EXISTING/IGNORED/OBSOLETE',
    `created_at` DATETIME DEFAULT NULL COMMENT '发现时间',
    `resolved_by` VARCHAR(64) DEFAULT NULL COMMENT '处理人',
    `resolved_at` DATETIME DEFAULT NULL COMMENT '处理时间',
    `resolution_note` VARCHAR(500) DEFAULT NULL COMMENT '处理备注',
    PRIMARY KEY (`id`),
    INDEX `idx_doc_conflict_pair_key` (`pair_key`),
    INDEX `idx_doc_conflict_status` (`status`),
    INDEX `idx_doc_conflict_new_md5` (`new_file_md5`),
    INDEX `idx_doc_conflict_existing_md5` (`existing_file_md5`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='疑似文档冲突记录表';

-- 文档版本化：业务文档、内容版本、构建批次三层。
-- 旧数据不写入这三张表，继续按 document_vectors.build_id 为空的 legacy 分支检索。
CREATE TABLE business_document (
                                   document_id VARCHAR(36) NOT NULL COMMENT '业务文档唯一标识（UUID）',
                                   owner_user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID，与 file_upload.user_id 同语义',
                                   title VARCHAR(255) NOT NULL COMMENT '文档标题，默认取首版文件名',
                                   active_version_id VARCHAR(36) DEFAULT NULL COMMENT '当前内容版本ID',
                                   active_build_id VARCHAR(36) DEFAULT NULL COMMENT '当前参与检索的构建ID',
                                   publication_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '发布状态：DRAFT/PUBLISHED/WITHDRAWN',
                                   metadata_revision_pending BOOLEAN NOT NULL DEFAULT FALSE COMMENT '元数据修订构建进行中',
                                   metadata_revision_build_id VARCHAR(36) DEFAULT NULL COMMENT '本次元数据修订绑定的构建ID，只有该构建成功才能解除限制',
                                   deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间，检索和列表排除',
                                   created_at DATETIME NOT NULL COMMENT '创建时间',
                                   updated_at DATETIME NOT NULL COMMENT '更新时间',
                                   PRIMARY KEY (document_id),
                                   KEY idx_business_document_owner (owner_user_id, deleted_at),
                                   KEY idx_business_document_active_build (active_build_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务文档表';

CREATE TABLE document_version (
                                  version_id VARCHAR(36) NOT NULL COMMENT '内容版本唯一标识（UUID）',
                                  document_id VARCHAR(36) NOT NULL COMMENT '所属业务文档ID',
                                  version_no INT NOT NULL COMMENT '内容版本号，内容变化时从 1 递增',
                                  file_md5 VARCHAR(32) NOT NULL COMMENT '文件内容指纹',
                                  file_upload_id BIGINT NOT NULL COMMENT '关联的上传/内容记录ID',
                                  file_name VARCHAR(255) NOT NULL COMMENT '该版本文件名',
                                  org_tag VARCHAR(50) DEFAULT NULL COMMENT '版本级组织标签快照',
                                  is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '版本级公开标记快照',
                                  doc_type VARCHAR(32) DEFAULT NULL COMMENT '合规文档类型：REGULATION/INTERNAL_POLICY/PRODUCT_TERMS/GUIDELINE',
                                  jurisdiction VARCHAR(100) DEFAULT NULL COMMENT '适用地区显示值，如 全国/北京/上海',
                                  jurisdiction_code VARCHAR(64) DEFAULT NULL COMMENT '归一化地区编码：NATIONAL/北京/上海',
                                  issuer VARCHAR(255) DEFAULT NULL COMMENT '发布机构（只表示谁发布）',
                                  applicable_institutions VARCHAR(512) DEFAULT NULL COMMENT '资料适用机构/主体',
                                  effective_at DATE DEFAULT NULL COMMENT '版本生效日期；缺失表示效力待确认',
                                  expires_at DATE DEFAULT NULL COMMENT '版本失效日期；缺失表示未标注失效',
                                  repealed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已废止',
                                  legacy_representation BOOLEAN NOT NULL DEFAULT FALSE COMMENT '该版本内容仍以旧 ES 分块表示',
                                  legacy_retired BOOLEAN NOT NULL DEFAULT FALSE COMMENT '旧分块已退休，不再参与检索',
                                  created_at DATETIME NOT NULL COMMENT '创建时间',
                                  PRIMARY KEY (version_id),
                                  UNIQUE KEY uk_document_version_no (document_id, version_no),
                                  UNIQUE KEY uk_document_version_md5 (document_id, file_md5),
                                  UNIQUE KEY uk_document_version_upload (file_upload_id),
                                  KEY idx_document_version_md5 (file_md5),
                                  KEY idx_document_version_retired (legacy_retired)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档内容版本表';

CREATE TABLE document_build (
                                build_id VARCHAR(36) NOT NULL COMMENT '构建批次唯一标识（UUID），同时是 ES 过滤键',
                                version_id VARCHAR(36) NOT NULL COMMENT '所属内容版本ID',
                                build_no INT NOT NULL COMMENT '版本内构建批次号，从 1 递增',
                                status VARCHAR(16) NOT NULL COMMENT '状态：PENDING/PROCESSING/READY/ACTIVE/SUPERSEDED/FAILED/CANCELLED',
                                chunk_count INT DEFAULT NULL COMMENT '期望/实际分块数',
                                error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
                                created_at DATETIME NOT NULL COMMENT '创建时间',
                                started_at DATETIME DEFAULT NULL COMMENT '开始加工时间',
                                completed_at DATETIME DEFAULT NULL COMMENT '加工完成时间',
                                PRIMARY KEY (build_id),
                                UNIQUE KEY uk_document_build_no (version_id, build_no),
                                KEY idx_document_build_status (status),
                                KEY idx_document_build_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档构建批次表';

CREATE TABLE knowledge_audit_log (
                                     id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                     actor_user_id VARCHAR(64) NOT NULL COMMENT '操作人ID',
                                     target_type VARCHAR(32) NOT NULL COMMENT 'USER/DOCUMENT',
                                     target_id VARCHAR(64) NOT NULL COMMENT '目标账号或文档ID',
                                     action VARCHAR(64) NOT NULL COMMENT 'ASSIGN_ROLE/UPDATE_METADATA/WITHDRAW/RESTORE/DELETE',
                                     before_value VARCHAR(255) DEFAULT NULL,
                                     after_value VARCHAR(255) DEFAULT NULL,
                                     created_at DATETIME NOT NULL,
                                     KEY idx_knowledge_audit_target (target_type, target_id),
                                     KEY idx_knowledge_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库角色与发布操作记录';
