package com.coderfaster.agent.lsp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LSP 服务器配置类
 * 定义如何启动和连接到一个 LSP 服务器
 */
public class LspServerConfig {

    /**
     * 服务器唯一名称标识
     */
    private final String name;

    /**
     * 此服务器支持的语言列表
     */
    private final List<String> languages;

    /**
     * 启动服务器的命令
     */
    private final String command;

    /**
     * 命令行参数
     */
    private final List<String> args;

    /**
     * 传输类型: stdio, tcp, socket
     */
    private final TransportType transport;

    /**
     * 环境变量
     */
    private final Map<String, String> env;

    /**
     * 工作空间根目录
     */
    private final String workspaceRoot;

    /**
     * 启动超时时间（毫秒）
     */
    private final long startupTimeoutMs;

    /**
     * 关闭超时时间（毫秒）
     */
    private final long shutdownTimeoutMs;

    /**
     * TCP/Socket 主机地址
     */
    private final String host;

    /**
     * TCP 端口号
     */
    private final int port;

    /**
     * Unix Socket 路径
     */
    private final String socketPath;

    /**
     * 传输类型枚举
     */
    public enum TransportType {
        /**
         * 标准输入输出流传输方式，通过进程的 stdin/stdout 进行通信
         */
        STDIO,
        /**
         * TCP 网络传输方式，通过 TCP 协议和指定的主机端口进行通信
         */
        TCP,
        /**
         * Unix Socket 传输方式，通过 Unix 域套接字文件进行本地进程间通信
         */
        SOCKET
    }

    private LspServerConfig(Builder builder) {
        this.name = builder.name;
        this.languages = builder.languages != null ? Collections.unmodifiableList(builder.languages) :
                Collections.emptyList();
        this.command = builder.command;
        this.args = builder.args != null ? Collections.unmodifiableList(builder.args) : Collections.emptyList();
        this.transport = builder.transport != null ? builder.transport : TransportType.STDIO;
        this.env = builder.env != null ? Collections.unmodifiableMap(builder.env) : Collections.emptyMap();
        this.workspaceRoot = builder.workspaceRoot;
        this.startupTimeoutMs = builder.startupTimeoutMs > 0 ? builder.startupTimeoutMs : 30000;
        this.shutdownTimeoutMs = builder.shutdownTimeoutMs > 0 ? builder.shutdownTimeoutMs : 5000;
        this.host = builder.host != null ? builder.host : "127.0.0.1";
        this.port = builder.port;
        this.socketPath = builder.socketPath;
    }

    public String getName() {
        return name;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public TransportType getTransport() {
        return transport;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public long getStartupTimeoutMs() {
        return startupTimeoutMs;
    }

    public long getShutdownTimeoutMs() {
        return shutdownTimeoutMs;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 创建 Java Language Server (jdtls) 的默认配置
     */
    public static LspServerConfig jdtls(String workspaceRoot, String jdtlsPath) {
        return builder("jdtls")
                .languages(List.of("java"))
                .command("java")
                .args(List.of(
                        "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                        "-Dosgi.bundles.defaultStartLevel=4",
                        "-Declipse.product=org.eclipse.jdt.ls.core.product",
                        "-Dlog.level=ALL",
                        "-noverify",
                        "-Xmx1G",
                        "--add-modules=ALL-SYSTEM",
                        "--add-opens", "java.base/java.util=ALL-UNNAMED",
                        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                        "-jar", jdtlsPath + "/plugins/org.eclipse.equinox.launcher_*.jar",
                        "-configuration", jdtlsPath + "/config_mac",
                        "-data", workspaceRoot + "/.jdtls-workspace"
                ))
                .workspaceRoot(workspaceRoot)
                .transport(TransportType.STDIO)
                .build();
    }

    /**
     * 创建 TypeScript Language Server 的默认配置
     */
    public static LspServerConfig typescript(String workspaceRoot) {
        return builder("typescript")
                .languages(List.of("typescript", "javascript", "typescriptreact", "javascriptreact"))
                .command("typescript-language-server")
                .args(List.of("--stdio"))
                .workspaceRoot(workspaceRoot)
                .transport(TransportType.STDIO)
                .build();
    }

    /**
     * 创建 Python Language Server (pylsp) 的默认配置
     */
    public static LspServerConfig python(String workspaceRoot) {
        return builder("python")
                .languages(List.of("python"))
                .command("pylsp")
                .workspaceRoot(workspaceRoot)
                .transport(TransportType.STDIO)
                .build();
    }

    /**
     * 创建 Go Language Server (gopls) 的默认配置
     */
    public static LspServerConfig go(String workspaceRoot) {
        return builder("go")
                .languages(List.of("go"))
                .command("gopls")
                .args(List.of("serve"))
                .workspaceRoot(workspaceRoot)
                .transport(TransportType.STDIO)
                .build();
    }

    /**
     * 创建 Rust Language Server (rust-analyzer) 的默认配置
     */
    public static LspServerConfig rust(String workspaceRoot) {
        return builder("rust")
                .languages(List.of("rust"))
                .command("rust-analyzer")
                .workspaceRoot(workspaceRoot)
                .transport(TransportType.STDIO)
                .build();
    }

    public static class Builder {
        private final String name;
        private List<String> languages;
        private String command;
        private List<String> args;
        private TransportType transport;
        private Map<String, String> env;
        private String workspaceRoot;
        private long startupTimeoutMs;
        private long shutdownTimeoutMs;
        private String host;
        private int port;
        private String socketPath;

        private Builder(String name) {
            this.name = name;
        }

        public Builder languages(List<String> languages) {
            this.languages = languages;
            return this;
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder args(List<String> args) {
            this.args = args;
            return this;
        }

        public Builder transport(TransportType transport) {
            this.transport = transport;
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder workspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
            return this;
        }

        public Builder startupTimeoutMs(long startupTimeoutMs) {
            this.startupTimeoutMs = startupTimeoutMs;
            return this;
        }

        public Builder shutdownTimeoutMs(long shutdownTimeoutMs) {
            this.shutdownTimeoutMs = shutdownTimeoutMs;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder socketPath(String socketPath) {
            this.socketPath = socketPath;
            return this;
        }

        public LspServerConfig build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Server name is required");
            }
            if (transport == TransportType.STDIO && (command == null || command.isEmpty())) {
                throw new IllegalArgumentException("Command is required for STDIO transport");
            }
            if (transport == TransportType.TCP && port <= 0) {
                throw new IllegalArgumentException("Port is required for TCP transport");
            }
            if (transport == TransportType.SOCKET && (socketPath == null || socketPath.isEmpty())) {
                throw new IllegalArgumentException("Socket path is required for SOCKET transport");
            }
            return new LspServerConfig(this);
        }
    }

    @Override
    public String toString() {
        return "LspServerConfig{" +
                "name='" + name + '\'' +
                ", languages=" + languages +
                ", command='" + command + '\'' +
                ", transport=" + transport +
                ", workspaceRoot='" + workspaceRoot + '\'' +
                '}';
    }
}
