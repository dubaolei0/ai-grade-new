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
     * 两张图片横向（左右）合并为一张 JPEG：各自等比缩放到固定高度（800），自左而右并排到白底画布后编码输出。
     * 不经过 HTML 渲染——上传的图片字节没有可被 flying-saucer 通过 URL 拉取的地址，直接解码拼接最稳妥；
     * 输出与 {@link #genImage(String)} 一致（白底、JPEG 质量 0.8）。
     *
     * @param leftBytes  左侧图片字节，不能为空
     * @param rightBytes 右侧图片字节，不能为空
     * @return 合并后的 JPEG 字节
     */
    public static byte[] mergeHorizontal(byte[] leftBytes, byte[] rightBytes) {
        return mergeHorizontal(List.of(leftBytes, rightBytes));
    }

    /**
     * 多张图片横向（左右）合并为一张 JPEG：各自等比缩放到固定高度（800），按列表顺序自左而右并排到白底画布后编码输出。
     * 适用单图、双图或多图场景；单图时等比缩放后原样输出，多图时横向并排。
     * 不经过 HTML 渲染——上传的图片字节没有可被 flying-saucer 通过 URL 拉取的地址，直接解码拼接最稳妥；
     * 输出与 {@link #genImage(String)} 一致（白底、JPEG 质量 0.8）。
     *
     * @param images 图片字节列表（自左而右顺序），不能为空
     * @return 合并后的 JPEG 字节
     */
    public static byte[] mergeHorizontal(List<byte[]> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("mergeHorizontal: 图片列表不能为空");
        }
        List<BufferedImage> scaled = new ArrayList<>(images.size());
        int totalWidth = 0;
        // 左右合并：统一缩放到固定高度（复用 800 基准），宽度按宽高比自适应，画布高度恒为 IMAGE_WIDTH
        int targetHeight = IMAGE_WIDTH;
        for (byte[] bytes : images) {
            // 先裁掉四周空白边再缩放，避免作答图等大片留白被原样堆进合并图
            BufferedImage img = scaleToHeight(trimWhitespace(readImage(bytes)), targetHeight);
            scaled.add(img);
            totalWidth += img.getWidth();
        }
        int width = Math.max(1, totalWidth);
        int height = targetHeight;
        // JPEG 无 alpha 通道，铺白底避免透明区变黑
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int x = 0;
            for (BufferedImage img : scaled) {
                g.drawImage(img, x, 0, null);
                x += img.getWidth();
            }
        } finally {
            g.dispose();
        }
        return toJpeg(canvas);
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
        // 上下合并：统一缩放到固定宽度（800 基准），高度按宽高比自适应，画布宽度恒为 IMAGE_WIDTH
        int targetWidth = IMAGE_WIDTH;
        for (byte[] bytes : images) {
            // 先裁掉四周空白边再缩放，避免作答图等大片留白被原样堆进合并图
            BufferedImage img = scaleToWidth(trimWhitespace(readImage(bytes)), targetWidth);
            scaled.add(img);
            totalHeight += img.getHeight();
        }
        int width = targetWidth;
        int height = Math.max(1, totalHeight);
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

    /**
     * 读取图片像素宽高，供调用方做尺寸/像素数校验（如豆包要求 宽×高 ∈ [196, 36000000]）。
     * 格式不支持或已损坏时抛 IllegalArgumentException。
     *
     * @param imageBytes 图片字节，不能为空
     * @return {@code int[]{width, height}}
     */
    public static int[] dimensions(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("dimensions: 图片字节不能为空");
        }
        BufferedImage img = readImage(imageBytes);
        return new int[]{ img.getWidth(), img.getHeight() };
    }

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

    /**
     * 裁掉图片四周的空白边（近似白底），只保留内容区域。
     * 拍摄的作业图常带大片留白，纵向合并前不裁掉会让合并图出现明显空白间隙。
     * 判定阈值：像素任一 RGB 通道低于 245 即视为内容（非近白）；整图空白时原样返回。
     *
     * @param src 原图
     * @return 裁剪后的子图；整图空白或无法判定时返回原图
     */
    private static BufferedImage trimWhitespace(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        // 带透明通道或其他类型的图先铺白底转成 RGB，避免 alpha 干扰「近白」判定
        BufferedImage rgb = src.getType() == BufferedImage.TYPE_INT_RGB ? src : toRgbWhiteBg(src);
        final int threshold = 245;
        int top = -1, bottom = -1, left = w, right = -1;
        // 按行批量取像素，比逐像素 getRGB 快得多，作业大图也能很快扫完
        for (int y = 0; y < h; y++) {
            // BufferedImage 没有 5 参数 getRGB，批量取一行像素要用 7 参数版本：
            // (startX, startY, w, h, rgbArray, offset, scansize)
            int[] row = rgb.getRGB(0, y, w, 1, null, 0, w);
            int rowLeft = w, rowRight = -1;
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                if (r < threshold || g < threshold || b < threshold) {
                    if (x < rowLeft) rowLeft = x;
                    if (x > rowRight) rowRight = x;
                }
            }
            if (rowRight >= 0) { // 本行有内容
                if (top < 0) top = y;
                bottom = y;
                if (rowLeft < left) left = rowLeft;
                if (rowRight > right) right = rowRight;
            }
        }
        if (top < 0) {
            // 整图空白，没有可裁的内容，原样返回
            return src;
        }
        return rgb.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    /** 把任意类型的图片铺白底后转成 TYPE_INT_RGB，消除透明通道对空白判定的影响。 */
    private static BufferedImage toRgbWhiteBg(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 等比缩放到指定高度，保持宽高比；高度已相等时原样返回。 */
    private static BufferedImage scaleToHeight(BufferedImage src, int targetHeight) {
        if (src.getHeight() == targetHeight) {
            return src;
        }
        int targetWidth = Math.max(1,
                (int) Math.round((double) src.getWidth() * targetHeight / src.getHeight()));
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
