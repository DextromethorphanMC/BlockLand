package com.blockland.client.graphics.upscale;

import com.blockland.BlockLand;
import com.blockland.client.graphics.upscale.dlss.DlssException;
import com.blockland.client.graphics.upscale.dlss.SlRuntime;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The session's one NVIDIA Streamline runtime ({@link SlRuntime}): DLSS Super Resolution ({@link DlssUpscaler}) and DLSS
 * Frame Generation ({@link FrameGenerator}) share it, since Streamline knows one device and one sequence of frames. It is
 * brought up once, on first use — with Frame Generation loaded whenever its files are there, switched on or not — and stays
 * up for the session: Streamline crashes when it is brought up a second time in the same process after Frame Generation
 * was loaded (a world change used to do exactly that). Clients close what they made on it ({@link Client#releaseRuntime}
 * is kept for that contract) but never the runtime. Render thread only.
 */
public final class StreamlineHost {
    /** Something holding objects on the runtime; it must close them when told to. */
    public interface Client {
        /** The runtime is about to go down: close everything made on it (it may be acquired again later). */
        void releaseRuntime();
    }

    private static final String ENGINE = "BlockLand / Minecraft 1.21.8";
    @Nullable
    private static SlRuntime runtime;
    private static final List<Client> clients = new ArrayList<>();
    /** Why Frame Generation failed to come up this session (it is not retried until the game restarts), or null. */
    @Nullable
    private static DlssException frameGenFailure;
    /** Why the runtime itself failed to come up this session, or null. */
    @Nullable
    private static DlssException failure;

    private StreamlineHost() {
    }

    /**
     * The runtime, brought up if needed.
     *
     * @param frameGeneration the client needs Frame Generation on it (a runtime without it is restarted with it, unless
     *                        that already failed this session: then the runtime comes without it and the client checks
     *                        {@link SlRuntime#frameGeneration()})
     * @throws DlssException why the runtime cannot run at all
     */
    static SlRuntime acquire(Client client, boolean frameGeneration) {
        if (failure != null) throw failure;
        if (runtime == null) {
            try {
                runtime = create(frameGenFailure == null && SlRuntime.missing(runtimeFolder(), SlRuntime.FRAME_GENERATION_FILES).isEmpty());
            } catch (DlssException e) {
                // not retried: a half-initialised Streamline must not be brought up again either
                failure = e;
                throw e;
            }
        }
        if (frameGeneration && !runtime.frameGeneration() && frameGenFailure == null) {
            // the runtime came up before Frame Generation's files were there (it is not brought up a second time)
            frameGenFailure = new DlssException("blockland.graphics.status.fg.restart", "");
        }
        if (!clients.contains(client)) clients.add(client);
        return runtime;
    }

    private static SlRuntime create(boolean frameGeneration) {
        Path logs = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("streamline");
        try {
            Files.createDirectories(logs);
        } catch (Exception ignored) {
            // Streamline then logs nowhere
        }
        SlRuntime created = SlRuntime.create(runtimeFolder(), logs, ENGINE, frameGeneration);
        if (created.frameGenerationFailure() != null) {
            // e.g. hardware-accelerated GPU scheduling off: Super Resolution still runs without it
            frameGenFailure = created.frameGenerationFailure();
            BlockLand.LOGGER.warn("[BlockLand] DLSS Frame Generation is off for this session: {}", frameGenFailure.getMessage());
        }
        BlockLand.LOGGER.info("[BlockLand] Streamline ready{}: {}", created.frameGeneration() ? " with DLSS Frame Generation" : "",
                created.description());
        return created;
    }

    /** The client is done with the runtime for now (the runtime itself stays up for the session). */
    static void release(Client client) {
        clients.remove(client);
    }

    /** Frame Generation failed while running: it stays off for the session (Super Resolution keeps the runtime). */
    static void failFrameGeneration(DlssException e) {
        frameGenFailure = e;
    }

    @Nullable
    static DlssException frameGenFailure() {
        return frameGenFailure;
    }

    /** Why Ray Reconstruction did not come up on the runtime (once it is up), or null. */
    @Nullable
    static DlssException rayReconstructionFailure() {
        return runtime != null ? runtime.rayReconstructionFailure() : null;
    }

    /** Where the Streamline runtime goes: {@code <game directory>/blockland/native/streamline}. */
    public static Path runtimeFolder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("blockland").resolve("native").resolve("streamline");
    }
}
