package com.yuanxuan.aigrade.utils;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;
import org.xhtmlrenderer.simple.Graphics2DRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * HTML → 图片。flying-saucer 相关逻辑全部集中在这里：
 * 1) jsoup 宽松解析富文本并转成 XHTML DOM（flying-saucer 的 XML 解析器要求标签 well-formed，未闭合的 img/br 会直接解析失败）；
 * 2) Graphics2DRenderer 先按默认宽度试排，取内容实际需要的宽度（html 里写死的固定宽，如 div/img width:1000px）作为画布宽，
 *    高度随内容自适应，再渲染到白底画布并按 JPEG 质量压缩输出字节。
 * 注意：img 的远程地址由 flying-saucer 内部逐张同步下载，服务器需能访问 OSS 域名；
 * 中文渲染依赖服务器已安装 CJK 字体，缺失时会渲染为方块；
 * flying-saucer 不支持 max-width，图片限宽要用显式 width。
 */
public class FlyingSaucerUtil {

    /** 默认画布宽度（像素）；html 里有更宽的固定宽内容时画布自动加宽 */
    private static final int IMAGE_WIDTH = 800;

    /** JPEG 压缩质量 */
    private static final float JPEG_QUALITY = 0.8f;

    public static byte[] genImage(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("html").attr("xmlns", "http://www.w3.org/1999/xhtml");
        org.w3c.dom.Document xhtml = new W3CDom().fromJsoup(doc);

        Graphics2DRenderer renderer = new Graphics2DRenderer(xhtml, "");
        BufferedImage probe = new BufferedImage(IMAGE_WIDTH, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        renderer.layout(g, new Dimension(IMAGE_WIDTH, 1));
        // 固定宽内容超出默认画布时右侧会被裁掉，画布宽取两者较大值
        Rectangle size = renderer.getMinimumSize();
        int width = Math.max(IMAGE_WIDTH, size.width);
        renderer.layout(g, new Dimension(width, 1));
        int height = renderer.getMinimumSize().height;
        g.dispose();

        // JPEG 无 alpha 通道，先铺白底再渲染，避免透明区变黑
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, width, height);
        renderer.layout(g2, new Dimension(width, height));
        renderer.render(g2);
        g2.dispose();
        return toJpeg(image);
    }

    /**
     * 两张图片纵向（上下）合并为一张 JPEG：各自等比缩放到默认画布宽度（800），自上而下堆叠到白底画布后编码输出。
     * 不经过 HTML 渲染——上传的图片字节没有可被 flying-saucer 通过 URL 拉取的地址，直接解码拼接最稳妥；
     * 输出与 {@link #genImage(String)} 一致（800 宽、白底、JPEG 质量 0.8）。
     *
     * @param topBytes    上方图片字节，不能为空
     * @param bottomBytes 下方图片字节，不能为空
     * @return 合并后的 JPEG 字节
     */
    public static byte[] mergeVertical(byte[] topBytes, byte[] bottomBytes) {
        return mergeVertical(List.of(topBytes, bottomBytes));
    }

    /**
     * 多张图片纵向（上下）合并为一张 JPEG：各自等比缩放到默认画布宽度（800），按列表顺序自上而下堆叠到白底画布后编码输出。
     * 适用单图、双图或多图场景；单图时等比缩放后原样输出，多图时纵向堆叠。
     * 不经过 HTML 渲染——上传的图片字节没有可被 flying-saucer 通过 URL 拉取的地址，直接解码拼接最稳妥；
     * 输出与 {@link #genImage(String)} 一致（800 宽、白底、JPEG 质量 0.8）。
     *
     * @param images 图片字节列表（自上而下顺序），不能为空
     * @return 合并后的 JPEG 字节
     */
    public static byte[] mergeVertical(List<byte[]> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("mergeVertical: 图片列表不能为空");
        }
        List<BufferedImage> scaled = new ArrayList<>(images.size());
        int totalHeight = 0;
        for (byte[] bytes : images) {
            BufferedImage img = scaleToWidth(readImage(bytes), IMAGE_WIDTH);
            scaled.add(img);
            totalHeight += img.getHeight();
        }
        int width = IMAGE_WIDTH;
        int height = totalHeight;
        // JPEG 无 alpha 通道，铺白底避免透明区变黑
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int y = 0;
            for (BufferedImage img : scaled) {
                g.drawImage(img, 0, y, null);
                y += img.getHeight();
            }
        } finally {
            g.dispose();
        }
        return toJpeg(canvas);
    }

    /**
     * 把 base64 解码成图片字节并写入本地文件。
     * 支持 {@code data:image/jpeg;base64,...} 形式的 data URL，也支持纯 base64 字符串；
     * 自动去掉 {@code data:...;base64,} 前缀后解码，按原字节写入目标路径。
     * 输出格式由调用方给出的文件扩展名决定（如 .jpg / .png）。
     * 父目录不存在时自动创建。
     *
     * @param base64   base64 字符串，可为 data URL 或纯 base64，不能为空
     * @param filePath 目标文件路径
     * @return 写出的文件
     */
    public static File base64ToFile(String base64, String filePath) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("base64ToFile: base64 不能为空");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("base64ToFile: 文件路径不能为空");
        }
        String data = base64.trim();
        // 去掉 data:image/...;base64, 前缀，只保留纯 base64 部分
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma >= 0) {
            data = data.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base64ToFile: base64 解码失败", e);
        }
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new UncheckedIOException(new IOException("base64ToFile: 无法创建目录 " + parent));
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("base64ToFile: 写入失败 " + filePath, e);
        }
        return file;
    }

    public static String readBase64FromFile(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        byte[] bytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
        return new String(bytes);
    }
    public static void main(String[] args) throws IOException {
        String base64 = readBase64FromFile("test-img-base64.txt");
        File file = base64ToFile(base64, "/tmp/test.jpg");
        System.out.println(file.getAbsolutePath());    }

    /** 解码图片字节；格式不支持或已损坏时抛 IllegalArgumentException。 */
    private static BufferedImage readImage(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IllegalArgumentException("无法解码图片（格式不支持或已损坏）");
            }
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException("FlyingSaucerUtil:readImage:读取失败", e);
        }
    }

    /** 等比缩放到指定宽度，保持宽高比；宽度已相等时原样返回。 */
    private static BufferedImage scaleToWidth(BufferedImage src, int targetWidth) {
        if (src.getWidth() == targetWidth) {
            return src;
        }
        int targetHeight = Math.max(1,
                (int) Math.round((double) src.getHeight() * targetWidth / src.getWidth()));
        BufferedImage dst = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] toJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("FlyingSaucerUtil:toJpeg:write失败", e);
        } finally {
            writer.dispose();
        }
    }
}
