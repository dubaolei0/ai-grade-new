package com.yuanxuan.aigrade.support;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

/** 把上传的图片转成模型可接受的 base64 data URL（对应 Python 的 to_image_url）。 */
public final class ImageUtils {

    private static final String[] IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"};

    private ImageUtils() {
    }

    public static boolean isImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        String name = lowerName(file);
        for (String suffix : IMAGE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static String toDataUrl(MultipartFile file) throws IOException {
        return "data:" + guessMime(file) + ";base64,"
                + Base64.getEncoder().encodeToString(file.getBytes());
    }

    private static String guessMime(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return contentType.toLowerCase(Locale.ROOT);
        }
        String name = lowerName(file);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    private static String lowerName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
