-- badcase 回流：保存用户对回答的反馈，并记录转成评测用例后的 JSONL 行
CREATE TABLE IF NOT EXISTS answer_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NULL,
    generation_id VARCHAR(64) NULL,
    rating VARCHAR(16) NOT NULL,
    reason TEXT NULL,
    question LONGTEXT NULL,
    answer LONGTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    note TEXT NULL,
    reviewer VARCHAR(64) NULL,
    eval_case_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    reviewed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_answer_feedback_status (status, created_at),
    KEY idx_answer_feedback_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
