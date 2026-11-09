package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.KnowledgeAuditLog;
import com.yizhaoqi.smartpai.repository.KnowledgeAuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 记录角色变更和文件发布操作；只做追加，不提供修改/删除入口。
 */
@Service
public class KnowledgeAuditService {

    private final KnowledgeAuditLogRepository repository;

    public KnowledgeAuditService(KnowledgeAuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String actorUserId, String targetType, String targetId,
                       String action, String beforeValue, String afterValue) {
        KnowledgeAuditLog log = new KnowledgeAuditLog();
        log.setActorUserId(actorUserId);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setAction(action);
        log.setBeforeValue(beforeValue);
        log.setAfterValue(afterValue);
        log.setCreatedAt(LocalDateTime.now());
        repository.save(log);
    }
}
