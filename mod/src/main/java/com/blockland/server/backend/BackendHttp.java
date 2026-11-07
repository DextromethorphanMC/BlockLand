package com.blockland.server.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * The one way this server talks to the backend. Every request carries the server token, so it only goes where the token
 * cannot be read on the way: over HTTPS anywhere, or plain HTTP to this machine (loopback) — anywhere else only when the
 * operator declared the network trusted ({@code [backend] allowInsecureRemote}). A refused request fails like an
 * unreachable backend (the server keeps its cached configuration). Redirects are never followed: the backend sends none,
 * and one would carry the token to wherever it points. Deployment: {@code tools/deploy/SECURITY.md}.
 */
public final class BackendHttp {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final BooleanSupplier allowInsecureRemote;

    public BackendHttp(BooleanSupplier allowInsecureRemote) {
        this.allowInsecureRemote = allowInsecureRemote;
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        String refusal = refusal(request.uri(), allowInsecureRemote.getAsBoolean());
        if (refusal != null) return CompletableFuture.failedFuture(new IllegalStateException(refusal));
        return http.sendAsync(request, handler);
    }

    /** {@link #refusal(URI, boolean)} for a configured URL, which may not even parse. */
    public static String refusal(String url, boolean allowInsecureRemote) {
        try {
            return refusal(URI.create(url.trim()), allowInsecureRemote);
        } catch (IllegalArgumentException e) {
            return "backend URL " + url + " is not a URL";
        }
    }

    /** Why the server token must not be sent to {@code uri}; null when it may. */
    public static String refusal(URI uri, boolean allowInsecureRemote) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return null;
        if (!scheme.equals("http")) return "backend URL " + uri + " is neither https:// nor http://";
        if (isLoopback(uri.getHost()) || allowInsecureRemote) return null;
        return "refusing plain HTTP to " + uri.getHost() + ": the server token would cross the network in the clear "
                + "(use an https:// backend URL; [backend] allowInsecureRemote=true only on a trusted network)";
    }

    /** This machine, by name or loopback literal (no DNS lookup). */
    static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1") || h.matches("127(\\.\\d{1,3}){3}");
    }
}
