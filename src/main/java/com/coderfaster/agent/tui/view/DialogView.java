package com.coderfaster.agent.tui.view;

import com.coderfaster.agent.tui.AppController;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Panel;

import static dev.tamboui.shaded.toolkit.Toolkit.panel;
import static dev.tamboui.shaded.toolkit.Toolkit.text;

/**
 * 对话框视图。
 * 渲染帮助对话框。确认流程现在通过 InputView 内联处理。
 */
public class DialogView {

    private final AppController ctrl;

    public DialogView(AppController ctrl) {
        this.ctrl = ctrl;
    }

    /**
     * 返回对话框 Panel，没有对话框时返回 null。
     */
    public Panel render() {
        switch (ctrl.dialogMode()) {
            case HELP:
                return renderHelpDialog();
            case NONE:
            default:
                return null;
        }
    }

    private Panel renderHelpDialog() {
        return panel("Help",
                text("Slash Commands (type / to start):").bold(),
                text(""),
                text("  /help, /h, /?     Show this help").fg(Color.CYAN),
                text("  /clear, /cls, /c  Clear chat history").fg(Color.CYAN),
                text("  /quit, /exit, /q  Quit application").fg(Color.CYAN),
                text("  /cancel, /stop    Cancel current task").fg(Color.CYAN),
                text("  /refresh, /r      Refresh the screen").fg(Color.CYAN),
                text(""),
                text("Keyboard Shortcuts:").bold(),
                text(""),
                text("  Enter         Send message / Execute command"),
                text("  Ctrl+C        Cancel current task"),
                text("  Ctrl+Q        Quit application"),
                text("  Ctrl+U/D      Scroll up/down half page"),
                text("  Ctrl+T/B      Scroll to top/bottom"),
                text("  Esc           Cancel input / Close dialog"),
                text("  Tab           Auto-complete command"),
                text(""),
                text("Press any key to close...").dim()
        ).rounded()
         .borderColor(Color.BLUE)
         .addClass("dialog");
    }
}
