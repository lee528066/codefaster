/**
 * CodeMate Agent TUI (Terminal User Interface)
 *
 * 基于 TamboUI Toolkit DSL 的声明式终端用户界面模块。
 *
 * 架构（MVC）：
 * - AppController：持有所有 UI 状态，提供命令方法
 * - View 层（view 包）：声明式 Element 构建，纯函数 Controller → Element
 * - AgentEventBridge：将 Agent 后台事件桥接到渲染线程
 * - KeyHandler：统一的按键处理
 *
 * 主要组件：
 * - TuiMain：命令行入口，构建并启动 ToolkitRunner
 * - AppController：MVC Controller，持有 AgentRunner 和所有 UI 状态
 * - MainView：主布局视图，组合子视图并处理 Dialog overlay
 * - HistoryView：历史消息视图
 * - InputView：输入框视图（集成 TamboUI TextInput）
 * - StatusView：状态栏视图
 * - DialogView：对话框视图（帮助、确认）
 *
 * 使用方法：
 * <pre>{@code
 * java -jar coderfaster.jar --working-dir /path/to/project --model qwen3.5-plus
 * }</pre>
 *
 * @see com.coderfaster.agent.tui.TuiMain
 * @see com.coderfaster.agent.tui.AppController
 */
package com.coderfaster.agent.tui;
