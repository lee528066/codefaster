#!/bin/bash

# =============================================================================
# CodeFaster 调试脚本
# 功能：开启远程 Debug 端口，用于 IDE 调试
# 注意：由于使用了 Shade 插件，不使用 mvn exec 命令
# =============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 默认配置
DEBUG_PORT="5005"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_FILENAME="coderfaster.jar"

echo -e "${GREEN}=========================================="
echo -e "  CodeFaster 调试脚本"
echo -e "==========================================${NC}"
echo ""

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --port|-p)
            DEBUG_PORT="$2"
            shift 2
            ;;
        --help|-h)
            echo "使用方法：$0 [选项]"
            echo ""
            echo "选项:"
            echo "  --port, -p <port>  指定调试端口 (默认：5005)"
            echo "  --help, -h         显示帮助"
            exit 0
            ;;
        *)
            log_error "未知选项：$1"
            exit 1
            ;;
    esac
done

# 检查 Java 是否安装
log_info "检查 Java 是否安装..."
if ! command -v java &> /dev/null; then
    log_error "Java 未安装，请先安装 Java 11 或更高版本"
    exit 1
fi
log_success "Java 已安装"

# 检查 JAR 文件是否存在
JAR_PATH="${PROJECT_DIR}/target/${JAR_FILENAME}"
if [ ! -f "${JAR_PATH}" ]; then
    log_warning "JAR 文件不存在：${JAR_PATH}"
    log_info "正在执行 Maven 打包..."
    cd "${PROJECT_DIR}"
    mvn clean package -DskipTests

    if [ ! -f "${JAR_PATH}" ]; then
        log_error "打包失败，未找到 ${JAR_PATH}"
        exit 1
    fi
    log_success "打包成功"
fi

# 检查端口是否被占用
if lsof -Pi :${DEBUG_PORT} -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    log_warning "端口 ${DEBUG_PORT} 已被占用，请确认是否有其他调试进程在运行"
    read -p "是否继续？(y/n): " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo ""
log_info "=========================================="
log_info "  调试配置"
log_info "=========================================="
echo "  JAR 文件：${JAR_PATH}"
echo "  调试端口：${DEBUG_PORT}"
echo ""
echo "  IDE 调试配置 (Remote JVM Debug):"
echo "  - Host: localhost"
echo "  - Port: ${DEBUG_PORT}"
echo ""
echo "  JVM 调试参数:"
echo "  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${DEBUG_PORT}"
echo "=========================================="
echo ""

log_info "启动调试模式..."
log_info "等待 IDE 连接 (调试器已就绪)..."
echo ""

# 启动应用（调试模式）
# 注意：使用 java -jar 直接运行 shaded JAR，而不是 mvn exec
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${DEBUG_PORT} \
     -jar "${JAR_PATH}" \
     --debug

# 如果上面的命令退出，显示退出信息
EXIT_CODE=$?
echo ""
if [ ${EXIT_CODE} -eq 0 ]; then
    log_success "应用正常退出"
else
    log_warning "应用退出，退出码：${EXIT_CODE}"
fi
