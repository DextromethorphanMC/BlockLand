package com.blockland.client.infection;

import com.blockland.BlockLand;
import com.blockland.network.BLNetwork;
import com.blockland.network.InfectionPacketListeners;
import com.blockland.network.packet.InfectionPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Client side of 枪战感染: the monster selection panel when infected, the skill key and the monster HUD. Everything
 * shown (monsters, health, skills, cooldowns) comes from the server; the client only remembers its own role.
 */
public final class ClientInfection {
    public static final KeyMapping SKILL = new KeyMapping("key.blockland.monster_skill", InputConstants.KEY_V, "key.categories.blockland");

    private static InfectionPackets.S2CRole role = InfectionPackets.S2CRole.NONE;
    private static long skillReadyAt;
    private static long skillActiveUntil;
    private static long skillUsedAt;

    private ClientInfection() {
    }

    public static void init(BusGroup modBus) {
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(SKILL));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("infection_hud"), ForgeLayeredDraw.CROSSHAIR, InfectionHud::render));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            Minecraft mc = Minecraft.getInstance();
            while (SKILL.consumeClick()) {
                if (isMonster() && mc.screen == null && System.nanoTime() >= skillReadyAt) BLNetwork.sendToServer(InfectionPackets.C2SUseSkill.INSTANCE);
            }
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> role = InfectionPackets.S2CRole.NONE);
        BLNetwork.setInfectionClientListener(new InfectionPacketListeners.Client() {
            @Override
            public void onSelect(InfectionPackets.S2CSelect select) {
                if (!select.options().isEmpty()) Minecraft.getInstance().setScreen(new MonsterSelectScreen(select));
            }

            @Override
            public void onRole(InfectionPackets.S2CRole received) {
                role = received;
                skillReadyAt = skillActiveUntil = skillUsedAt = 0;
                // the monster has been applied (chosen or timed out): the panel is done
                if (received.role() != InfectionPackets.Role.NONE && Minecraft.getInstance().screen instanceof MonsterSelectScreen screen) {
                    screen.onClose();
                }
            }

            @Override
            public void onSkill(InfectionPackets.S2CSkill skill) {
                long now = System.nanoTime();
                skillUsedAt = now;
                skillReadyAt = now + (long) (skill.cooldown() * 1e9);
                skillActiveUntil = now + (long) (skill.active() * 1e9);
            }
        });
    }

    static InfectionPackets.S2CRole role() {
        return role;
    }

    static boolean isMonster() {
        return role.role() == InfectionPackets.Role.MONSTER;
    }

    /** Seconds until the skill is ready (0 = ready). */
    static float skillCooldownLeft() {
        return Math.max(0, (skillReadyAt - System.nanoTime()) / 1e9f);
    }

    /** 0..1 of the skill's active time left (0 = not active). */
    static float skillActive() {
        long now = System.nanoTime();
        if (now >= skillActiveUntil || skillActiveUntil <= skillUsedAt) return 0;
        return (skillActiveUntil - now) / (float) (skillActiveUntil - skillUsedAt);
    }
}
