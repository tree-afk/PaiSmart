package com.yizhaoqi.smartpai.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旧直接解析入口已停用：它不创建内容版本/构建，也不经过发布状态校验，
 * 会让文件绕过共享库的版本管理和发布范围。请使用 /upload/chunk + /upload/merge。
 */
@RestController
@RequestMapping("/api/v1/parse")
public class ParseController {

    @PostMapping
    public ResponseEntity<String> parseDocument() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body("直接解析接口已停用，请通过知识库上传或新版本流程提交文件");
    }
}
