package com.yizhaoqi.smartpai.service.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合规文档条款切分：按行首的 第X章 / 第X节 / 第X条 / 第X款 标记切分原文。
 * <p>
 * 每个片段带上条款路径（articlePath，如 "第三章 风险管理 > 第十二条"）和条款号
 * （clauseNo，如 "第十二条"；出现款标记时带上款号，如 "第十二条第二款"）。
 * 没有识别到任何标记时返回一个元数据为空的片段，交由上层按原逻辑继续分块。
 * <p>
 * 流式解析和 PDF 逐页解析必须使用 {@link #newSession()} 返回的有状态会话：
 * 章/节/条/款状态和未完成的一行会跨缓冲区保留，长条款被父块截断后仍能继承所属条款。
 */
public class ComplianceClauseChunker {

    private static final String NUMERALS = "[0-9０-９一二三四五六七八九十百千万亿零〇两]+";
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("^(第" + NUMERALS + "章)(.*)$");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^(第" + NUMERALS + "节)(.*)$");
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("^(第" + NUMERALS + "条)\\s*(第" + NUMERALS + "款)?(.*)$");
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("^(第" + NUMERALS + "款)(.*)$");

    /**
     * 新建一次解析会话；流式解析应在文档开始时创建、跨父块/跨页复用，文档结束时调用 {@link Session#finish()}。
     */
    public Session newSession() {
        return new Session();
    }

    /**
     * 无状态切分，等价于“新建会话 → 切分 → 收尾”，适用于一次性传入完整文本。
     */
    public List<ClauseUnit> split(String text) {
        Session session = new Session();
        List<ClauseUnit> units = new ArrayList<>(session.splitComplete(text));
        units.addAll(session.finish());
        return units;
    }

    /**
     * 切分后立即执行长片段拆分，所有子片段继承父片段的条款路径和条款号。
     *
     * @param longUnitSplitter 上层已有的按大小/语义拆分逻辑；返回空列表时保留原片段
     */
    public List<ClauseUnit> splitWith(String text, Function<String, List<String>> longUnitSplitter) {
        Session session = new Session();
        List<ClauseUnit> units = new ArrayList<>(splitLongUnits(session.splitComplete(text), longUnitSplitter));
        units.addAll(splitLongUnits(session.finish(), longUnitSplitter));
        return units;
    }

    /**
     * 有状态条款解析会话。
     */
    public static final class Session {

        private String chapter;
        private String section;
        private String article;
        private String clause;
        private final StringBuilder prefix = new StringBuilder();
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder lineCarry = new StringBuilder();

        private Session() {
        }

        /**
         * 处理一段完整文本（最后一行也算完成），用于一次性切分。
         */
        private List<ClauseUnit> splitComplete(String text) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            List<ClauseUnit> units = new ArrayList<>();
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            String combined = lineCarry + normalized;
            lineCarry.setLength(0);
            for (String rawLine : combined.split("\n", -1)) {
                processLine(rawLine.strip(), units);
            }
            flush(units);
            return units;
        }

        /**
         * 处理一个缓冲区/一页文本，返回本次能完整切出的条款单元；未完成的一行留到下一次调用。
         */
        public List<ClauseUnit> split(String text) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }

            List<ClauseUnit> units = new ArrayList<>();
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            String combined = lineCarry + normalized;
            lineCarry.setLength(0);

            String complete;
            if (combined.endsWith("\n")) {
                complete = combined;
            } else {
                int lastNewline = combined.lastIndexOf('\n');
                if (lastNewline < 0) {
                    lineCarry.append(combined);
                    complete = "";
                } else {
                    complete = combined.substring(0, lastNewline + 1);
                    lineCarry.append(combined.substring(lastNewline + 1));
                }
            }

            if (!complete.isEmpty()) {
                for (String rawLine : complete.split("\n", -1)) {
                    processLine(rawLine.strip(), units);
                }
            }
            flush(units);
            return units;
        }

        /**
         * 与 {@link #split(String)} 相同，并对本次产出的片段执行长片段拆分。
         */
        public List<ClauseUnit> splitWith(String text, Function<String, List<String>> longUnitSplitter) {
            return splitLongUnits(split(text), longUnitSplitter);
        }

        /**
         * 页面结束时调用：把本页最后一行按“完整行”处理并冲刷，避免下一页首行被拼进本页条款；
         * 不输出悬挂的章/节标题，也不重置章/节/条状态，下一页仍然继承所属条款。
         */
        public List<ClauseUnit> pageBreak() {
            List<ClauseUnit> units = new ArrayList<>();
            if (lineCarry.length() > 0) {
                processLine(lineCarry.toString().strip(), units);
                lineCarry.setLength(0);
            }
            flush(units);
            return units;
        }

        public List<ClauseUnit> pageBreakWith(Function<String, List<String>> longUnitSplitter) {
            return splitLongUnits(pageBreak(), longUnitSplitter);
        }

        /**
         * 文档结束时调用，并在收尾片段上执行长片段拆分；流式尾部同样必须遵守 chunkSize。
         */
        public List<ClauseUnit> finishWith(Function<String, List<String>> longUnitSplitter) {
            return splitLongUnits(finish(), longUnitSplitter);
        }

        /**
         * 文档结束时调用：处理最后一行、冲刷缓冲，并在只有章/节标题时保留标题片段。
         */
        public List<ClauseUnit> finish() {
            List<ClauseUnit> units = new ArrayList<>();
            if (lineCarry.length() > 0) {
                processLine(lineCarry.toString().strip(), units);
                lineCarry.setLength(0);
            }
            flush(units);
            if (!prefix.isEmpty()) {
                String heading = prefix.toString().strip();
                prefix.setLength(0);
                if (!heading.isEmpty()) {
                    units.add(new ClauseUnit(heading,
                            buildArticlePath(chapter, section, article),
                            buildClauseNo(article, clause)));
                }
            }
            return units;
        }

        private void processLine(String line, List<ClauseUnit> units) {
            if (line.isEmpty()) {
                // 章/节标题与条正文之间的空行不算正文，不能让它提前消费章/节前缀
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                return;
            }

            Matcher chapterMatcher = CHAPTER_PATTERN.matcher(line);
            if (chapterMatcher.matches()) {
                flush(units);
                chapter = normalizeHeading(chapterMatcher.group(1), chapterMatcher.group(2));
                section = null;
                article = null;
                clause = null;
                prefix.setLength(0);
                prefix.append(chapter);
                return;
            }

            Matcher sectionMatcher = SECTION_PATTERN.matcher(line);
            if (sectionMatcher.matches()) {
                flush(units);
                section = normalizeHeading(sectionMatcher.group(1), sectionMatcher.group(2));
                article = null;
                clause = null;
                prefix.setLength(0);
                if (chapter != null) {
                    prefix.append(chapter).append('\n');
                }
                prefix.append(section);
                return;
            }

            Matcher articleMatcher = ARTICLE_PATTERN.matcher(line);
            if (articleMatcher.matches()) {
                flush(units);
                article = articleMatcher.group(1);
                clause = articleMatcher.group(2);
                appendPrefix(buffer, prefix);
                buffer.append(line).append('\n');
                return;
            }

            Matcher clauseMatcher = CLAUSE_PATTERN.matcher(line);
            if (clauseMatcher.matches()) {
                flush(units);
                clause = clauseMatcher.group(1);
                appendPrefix(buffer, prefix);
                buffer.append(line).append('\n');
                return;
            }

            appendPrefix(buffer, prefix);
            buffer.append(line).append('\n');
        }

        private void appendPrefix(StringBuilder buffer, StringBuilder prefix) {
            if (prefix.isEmpty()) {
                return;
            }
            buffer.append(prefix).append('\n');
            prefix.setLength(0);
        }

        private void flush(List<ClauseUnit> units) {
            String content = buffer.toString().strip();
            if (!content.isEmpty()) {
                units.add(new ClauseUnit(content, buildArticlePath(chapter, section, article),
                        buildClauseNo(article, clause)));
            }
            buffer.setLength(0);
        }
    }

    private static List<ClauseUnit> splitLongUnits(List<ClauseUnit> units,
                                                   Function<String, List<String>> longUnitSplitter) {
        if (longUnitSplitter == null) {
            return units;
        }
        List<ClauseUnit> result = new ArrayList<>();
        for (ClauseUnit unit : units) {
            List<String> pieces = longUnitSplitter.apply(unit.text());
            if (pieces == null || pieces.isEmpty()) {
                result.add(unit);
                continue;
            }
            for (String piece : pieces) {
                if (piece != null && !piece.isBlank()) {
                    result.add(new ClauseUnit(piece, unit.articlePath(), unit.clauseNo()));
                }
            }
        }
        return result;
    }

    private static String buildArticlePath(String chapter, String section, String article) {
        List<String> parts = new ArrayList<>(3);
        if (chapter != null) {
            parts.add(chapter);
        }
        if (section != null) {
            parts.add(section);
        }
        if (article != null) {
            parts.add(article);
        }
        return parts.isEmpty() ? null : String.join(" > ", parts);
    }

    private static String buildClauseNo(String article, String clause) {
        if (article == null) {
            return clause;
        }
        return clause == null ? article : article + clause;
    }

    private static String normalizeHeading(String marker, String rest) {
        String title = rest == null ? "" : rest.strip().replaceAll("\\s+", " ");
        title = title.replaceFirst("^[、.．:：\\s]+", "");
        return title.isEmpty() ? marker : marker + " " + title;
    }

    /**
     * 一个条款切分单元：text 是原文片段，articlePath/clauseNo 可能为空（无标记文档）。
     */
    public record ClauseUnit(String text, String articlePath, String clauseNo) {
    }
}
