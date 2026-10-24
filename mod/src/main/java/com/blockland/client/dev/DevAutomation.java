package com.blockland.client.dev;

import com.blockland.BlockLand;
import com.blockland.client.BlockLandClient;
import com.blockland.client.craft.ClientCraft;
import com.blockland.client.craft.HeldCraftScreen;
import com.blockland.client.gun.ClientGuns;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.screen.LobbyScreen;
import com.blockland.gun.GunStats;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Development-only test driver, enabled only when the dev run sets {@code -Dblockland.automation=<dir>}
 * ({@code build.ps1 runClient -Pautomation=<dir>}); never active in a normal game. Each client reads
 * {@code <dir>/<username>.txt} (consumed when read) and appends results to {@code <dir>/<username>.log}, so features
 * can be exercised and screenshotted without OS-level input — e.g. while the desktop is locked.
 *
 * <pre>
 * wait 20               pause 20 ticks
 * cmd give @s blockland:p9   run a server command (needs permission)
 * slot 0                select a hotbar slot
 * look 90 0             yaw / pitch
 * key attack tap        key: attack|use|reload|inspect|gunsmith|jump|forward|sprint|lobby, action: down|up|tap
 * screen none|lobby|gunsmith
 * gui click 120 80      click in the open screen (GUI coordinates)
 * gui key 256           key press in the open screen (GLFW code)
 * camera first|third
 * f3                    toggle the debug overlay
 * state                 log gun state, fps, look target
 * shot name.png         save a framebuffer screenshot into the automation dir
 * frames 12 name        save the next 12 rendered frames (name_000.png …; plus the scope picture and an optics line)
 * world                 the lobby's 进入世界 (OP; waits until the lobby is up)
 * scroll 1              the mouse wheel (a variable scope steps its power while aiming)
 * optics                log the raised optic's state (power, eyepiece, sway, breath, sight line)
 * setting scopeQuality high   set a field of the client settings (not saved)
 * reload / quit         reload resources / close the client
 * </pre>
 * While it runs the desktop's mouse is ignored (no button presses, no grabbed cursor), so a script is not disturbed by
 * whatever happens on the desktop while its window has focus.
 */
public final class DevAutomation {
    private static Path dir;
    private static final Deque<String> queue = new ArrayDeque<>();
    private static int waitTicks;
    private static int pollCounter;
    private static KeyMapping releaseNext;

    private DevAutomation() {
    }

