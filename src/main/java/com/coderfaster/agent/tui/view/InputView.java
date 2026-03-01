package com.coderfaster.agent.tui.view;

import com.coderfaster.agent.tui.AppController;
import com.coderfaster.agent.tui.AppController.ConfirmDialogData;
import com.coderfaster.agent.tui.command.CommandRegistry;
import com.coderfaster.agent.tui.command.SlashCommand;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.elements.TextInputElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.spinner.SpinnerState;

import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.shaded.toolkit.Toolkit.panel;
import static dev.tamboui.shaded.toolkit.Toolkit.row;
import static dev.tamboui.shaded.toolkit.Toolkit.spinner;
import static dev.tamboui.shaded.toolkit.Toolkit.text;
import static dev.tamboui.shaded.toolkit.Toolkit.textInput;

/**
 * 输入框视图。
 * 包含文本输入和斜杠命令自动补全列表。
 */
public class InputView {

    private static final int BASE_HEIGHT = 5;

    private final AppController ctrl;
    private final TextInputState inputState = new TextInputState();
    private final SpinnerState spinnerState = new SpinnerState();

    private List<SlashCommand> suggestions;
    private int selectedSuggestion = -1;
    private boolean showSuggestions = false;

    public InputView(AppController ctrl) {
        this.ctrl = ctrl;
    }

    /**
     * 返回当前输入视图需要的高度。
     * 当显示建议列表时，高度会增加。
     * 当处于确认状态时，高度会根据确认信息动态调整。
     */
    public int getRequiredHeight() {
        if (ctrl.sessionState() == AppController.SessionState.CONFIRMING) {
            ConfirmDialogData data = ctrl.confirmDialogData();
            if (data != null && data.toolParams() != null) {
                int paramLines = Math.min(data.toolParams().split("\n").length, 6);
                return BASE_HEIGHT + paramLines + 4;
            }
            return BASE_HEIGHT + 5;
        }
        if (showSuggestions && suggestions != null && !suggestions.isEmpty()) {
            int suggestionListHeight = suggestions.size() + 3;
            return BASE_HEIGHT + suggestionListHeight;
        }
        return BASE_HEIGHT;
    }

    public Panel render() {
        // 确认状态下显示确认信息面板
        if (ctrl.sessionState() == AppController.SessionState.CONFIRMING) {
            return renderConfirmPanel();
        }

        boolean idle = ctrl.sessionState() == AppController.SessionState.IDLE;

        // 在每次渲染时检查是否需要更新建议列表
        updateSuggestionsIfNeeded();

        Element statusIndicator = buildStatusIndicator();

        TextElement prompt = text("\u276F ").bold().cyan().fit();
        TextInputElement input = textInput(inputState)
                .placeholder(idle ? "Type message or / for commands... (Enter to send)" : "")
                .id("main-input")
                .fill()
                .onSubmit(this::onSubmit);

        Row inputRow = row(prompt, input);

        TextElement shortcuts = idle
                ? text("Enter:Send | /:Commands | Esc:Cancel | Ctrl+C:Stop").dim()
                : text("Ctrl+C:Cancel task").dim();

        List<Element> children = new ArrayList<>();

        if (showSuggestions && suggestions != null && !suggestions.isEmpty()) {
            children.add(buildSuggestionList());
        }

        if (statusIndicator != null) {
            children.add(row(inputRow.fill(), ((StyledElement<?>) statusIndicator).fit()));
        } else {
            children.add(inputRow);
        }

        children.add(shortcuts);

        Panel inputPanel = panel("Input", children.toArray(new Element[0]));

        return inputPanel
                .rounded()
                .id("input-panel")
                .addClass("input-panel")
                .onKeyEvent(this::handleKey);
    }

