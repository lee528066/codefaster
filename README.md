# CodeFaster

**CodeFaster** - AI Coding Agent 客户端，基于 ReAct 模式构建，提供强大的终端用户界面 (TUI) 和智能代码辅助能力。

## 项目简介

CodeFaster 是一个功能丰富的 AI 编程助手客户端，采用 ReAct (Reasoning + Acting) 模式，能够理解自然语言指令并执行各种开发任务。项目使用 Java 11 开发，基于 Maven 构建，并采用 TamboUI Toolkit 构建声明式终端 UI。

## 主要特性

- 🤖 **ReAct 模式 AI Agent** - 支持推理与行动相结合的智能决策
- 🖥️ **终端用户界面 (TUI)** - 基于 TamboUI 的现代化终端界面
- 🛠️ **丰富的工具集** - 内置 15+ 种开发工具
- 💬 **会话管理** - 支持会话持久化、恢复和导出
- 🔍 **代码分析** - 集成 LSP 协议支持代码智能分析
- 🌐 **网络能力** - 支持网页搜索和内容获取
- 📝 **Markdown 渲染** - 内置 Markdown 渲染支持

## 系统要求

- **Java**: 11 或更高版本
- **Maven**: 3.6+ 
- **操作系统**: Linux / macOS / Windows

## 快速开始

### 1. 安装

```bash
# 进入项目目录
cd /path/to/codefast

# 执行安装脚本
./scripts/install.sh
```

安装脚本会自动：
- 检查 Java 和 Maven 环境
- 执行 Maven 打包
- 将 `coderfaster` 命令安装到 `/usr/local/bin`

### 2. 运行

```bash
# 直接运行
coderfaster

# 或指定工作目录
coderfaster --working-dir /path/to/project

# 使用特定模型
coderfaster --model qwen3.5-plus

# 恢复上次会话
coderfaster --continue

# 调试模式
coderfaster --debug
```

### 3. 基本使用

启动后，您将看到交互式 TUI 界面：

1. 在输入框中输入您的请求（如："帮我分析这个项目的代码结构"）
2. 按 `Enter` 发送消息
3. Agent 将分析请求并使用适当的工具执行任务
4. 查看输出结果和历史记录

## 命令行选项

| 选项 | 说明 | 默认值 |
|------|------|--------|
| `--working-dir <path>` | 工作目录 | 当前目录 |
| `--model <name>` | 模型名称 | qwen3.5-plus |
| `--auto-confirm` | 自动确认危险操作 | false |
| `--debug` | 调试模式 | false |
| `--max-iterations <n>` | 最大迭代次数 | 50 |
| `--resume [id]` | 恢复会话 | - |
| `--continue, -c` | 恢复最近会话 | - |
| `--cleanup-days <n>` | 会话保留天数 | 30 |
| `--help, -h` | 显示帮助 | - |

## 键盘快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 发送消息 / 执行命令 |
| `Ctrl+C` | 取消当前任务 |
| `Ctrl+Q` / `q` | 退出应用 |
| `Ctrl+U` / `Ctrl+D` | 上/下滚动半屏 |
| `Ctrl+T` / `Ctrl+B` | 滚动到顶部/底部 |
| `Tab` | 自动补全斜杠命令 |
| `Esc` | 取消输入 / 关闭对话框 |

## 斜杠命令

### 基本命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助信息 |
| `/clear` | 清除聊天历史 |
| `/quit` | 退出应用 |
| `/cancel` | 取消当前任务 |

### 会话管理

| 命令 | 说明 |
|------|------|
| `/sessions` | 列出所有会话 |
| `/resume [id]` | 恢复指定会话 |
| `/new` | 开始新会话 |
| `/delete <id>` | 删除会话 |
| `/compact [hint]` | 压缩当前会话 |
| `/context` | 显示上下文统计 |
| `/export [id]` | 导出会话为 Markdown |

