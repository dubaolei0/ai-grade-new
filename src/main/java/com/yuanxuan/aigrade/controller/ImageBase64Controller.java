package com.yuanxuan.aigrade.controller;

import com.yuanxuan.aigrade.support.ImageUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片转 base64 工具接口：上传一张图片，返回 data URL 形式的 base64 字符串。
 * 与 {@link GradeController} 区分，专门用于前端需要拿到单张图片 base64 时调用。
 */
@RestController
@RequestMapping("/api/image")
public class ImageBase64Controller {

    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /**
     * 上传一张图片，返回形如 {@code data:image/jpeg;base64,{base64}} 的字符串。
     *
     * @param image 图片文件（jpg / jpeg / png / webp / gif / bmp）
     * @return 包含 dataUrl 字段与原始文件信息的对象
     */
    @PostMapping("/base64")
    public Map<String, Object> toBase64(@RequestParam("image") MultipartFile image) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();

        if (image == null || image.isEmpty()) {
            result.put("success", false);
            result.put("message", "请上传一张图片");
            return result;
        }
        if (!ImageUtils.isImage(image)) {
            result.put("success", false);
            result.put("message", "文件不是图片（jpg / jpeg / png / webp / gif / bmp）");
            return result;
        }
        if (image.getSize() > MAX_FILE_BYTES) {
            result.put("success", false);
            result.put("message", "图片超过 20MB，请压缩后再上传");
            return result;
        }

        // data:image/jpeg;base64,{base64_image}
        result.put("success", true);
        result.put("filename", image.getOriginalFilename());
        result.put("size", image.getSize());
        result.put("dataUrl", ImageUtils.toDataUrl(image));
        return result;
    }
}