    private Panel renderConfirmPanel() {
        ConfirmDialogData data = ctrl.confirmDialogData();
        List<Element> content = new ArrayList<>();

        content.add(text("⚠ Agent wants to execute a potentially dangerous operation:").bold().fg(Color.YELLOW));
        content.add(text(""));

        if (data != null) {
            content.add(row(
                    text("  Tool: ").fit(),
                    text(data.toolName()).bold().fg(Color.CYAN).fit()
            ));

            if (data.toolParams() != null && !data.toolParams().isEmpty()) {
                content.add(text("  Parameters:").dim());
                String[] lines = data.toolParams().split("\n");
                int maxLines = Math.min(lines.length, 6);
                for (int i = 0; i < maxLines; i++) {
                    String line = lines[i];
                    String display = line.length() > 70 ? line.substring(0, 67) + "..." : line;
                    content.add(text("    " + display).dim());
                }
                if (lines.length > maxLines) {
                    content.add(text("    ... (" + (lines.length - maxLines) + " more lines)").dim());
                }
            }
        }

        content.add(text(""));
        content.add(row(
                text("Press ").fit(),
                text("[Y]").bold().fg(Color.GREEN).fit(),
                text(" to confirm, ").fit(),
                text("[N]").bold().fg(Color.RED).fit(),
                text(" or ").fit(),
                text("[Esc]").bold().fit(),
                text(" to cancel").fit()
        ));

        return panel("Confirm", content.toArray(new Element[0]))
                .rounded()
                .borderColor(Color.YELLOW)
                .id("input-panel")
                .addClass("input-panel", "confirm-inline")
                .onKeyEvent(this::handleConfirmKey);
    }

