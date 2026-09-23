package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalDatasetParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCasesAndSkipsCommentsAndBlankLines() throws Exception {
        String jsonl = """
                # 注释
                {"id": "c1", "question": "端口是多少？", "expectedFileMd5s": ["m1"], "referenceAnswer": "8080"}

                // 另一种注释
                {"question": "版本？", "expectedFileNames": "a.pdf", "referenceAnswer": "v2"}
                {"question": "无标注", "referenceAnswer": "x"}
                """;

        List<EvalCase> cases = EvalDatasetParser.parse(new StringReader(jsonl), objectMapper, 10);

        assertEquals(3, cases.size());
        assertEquals("c1", cases.get(0).id());
        assertEquals(List.of("m1"), cases.get(0).expectedFileMd5s());
        assertEquals("line-5", cases.get(1).id());
        assertEquals(List.of("a.pdf"), cases.get(1).expectedFileNames());
        assertFalse(cases.get(2).hasExpectedDocs());
    }

    @Test
    void reportsLineNumberOfInvalidLine() {
        String jsonl = "{\"question\": \"q\", \"referenceAnswer\": \"a\"}\n{\"question\": \"q2\"}\n";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EvalDatasetParser.parse(new StringReader(jsonl), objectMapper, 10));
        assertTrue(error.getMessage().contains("第 2 行"));
        assertTrue(error.getMessage().contains("referenceAnswer"));

        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> EvalDatasetParser.parse(new StringReader("{not json}"), objectMapper, 10));
        assertTrue(broken.getMessage().contains("第 1 行"));
    }

    @Test
    void enforcesCaseLimitAndRejectsEmptyDataset() {
        String jsonl = "{\"question\": \"q\", \"referenceAnswer\": \"a\"}\n".repeat(3);
        assertThrows(IllegalArgumentException.class, () -> EvalDatasetParser.parse(new StringReader(jsonl), objectMapper, 2));
        assertThrows(IllegalArgumentException.class, () -> EvalDatasetParser.parse(new StringReader("# only comment\n"), objectMapper, 2));
    }

    @Test
    void bundledSampleDatasetIsValid() throws Exception {
        try (var in = getClass().getResourceAsStream("/eval/sample-eval.jsonl")) {
            List<EvalCase> cases = EvalDatasetParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), objectMapper, 100);
            assertTrue(cases.size() >= 3);
            assertTrue(cases.stream().allMatch(EvalCase::hasExpectedDocs));
        }
    }
}
