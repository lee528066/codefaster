package com.coderfaster.agent.tui.view;

import com.coderfaster.agent.tui.AppController;
import com.coderfaster.agent.tui.AppController.HistoryItem;
import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.RichTextAreaElement;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.tamboui.shaded.toolkit.Toolkit.panel;
import static dev.tamboui.shaded.toolkit.Toolkit.richTextArea;

/**
 * 历史消息视图。
 * 使用 RichTextAreaElement 实现文字自动换行、滚动条和鼠标滚动。
 */
public class HistoryView {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppController ctrl;
    private final RichTextAreaElement historyArea;
    private int lastHistorySize = 0;
    private boolean autoScroll = true;

    public HistoryView(AppController ctrl) {
        this.ctrl = ctrl;
        this.historyArea = richTextArea()
                .wrapWord()
                .id("history-area");
    }

    public Panel render() {
        List<HistoryItem> items = ctrl.history();

        if (items.isEmpty()) {
            historyArea.text(Text.from(
                    Line.from(Span.raw("No messages yet. Type your question below.").dim())
            ));
        } else {
            Text content = buildHistoryText(items);
            historyArea.text(content);
        }

        if (items.size() > lastHistorySize && autoScroll) {
            historyArea.state().scrollToBottom();
        }
        lastHistorySize = items.size();

        return panel("Messages", historyArea.fill())
                .rounded().addClass("history-panel");
    }

    // ========== Scroll control (called by KeyHandler) ==========

    public void scrollUp(int lines) {
        historyArea.state().scrollUp(lines);
        autoScroll = false;
    }

    public void scrollDown(int lines) {
        historyArea.state().scrollDown(lines);
        if (!historyArea.state().canScrollDown()) {
            autoScroll = true;
        }
    }

    public void pageUp() {
        historyArea.state().pageUp();
        autoScroll = false;
    }

    public void pageDown() {
        historyArea.state().pageDown();
        if (!historyArea.state().canScrollDown()) {
            autoScroll = true;
        }
    }

    public void scrollToTop() {
        historyArea.state().scrollToTop();
        autoScroll = false;
    }

    public void scrollToBottom() {
        historyArea.state().scrollToBottom();
        autoScroll = true;
    }

    private Text buildHistoryText(List<HistoryItem> items) {
        List<Line> lines = new ArrayList<>();
        Map<String, ToolCallGroup> pendingTools = new HashMap<>();

        for (HistoryItem item : items) {
            switch (item.type()) {
                case USER_MESSAGE: {
                    String time = formatTime(item);
                    lines.add(Line.from(Span.raw("[You] [" + time + "]").cyan().bold()));
                    addContentLines(lines, item.content(), Color.CYAN);
                    lines.add(Line.empty());
                    break;
                }
                case ASSISTANT_MESSAGE: {
                    String time = formatTime(item);
                    lines.add(Line.from(Span.raw("[Assistant] [" + time + "]").white().bold()));
                    addContentLines(lines, item.content(), Color.WHITE);
                    lines.add(Line.empty());
                    break;
                }
                case TOOL_CALL: {
                    ToolCallGroup group = new ToolCallGroup();
                    group.toolName = item.toolName();
                    group.params = item.content();
                    // 优先使用 callId 作为配对 key，否则回退到 toolName
                    String key = item.callId() != null ? item.callId() : item.toolName();
                    pendingTools.put(key, group);
                    break;
                }
                case TOOL_RESULT: {
                    // 优先使用 callId 作为配对 key，否则回退到 toolName
                    String key = item.callId() != null ? item.callId() : item.toolName();
                    ToolCallGroup group = pendingTools.remove(key);
                    if (group == null) {
                        group = new ToolCallGroup();
                        group.toolName = item.toolName();
                    }
                    group.success = item.toolSuccess();
                    group.result = item.content();
                    addToolCardLines(lines, group);
                    lines.add(Line.empty());
                    break;
                }
                case SYSTEM_MESSAGE: {
                    String time = formatTime(item);
                    lines.add(Line.from(Span.raw("[System] [" + time + "]").blue().bold()));
                    addContentLines(lines, item.content(), Color.BLUE);
                    lines.add(Line.empty());
                    break;
                }
                case ERROR: {
                    String time = formatTime(item);
                    lines.add(Line.from(Span.raw("[Error] [" + time + "]").red().bold()));
                    addContentLines(lines, item.content(), Color.RED);
                    lines.add(Line.empty());
                    break;
                }
            }
        }

        for (ToolCallGroup group : pendingTools.values()) {
            group.result = "Executing...";
            addToolCardLines(lines, group);
            lines.add(Line.empty());
        }

        return Text.from(lines);
    }

    private void addContentLines(List<Line> lines, String content, Color color) {
        if (content == null || content.isEmpty()) return;
        String sanitized = sanitizeForDisplay(content);
        MarkdownRenderer renderer = new MarkdownRenderer(color);
        lines.addAll(renderer.render(sanitized));
    }

