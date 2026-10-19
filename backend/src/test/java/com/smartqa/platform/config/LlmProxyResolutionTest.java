package com.smartqa.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code rag.llm.proxy}（{@code AI_PROXY}）解析契约测试。
 *
 * <p>只钉一条核心契约：<b>这个配置永远不许把应用启动搞挂</b>。它是可选能力，任何手误
 * （漏写 scheme、含空格、缺主机名、端口没人监听）都只能降级为直连 + WARN，不能抛异常。</p>
 *
 * <p>缘起：PR #101 首版用 {@code URI.create(proxyUrl)} 直接解析，而 {@code 127.0.0.1:7890}
 * 这种最常见的省略 scheme 写法会让 {@code URI.create} 抛 {@code IllegalArgumentException}
 * （冒号在相对 URI 首段非法），Bean 创建失败 → 整个应用起不来。用户只是照着 PR 描述
 * 里写的「host:port」填配置就能踩到，属于无提示的启动崩溃，必须有回归用例。</p>
 *
 * <p>正例（能解析出代理）用一个本地临时监听端口构造，不依赖外网也不依赖 7890 上真的跑着 Clash；
 * 反例（不可达）用 9 号端口，常规环境无人监听。</p>
 */
class LlmProxyResolutionTest {

    private static final int NOBODY_LISTENING_PORT = 9;

    @Test
    @DisplayName("null / 空白 / 全空格 → 直连，不报错")
    void blankOrNullMeansDirect() {
        assertNull(LangChain4jConfig.buildProxy(null));
        assertNull(LangChain4jConfig.buildProxy(""));
        assertNull(LangChain4jConfig.buildProxy("   "));
    }

    @Test
    @DisplayName("省略 scheme 的 host:port 不许抛异常（PR #101 首版的启动崩溃点）")
    void schemeLessHostPortMustNotThrow() {
        assertDoesNotThrow(() -> LangChain4jConfig.buildProxy("127.0.0.1:" + NOBODY_LISTENING_PORT));
    }

    @Test
    @DisplayName("含非法字符的配置 → 降级直连，不抛异常")
    void malformedUrlDegradesToDirect() {
        assertNull(LangChain4jConfig.buildProxy("http://bad host:7890"));
        assertNull(LangChain4jConfig.buildProxy("http://"));
    }

    @Test
    @DisplayName("缺主机名（http://:7890）→ 降级直连")
    void missingHostDegradesToDirect() {
        assertNull(LangChain4jConfig.buildProxy("http://:7890"));
    }

    @Test
    @DisplayName("端口没人监听 → 降级直连（日志可区分「代理没开」与「模型有问题」）")
    void unreachablePortDegradesToDirect() {
        assertNull(LangChain4jConfig.buildProxy("http://127.0.0.1:" + NOBODY_LISTENING_PORT));
    }

    @Test
    @DisplayName("省略 scheme 且端口可达 → 按 HTTP 代理生效")
    void schemeLessReachableHostPortBecomesHttpProxy() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            Proxy proxy = LangChain4jConfig.buildProxy("127.0.0.1:" + listener.getLocalPort());

            assertNotNull(proxy, "端口在监听时应返回代理，而不是降级直连");
            assertEquals(Proxy.Type.HTTP, proxy.type());
        }
    }

    @Test
    @DisplayName("socks5:// → 按 SOCKS 代理生效，不再被静默当成 HTTP")
    void socks5SchemeBecomesSocksProxy() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            Proxy proxy = LangChain4jConfig.buildProxy("socks5://127.0.0.1:" + listener.getLocalPort());

            assertNotNull(proxy);
            assertEquals(Proxy.Type.SOCKS, proxy.type());
        }
    }
}
