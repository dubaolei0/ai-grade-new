package com.yuanxuan.aigrade.service;


import com.yuanxuan.aigrade.utils.FlyingSaucerUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QuestionJudgingService {

    private static final String HTML_SKELETON = """
            <!DOCTYPE html>
                   <html>
                   	<head>
                   		<meta charset="utf-8">
                   		<title></title>
                   	</head>
                   	<body>
                   		<div>
                   		{{body}}
                   		</div>
                   	</body>
                   </html>
            """;

    /**
     * 按传入内容组装 html 并渲染成 JPEG 图片字节；四块内容任意组合，空的不进 html。
     * 支持：只题干（answerList 空）、只答案、只讲解+标准答案等任意子集。
     *
     * @param stemContent          题干内容，可空
     * @param answerList           学生答案图片地址，oss地址，可空
     * @param explainContent       题目讲解内容，可空
     * @param standardAnswerContent 题目标准答案内容，可空
     * @return JPEG 图片字节
     */
    private byte[] gen(String stemContent, List<String> answerList, String explainContent, String standardAnswerContent) {
        StringBuilder body = new StringBuilder();
        appendParagraph(body, stemContent);
        if (answerList != null && !answerList.isEmpty()) {
            // 答案照片用相对宽度铺满画布，画布宽由 FlyingSaucerUtil 统一控制
            body.append("<p>")
                    .append(answerList.stream()
                            .map(url -> "<img style=\"width:100%\" src=\"" + url + "\"/>")
                            .collect(Collectors.joining()))
                    .append("</p>");
        }
        appendParagraph(body, explainContent);
        appendParagraph(body, standardAnswerContent);

        byte[] imageBytes = FlyingSaucerUtil.genImage(HTML_SKELETON.replace("{{body}}", body));
        log.info("QuestionJudgingService:gen:rendered stemBlank:{} answerCount:{} explainBlank:{} standardAnswerBlank:{} imageBytes:{}",
                isBlank(stemContent), answerList == null ? 0 : answerList.size(),
                isBlank(explainContent), isBlank(standardAnswerContent), imageBytes.length);
        return imageBytes;
    }

    /**
     * 上传两张图片，纵向合并为一张 JPEG。两图各自等比缩放到 800 宽后上下堆叠，白底输出。
     *
     * @param first  第一张（上方）图片，不能为空
     * @param second 第二张（下方）图片，不能为空
     * @return 合并后的 JPEG 字节
     */
    public byte[] mergeImages(MultipartFile first, MultipartFile second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            throw new IllegalArgumentException("待合并图片不能为空");
        }
        byte[] merged;
        try {
            merged = FlyingSaucerUtil.mergeVertical(first.getBytes(), second.getBytes());
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("合并图片失败：" + e.getMessage());
        }
        log.info("QuestionJudgingService:mergeImages: first={} firstBytes={} second={} secondBytes={} mergedBytes={}",
                first.getOriginalFilename(), first.getSize(),
                second.getOriginalFilename(), second.getSize(), merged.length);
        return merged;
    }

    private static void appendParagraph(StringBuilder body, String content) {
        if (!isBlank(content)) {
            body.append("<p>").append(content).append("</p>");
        }
    }

    private static boolean isBlank(String content) {
        return content == null || content.isBlank();
    }

    /** 本地看样式用：渲染各组合样例，保存到 target/ 下 */
    public static void main(String[] args) throws Exception {
        String stem = "经过椭圆 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/f825917c14e64ce695ca36e5081a3a67.png\"> 的右焦点 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/dc07fbc2b8414cc69862e9bfd0134d19.png\"> 作垂直于 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/fd0b20bd62b142c89554e7e7f048ff2a.png\"> 轴的直线 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/1e9954c01421453a846c7401a4c9dab0.png\">，交椭圆于 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/8f97804461de41d7a135a212570f191e.png\">、<img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/0f1742bf9ef448caa39e8653189b07fe.png\"> 两点，<img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/15a0c38845ca48e38fcf8b012a550583.png\"> 是椭圆的左焦点．<br/>（1）求 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/14185d3c4aff4b6abaea27d39479600b.png\"> 的周长．<br/>（2）如果直线 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/d5f7613a0d9e4da1a23fd1c5c7980a9b.png\"> 仍过 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/61cbec49dc644146b128ccf29f4502aa.png\">，但不垂直于 <img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/760c18bab38544f0a0f715cbc95804d3.png\"> 轴，<img src=\"https://aixue-prod.oss-cn-beijing.aliyuncs.com/images/9c4741d2c06d4955b640d7086f73e4ef.png\"> 的周长有变化吗？为什么？";
        List<String> answers = List.of(
                "https://aixue-test.oss-cn-beijing.aliyuncs.com/images/d32b1a4baef54230b71223e8a0262a5b.jpg",
                "https://aixue-test.oss-cn-beijing.aliyuncs.com/images/c375eb5128014afd88b631f6076e0892.jpg");
        String explain = "【讲解】过焦点且垂直于长轴的弦是通径，长度为 2b²/a。由椭圆定义，椭圆上任一点到两焦点距离之和为 2a，故过焦点的弦与另一焦点围成的三角形周长恒为 4a，与弦的倾斜程度无关。";
        String standardAnswer = "【标准答案】（1）由 a²=25 得 a=5，周长 = 4a = 20。<br/>（2）不变，仍为 4a = 20，理由见讲解。";
        QuestionJudgingService service = new QuestionJudgingService();
        save(service.gen(stem, answers, null, null), "target/question-judging-sample.jpg");
        save(service.gen(stem, List.of(), null, null), "target/question-judging-stem-only.jpg");
        save(service.gen(null, answers, null, null), "target/question-judging-answer-only.jpg");
        save(service.gen(null, null, explain, standardAnswer), "target/question-judging-explain-only.jpg");
        // 两张图片纵向合并自测：复用上面两张答案图，拉取字节后合并
        byte[] topBytes = new URL(answers.get(0)).openStream().readAllBytes();
        byte[] bottomBytes = new URL(answers.get(1)).openStream().readAllBytes();
        save(FlyingSaucerUtil.mergeVertical(topBytes, bottomBytes), "target/merged-vertical.jpg");
    }

    private static void save(byte[] imageBytes, String path) throws Exception {
        Path out = Path.of(path).toAbsolutePath();
        Files.write(out, imageBytes);
        System.out.println("saved: " + out + " bytes=" + imageBytes.length);
    }
}
