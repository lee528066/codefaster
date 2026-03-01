package com.coderfaster.agent.example;

import com.coderfaster.agent.tui.TuiMain;

/**
 * TUI 示例程序
 * 委托给 TuiMain 启动交互式终端界面
 */
public class TuiExample {

    public static void main(String[] args) {
        System.out.println("Starting CodeMate Agent TUI...");
        System.out.println();
        TuiMain.main(args);
    }
}
