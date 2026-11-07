package com.blockland.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Browser hardening for the admin UI and the API: no MIME sniffing, no framing (clickjacking the admin pages), no
 * referrer leaking admin URLs, and — when the backend serves HTTPS itself — HSTS, so a browser that has seen it once never
 * falls back to plain HTTP (behind a reverse proxy, the proxy sets it).
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.isSecure()) response.setHeader("Strict-Transport-Security", "max-age=31536000");
        chain.doFilter(request, response);
    }
}
