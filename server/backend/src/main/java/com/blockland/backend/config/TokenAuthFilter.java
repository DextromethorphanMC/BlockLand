package com.blockland.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Two independent shared secrets:
 * {@code /api/admin/**} needs {@code Authorization: Bearer <admin-token>} (the React admin UI),
 * {@code /api/server/**} needs {@code X-Server-Token: <server-token>} (the Minecraft server).
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {
    private final BlockLandProperties properties;

    public TokenAuthFilter(BlockLandProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/admin/")) {
            String header = request.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            if (!matches(token, properties.adminToken())) {
                reject(response, "管理令牌无效");
                return;
            }
        } else if (path.startsWith("/api/server/")) {
            if (!matches(request.getHeader("X-Server-Token"), properties.serverToken())) {
                reject(response, "服务器令牌无效");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String given, String expected) {
        if (given == null || expected == null || expected.isBlank()) return false;
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
