package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.KnowledgeAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeAuditLogRepository extends JpaRepository<KnowledgeAuditLog, Long> {
}
