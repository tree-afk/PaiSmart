package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.service.eval.EvalCase;
import com.yizhaoqi.smartpai.service.eval.RagEvalService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线 RAG 评测（仅管理员，/api/v1/admin/** 由 SecurityConfig 限制为 ADMIN 角色）。
 * 检索以发起评测的管理员身份执行，权限过滤与线上一致；评测异步运行，通过状态接口查看进度和报告路径。
 */
@RestController
@RequestMapping("/api/v1/admin/eval")
public class RagEvalAdminController {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private final RagEvalService evalService;
    private final JwtUtils jwtUtils;

    public RagEvalAdminController(RagEvalService evalService, JwtUtils jwtUtils) {
        this.evalService = evalService;
        this.jwtUtils = jwtUtils;
    }

    /**
     * JSON 方式提交：datasetPath（classpath:eval/xxx.jsonl 或 eval.dataset-dir 下的相对路径）与 jsonl（直接贴内容）二选一
     */
    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> run(@RequestHeader("Authorization") String token, @RequestBody RunRequest request) {
        if (request == null) {
            return error(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        boolean hasPath = request.datasetPath() != null && !request.datasetPath().isBlank();
        boolean hasInline = request.jsonl() != null && !request.jsonl().isBlank();
        if (hasPath == hasInline) {
            return error(HttpStatus.BAD_REQUEST, "datasetPath 与 jsonl 必须且只能提供一个");
        }
        return submit(token, request.topK(), request.label(), hasPath ? request.datasetPath().trim() : "inline",
                () -> hasPath ? evalService.loadDataset(request.datasetPath()) : evalService.parseInline(request.jsonl()));
    }

    /**
     * 上传 JSONL 文件提交
     */
    @PostMapping(value = "/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> runUpload(@RequestHeader("Authorization") String token,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) Integer topK,
                                       @RequestParam(required = false) String label) {
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "上传的数据集为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "数据集文件不能超过 5MB");
        }
        String source = "upload:" + (file.getOriginalFilename() == null ? "dataset.jsonl" : file.getOriginalFilename());
        return submit(token, topK, label, source,
                () -> evalService.parseInline(new String(readBytes(file), StandardCharsets.UTF_8)));
    }

    @GetMapping("/runs")
    public ResponseEntity<?> runs() {
        return ok("获取评测任务成功", evalService.listStatuses());
    }

    @GetMapping("/runs/latest")
    public ResponseEntity<?> latest() {
        return ok("获取评测任务成功", evalService.getLatestStatus().orElse(null));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> status(@PathVariable String runId) {
        return evalService.getStatus(runId)
                .<ResponseEntity<?>>map(status -> ok("获取评测任务成功", status))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "评测任务不存在: " + runId));
    }

    @GetMapping("/runs/{runId}/report")
    public ResponseEntity<?> report(@PathVariable String runId) {
        try {
            return evalService.readReport(runId)
                    .<ResponseEntity<?>>map(report -> ok("获取评测报告成功", report))
                    .orElseGet(() -> error(HttpStatus.NOT_FOUND, "评测报告不存在或任务尚未完成: " + runId));
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        }
    }

    private ResponseEntity<?> submit(String token, Integer topK, String label, String source, DatasetLoader loader) {
        String cleanToken = token.replace("Bearer ", "");
        String operator = jwtUtils.extractUsernameFromToken(cleanToken);
        String userId = jwtUtils.extractUserIdFromToken(cleanToken);
        String searchUserId = userId != null && !userId.isBlank() ? userId : operator;
        try {
            List<EvalCase> cases = loader.load();
            RagEvalService.RunStatus status = evalService.start(cases, source, topK, label, searchUserId, operator);
            LogUtils.logUserOperation(operator, "RAG_EVAL_START", status.runId(), "SUBMITTED");
            return ok("评测任务已提交", status);
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            LogUtils.logBusinessError("RAG_EVAL_START", operator, "提交评测任务失败", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "提交评测任务失败: " + e.getMessage());
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new CustomException("读取上传文件失败: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
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

    @FunctionalInterface
    private interface DatasetLoader {
        List<EvalCase> load();
    }

    public record RunRequest(String datasetPath, String jsonl, Integer topK, String label) {
    }
}
