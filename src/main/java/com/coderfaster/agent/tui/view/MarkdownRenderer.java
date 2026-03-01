package com.coderfaster.agent.tui.view;

import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简易 Markdown 渲染器，将 Markdown 文本转换为带样式的 TamboUI Line 列表。
 * 支持的语法:
 * - 标题: # H1, ## H2, ### H3
 * - 加粗: **text** 或 __text__
 * - 斜体: *text* 或 _text_ (在终端中用 dim 表示)
 * - 行内代码: `code`
 * - 列表: - item 或 * item 或 1. item
 * - 分隔线: --- 或 ***
 * - 引用: > text
 */
public class MarkdownRenderer {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)[-*]\\s+(.*)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^>\\s*(.*)$");
    private static final Pattern HORIZONTAL_RULE_PATTERN = Pattern.compile("^(---+|\\*\\*\\*+|___+)\\s*$");

    private final Color baseColor;

    public MarkdownRenderer(Color baseColor) {
        this.baseColor = baseColor;
    }

    /**
     * 渲染 Markdown 文本为带样式的 Line 列表
     */
    public List<Line> render(String markdown) {
        List<Line> lines = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return lines;
        }

        String[] rawLines = markdown.split("\n", -1);
        boolean inCodeBlock = false;

        for (String rawLine : rawLines) {
            if (rawLine.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (inCodeBlock) {
                    String lang = rawLine.substring(3).trim();
                    if (!lang.isEmpty()) {
                        lines.add(Line.from(Span.raw("  ╭─ " + lang + " ").fg(Color.DARK_GRAY)));
                    } else {
                        lines.add(Line.from(Span.raw("  ╭───────────").fg(Color.DARK_GRAY)));
                    }
                } else {
                    lines.add(Line.from(Span.raw("  ╰───────────").fg(Color.DARK_GRAY)));
                }
                continue;
            }

            if (inCodeBlock) {
                lines.add(Line.from(
                        Span.raw("  │ ").fg(Color.DARK_GRAY),
                        Span.raw(rawLine).fg(Color.YELLOW)
                ));
                continue;
            }

            lines.add(renderLine(rawLine));
        }

        return lines;
    }

    private Line renderLine(String rawLine) {
        String line = rawLine;

        Matcher hrMatcher = HORIZONTAL_RULE_PATTERN.matcher(line);
        if (hrMatcher.matches()) {
            return Line.from(Span.raw("  ────────────────────────────────").fg(Color.DARK_GRAY));
        }

        Matcher headerMatcher = HEADER_PATTERN.matcher(line);
        if (headerMatcher.matches()) {
            int level = headerMatcher.group(1).length();
            String content = headerMatcher.group(2);
            return renderHeader(level, content);
        }

        Matcher blockquoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
        if (blockquoteMatcher.matches()) {
            String content = blockquoteMatcher.group(1);
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw("  │ ").fg(Color.BLUE));
            spans.addAll(parseInlineStyles(content, Color.BLUE));
            return Line.from(spans);
        }

        Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(line);
        if (ulMatcher.matches()) {
            String indent = ulMatcher.group(1);
            String content = ulMatcher.group(2);
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw("  " + indent + "• ").fg(baseColor));
            spans.addAll(parseInlineStyles(content, baseColor));
            return Line.from(spans);
        }

        Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);
        if (olMatcher.matches()) {
            String indent = olMatcher.group(1);
            String number = olMatcher.group(2);
            String content = olMatcher.group(3);
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw("  " + indent + number + ". ").fg(baseColor));
            spans.addAll(parseInlineStyles(content, baseColor));
            return Line.from(spans);
        }

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("  ").fg(baseColor));
        spans.addAll(parseInlineStyles(line, baseColor));
        return Line.from(spans);
    }

    private Line renderHeader(int level, String content) {
        String prefix;
        Color color;
        boolean bold = true;

        switch (level) {
            case 1:
                prefix = "  ▌ ";
                color = Color.CYAN;
                break;
            case 2:
                prefix = "  ▎ ";
                color = Color.GREEN;
                break;
            case 3:
                prefix = "  ▏ ";
                color = Color.YELLOW;
                break;
            default:
                prefix = "    ";
                color = baseColor;
                bold = false;
                break;
        }

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(prefix).fg(color));

        List<Span> contentSpans = parseInlineStyles(content, color);
        for (Span span : contentSpans) {
            if (bold) {
                spans.add(span.bold());
            } else {
                spans.add(span);
            }
        }

        return Line.from(spans);
    }

    /**
     * 解析行内样式: **bold**, *italic*, `code`
     */
    private List<Span> parseInlineStyles(String text, Color color) {
        List<Span> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }

        StringBuilder current = new StringBuilder();
        int i = 0;

        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (current.length() > 0) {
                    spans.add(Span.raw(current.toString()).fg(color));
                    current = new StringBuilder();
                }
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    String boldText = text.substring(i + 2, end);
                    spans.add(Span.raw(boldText).fg(color).bold());
                    i = end + 2;
                    continue;
                }
            }

            if (i + 1 < text.length() && text.charAt(i) == '_' && text.charAt(i + 1) == '_') {
                if (current.length() > 0) {
                    spans.add(Span.raw(current.toString()).fg(color));
                    current = new StringBuilder();
                }
                int end = text.indexOf("__", i + 2);
                if (end > i + 2) {
                    String boldText = text.substring(i + 2, end);
                    spans.add(Span.raw(boldText).fg(color).bold());
                    i = end + 2;
                    continue;
                }
            }

            if (text.charAt(i) == '*' && (i == 0 || text.charAt(i - 1) != '*') 
                    && (i + 1 >= text.length() || text.charAt(i + 1) != '*')) {
                if (current.length() > 0) {
                    spans.add(Span.raw(current.toString()).fg(color));
                    current = new StringBuilder();
                }
                int end = findClosingDelimiter(text, i + 1, '*');
                if (end > i + 1) {
                    String italicText = text.substring(i + 1, end);
                    spans.add(Span.raw(italicText).fg(color).dim());
                    i = end + 1;
                    continue;
                }
            }

            if (text.charAt(i) == '_' && (i == 0 || text.charAt(i - 1) != '_')
                    && (i + 1 >= text.length() || text.charAt(i + 1) != '_')) {
                if (!isPartOfWord(text, i)) {
                    if (current.length() > 0) {
                        spans.add(Span.raw(current.toString()).fg(color));
                        current = new StringBuilder();
                    }
                    int end = findClosingDelimiter(text, i + 1, '_');
                    if (end > i + 1 && !isPartOfWord(text, end)) {
                        String italicText = text.substring(i + 1, end);
                        spans.add(Span.raw(italicText).fg(color).dim());
                        i = end + 1;
                        continue;
                    }
                }
            }

            if (text.charAt(i) == '`') {
                if (current.length() > 0) {
                    spans.add(Span.raw(current.toString()).fg(color));
                    current = new StringBuilder();
                }
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    String codeText = text.substring(i + 1, end);
                    spans.add(Span.raw(codeText).fg(Color.YELLOW).bg(Color.DARK_GRAY));
                    i = end + 1;
                    continue;
                }
            }

            current.append(text.charAt(i));
            i++;
        }

        if (current.length() > 0) {
            spans.add(Span.raw(current.toString()).fg(color));
        }

        return spans;
    }

    private int findClosingDelimiter(String text, int start, char delimiter) {
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == delimiter) {
                if (delimiter == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    continue;
                }
                if (delimiter == '_' && i + 1 < text.length() && text.charAt(i + 1) == '_') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private boolean isPartOfWord(String text, int pos) {
        boolean prevIsWord = pos > 0 && Character.isLetterOrDigit(text.charAt(pos - 1));
        boolean nextIsWord = pos + 1 < text.length() && Character.isLetterOrDigit(text.charAt(pos + 1));
        return prevIsWord || nextIsWord;
    }
}
