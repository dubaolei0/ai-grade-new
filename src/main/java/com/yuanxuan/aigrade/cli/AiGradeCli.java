package com.yuanxuan.aigrade.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 所给 Python 批改脚本的 1:1 Java 实现。
 *
 * <p>纯 JDK 21、零第三方依赖、单文件可直接编译运行，不依赖 Spring。
 * 结构与方法名与 Python 版一一对应：
 * <pre>
 *   os.getenv("DASHSCOPE_API_KEY")  ->  API_KEY
 *   IMAGE / MODEL / SHOW_THINKING   ->  同名常量
 *   to_image_url()                  ->  toImageUrl()
 *   chat(content, thinking, ...)    ->  chat()
 *   extra_body.enable_thinking      ->  请求体顶层 enable_thinking
 *   grade(image)                    ->  grade(String... images)
 *   __main__                        ->  main()
 * </pre>
 *
 * <p>运行（在项目根目录）：
 * <pre>
 *   javac -encoding UTF-8 -d target/cli-classes src/main/java/com/yuanxuan/aigrade/cli/AiGradeCli.java
 *   java -Ddashscope.apiKey=sk-xxx -cp target/cli-classes com.yuanxuan.aigrade.cli.AiGradeCli 学生作答题.jpg
 *   java -Ddashscope.apiKey=sk-xxx -cp target/cli-classes com.yuanxuan.aigrade.cli.AiGradeCli 题目.jpg 答案.jpg
 * </pre>
 * 命令行可传多个图片路径 / URL，按参数顺序合并为一份作业；不传则使用默认的 {@code 学生作答题.jpg}。
 * 也可用环境变量 DASHSCOPE_API_KEY 代替 -D；可用 -DshowThinking=false 关闭思考过程打印，
 * 用 -Ddashscope.model=xxx / -Ddashscope.baseUrl=xxx 覆盖模型与地址。
 */
public final class AiGradeCli {

    // ------------------------------------------------------------------ 配置

    private static final String API_KEY = pick(
            System.getProperty("dashscope.apiKey"),
            System.getenv("DASHSCOPE_API_KEY"));

    private static final String BASE_URL = System.getProperty(
            "dashscope.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");

    private static final String MODEL = System.getProperty("dashscope.model", "qwen3.8-max");

    /** 是否在终端打印转录结果和思考过程（对应 Python 的 SHOW_THINKING）。 */
    private static final boolean SHOW_THINKING =
            !"false".equalsIgnoreCase(System.getProperty("showThinking", "true"));

    /** 默认作业图片：支持本地路径或 http(s) URL，也可通过命令行参数传入。 */
    private static final String IMAGE = "学生作答题.jpg";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ------------------------------------------------------------------ 提示词（与 Prompts.java 保持一致）

    /** 第一阶段：只转录不解题，避免模型先算出答案再"脑补"学生手写内容。 */
    private static final String TRANSCRIBE_PROMPT = """
            你是作业转录员，只负责照原样转录，不解题、不判断对错。
            请逐题转录图片中的内容：
            - 题干：完整转录，公式用 LaTeX。
            - 学生答案：逐字符照原样转录学生手写的作答（数字、根号内数字、符号逐个确认），看到什么写什么；无法辨认的字符用“?”表示，未作答写“未作答”。
            输出格式：
            第N题
            题干：xxx
            学生答案：xxx
            """;

    /** 第二阶段：基于转录文本解题并批改。 */
    private static final String GRADE_PROMPT = """
            你是高中作业批改老师。下面是作业的转录结果，其中“学生答案”为照原样转录，必须以它为准，不得修改。
            请对每题仅根据题干独立求出标准答案，再与学生答案比对并打分。
            批改规则：
            1. 默认每题满分10分；若题目标注了分值，按标注分值。
            2. 客观题（选择、填空）：答案与标准答案等价即满分，否则0分；等价形式（如 $\\sqrt{4}$ 与 2）视为正确。
            3. 主观题：按关键步骤给分，答案正确且过程完整得满分。
            4. 学生未作答或含无法辨认字符，判“未作答”，0分。
            输出要求：严格按以下格式，每题一段，不输出解题过程和其他内容：
            第N题
            - 学生答案：xxx
            - 标准答案：xxx
            - 判定：✅正确 / ❌错误 / ⚠️部分正确 / 未作答
            - 得分：x/满分
            最后单独一行：【总分】x/满分合计
            转录结果：
            {transcript}
            """;

    /** 多图补充提示：与 Prompts.MULTI_IMAGE 保持一致。 */
    private static final String MULTI_IMAGE_HINT = """
            补充说明：本次提供了多张图片，它们是同一份作业的不同部分（题干与学生作答可能分别在不同图片中，也可能一页拍不完）。
            请按图片提供的先后顺序，把它们当作一份连续作业来转录：
            - 先在全部图片中找出每道题的题干，再找出该题对应的学生作答，配对后逐题输出上述格式。
            - 同一道题只输出一次；不要因为某张图只有题干或只有答案就漏掉该题，也不要把不同图片里的同一题重复转录。
            - 若某题的题干与作答分散在两张图中，以题干所在图片的题号为依据合并。
            """;

    private AiGradeCli() {
    }

    // ------------------------------------------------------------------ 主流程

