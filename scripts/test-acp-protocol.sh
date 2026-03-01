#!/bin/bash
# ACP 协议测试脚本

set -e

echo "=========================================="
echo "  ACP 协议测试"
echo "=========================================="
echo ""

cd /Users/lee/codefast

# 1. 编译
echo "📦 编译项目..."
mvn compile -q
echo "✓ 编译成功"
echo ""

# 2. 准备测试配置
echo "📄 准备测试配置..."
mkdir -p ~/.codefaster
cat > ~/.codefaster/setting.json << 'TESTCONFIG'
{
  "authType": "NORMAL",
  "apiKey": "sk-test_key",
  "modelName": "qwen3.5-plus",
  "codePlanBaseUrl": "https://codeplan.aliyun.com/api",
  "normalBaseUrl": "https://dashscope.aliyuncs.com/api"
}
TESTCONFIG
echo "✓ 测试配置已准备"
echo ""

# 3. ACP 协议测试用例
echo "🧪 ACP 协议测试用例:"
echo ""

echo "测试 1: initialize 方法"
echo '输入：{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
echo ""

echo "测试 2: session_new 方法"
echo '输入：{"jsonrpc":"2.0","id":2,"method":"session_new","params":{"cwd":"/tmp/test"}}'
echo ""

echo "测试 3: session_prompt 方法"
echo '输入：{"jsonrpc":"2.0","id":3,"method":"session_prompt","params":{"sessionId":"xxx","prompt":"Hello"}}'
echo ""

echo "=========================================="
echo "  手动测试命令:"
echo "=========================================="
echo ""
echo "# 启动 ACP 服务"
echo "mvn exec:java -Dexec.mainClass=\"com.coderfaster.agent.acp.AcpMain\""
echo ""
echo "# 发送测试请求（使用 netcat）"
echo "echo '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}' | nc localhost 8080"
echo ""
echo "=========================================="
echo "  测试脚本完成"
echo "=========================================="
