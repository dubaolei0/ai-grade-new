package com.yuanxuan.aigrade.model;

import java.util.List;

/**
 * {@code GradeResponse.content} 的结构化封装：一个对象，内含逐题批改结果列表。
 *
 * 序列化为 {@code {"questions":[{...},{...}]}}，使 {@code content} 本身是一个 JSON 对象
 * （而非裸数组），每题含「题号 / 学生答案 / 标准答案 / 判定结果」4 个字段。
 */
public class GradeContent {

    private final List<QuestionResult> questions;

    public GradeContent(List<QuestionResult> questions) {
        this.questions = questions == null ? List.of() : questions;
    }

    public List<QuestionResult> getQuestions() {
        return questions;
    }
}
