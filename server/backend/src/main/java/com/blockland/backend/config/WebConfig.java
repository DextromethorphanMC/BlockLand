package com.blockland.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** CORS for the Vite dev server, and serving the built React app (with SPA fallback) when present. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final BlockLandProperties properties;

    public WebConfig(BlockLandProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        if (properties.corsOrigins().isEmpty()) return;
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        Path webDir = properties.webPath();
        if (webDir != null && Files.isDirectory(webDir)) registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        Path webDir = properties.webPath();
        if (webDir == null || !Files.isDirectory(webDir)) return;
        Path root = webDir.toAbsolutePath().normalize();
        registry.addResourceHandler("/**")
                .addResourceLocations(root.toUri().toString())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location) throws IOException {
                        if (resourcePath.startsWith("api/")) return null;
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) return requested;
                        return new FileSystemResource(root.resolve("index.html"));
                    }
                });
    }
}