    private EventResult handleConfirmKey(KeyEvent event) {
        if (event.code() == KeyCode.CHAR) {
            char c = Character.toLowerCase(event.character());
            if (c == 'y') {
                ctrl.confirmAction(true);
                return EventResult.HANDLED;
            } else if (c == 'n') {
                ctrl.confirmAction(false);
                return EventResult.HANDLED;
            }
        }
        if (event.isCancel()) {
            ctrl.confirmAction(false);
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private Element buildSuggestionList() {
        List<Element> items = new ArrayList<>();

        for (int i = 0; i < suggestions.size(); i++) {
            SlashCommand cmd = suggestions.get(i);
            String name = "/" + cmd.getName();
            String desc = cmd.getDescription() != null ? cmd.getDescription() : "";

            if (i == selectedSuggestion) {
                items.add(row(
                        text(" \u25B6 " + name).bold().fg(Color.YELLOW).fit(),
                        text("  " + desc).fg(Color.YELLOW).dim().fit()
                ).bg(Color.DARK_GRAY));
            } else {
                items.add(row(
                        text("   " + name).fg(Color.CYAN).fit(),
                        text("  " + desc).dim().fit()
                ));
            }
        }

        items.add(text(" Tab:Select | Enter:Execute | Esc:Close").dim());

        int listHeight = items.size() + 2;
        return panel(items.toArray(new Element[0]))
                .borderColor(Color.CYAN)
                .rounded()
                .length(listHeight)
                .addClass("suggestion-panel");
    }

    private void executeSelectedCommand() {
        if (selectedSuggestion >= 0 && selectedSuggestion < suggestions.size()) {
            SlashCommand cmd = suggestions.get(selectedSuggestion);
            String commandText = "/" + cmd.getName();
            hideSuggestions();
            
            // 如果命令需要用户输入参数，只填充命令到输入框，不执行
            if (cmd.isRequiresInput()) {
                inputState.clear();
                inputState.insert(commandText + " ");
                // 重置 lastInputText 以避免触发建议列表
                lastInputText = inputState.text();
            } else {
                // 直接提交命令，不经过输入框
                ctrl.submitInput(commandText);
                inputState.clear();
            }
        }
    }

    private void onSubmit() {
        // 如果正在显示建议列表，Enter 键应该执行选中的命令
        if (showSuggestions && suggestions != null && !suggestions.isEmpty() && selectedSuggestion >= 0) {
            executeSelectedCommand();
            return;
        }

        String text = inputState.text();
        if (text.isEmpty()) return;

        hideSuggestions();
        ctrl.submitInput(text);
        inputState.clear();
    }

    private EventResult handleKey(KeyEvent event) {
        if (showSuggestions && suggestions != null && !suggestions.isEmpty()) {
            EventResult result = handleSuggestionKey(event);
            if (result == EventResult.HANDLED) return result;
        }

        if (event.isCancel()) {
            if (showSuggestions) {
                hideSuggestions();
                return EventResult.HANDLED;
            }
            if (!inputState.text().isEmpty()) {
                inputState.clear();
                hideSuggestions();
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        if (event.code() == KeyCode.TAB) {
            String text = inputState.text();
            if (text.startsWith("/")) {
                CommandRegistry registry = ctrl.commandRegistry();
                String prefix = text.length() > 1 ? text.substring(1) : "";
                suggestions = registry.getSuggestions(prefix);
                if (!suggestions.isEmpty()) {
                    showSuggestions = true;
                    selectedSuggestion = 0;
                }
                return EventResult.HANDLED;
            }
        }

        // 对于普通字符输入和删除操作，返回 UNHANDLED 让输入框处理
        // 但不在这里更新建议，而是在 render 方法中检查并更新
        return EventResult.UNHANDLED;
    }

    private EventResult handleSuggestionKey(KeyEvent event) {
        if (event.isUp()) {
            selectedSuggestion = selectedSuggestion <= 0
                    ? suggestions.size() - 1
                    : selectedSuggestion - 1;
            return EventResult.HANDLED;
        }
        if (event.isDown()) {
            selectedSuggestion = selectedSuggestion < suggestions.size() - 1
                    ? selectedSuggestion + 1
                    : 0;
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.TAB) {
            if (selectedSuggestion >= 0 && selectedSuggestion < suggestions.size()) {
                SlashCommand cmd = suggestions.get(selectedSuggestion);
                inputState.clear();
                // 如果命令需要输入参数，在后面加空格方便用户继续输入
                String suffix = cmd.isRequiresInput() ? " " : "";
                inputState.insert("/" + cmd.getName() + suffix);
                // 更新 lastInputText 以避免立即触发建议列表
                lastInputText = inputState.text();
                hideSuggestions();
            }
            return EventResult.HANDLED;
        }
        if (event.isSelect() || event.code() == KeyCode.ENTER) {
            executeSelectedCommand();
            return EventResult.HANDLED;
        }
        if (event.isCancel()) {
            hideSuggestions();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private String lastInputText = "";

    private void updateSuggestionsIfNeeded() {
        String currentText = inputState.text();
        // 只在输入内容变化时更新建议
        if (!currentText.equals(lastInputText)) {
            lastInputText = currentText;
            updateSuggestionsAfterInput();
        }
    }

    private void updateSuggestionsAfterInput() {
        String text = inputState.text();
        if (text.startsWith("/")) {
            CommandRegistry registry = ctrl.commandRegistry();
            String prefix = text.length() > 1 ? text.substring(1) : "";
            suggestions = registry.getSuggestions(prefix);
            showSuggestions = !suggestions.isEmpty();
            selectedSuggestion = showSuggestions ? 0 : -1;
        } else {
            hideSuggestions();
        }
    }

    private void hideSuggestions() {
        showSuggestions = false;
        suggestions = null;
        selectedSuggestion = -1;
    }

    private Element buildStatusIndicator() {
        switch (ctrl.sessionState()) {
            case THINKING:
                return row(
                        spinner().state(spinnerState).fit(),
                        text(" Thinking").fg(Color.YELLOW).bold().fit()
                );
            case EXECUTING:
                return row(
                        spinner().state(spinnerState).fit(),
                        text(" Running").fg(Color.YELLOW).bold().fit()
                );
            default:
                return null;
        }
    }
}
