#!/bin/bash

# =============================================================================
# CodeFaster 卸载脚本
# 功能：从系统中移除 coderfaster 命令和相关文件
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

# 常量定义
COMMAND_NAME="coderfaster"
JAR_FILENAME="coderfaster.jar"
INSTALL_DIR="/usr/local/bin"

echo -e "${GREEN}=========================================="
echo -e "  CodeFaster 卸载脚本"
echo -e "==========================================${NC}"
echo ""

# 检查是否需要 sudo 权限
needs_sudo=false
if [ ! -w "$INSTALL_DIR" ]; then
    needs_sudo=true
    log_warning "需要 sudo 权限来卸载 ${INSTALL_DIR} 中的文件"
    sudo -v || {
        log_error "无法获取 sudo 权限，卸载失败"
        exit 1
    }
fi

# 卸载命令
log_info "正在卸载 ${COMMAND_NAME} 命令..."

if [ -f "${INSTALL_DIR}/${COMMAND_NAME}" ]; then
    if [ "$needs_sudo" = true ]; then
        sudo rm -f "${INSTALL_DIR}/${COMMAND_NAME}"
    else
        rm -f "${INSTALL_DIR}/${COMMAND_NAME}"
    fi
    log_success "已移除命令：${INSTALL_DIR}/${COMMAND_NAME}"
else
    log_info "命令文件不存在：${INSTALL_DIR}/${COMMAND_NAME}"
fi

# 卸载 JAR 文件
log_info "正在卸载 JAR 文件..."

if [ -f "${INSTALL_DIR}/${JAR_FILENAME}" ]; then
    if [ "$needs_sudo" = true ]; then
        sudo rm -f "${INSTALL_DIR}/${JAR_FILENAME}"
    else
        rm -f "${INSTALL_DIR}/${JAR_FILENAME}"
    fi
    log_success "已移除 JAR 文件：${INSTALL_DIR}/${JAR_FILENAME}"
else
    log_info "JAR 文件不存在：${INSTALL_DIR}/${JAR_FILENAME}"
fi

# 清理项目构建产物（可选）
echo ""
log_info "是否清理项目构建产物？(target 目录)"
read -p "清理 target 目录？(y/n): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    log_info "清理 target 目录..."
    if [ -d "target" ]; then
        rm -rf "target"
        log_success "已清理 target 目录"
    fi
fi

# 验证卸载
echo ""
log_info "验证卸载..."
if command -v "${COMMAND_NAME}" &> /dev/null; then
    log_warning "${COMMAND_NAME} 命令仍然可用，可能需要重新加载 shell"
    log_info "请运行 'hash -r' 或重启终端"
else
    log_success "${COMMAND_NAME} 命令已成功卸载"
fi

echo ""
echo -e "${GREEN}=========================================="
echo -e "  卸载完成!"
echo -e "==========================================${NC}"
