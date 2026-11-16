package com.yuanxuan.aigrade.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 单道题的批改结果，对应模型返回 JSON 数组的一个元素。
 *
 * 模型按 {@code Prompts.GRADE_JSON} 输出中文键（题号/学生答案/标准答案/判定结果/子项 等），这里用
 * {@code @JsonAlias} 接收这些中文键进行反序列化；而接口返回值（序列化）使用英文组件名：
 * {@code questionNo / studentAnswer / standardAnswer / judgeResult / subResults}。
 *
 * 判定类字段统一为布尔：{@code true} 表示正确，{@code false} 表示错误 / 部分正确 / 未作答。
 *
 * {@code subResults}（子项）仅在「多空填空题」或「多子题解答题」时有值：按题干中子部分出现的先后顺序，
 * 依次编号为「1」「2」…（不带“子题”二字），承载每个子题的学生答案与判定；单空 / 单道题时为 null，序列化时省略。
 * 题级 {@code judgeResult} 当且仅当所有子题判定均为 {@code true} 时才为 {@code true}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionResult {

    private final String questionNo;
    private final String studentAnswer;
    private final String standardAnswer;
    private final boolean judgeResult;
    private final List<SubResult> subResults;

    @JsonCreator
    public QuestionResult(
            @JsonAlias("题号") @JsonProperty("questionNo") String questionNo,
            @JsonAlias("学生答案") @JsonProperty("studentAnswer") String studentAnswer,
            @JsonAlias("标准答案") @JsonProperty("standardAnswer") String standardAnswer,
            @JsonAlias("判定结果") @JsonProperty("judgeResult") boolean judgeResult,
            @JsonAlias("子项") @JsonProperty("subResults") List<SubResult> subResults) {
        this.questionNo = questionNo;
        this.studentAnswer = studentAnswer;
        this.standardAnswer = standardAnswer;
        this.judgeResult = judgeResult;
        this.subResults = subResults;
    }

    public String getQuestionNo() {
        return questionNo;
    }

    public String getStudentAnswer() {
        return studentAnswer;
    }

    public String getStandardAnswer() {
        return standardAnswer;
    }

    public boolean isJudgeResult() {
        return judgeResult;
    }

    public List<SubResult> getSubResults() {
        return subResults;
    }

    /** 多空填空题 / 多子题解答题中，单个子题的判定。编号对应题干中的「1」「2」…，顺序不能乱。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SubResult {

        private final String no;
        private final String studentAnswer;
        private final boolean judge;

        @JsonCreator
        public SubResult(
                @JsonAlias("编号") @JsonProperty("no") String no,
                @JsonAlias("学生答案") @JsonProperty("studentAnswer") String studentAnswer,
                @JsonAlias("判定") @JsonProperty("judge") boolean judge) {
            this.no = no;
            this.studentAnswer = studentAnswer;
            this.judge = judge;
        }

        public String getNo() {
            return no;
        }

        public String getStudentAnswer() {
            return studentAnswer;
        }

        public boolean isJudge() {
            return judge;
        }
    }
}
