#!/bin/bash

# =============================================================================
# CodeFaster 安装脚本
# 功能：打包项目并注册系统命令 coderfaster
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

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# 常量定义
COMMAND_NAME="coderfaster"
JAR_FILENAME="coderfaster.jar"
INSTALL_DIR="/usr/local/bin"

log_info "=========================================="
log_info "  CodeFaster 安装脚本"
log_info "=========================================="
echo ""

# 检查 Maven 是否安装
log_info "检查 Maven 是否安装..."
if ! command -v mvn &> /dev/null; then
    log_error "Maven 未安装，请先安装 Maven"
    exit 1
fi
log_success "Maven 已安装：$(mvn --version | head -n 1)"

# 检查 Java 是否安装
log_info "检查 Java 是否安装..."
if ! command -v java &> /dev/null; then
    log_error "Java 未安装，请先安装 Java 11 或更高版本"
    exit 1
fi
log_success "Java 已安装：$(java -version 2>&1 | head -n 1)"

# 进入项目目录
log_info "进入项目目录：$PROJECT_DIR"
cd "$PROJECT_DIR"

# 清理旧的构建产物
log_info "清理旧的构建产物..."
if [ -f "target/${JAR_FILENAME}" ]; then
    rm -f "target/${JAR_FILENAME}"
    log_info "已删除旧的 target/${JAR_FILENAME}"
fi

# Maven 打包
log_info "执行 Maven 打包 (跳过测试)..."
mvn clean package -DskipTests

# 检查打包结果
if [ ! -f "target/${JAR_FILENAME}" ]; then
    log_error "打包失败，未找到 target/${JAR_FILENAME}"
    exit 1
fi
log_success "打包成功：target/${JAR_FILENAME}"

# 安装到系统目录
log_info "安装命令到 ${INSTALL_DIR}..."

# 检查是否需要 sudo 权限
if [ ! -w "$INSTALL_DIR" ]; then
    log_warning "需要 sudo 权限来安装到 ${INSTALL_DIR}"
    sudo -v || {
        log_error "无法获取 sudo 权限，安装失败"
        exit 1
    }

    # 删除旧的安装
    if [ -f "${INSTALL_DIR}/${COMMAND_NAME}" ]; then
        sudo rm -f "${INSTALL_DIR}/${COMMAND_NAME}"
        log_info "已卸载旧版本命令"
    fi

    # 创建启动脚本
    sudo cp "target/${JAR_FILENAME}" "${INSTALL_DIR}/"

    # 创建包装脚本
    sudo bash -c "cat > '${INSTALL_DIR}/${COMMAND_NAME}' << 'EOF'
#!/bin/bash
exec java -jar ${INSTALL_DIR}/${JAR_FILENAME} \"\$@\"
EOF"

    # 设置执行权限
    sudo chmod +x "${INSTALL_DIR}/${COMMAND_NAME}"
else
    # 不需要 sudo，直接安装
    if [ -f "${INSTALL_DIR}/${COMMAND_NAME}" ]; then
        rm -f "${INSTALL_DIR}/${COMMAND_NAME}"
        log_info "已卸载旧版本命令"
    fi

    cp "target/${JAR_FILENAME}" "${INSTALL_DIR}/"

    # 创建包装脚本
    cat > "${INSTALL_DIR}/${COMMAND_NAME}" << EOF
#!/bin/bash
exec java -jar ${INSTALL_DIR}/${JAR_FILENAME} "\$@"
EOF

    chmod +x "${INSTALL_DIR}/${COMMAND_NAME}"
fi

log_success "命令已安装到 ${INSTALL_DIR}/${COMMAND_NAME}"

# 验证安装
log_info "验证安装..."
if command -v "${COMMAND_NAME}" &> /dev/null; then
    log_success "安装成功！"
    echo ""
    log_info "现在可以使用 '${COMMAND_NAME}' 命令启动 CodeFaster"
    echo ""
    echo -e "${GREEN}=========================================="
    echo -e "  安装完成!"
    echo -e "  使用命令：${COMMAND_NAME}"
    echo -e "==========================================${NC}"
else
    log_warning "安装完成，但 ${COMMAND_NAME} 不在 PATH 中"
    log_info "请确保 ${INSTALL_DIR} 在您的 PATH 环境变量中"
    echo ""
    log_info "您可以手动添加：export PATH=\"${INSTALL_DIR}:\$PATH\""
fi
