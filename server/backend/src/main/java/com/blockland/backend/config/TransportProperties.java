package com.blockland.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the backend may be reached ({@link TransportSecurity}).
 *
 * @param allowInsecureRemote listen beyond this machine over plain HTTP: only for a trusted private network, since every
 *                            request carries a token in the clear
 */
@ConfigurationProperties(prefix = "blockland.transport")
public record TransportProperties(boolean allowInsecureRemote) {
}
