package com.blockland.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps the tokens off the wire. Every admin request carries the admin token and every Minecraft server request the
 * server token, so plain HTTP is fine only where the traffic never leaves the machine: the backend listens on 127.0.0.1
 * (application.yml), and admins come in through an HTTPS reverse proxy in front of it. Listening anywhere else needs
 * HTTPS of its own ({@code server.ssl.*}) — or {@code blockland.transport.allow-insecure-remote} for a trusted private
 * network — and strong tokens either way; the backend refuses to start otherwise. The default (or a short) token works
 * only for requests made directly on this machine ({@link TokenAuthFilter}), so a proxy never exposes it.
 * Deployment: {@code tools/deploy/SECURITY.md}.
 */
@Component
public class TransportSecurity {
    private static final Logger LOG = LoggerFactory.getLogger(TransportSecurity.class);
    /** Shorter tokens are guessable enough not to leave this machine. */
    static final int MIN_TOKEN_LENGTH = 24;
    /** The development tokens in application.yml. */
    static final Set<String> DEFAULT_TOKENS = Set.of("change-me-admin", "change-me-server");
    /** Headers a reverse proxy adds: a request that has one did not come from this machine. */
    private static final List<String> FORWARDING_HEADERS = List.of("X-Forwarded-For", "Forwarded", "X-Real-IP");

    public TransportSecurity(ObjectProvider<ServerProperties> server, BlockLandProperties properties, TransportProperties transport) {
        ServerProperties web = server.getIfAvailable();
        if (web == null) return; // no web server (tests without one): nothing listens
        boolean tls = Ssl.isEnabled(web.getSsl());
        List<String> problems = problems(web.getAddress(), tls, transport.allowInsecureRemote(),
                properties.adminToken(), properties.serverToken());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("后端拒绝启动（部署说明：tools/deploy/SECURITY.md）：\n - " + String.join("\n - ", problems));
        }
        if (weakToken(properties.adminToken()) || weakToken(properties.serverToken())) {
            LOG.warn("Default or short tokens: accepted only from requests made directly on this machine "
                    + "(set strong blockland.admin-token / server-token before exposing the backend)");
        }
        if (!isLoopback(web.getAddress()) && !tls) {
            LOG.warn("Listening beyond this machine over plain HTTP (blockland.transport.allow-insecure-remote): "
                    + "tokens cross the network in the clear");
        }
    }

    /** Why the backend must not start as configured; empty when it may. */
    static List<String> problems(InetAddress address, boolean tls, boolean allowInsecureRemote, String adminToken, String serverToken) {
        List<String> out = new ArrayList<>();
        if (isLoopback(address)) return out;
        String where = address == null ? "所有网卡" : address.getHostAddress();
        if (!tls && !allowInsecureRemote) {
            out.add("监听在 " + where + "（不只本机）却没有启用 HTTPS，令牌会以明文经过网络。请配置 server.ssl.*，或改回 "
                    + "server.address=127.0.0.1 并在前面放 HTTPS 反向代理；只有在可信内网中才可设置 "
                    + "blockland.transport.allow-insecure-remote=true");
        }
        if (weakToken(adminToken)) out.add("blockland.admin-token 是默认值或少于 " + MIN_TOKEN_LENGTH + " 个字符，不能在本机以外使用");
        if (weakToken(serverToken)) out.add("blockland.server-token 是默认值或少于 " + MIN_TOKEN_LENGTH + " 个字符，不能在本机以外使用");
        return out;
    }

    /** A token that must not leave this machine: missing, a development default, or short. */
    static boolean weakToken(String token) {
        return token == null || token.isBlank() || DEFAULT_TOKENS.contains(token) || token.length() < MIN_TOKEN_LENGTH;
    }

    /** A request made directly on this machine: from a loopback address, through no proxy. */
    static boolean direct(HttpServletRequest request) {
        for (String header : FORWARDING_HEADERS) if (request.getHeader(header) != null) return false;
        String remote = request.getRemoteAddr();
        return remote != null && (remote.startsWith("127.") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"));
    }

    private static boolean isLoopback(InetAddress address) {
        return address != null && address.isLoopbackAddress();
    }
}
