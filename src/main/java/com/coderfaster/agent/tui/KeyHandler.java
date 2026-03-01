package com.coderfaster.agent.tui;

import com.coderfaster.agent.tui.view.HistoryView;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 统一按键处理器。
 * 根据当前 UI 状态（Dialog、输入、全局）分发按键事件。
 * 滚动操作委托给 HistoryView 的 RichTextState。
 */
public class KeyHandler {

    private final AppController controller;
    private final HistoryView historyView;

    public KeyHandler(AppController controller, HistoryView historyView) {
        this.controller = controller;
        this.historyView = historyView;
    }

    public EventResult handle(KeyEvent event) {
        if (controller.dialogMode() != AppController.DialogMode.NONE) {
            return handleDialogKey(event);
        }

        EventResult result = handleGlobalKey(event);
        if (result == EventResult.HANDLED) return result;

        return EventResult.UNHANDLED;
    }

    private EventResult handleDialogKey(KeyEvent event) {
        // 只处理帮助对话框，确认流程现在通过 InputView 内联处理
        controller.hideDialog();
        return EventResult.HANDLED;
    }

    private EventResult handleGlobalKey(KeyEvent event) {
        if (event.isQuit()) {
            controller.quit();
            return EventResult.HANDLED;
        }

        if (event.code() == KeyCode.CHAR && event.modifiers().ctrl()) {
            char c = Character.toLowerCase(event.character());
            switch (c) {
                case 'c':
                    controller.cancelTask();
                    return EventResult.HANDLED;
                case 'u':
                    historyView.pageUp();
                    return EventResult.HANDLED;
                case 'd':
                    historyView.pageDown();
                    return EventResult.HANDLED;
                case 't':
                    historyView.scrollToTop();
                    return EventResult.HANDLED;
                case 'b':
                    historyView.scrollToBottom();
                    return EventResult.HANDLED;
                default:
                    return EventResult.UNHANDLED;
            }
        }

        return EventResult.UNHANDLED;
    }
}
