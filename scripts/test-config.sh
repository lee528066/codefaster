#!/bin/bash
# 测试配置加载功能

set -e

echo "=========================================="
echo "  CodeFaster 配置加载测试"
echo "=========================================="
echo ""

cd /Users/lee/codefast

# 1. 编译
echo "📦 编译项目..."
mvn compile -q
echo "✓ 编译成功"
echo ""

# 2. 检查配置文件
echo "📄 检查配置文件..."
if [ -f ~/.codefaster/setting.json ]; then
    echo "✓ 配置文件存在"
    echo "内容预览:"
    cat ~/.codefaster/setting.json | head -10
    echo ""
else
    echo "⚠️  配置文件不存在，将创建测试配置..."
    mkdir -p ~/.codefaster
    cat > ~/.codefaster/setting.json << 'TESTCONFIG'
{
  "authType": "NORMAL",
  "apiKey": "sk-test_key_for_validation",
  "modelName": "qwen3.5-plus",
  "codePlanBaseUrl": "https://codeplan.aliyun.com/api",
  "normalBaseUrl": "https://dashscope.aliyuncs.com/api"
}
TESTCONFIG
    echo "✓ 测试配置已创建"
    echo ""
fi

# 3. 运行配置加载测试
echo "🧪 测试配置加载..."
java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=compile -Dmdep.outputFile=/dev/stdout) \
    com.coderfaster.agent.config.local.ConfigInitializer 2>&1 | head -20 || true

echo ""
echo "=========================================="
echo "  测试完成！"
echo "=========================================="
