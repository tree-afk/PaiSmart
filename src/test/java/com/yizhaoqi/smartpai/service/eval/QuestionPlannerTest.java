package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuestionPlannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesSubQuestionsFromObject() {
        List<String> questions = QuestionPlanner.parse("""
                {"sub_questions": ["监管对资本充足率的要求是什么？", "监管对拨备覆盖率的要求是什么？"]}
                """, objectMapper);

        assertEquals(List.of("监管对资本充足率的要求是什么？", "监管对拨备覆盖率的要求是什么？"), questions);
    }

    @Test
    void emptyArrayMeansNoSplit() {
        assertEquals(List.of(), QuestionPlanner.parse("{\"sub_questions\": []}", objectMapper));
    }

    @Test
    void singleSubQuestionIsNotASplit() {
        assertEquals(List.of(), QuestionPlanner.parse("{\"sub_questions\": [\"只拆出一条\"]}", objectMapper));
    }

    @Test
    void toleratesCodeFenceAndProseAndCapsAtThree() {
        List<String> questions = QuestionPlanner.parse("""
                分析结果如下：
                ```json
                ["子问题一", "子问题二", "子问题三", "子问题四"]
                ```
                以上。
                """, objectMapper);

        assertEquals(List.of("子问题一", "子问题二", "子问题三"), questions);
    }

    @Test
    void dropsBlankAndNonStringItems() {
        List<String> questions = QuestionPlanner.parse(
                "{\"subQuestions\": [\"甲是指什么\", \"  \", 42, \"乙是指什么\"]}", objectMapper);

        assertEquals(List.of("甲是指什么", "乙是指什么"), questions);
    }

    @Test
    void unparseableOutputRaisesSoCallerCanFallBack() {
        assertThrows(IllegalArgumentException.class, () -> QuestionPlanner.parse("不拆", objectMapper));
        assertThrows(IllegalArgumentException.class, () -> QuestionPlanner.parse("{\"plan\": \"先查A再查B\"}", objectMapper));
    }
}