## 内置工具

CodeFaster 提供以下开发工具：

| 工具 | 说明 |
|------|------|
| `read_file` | 读取文件内容 |
| `write_file` | 写入文件 |
| `edit` | 编辑文件 |
| `list_directory` | 列出目录内容 |
| `glob` | 文件路径匹配搜索 |
| `grep_search` | 代码内容搜索 |
| `run_shell_command` | 执行 shell 命令 |
| `todo_write` | 任务列表管理 |
| `task` | 子任务委托 |
| `skill` | 技能调用 |
| `memory` | 保存记忆 |
| `web_search` | 网络搜索 |
| `web_fetch` | 获取网页内容 |
| `lsp` | 语言服务器协议支持 |
| `exit_plan_mode` | 退出计划模式 |

## 项目结构

```
codefast/
├── src/main/
│   ├── java/
│   │   ├── com/coderfaster/
│   │   │   ├── agent/
│   │   │   │   ├── tui/          # TUI 界面相关
│   │   │   │   ├── tool/         # 工具实现
│   │   │   │   ├── session/      # 会话管理
│   │   │   │   ├── core/         # 核心 Agent 逻辑
│   │   │   │   ├── mock/         # Mock LLM 客户端
│   │   │   │   ├── lsp/          # LSP 服务
│   │   │   │   ├── http/         # HTTP 客户端
│   │   │   │   └── config/       # 配置类
│   │   │   └── dev/
│   │   └── resources/            # 资源文件
├── scripts/
│   ├── install.sh                # 安装脚本
│   ├── uninstall.sh              # 卸载脚本
│   └── debug.sh                  # 调试脚本
├── pom.xml                       # Maven 配置
└── README.md                     # 项目文档
```

## 开发指南

### 构建项目

```bash
# 编译打包
mvn clean package

# 跳过测试打包
mvn clean package -DskipTests

# 运行 TUI
mvn exec:java@run-shaded
```

### 运行示例

```bash
# 运行 TUI 示例
mvn exec:java -Dexec.mainClass="com.coderfaster.agent.example.TuiExample"
```

### 添加自定义工具

1. 继承 `BaseTool` 类
2. 实现工具逻辑
3. 在 `ToolRegistry` 中注册

```java
public class MyCustomTool extends BaseTool {
    @Override
    public String getName() {
        return "my_custom_tool";
    }
    
    @Override
    public ToolResult execute(Map<String, Object> args) {
        // 实现工具逻辑
        return ToolResult.success("Result");
    }
}
```

## 配置说明

### 环境变量

- `DASHSCOPE_API_KEY` - 百炼 SDK API 密钥

### 会话配置

会话数据保存在工作目录的 `.codefast/sessions` 目录下，支持：
- 自动保存和恢复
- 定期清理（默认 30 天）
- Markdown 格式导出

## 依赖技术

- **TamboUI** - 终端 UI 框架
- **Jackson** - JSON 处理
- **OkHttp** - HTTP 客户端
- **LSP4J** - 语言服务器协议
- **Logback** - 日志框架
- **Lombok** - 代码简化

## 常见问题

**Q: 安装时提示权限不足？**
A: 安装脚本需要 sudo 权限来写入 `/usr/local/bin`，请确保您有管理员权限。

**Q: 如何查看调试信息？**
A: 使用 `--debug` 参数启动，日志将输出到控制台。

**Q: 会话数据保存在哪里？**
A: 会话数据保存在工作目录的 `.codefast/sessions` 子目录中。

**Q: 如何切换模型？**
A: 使用 `--model <model-name>` 参数指定模型名称。

## 卸载

```bash
# 运行卸载脚本
./scripts/uninstall.sh
```

## 许可证

本项目采用 MIT 许可证。

## 联系方式

如有问题或建议，请提交 Issue 或联系开发团队。

---

**CodeFaster** - 让编码更高效！🚀