    public static void init() {
        String property = System.getProperty("blockland.automation");
        if (property == null || property.isBlank()) return;
        dir = Path.of(property);
        BlockLand.LOGGER.warn("[BlockLand] Dev automation enabled, reading commands from {}", dir);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
        TickEvent.RenderTickEvent.Post.BUS.addListener(event -> frameRendered());
        // the scripted client must not react to whatever the desktop's mouse does while its window has focus: real
        // buttons are ignored and the cursor is never grabbed (so moving it does not turn the player)
        net.minecraftforge.client.event.InputEvent.MouseButton.Pre.BUS.addListener(event -> true);
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.pauseOnLostFocus = false;
            if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        });
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (releaseNext != null) {
            releaseNext.setDown(false);
            releaseNext = null;
        }
        if (++pollCounter % 10 == 0) poll(mc);
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (!queue.isEmpty() && waitTicks == 0) {
            String line = queue.peek().trim();
            // in the lobby (no world yet) only commands that need no player run; the rest waits for the world
            if (mc.player == null && !LOBBY_COMMANDS.contains(line.split("\\s+")[0]) && !line.isEmpty() && !line.startsWith("#")) return;
            queue.poll();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                run(mc, line);
            } catch (Exception e) {
                log("ERROR " + line + " -> " + e);
            }
        }
    }

    private static final java.util.Set<String> LOBBY_COMMANDS = java.util.Set.of("wait", "world", "setting", "reload", "quit");

    /** Frames still to capture with {@code frames}, and their file name prefix. */
    private static int framesLeft;
    private static int frameIndex;
    private static String framePrefix = "frame";

    /** Saves the framebuffer after every rendered frame while a {@code frames} capture runs (catches one-frame flicker). */
    private static void frameRendered() {
        if (framesLeft <= 0) return;
        framesLeft--;
        String name = String.format("%s_%03d.png", framePrefix, frameIndex);
        Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(), image -> save(image, name));
        var picture = com.blockland.client.scope.Optics.debugPicture();
        if (picture != null) {
            String pictureName = String.format("%s_%03d_pic.png", framePrefix, frameIndex);
            Screenshot.takeScreenshot(picture, image -> save(image, pictureName));
        }
        log(String.format("frame %03d %s", frameIndex, com.blockland.client.scope.Optics.debugLine()));
        frameIndex++;
    }

    private static void poll(Minecraft mc) {
        Path file = dir.resolve(mc.getUser().getName() + ".txt");
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.delete(file);
            queue.addAll(lines);
            log("loaded " + lines.size() + " commands");
        } catch (Exception e) {
            log("ERROR reading " + file + " -> " + e);
        }
    }

    private static void run(Minecraft mc, String line) throws Exception {
        String[] a = line.split("\\s+");
        LocalPlayer player = mc.player;
        switch (a[0]) {
            case "wait" -> waitTicks = Integer.parseInt(a[1]);
            case "world" -> {
                // the lobby's 进入世界 button (OP only)
                if (mc.player != null) {
                    log("world: already in a world");
                } else if (!com.blockland.client.ClientLobbyState.canEnterWorld()) {
                    // not in the lobby yet (still connecting): try again shortly
                    queue.addFirst(line);
                    waitTicks = 10;
                } else {
                    com.blockland.network.BLNetwork.sendToServer(com.blockland.network.packet.LobbyPackets.C2SEnterWorld.INSTANCE);
                    log("world: entering");
                }
            }
            case "reload" -> mc.reloadResourcePacks();
            case "quit" -> mc.stop();
            case "scroll" -> {
                // the mouse wheel as Forge reports it (a variable scope takes it while aiming)
                double dy = Double.parseDouble(a[1]);
                boolean taken = net.minecraftforge.client.event.InputEvent.MouseScrollingEvent.BUS.post(
                        new net.minecraftforge.client.event.InputEvent.MouseScrollingEvent(0, dy, false, false, false, 0, 0));
                log("scroll " + dy + (taken ? " (taken)" : ""));
            }
            case "optics" -> log(com.blockland.client.scope.Optics.debugLine());
            case "setting" -> {
                // setting <field of ClientSettings.Data> <value>
                var data = com.blockland.client.settings.ClientSettings.get();
                var field = data.getClass().getField(a[1]);
                Object value = field.getType() == boolean.class ? (Object) Boolean.parseBoolean(a[2])
                        : field.getType() == float.class ? (Object) Float.parseFloat(a[2]) : a[2];
                field.set(data, value);
                log("setting " + a[1] + " = " + value);
            }
            case "frames" -> {
                framesLeft = Integer.parseInt(a[1]);
                framePrefix = a.length > 2 ? a[2] : "frame";
                frameIndex = 0;
            }
            case "cmd" -> player.connection.sendCommand(line.substring(4).trim());
            case "slot" -> player.getInventory().setSelectedSlot(Integer.parseInt(a[1]));
            case "look" -> {
                float yaw = Float.parseFloat(a[1]), pitch = Float.parseFloat(a[2]);
                player.setYRot(yaw);
                player.yRotO = yaw;
                player.setXRot(pitch);
                player.xRotO = pitch;
            }
            case "key" -> key(mc, a[1], a.length > 2 ? a[2] : "tap");
            case "screen" -> mc.setScreen(switch (a[1]) {
                case "lobby" -> new LobbyScreen();
                case "gunsmith", "craft" -> HeldCraftScreen.forHeld(player.getMainHandItem(), player.getInventory().getSelectedSlot()).orElse(null);
                default -> null;
            });
            case "gui" -> {
                if (mc.screen == null) {
                    log("no screen for " + line);
                    return;
                }
                switch (a[1]) {
                    case "click" -> {
                        double x = Double.parseDouble(a[2]), y = Double.parseDouble(a[3]);
                        mc.screen.mouseClicked(x, y, 0);
                        mc.screen.mouseReleased(x, y, 0);
                    }
                    case "drag" -> {
                        double x = Double.parseDouble(a[2]), y = Double.parseDouble(a[3]), dx = Double.parseDouble(a[4]), dy = Double.parseDouble(a[5]);
                        mc.screen.mouseClicked(x, y, 0);
                        mc.screen.mouseDragged(x + dx, y + dy, 0, dx, dy);
                        mc.screen.mouseReleased(x + dx, y + dy, 0);
                    }
                    case "key" -> mc.screen.keyPressed(Integer.parseInt(a[2]), 0, 0);
                    case "hover" -> hover(mc, Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                    case "scroll" -> mc.screen.mouseScrolled(Double.parseDouble(a[2]), Double.parseDouble(a[3]), 0, Double.parseDouble(a[4]));
                    case "type" -> line.substring(line.indexOf("type") + 5).chars().forEach(c -> mc.screen.charTyped((char) c, 0));
                    default -> log("unknown gui action " + a[1]);
                }
            }
            case "aim" -> aim(mc, player, a[1], a.length > 2 ? a[2] : "body");
            case "perf" -> {
                // measure real frame cost: no AFK cap, no vsync, no frame limit
                mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(260);
                mc.getWindow().updateVsync(false);
                mc.options.pauseOnLostFocus = false;
                if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen
                        || mc.screen instanceof com.blockland.client.screen.pause.GameMenuScreen) mc.setScreen(null);
                log("perf mode: unlimited fps, vsync off, no pause on lost focus");
            }
            case "camera" -> mc.options.setCameraType("third".equals(a[1]) ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
            case "f3" -> mc.getDebugOverlay().toggleOverlay();
            case "state" -> state(mc, player);
            case "shot" -> Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> save(image, a[1]));
            default -> log("unknown command " + line);
        }
    }

    /** Moves the GUI cursor (GUI coordinates) so hover-only controls can be exercised. */
    private static void hover(Minecraft mc, double x, double y) throws Exception {
        double scale = mc.getWindow().getGuiScale();
        var handler = mc.mouseHandler;
        var xField = handler.getClass().getDeclaredField("xpos");
        var yField = handler.getClass().getDeclaredField("ypos");
        xField.setAccessible(true);
        yField.setAccessible(true);
        xField.setDouble(handler, x * scale);
        yField.setDouble(handler, y * scale);
    }

    /** Turns the player to look at another player's head or body. */
    private static void aim(Minecraft mc, LocalPlayer player, String name, String part) {
        var target = mc.level.players().stream().filter(p -> p.getName().getString().equals(name)).findFirst().orElse(null);
        if (target == null) {
            log("aim: no player " + name);
            return;
        }
        var eye = player.getEyePosition();
        var point = "head".equals(part) ? target.getEyePosition().add(0, 0.05, 0) : target.position().add(0, target.getBbHeight() * 0.55, 0);
        var d = point.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(pitch);
        player.xRotO = pitch;
        log(String.format("aim %s %s yaw=%.1f pitch=%.1f dist=%.1f", name, part, yaw, pitch, d.length()));
    }

    private static void key(Minecraft mc, String name, String action) {
        KeyMapping mapping = switch (name) {
            case "attack" -> mc.options.keyAttack;
            case "use" -> mc.options.keyUse;
            case "jump" -> mc.options.keyJump;
            case "forward" -> mc.options.keyUp;
            case "back" -> mc.options.keyDown;
            case "left" -> mc.options.keyLeft;
            case "right" -> mc.options.keyRight;
            case "sneak" -> mc.options.keyShift;
            case "firemode" -> ClientGuns.FIRE_MODE;
            case "sprint" -> mc.options.keySprint;
            case "reload" -> ClientGuns.RELOAD;
            case "inspect" -> WeaponInput.INSPECT;
            case "gunsmith", "craft" -> ClientCraft.KEY;
            case "lobby" -> BlockLandClient.OPEN_LOBBY;
            default -> null;
        };
        if (mapping == null) {
            log("unknown key " + name);
            return;
        }
        switch (action) {
            case "down" -> {
                mapping.setDown(true);
                pressEdge(mc, mapping);
            }
            case "up" -> mapping.setDown(false);
            default -> {
                KeyMapping.click(mapping.getKey());
                mapping.setDown(true);
                releaseNext = mapping;
                // OS input reaches the gun as a press edge; simulated keys have to say so themselves
                pressEdge(mc, mapping);
            }
        }
    }

    /** OS input reaches the weapons as press edges; simulated attack/use presses have to report them themselves. */
    private static void pressEdge(Minecraft mc, KeyMapping mapping) {
        if (mapping == mc.options.keyAttack) WeaponInput.attackPressed();
        if (mapping == mc.options.keyUse) WeaponInput.usePressed();
    }

    private static void state(Minecraft mc, LocalPlayer player) {
        GunStats stats = ClientGuns.stats();
        Entity target = mc.crosshairPickEntity;
        log(String.format("fps=%d holding=%s gun=%s ammo=%d reserve=%d reloading=%s aim=%.2f spread=%.2f yaw=%.1f pitch=%.1f target=%s hp=%.1f",
                mc.getFps(), ClientGuns.holdingGun(), stats == null ? "-" : stats.gun().id, ClientGuns.ammo(), ClientGuns.reserve(),
                ClientGuns.isReloading(), ClientGuns.aim(), ClientGuns.currentSpread(), player.getYRot(), player.getXRot(),
                target == null ? "-" : target.getName().getString(), player.getHealth()));
    }

    private static void save(NativeImage image, String name) {
        try (image) {
            image.writeToFile(dir.resolve(name));
            log("saved " + name);
        } catch (Exception e) {
            log("ERROR saving " + name + " -> " + e);
        }
    }

    private static void log(String message) {
        try {
            String user = Minecraft.getInstance().getUser().getName();
            Files.writeString(dir.resolve(user + ".log"), LocalTime.now().withNano(0) + " " + message + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // the log is best effort
        }
    }
}