    private static final java.util.Map<Integer, String> EMOJI_MAP = new java.util.HashMap<>();
    static {
        // 常见表情
        EMOJI_MAP.put(0x1F600, ":)");   // 😀
        EMOJI_MAP.put(0x1F601, ":D");   // 😁
        EMOJI_MAP.put(0x1F602, "XD");   // 😂
        EMOJI_MAP.put(0x1F603, ":D");   // 😃
        EMOJI_MAP.put(0x1F604, ":D");   // 😄
        EMOJI_MAP.put(0x1F605, ":')");  // 😅
        EMOJI_MAP.put(0x1F606, "XD");   // 😆
        EMOJI_MAP.put(0x1F609, ";)");   // 😉
        EMOJI_MAP.put(0x1F60A, ":)");   // 😊
        EMOJI_MAP.put(0x1F60E, "B)");   // 😎
        EMOJI_MAP.put(0x1F60D, "<3");   // 😍
        EMOJI_MAP.put(0x1F614, ":(");   // 😔
        EMOJI_MAP.put(0x1F622, ":'(");  // 😢
        EMOJI_MAP.put(0x1F62D, "T_T");  // 😭
        EMOJI_MAP.put(0x1F631, "D:");   // 😱
        EMOJI_MAP.put(0x1F642, ":)");   // 🙂
        EMOJI_MAP.put(0x1F643, "(:)");  // 🙃
        EMOJI_MAP.put(0x1F914, "?");    // 🤔
        EMOJI_MAP.put(0x1F917, ":)");   // 🤗
        EMOJI_MAP.put(0x1F92F, "!!");   // 🤯
        // 手势
        EMOJI_MAP.put(0x1F44D, "+1");   // 👍
        EMOJI_MAP.put(0x1F44E, "-1");   // 👎
        EMOJI_MAP.put(0x1F44B, "Hi");   // 👋
        EMOJI_MAP.put(0x1F44F, "*");    // 👏
        EMOJI_MAP.put(0x1F64F, "*");    // 🙏
        EMOJI_MAP.put(0x270B, "*");     // ✋
        // 符号
        EMOJI_MAP.put(0x2705, "[v]");   // ✅
        EMOJI_MAP.put(0x274C, "[x]");   // ❌
        EMOJI_MAP.put(0x2757, "!");     // ❗
        EMOJI_MAP.put(0x2753, "?");     // ❓
        EMOJI_MAP.put(0x26A0, "!");     // ⚠️
        EMOJI_MAP.put(0x1F4A1, "*");    // 💡
        EMOJI_MAP.put(0x1F525, "*");    // 🔥
        EMOJI_MAP.put(0x2B50, "*");     // ⭐
        EMOJI_MAP.put(0x1F31F, "*");    // 🌟
        EMOJI_MAP.put(0x2728, "*");     // ✨
        EMOJI_MAP.put(0x1F389, "*");    // 🎉
        EMOJI_MAP.put(0x1F38A, "*");    // 🎊
        // 物品/工具
        EMOJI_MAP.put(0x1F4DD, "#");    // 📝
        EMOJI_MAP.put(0x1F4D6, "#");    // 📖
        EMOJI_MAP.put(0x1F4DA, "#");    // 📚
        EMOJI_MAP.put(0x1F4BB, "[PC]"); // 💻
        EMOJI_MAP.put(0x1F5A5, "[PC]"); // 🖥
        EMOJI_MAP.put(0x2699, "[*]");   // ⚙️
        EMOJI_MAP.put(0x1F527, "[*]");  // 🔧
        EMOJI_MAP.put(0x1F528, "[*]");  // 🔨
        EMOJI_MAP.put(0x1F529, "[*]");  // 🔩
        EMOJI_MAP.put(0x1F6E0, "[*]");  // 🛠
        EMOJI_MAP.put(0x1F50D, "[?]");  // 🔍
        EMOJI_MAP.put(0x1F50E, "[?]");  // 🔎
        EMOJI_MAP.put(0x1F4E6, "[+]");  // 📦
        EMOJI_MAP.put(0x1F4C1, "[D]");  // 📁
        EMOJI_MAP.put(0x1F4C2, "[D]");  // 📂
        EMOJI_MAP.put(0x1F4C4, "[F]");  // 📄
        // 箭头
        EMOJI_MAP.put(0x27A1, "->");    // ➡️
        EMOJI_MAP.put(0x2B05, "<-");    // ⬅️
        EMOJI_MAP.put(0x2B06, "^");     // ⬆️
        EMOJI_MAP.put(0x2B07, "v");     // ⬇️
        EMOJI_MAP.put(0x1F449, "->");   // 👉
        EMOJI_MAP.put(0x1F448, "<-");   // 👈
        // 数字/标记
        EMOJI_MAP.put(0x1F4AF, "100");  // 💯
        EMOJI_MAP.put(0x2139, "(i)");   // ℹ️
        // 时间相关
        EMOJI_MAP.put(0x23F0, "[T]");   // ⏰
        EMOJI_MAP.put(0x231A, "[T]");   // ⌚
        EMOJI_MAP.put(0x23F3, "[~]");   // ⏳
        EMOJI_MAP.put(0x231B, "[~]");   // ⌛
        // 通信
        EMOJI_MAP.put(0x1F4E7, "[@]");  // 📧
        EMOJI_MAP.put(0x1F4AC, "[>]");  // 💬
        EMOJI_MAP.put(0x1F4AD, "[.]");  // 💭
        // 心形
        EMOJI_MAP.put(0x2764, "<3");    // ❤️
        EMOJI_MAP.put(0x1F499, "<3");   // 💙
        EMOJI_MAP.put(0x1F49A, "<3");   // 💚
        EMOJI_MAP.put(0x1F49B, "<3");   // 💛
        EMOJI_MAP.put(0x1F49C, "<3");   // 💜
        // 警告/状态
        EMOJI_MAP.put(0x1F6A7, "[!]");  // 🚧
        EMOJI_MAP.put(0x1F512, "[L]");  // 🔒
        EMOJI_MAP.put(0x1F513, "[U]");  // 🔓
        EMOJI_MAP.put(0x1F680, "[>]");  // 🚀
    }

