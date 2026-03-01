package com.coderfaster.agent.tui.view;

import com.coderfaster.agent.tui.AppController;
import com.coderfaster.agent.tui.KeyHandler;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

import static dev.tamboui.shaded.toolkit.Toolkit.column;
import static dev.tamboui.shaded.toolkit.Toolkit.row;
import static dev.tamboui.shaded.toolkit.Toolkit.spacer;
import static dev.tamboui.shaded.toolkit.Toolkit.stack;

/**
 * 主布局视图。
 * 组合 HistoryView、InputView、StatusView、DialogView 构建完整 UI。
 * 当有 Dialog 时，Dialog 以带遮罩的浮层形式显示。
 */
public class MainView {

    private static final int MOUSE_SCROLL_LINES = 3;

    private final HistoryView historyView;
    private final InputView inputView;
    private final StatusView statusView;
    private final DialogView dialogView;
    private final KeyHandler keyHandler;

    public MainView(AppController ctrl) {
        this.historyView = new HistoryView(ctrl);
        this.inputView = new InputView(ctrl);
        this.statusView = new StatusView(ctrl);
        this.dialogView = new DialogView(ctrl);
        this.keyHandler = new KeyHandler(ctrl, historyView);
    }

    public Element render() {
        Panel dialog = dialogView.render();

        Element mainLayout = column(
                historyView.render().fill(),
                inputView.render().length(inputView.getRequiredHeight()),
                statusView.render().length(1)
        ).onMouseEvent(this::handleMouse);

        if (dialog != null) {
            // 创建半透明遮罩层（使用 dim() 方法实现半透明效果）
            Element overlay = column().bg(new Color.Rgb(0, 0, 0)).dim();
            // 将键盘事件处理器绑定到 dialog 上，确保能捕获键盘事件
            Panel dialogWithKeyHandler = dialog.onKeyEvent(keyHandler::handle);
            Element dialogCentered = column(
                    spacer(),
                    row(spacer(), dialogWithKeyHandler, spacer()),
                    spacer()
            );
            return stack(mainLayout, overlay, dialogCentered);
        }

        // 没有 dialog 时，让输入框和全局处理器按正常流程处理
        return mainLayout;
    }

    private EventResult handleMouse(MouseEvent event) {
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            historyView.scrollUp(MOUSE_SCROLL_LINES);
            return EventResult.HANDLED;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            historyView.scrollDown(MOUSE_SCROLL_LINES);
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }
}
