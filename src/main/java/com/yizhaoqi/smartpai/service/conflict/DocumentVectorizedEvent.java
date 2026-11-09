package com.yizhaoqi.smartpai.service.conflict;

/**
 * 文件向量化完成（首次入库或重试 / 重建单个文档）后发布，供冲突检测等后置任务订阅。
 * documentId 用于排除同一业务文档的历史版本，legacy 任务为 null。
 */
public record DocumentVectorizedEvent(String fileMd5, String documentId) {

    public DocumentVectorizedEvent(String fileMd5) {
        this(fileMd5, null);
    }
}
