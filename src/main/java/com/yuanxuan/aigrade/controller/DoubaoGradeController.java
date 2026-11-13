package com.yuanxuan.aigrade.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuanxuan.aigrade.config.DoubaoProperties;
import com.yuanxuan.aigrade.service.DoubaoClient;
import com.yuanxuan.aigrade.support.ImageUtils;
import com.yuanxuan.aigrade.utils.FlyingSaucerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 豆包爱学（火山引擎）作业批改接口。
 *
 * 与阿里百炼的 {@code /api/grade/stream} 完全独立，互不影响：
 *   POST /api/doubao/grade/submit  multipart 上传真题 + 作答两张图，后端纵向拼接为单图后提交任务，返回 task_id
 *   POST /api/doubao/grade/query   按 task_id 查询批改结果（手动触发）
 *   GET  /api/doubao/grade/config  返回豆包配置状态
 *
 * 豆包接口为同步 JSON，不走 SSE。提交拿到 task_id 后，前端展示「查询批改结果」按钮，
 * 由用户手动触发查询接口（对应 md 文档里的第二个接口）。
 */
@RestController
@RequestMapping("/api/doubao/grade")
public class DoubaoGradeController {

    private static final Logger log = LoggerFactory.getLogger(DoubaoGradeController.class);

    private static final int MAX_IMAGES = 8;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** 豆包对提交图片的像素数（宽×高）限制范围。 */
    private static final long MIN_PIXELS = 196L;
    private static final long MAX_PIXELS = 36_000_000L;

    private final DoubaoClient client;
    private final DoubaoProperties props;
    private final ObjectMapper mapper;

    public DoubaoGradeController(DoubaoClient client, DoubaoProperties props, ObjectMapper mapper) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
    }

    /** 前端用来展示豆包配置状态。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiKeyConfigured", props.getApiKey() != null && !props.getApiKey().isBlank());
        result.put("botId", props.getBotId());
        result.put("baseUrl", props.getBaseUrl());
        return result;
    }

    /**
     * 提交批改任务：multipart 上传一张或多张图片（页面固定两张：真题 + 作答），
     * 后端纵向拼接成单图后调用豆包提交接口，返回 task_id 等。
     */
    @PostMapping(value = "/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> submit(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "image", required = false) MultipartFile image) throws Exception {

        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("尚未配置豆包 API Key（doubao.api-key）");
        }
        if (props.getBotId() == null || props.getBotId().isBlank()) {
            throw new IllegalStateException("尚未配置豆包 bot_id（doubao.bot-id）");
        }

        List<MultipartFile> files = collectFiles(images, image);
        if (files.isEmpty()) {
            throw new IllegalStateException("请至少上传一张图片");
        }
        if (files.size() > MAX_IMAGES) {
            throw new IllegalStateException("一次最多上传 " + MAX_IMAGES + " 张图片，当前为 " + files.size() + " 张");
        }
        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            if (!ImageUtils.isImage(f)) {
                throw new IllegalStateException("第 " + (i + 1) + " 个文件不是图片（jpg / jpeg / png / webp / gif / bmp）");
            }
            if (f.getSize() > MAX_FILE_BYTES) {
                throw new IllegalStateException("第 " + (i + 1) + " 张图片超过 20MB，请压缩后再上传");
            }
        }

        // 上传的多张图片字节按顺序纵向（上下）拼接成单图（FlyingSaucerUtil.mergeVertical），
        // 再编码成 data URL 提交给豆包——豆包 submit 接口只接收单张图片。
        List<byte[]> imageBytes = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            imageBytes.add(f.getBytes());
        }
        byte[] merged = FlyingSaucerUtil.mergeVertical(imageBytes);
        // 豆包要求提交图片的 宽×高 像素数落在 [196, 36000000] 内，拼接后立即校验，命中直接 400 提示前端
        int[] dim = FlyingSaucerUtil.dimensions(merged);
        long pixels = (long) dim[0] * dim[1];
        if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) {
            throw new IllegalStateException("图片像素数（宽×高）需在 [" + MIN_PIXELS + ", " + MAX_PIXELS
                    + "] 之间，当前为 " + dim[0] + "×" + dim[1] + " = " + pixels + "，请调整图片后重试");
        }
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(merged);

        log.info("豆包提交任务请求：bot_id={}, 图片数={}, 拼接后 base64 长度={}",
                props.getBotId(), files.size(), dataUrl.length());
        JsonNode result = client.submit(dataUrl);
        log.info("豆包提交任务返回：{}", result);
        return toMap(result);
    }

    /**
     * 查询批改结果：请求体 {@code {"taskId":"..."}}，返回 status / mark_results / usage 等。
     */
    @PostMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> query(@RequestBody Map<String, Object> body) throws Exception {
        String taskId = body == null ? null : String.valueOf(body.getOrDefault("taskId", ""));
        if (taskId == null || taskId.isBlank() || "null".equals(taskId)) {
            throw new IllegalStateException("缺少 taskId");
        }
        log.info("豆包查询任务请求：bot_id={}, task_id={}", props.getBotId(), taskId);
        JsonNode result = client.query(taskId);
        log.info("豆包查询任务返回：{}", result);
        return toMap(result);
    }

    /** 把豆包返回的 Result 节点原样转成 Map 透传给前端（保留 mark_results 等嵌套结构）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) throws Exception {
        return mapper.convertValue(node, Map.class);
    }

    private List<MultipartFile> collectFiles(List<MultipartFile> images, MultipartFile image) {
        List<MultipartFile> files = new ArrayList<>();
        if (images != null) {
            for (MultipartFile f : images) {
                if (f != null && !f.isEmpty()) {
                    files.add(f);
                }
            }
        }
        if (files.isEmpty() && image != null && !image.isEmpty()) {
            files.add(image);
        }
        return files;
    }

    /** 统一把异常转成 {"message":"..."} 返回 400，前端据 message 提示。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