    public static void main(String[] args) {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("未找到 API Key：请设置环境变量 DASHSCOPE_API_KEY，或用 -Ddashscope.apiKey=sk-xxx 传入。");
            System.err.println("示例：java -Ddashscope.apiKey=sk-xxx -cp target/cli-classes "
                    + "com.yuanxuan.aigrade.cli.AiGradeCli 题目.jpg 答案.jpg");
            return;
        }
        try {
            grade(args.length > 0 ? args : new String[]{IMAGE});
        } catch (Exception e) {
            System.err.println("\n批改失败：" + e.getMessage());
        }
    }

    /** 两阶段批改：先转录（多张图片按参数顺序合并），再解题打分。 */
    private static void grade(String... images) throws Exception {
        // 第一阶段：只转录不解题（thinking = false）；多个 image_url 连续放在数组前面
        StringBuilder content = new StringBuilder("[");
        for (String image : images) {
            if (content.length() > 1) {
                content.append(',');
            }
            content.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"")
                    .append(escapeJson(toImageUrl(image))).append("\"}}");
        }
        String prompt = TRANSCRIBE_PROMPT;
        if (images.length > 1) {
            prompt += MULTI_IMAGE_HINT;
            System.out.println("已载入 " + images.length + " 张图片，按参数顺序合并转录。");
        }
        content.append(",{\"type\":\"text\",\"text\":\"")
                .append(escapeJson(prompt)).append("\"}]");

        String transcript = chat(content.toString(), false, "转录结果", SHOW_THINKING);

        // 第二阶段：基于转录文本解题并批改（thinking = true）
        String gradePrompt = GRADE_PROMPT.replace("{transcript}", transcript);
        chat("\"" + escapeJson(gradePrompt) + "\"", true, "批改结果", true);
    }

    /**
     * 流式调用模型，返回最终回答文本。
     *
     * @param contentJson 已构造好的 JSON 字面量：字符串（纯文本）或数组（多模态）
     * @param thinking    是否开启思考模式
     * @param title       打印正文前的分隔线标题
     * @param showContent 是否在终端打印正文
     */
    private static String chat(String contentJson, boolean thinking, String title,
                               boolean showContent) throws Exception {
        String body = "{\"model\":\"" + escapeJson(MODEL) + "\","
                + "\"stream\":true,"
                + "\"enable_thinking\":" + thinking + ","
                + "\"messages\":[{\"role\":\"user\",\"content\":" + contentJson + "}]}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(BASE_URL) + "/chat/completions"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response =
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            String detail;
            try (Stream<String> lines = response.body()) {
                detail = String.join("\n", lines.limit(20).toList());
            }
            throw new IOException("上游返回 HTTP " + response.statusCode() + "：" + detail);
        }

        if (SHOW_THINKING && thinking) {
            System.out.println("==================== 思考过程 ====================");
        }

        final boolean[] answering = {false};
        StringBuilder result = new StringBuilder();

        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line == null || !line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                // 只取 choices[0].delta，避免误读同名字段
                String delta = deltaObject(data);
                if (delta == null) {
                    return;
                }
                String reasoning = jsonString(delta, "reasoning_content");
                String content = jsonString(delta, "content");

                if (reasoning != null && SHOW_THINKING) {
                    System.out.print(reasoning);
                    System.out.flush();
                }
                if (content == null || content.isEmpty()) {
                    return;
                }
                result.append(content);
                if (!showContent) {
                    return;
                }
                if (!answering[0]) {
                    answering[0] = true;
                    if (SHOW_THINKING) {
                        System.out.print("\n\n==================== " + title + " ====================\n");
                    }
                }
                System.out.print(content);
                System.out.flush();
            });
        } catch (java.io.UncheckedIOException e) {
            throw new IOException("读取模型流式响应时连接中断", e);
        }

        if (showContent) {
            System.out.println();
        }
        return result.toString();
    }

    /** 本地图片转 base64 data URL，URL 原样返回（对应 Python 的 to_image_url）。 */
    private static String toImageUrl(String image) throws IOException {
        if (image.startsWith("http://") || image.startsWith("https://")) {
            return image;
        }
        Path path = Path.of(image);
        if (!Files.isRegularFile(path)) {
            throw new IOException("找不到图片文件：" + path.toAbsolutePath());
        }
        String mime = Files.probeContentType(path);
        if (mime == null || !mime.startsWith("image/")) {
            mime = guessMime(image);
        }
        return "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    // --------------------------------------------------- 极简 JSON 工具（保持零依赖）

    /** 字符串转义成合法 JSON 字符串内容。 */
    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 从 SSE chunk 中截取 choices[0].delta 的 JSON 对象子串。 */
    private static String deltaObject(String json) {
        int key = json.indexOf("\"delta\"");
        if (key < 0) {
            return null;
        }
        int start = json.indexOf('{', key + 7);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 读取 JSON 对象里某个字符串字段的值，字段不存在或为 null 时返回 null。 */
    private static String jsonString(String json, String key) {
        int keyAt = json.indexOf("\"" + key + "\"");
        if (keyAt < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyAt + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        p++;
        while (p < json.length()) {
            char c = json.charAt(p++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (p >= json.length()) {
                break;
            }
            char e = json.charAt(p++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (p + 4 <= json.length()) {
                        sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                        p += 4;
                    }
                }
                default -> sb.append(e);
            }
        }
        return sb.toString();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String pick(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