    /**
     * 清理文本中可能导致 TamboUI 渲染问题的特殊 Unicode 字符。
     * 将 emoji 转换为 ASCII 符号以保证兼容性。
     */
    private static String sanitizeForDisplay(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                // 处理 surrogate pair (emoji 等 4 字节字符)
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, text.charAt(i + 1));
                    String mapped = EMOJI_MAP.get(codePoint);
                    if (mapped != null) {
                        sb.append(mapped);
                    } else {
                        sb.append("*"); // 未知 emoji 用 * 代替
                    }
                    i++; // 跳过 low surrogate
                } else {
                    sb.append('?');
                }
            } else if (c > 0x7F && c < 0x100) {
                // Latin-1 Supplement (0x80-0xFF) - 保留常见字符
                sb.append(c);
            } else if (c >= 0x2000 && c <= 0x2FFF) {
                // 通用标点、符号等
                sb.append(mapSymbol(c));
            } else if (c >= 0x3000 && c <= 0x9FFF) {
                // CJK 字符 (中日韩) - 保留
                sb.append(c);
            } else if (c >= 0xFF00 && c <= 0xFFEF) {
                // 全角字符 - 转换为半角
                if (c >= 0xFF01 && c <= 0xFF5E) {
                    sb.append((char) (c - 0xFEE0));
                } else {
                    sb.append(c);
                }
            } else if (c < 0x80 || Character.isLetterOrDigit(c) || Character.isWhitespace(c)) {
                // ASCII 和常规字符
                sb.append(c);
            } else {
                // 其他特殊字符 - 保留
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static char mapSymbol(char c) {
        switch (c) {
            case '\u2713': return 'v';  // ✓
            case '\u2714': return 'v';  // ✔
            case '\u2717': return 'x';  // ✗
            case '\u2718': return 'x';  // ✘
            case '\u2022': return '*';  // •
            case '\u2026': return '.';  // …
            case '\u2018':
            case '\u2019': return '\''; // ' '
            case '\u201C':
            case '\u201D': return '"';  // " "
            case '\u2014':
            case '\u2013': return '-';  // — –
            case '\u2028': // Line Separator
            case '\u2029': return '\n'; // Paragraph Separator
            default: return c;
        }
    }

    private void addToolCardLines(List<Line> lines, ToolCallGroup group) {
        Color color;
        String icon;
        if (group.success == null) {
            icon = "*";
            color = Color.YELLOW;
        } else if (group.success) {
            icon = "v";
            color = Color.GREEN;
        } else {
            icon = "x";
            color = Color.RED;
        }

        String toolName = group.toolName != null ? group.toolName : "unknown_tool";
        lines.add(Line.from(
                Span.raw(icon + " ").fg(color),
                Span.raw(toolName).fg(color).bold()
        ));

        if (group.params != null && !group.params.isEmpty()) {
            String preview = preview(group.params, 80);
            lines.add(Line.from(Span.raw("  Params: " + preview).fg(color).dim()));
        }
        if (group.result != null && !group.result.isEmpty()) {
            String preview = preview(group.result, 80);
            lines.add(Line.from(Span.raw("  Result: " + preview).fg(color).dim()));
        }
    }

    private String formatTime(HistoryItem item) {
        return TIME_FMT.format(item.timestamp().atZone(ZoneId.systemDefault()));
    }

    private static String preview(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        String clean = sanitizeForDisplay(text.replaceAll("\\s+", " ").trim());
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "..." : clean;
    }

    private static class ToolCallGroup {
        String toolName;
        String params;
        String result;
        Boolean success;
    }
}
