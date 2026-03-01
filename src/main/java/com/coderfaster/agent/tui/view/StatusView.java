package com.coderfaster.agent.tui.view;

import com.coderfaster.agent.tui.AppController;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.elements.Row;

import static dev.tamboui.shaded.toolkit.Toolkit.row;
import static dev.tamboui.shaded.toolkit.Toolkit.spacer;
import static dev.tamboui.shaded.toolkit.Toolkit.text;

/**
 * 状态栏视图。
 * 显示应用标题、会话 ID 和当前状态。
 */
public class StatusView {

    private final AppController ctrl;

    public StatusView(AppController ctrl) {
        this.ctrl = ctrl;
    }

    public Row render() {
        String leftStatus = buildLeftStatus();
        String rightStatus = buildRightStatus();

        return row(
                text(" " + leftStatus).bold().fg(Color.WHITE).fill(),
                spacer(),
                text(rightStatus + " ").fg(Color.WHITE).fit()
        ).bg(Color.BLUE).addClass("status-bar").id("status-bar");
    }

    private String buildLeftStatus() {
        StringBuilder sb = new StringBuilder("CodeMate Agent");
        String sid = ctrl.sessionId();
        if (sid != null) {
            sb.append(" | Session: ")
              .append(sid, 0, Math.min(8, sid.length()))
              .append("...");
        }
        return sb.toString();
    }

    private String buildRightStatus() {
        switch (ctrl.sessionState()) {
            case IDLE: return "[Ready]";
            case THINKING: return "[Thinking...]";
            case EXECUTING:
                String tool = ctrl.currentToolName();
                return "[Executing: " + (tool != null ? tool : "...") + "]";
            case CONFIRMING: return "[Confirm?]";
            case ERROR: return "[Error]";
            case COMPLETED: return "[Done]";
            default: return "[Ready]";
        }
    }
}
