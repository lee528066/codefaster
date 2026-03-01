package com.coderfaster.agent.example;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.core.AgentResult;

import java.nio.file.Path;
import java.util.Scanner;

/**
 * 多模态模式使用示例
 *
 * 多模态模式使用百炼多模态大模型（如 qwen-vl-max, qwen-vl-plus, qwen3-vl-plus 等），
 * 支持图像、视频等输入，适用于需要视觉理解能力的场景。
 *
 * 使用前需要：
 * 1. 设置环境变量 DASHSCOPE_API_KEY 为你的百炼 API Key
 * 2. 或者在代码中通过 apiKey() 方法设置
 */
public class MultiModalModeExample {

    public static void main(String[] args) {
        interactiveMockMode();
    }
    /**
     * 交互式 Mock 模式示例
     */
    public static void interactiveMockMode() {
        System.out.println("=== Interactive Mock Mode ===\n");

        // 注意：不要关闭 System.in，因为这是标准输入流，关闭后整个程序都无法再读取输入
        Scanner scanner = new Scanner(System.in);

        try (AgentRunner agent = AgentRunner.builder()
//                .modelName("qwen3.5-plus")
                .modelName("qwen3.5-plus")
                .uid("235419")
                .workingDirectory(Path.of("."))
                .autoConfirm(true)
                .debug(true)
                .confirmationHandler((toolName, params) -> {
                    System.out.println("\n需要确认执行以下操作:");
                    System.out.println("工具: " + toolName);
                    System.out.println("参数: " + params.toString());
                    System.out.print("是否允许? (y/n): ");

                    String input = scanner.nextLine().trim().toLowerCase();
                    return input.equals("y") || input.equals("yes");
                })
                .build()) {

            System.out.println("Mock 模式交互式会话已启动");
            System.out.println("输入 'exit' 退出\n");

            String sessionId = null;

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("exit")) {
                    System.out.println("再见！");
                    break;
                }

                if (input.isEmpty()) {
                    continue;
                }

                AgentResult result;
                if (sessionId != null) {
                    result = agent.run(input, sessionId);
                } else {
                    result = agent.run(input);
                    sessionId = result.getSessionId();
                }

                if (result.isSuccess()) {
                    System.out.println("\n" + result.getContent() + "\n");
                } else {
                    System.out.println("\n错误：" + result.getError() + "\n");
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            // 关闭 Scanner，释放资源（但不关闭 System.in）
            scanner.close();
        }
    }
}
