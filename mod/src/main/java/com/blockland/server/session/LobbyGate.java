package com.blockland.server.session;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraftforge.network.config.ConfigurationTaskContext;

import java.util.function.Consumer;

/**
 * Configuration task added to every login: it does not finish by itself, so the new player stays in the
 * configuration phase — the lobby — until a match brings them into the world ({@link PlayerSessions#bring}).
 * Runs after Forge's own tasks, so the mod's network channel is already negotiated.
 */
final class LobbyGate implements ConfigurationTask {
    static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("blockland:lobby");

    private final PlayerSessions sessions;

    LobbyGate(PlayerSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void start(ConfigurationTaskContext context) {
        sessions.gateStarted(context.getConnection());
    }

    @Override
    public void start(Consumer<Packet<?>> send) {
        throw new IllegalStateException("Forge starts configuration tasks with a context");
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
