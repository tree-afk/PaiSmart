package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.AnswerFeedbackService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * badcase 回流管理（仅 ADMIN，/api/v1/admin/** 由 SecurityConfig 限制）：
 * 查看反馈 → 复核/忽略 → 补 gold 转成 JSONL → 导出到 eval.dataset-dir 后回归。
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminFeedbackController {

    private final AnswerFeedbackService feedbackService;
    private final JwtUtils jwtUtils;

    public AdminFeedbackController(AnswerFeedbackService feedbackService, JwtUtils jwtUtils) {
        this.feedbackService = feedbackService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        try {
            return ok("获取反馈成功", feedbackService.list(status, page, size));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@RequestHeader("Authorization") String token,
                                          @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        try {
            return ok("反馈状态已更新",
                    feedbackService.updateStatus(id, request.status(), request.note(), reviewer(token)));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{id}/convert")
    public ResponseEntity<?> convert(@RequestHeader("Authorization") String token,
                                     @PathVariable Long id,
                                     @RequestBody ConvertRequest request) {
        try {
            return ok("反馈已转为评测用例", feedbackService.convert(id, request.referenceAnswer(),
                    request.expectedFileMd5s(), request.expectedFileNames(), request.expectedEvidence(), reviewer(token)));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping(value = "/export", produces = "application/x-ndjson;charset=UTF-8")
    public ResponseEntity<String> export(@RequestParam(required = false) String status) {
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=badcase.jsonl")
                    .body(feedbackService.export(status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }

    private String reviewer(String token) {
        String clean = token == null ? "" : token.replace("Bearer ", "");
        String username = jwtUtils.extractUsernameFromToken(clean);
        return username == null || username.isBlank() ? "admin" : username;
    }

    private ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status.value(), "message", message));
    }

    public record StatusRequest(String status, String note) {
    }

    public record ConvertRequest(String referenceAnswer,
                                 List<String> expectedFileMd5s,
                                 List<String> expectedFileNames,
                                 List<String> expectedEvidence) {
    }
}
