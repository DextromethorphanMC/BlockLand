package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.PartyPackets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * Party strip (top right of the play tab): member heads, pending invitations and a "+" slot that opens a
 * name field to invite a player. Invitations to other parties appear below as cards with accept/decline.
 * Talks to the server only through {@link PartyPackets.C2SAction}.
 */
public final class PartyPanel {
    private static final int SLOT = 20;
    private static final int GAP = 4;
    private static final int POP_W = 176;
    private static final int POP_H = 44;
    private static final int CARD_W = 176;
    private static final int CARD_H = 38;

    private final EditBox input;
    private final AnimValue popover = new AnimValue(0);
    private final Map<String, Spring> hover = new HashMap<>();
    private final Map<UUID, AnimValue> cardIn = new HashMap<>();
    private boolean popoverOpen;
    private final List<Hit> hits = new ArrayList<>();
    private int stripLeft;
    private int stripBottom;

    /** A clickable region recorded while rendering. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public PartyPanel(Font font) {
        this.input = new EditBox(font, 0, 0, 120, 16, Component.translatable("blockland.party.name_field"));
        input.setMaxLength(16);
        input.setHint(Component.translatable("blockland.party.name_hint").withColor(DIM));
    }

    /** Left edge of the strip after the last render, so the screen can keep other content clear of it. */
    public int left() {
        return stripLeft;
    }

    /** Bottom of everything drawn last frame (strip, invite field, invitation cards). */
    public int bottom() {
        return stripBottom;
    }

    public boolean isTyping() {
        return popoverOpen;
    }

    // ---- rendering ------------------------------------------------------------------------------

    /**
     * @param right right edge of the strip
     * @param top   top of the strip
     */
    public void render(GuiGraphics g, int mouseX, int mouseY, int right, int top, float alpha) {
        hits.clear();
        UUID self = ClientLobbyState.selfId();
        PartyPackets.PartyInfo party = ClientLobbyState.party();
        boolean leader = ClientLobbyState.isPartyLeader(self);
        boolean inParty = !party.members().isEmpty();

        List<PartyPackets.Member> members = inParty ? party.members()
                : List.of(new PartyPackets.Member(self, ClientLobbyState.selfName(), true,
                ClientLobbyState.isQueued() ? PartyPackets.Status.QUEUED : PartyPackets.Status.LOBBY));
        int slots = Math.max(2, Math.min(party.maxSize(), members.size() + party.pending().size() + 1));
        int width = slots * SLOT + (slots - 1) * GAP;
        int x = right - width;
        String label = inParty ? tr("blockland.party.label", members.size(), party.maxSize()) : tr("blockland.party.solo");
        stripLeft = x - UiFont.width(label) - 8;
        Draw.text(g, label, stripLeft, top + 6, 1f, ColorMath.fade(inParty ? TEXT : MUTED, alpha), false);

        int i = 0;
        String hoveredName = null;
        int hoveredX = 0;
        for (PartyPackets.Member member : members) {
            int sx = x + i * (SLOT + GAP);
            boolean hovered = inside(mouseX, mouseY, sx, top, SLOT, SLOT);
            float hv = spring("m" + member.id(), hovered);
            drawHead(g, member.id(), sx, top, hv, statusColor(member.status()), alpha);
            if (member.leader() && inParty) Draw.text(g, "★", sx + SLOT - 6, top - 4, 1f, ColorMath.fade(GOLD, alpha), true);
            if (hovered) {
                hoveredName = member.name() + statusLabel(member.status()) + (member.leader() && inParty ? tr("blockland.party.leader_suffix") : "");
                hoveredX = sx + SLOT / 2;
            }
            boolean isSelf = member.id().equals(self);
            if (inParty && (isSelf || leader) && hv > 0.05f) {
                // ×: leave (own slot) or kick (leader on another member)
                int bx = sx + SLOT - 7, by = top + SLOT - 7;
                Draw.rect(g, bx, by, 8, 8, ColorMath.fade(CANCEL, alpha * hv));
                Draw.text(g, "×", bx + 1.5f, by - 0.5f, 1f, ColorMath.fade(TEXT, alpha * hv), false);
                UUID id = member.id();
                hits.add(new Hit(bx - 1, by - 1, 10, 10, isSelf
                        ? () -> send(PartyPackets.Action.LEAVE, "")
                        : () -> send(PartyPackets.Action.KICK, id.toString())));
                if (inside(mouseX, mouseY, bx - 1, by - 1, 10, 10)) hoveredName = isSelf ? tr("blockland.party.leave") : tr("blockland.party.kick", member.name());
            }
            if (inParty && leader && !isSelf && hv > 0.05f) {
                // ★: hand the party over to this member
                int px = sx - 1, py = top - 1;
                Draw.rect(g, px, py, 8, 8, ColorMath.fade(GOLD, alpha * hv));
                Draw.text(g, "★", px + 1, py, 1f, ColorMath.fade(0xFF1A1206, alpha * hv), false);
                UUID id = member.id();
                hits.add(new Hit(px - 1, py - 1, 10, 10, () -> send(PartyPackets.Action.PROMOTE, id.toString())));
                if (inside(mouseX, mouseY, px - 1, py - 1, 10, 10)) hoveredName = tr("blockland.party.promote", member.name());
            }
            i++;
        }
        for (String pending : party.pending()) {
            if (i >= slots) break;
            int sx = x + i * (SLOT + GAP);
            Draw.rect(g, sx, top, SLOT, SLOT, ColorMath.fade(0x30FFFFFF, alpha));
            String dots = ".".repeat(1 + (int) (Ticker.global().time() * 2 % 3));
            Draw.text(g, dots, sx + (SLOT - UiFont.width(dots)) / 2f, top + 5, 1f, ColorMath.fade(MUTED, alpha), false);
            if (inside(mouseX, mouseY, sx, top, SLOT, SLOT)) {
                hoveredName = tr("blockland.party.pending", pending);
                hoveredX = sx + SLOT / 2;
            }
            i++;
        }
        if (i < slots) {
            // invite slot
            int sx = x + i * (SLOT + GAP);
            boolean hovered = inside(mouseX, mouseY, sx, top, SLOT, SLOT);
            float hv = spring("add", hovered || popoverOpen);
            Draw.rect(g, sx, top, SLOT, SLOT, ColorMath.fade(ColorMath.mix(0xFF1A2333, 0xFF2A3650, hv), alpha));
            Draw.outline(g, sx, top, SLOT, SLOT, ColorMath.fade(ColorMath.mix(LINE, ACCENT, hv), alpha));
            Draw.text(g, "+", sx + 7, top + 5.5f, 1.2f, ColorMath.fade(ColorMath.mix(MUTED, ACCENT, hv), alpha), false);
            hits.add(new Hit(sx, top, SLOT, SLOT, () -> setPopover(!popoverOpen)));
            if (hovered && !popoverOpen) {
                hoveredName = tr("blockland.party.invite_title");
                hoveredX = sx + SLOT / 2;
            }
        }

        int below = top + SLOT + 6;
        float pop = popover.get();
        if (pop > 0.01f) {
            renderPopover(g, mouseX, mouseY, right - POP_W, below, alpha * pop, (1 - pop) * -6);
            below += POP_H + 6;
        }
        stripBottom = renderInvites(g, mouseX, mouseY, right, below, alpha);

        if (hoveredName != null) {
            int tw = UiFont.width(hoveredName) + 8;
            int tx = Math.min(right - tw, hoveredX - tw / 2);
            Draw.rect(g, tx, top + SLOT + 3, tw, 12, ColorMath.fade(0xF005080D, alpha));
            Draw.text(g, hoveredName, tx + 4, top + SLOT + 5, 1f, ColorMath.fade(TEXT, alpha), false);
        }
    }

    private void renderPopover(GuiGraphics g, int mouseX, int mouseY, int px, int py, float a, float shift) {
        g.pose().pushMatrix();
        g.pose().translate(0, shift);
        Draw.rect(g, px, py, POP_W, POP_H, ColorMath.fade(0xF20C121C, a));
        Draw.outline(g, px, py, POP_W, POP_H, ColorMath.fade(ColorMath.withAlpha(ACCENT, 0x66), a));
        Draw.text(g, tr("blockland.party.invite_title"), px + 8, py + 6, 1f, ColorMath.fade(TEXT, a), false);
        // the EditBox only edits; the field is drawn here so it matches the lobby and fades with the popover
        int fx = px + 8, fy = py + 20, fw = POP_W - 66, fh = 16;
        input.setX(fx);
        input.setY(fy);
        input.setWidth(fw);
        Draw.rect(g, fx, fy, fw, fh, ColorMath.fade(0xFF05080D, a));
        Draw.outline(g, fx, fy, fw, fh, ColorMath.fade(popoverOpen ? ACCENT : LINE, a * 0.8f));
        String value = input.getValue();
        if (value.isEmpty()) {
            Draw.text(g, tr("blockland.party.name_hint"), fx + 5, fy + 4, 1f, ColorMath.fade(DIM, a), false);
        } else {
            Draw.text(g, Draw.fit(value, fw - 12), fx + 5, fy + 4, 1f, ColorMath.fade(TEXT, a), false);
        }
        if (popoverOpen && (long) (Ticker.global().time() * 2) % 2 == 0) {
            int caret = fx + 5 + Math.min(UiFont.width(value), fw - 12);
            g.fill(caret, fy + 3, caret + 1, fy + fh - 3, ColorMath.fade(ACCENT, a));
        }
        int bx = px + POP_W - 52, by = py + 20, bw = 44, bh = 16;
        boolean hovered = inside(mouseX, mouseY, bx, by, bw, bh);
        float hv = spring("send", hovered);
        boolean enabled = !input.getValue().isBlank();
        Draw.rect(g, bx, by, bw, bh, ColorMath.fade(enabled ? ColorMath.lighten(GO, 0.1f * hv) : DISABLED, a));
        String inviteLabel = tr("blockland.party.invite");
        Draw.text(g, inviteLabel, bx + (bw - UiFont.width(inviteLabel)) / 2f, by + 4, 1f, ColorMath.fade(enabled ? TEXT : MUTED, a), false);
        g.pose().popMatrix();
        if (popoverOpen) hits.add(new Hit(bx, by, bw, bh, this::submit));
    }

    /** @return the y below the last card */
    private int renderInvites(GuiGraphics g, int mouseX, int mouseY, int right, int top, float alpha) {
        List<PartyPackets.Invite> invites = ClientLobbyState.party().invites();
        cardIn.keySet().removeIf(id -> invites.stream().noneMatch(inv -> inv.fromId().equals(id)));
        int y = top;
        for (PartyPackets.Invite invite : invites.subList(0, Math.min(3, invites.size()))) {
            AnimValue in = cardIn.computeIfAbsent(invite.fromId(), id -> {
                AnimValue value = new AnimValue(0);
                Anim.to(value, 1, 0.5).ease(Ease.BACK_OUT);
                return value;
            });
            float e = in.get();
            float a = alpha * Math.min(1, Math.max(0, e));
            int x = Math.round(right - CARD_W + (1 - e) * 40);
            int left = ClientLobbyState.secondsLeft(invite);
            Draw.glow(g, x + CARD_W / 2f, y + CARD_H / 2f, CARD_W * 0.6f, CARD_H, ColorMath.fade(ACCENT, 0.12f * a));
            Draw.rect(g, x, y, CARD_W, CARD_H, ColorMath.fade(0xF2141C29, a));
            Draw.rect(g, x, y, 3, CARD_H, ColorMath.fade(ACCENT, a));
            drawFace(g, invite.fromId(), x + 8, y + 7, 12, a);
            String title = tr("blockland.party.invited_card", Draw.fit(invite.fromName(), CARD_W - 110));
            Draw.text(g, title, x + 24, y + 6, 1f, ColorMath.fade(TEXT, a), false);
            Draw.text(g, tr("blockland.party.invite_detail", invite.partySize(), left), x + 24, y + 17, 1f, ColorMath.fade(MUTED, a), false);
            // expiry bar
            float frac = Math.min(1, left / 60f);
            g.fill(x + 3, y + CARD_H - 2, x + 3 + Math.round((CARD_W - 3) * frac), y + CARD_H, ColorMath.fade(ACCENT, a * 0.7f));

            UUID from = invite.fromId();
            int bw = 30, bh = 12, by = y + 7;
            int acceptX = x + CARD_W - bw * 2 - 10, declineX = x + CARD_W - bw - 6;
            float ha = spring("acc" + from, inside(mouseX, mouseY, acceptX, by, bw, bh));
            float hd = spring("dec" + from, inside(mouseX, mouseY, declineX, by, bw, bh));
            Draw.rect(g, acceptX, by, bw, bh, ColorMath.fade(ColorMath.lighten(GO, 0.12f * ha), a));
            String accept = tr("blockland.party.accept"), decline = tr("blockland.party.decline");
            Draw.text(g, accept, acceptX + (bw - UiFont.width(accept)) / 2f, by + 2, 1f, ColorMath.fade(TEXT, a), false);
            Draw.rect(g, declineX, by, bw, bh, ColorMath.fade(ColorMath.mix(0xFF2A3344, CANCEL, hd), a));
            Draw.text(g, decline, declineX + (bw - UiFont.width(decline)) / 2f, by + 2, 1f, ColorMath.fade(TEXT, a), false);
            hits.add(new Hit(acceptX, by, bw, bh, () -> send(PartyPackets.Action.ACCEPT, from.toString())));
            hits.add(new Hit(declineX, by, bw, bh, () -> send(PartyPackets.Action.DECLINE, from.toString())));
            y += CARD_H + 4;
        }
        return y;
    }

    private void drawHead(GuiGraphics g, UUID id, int x, int y, float hv, int ring, float alpha) {
        float lift = hv * 1.5f;
        Draw.glow(g, x + SLOT / 2f, y + SLOT / 2f, SLOT * 0.8f, SLOT * 0.8f, ColorMath.fade(ring, alpha * (0.15f + 0.25f * hv)));
        Draw.rect(g, x, Math.round(y - lift), SLOT, SLOT, ColorMath.fade(ring, alpha));
        drawFace(g, id, x + 2, Math.round(y + 2 - lift), SLOT - 4, alpha);
    }

    private static void drawFace(GuiGraphics g, UUID id, int x, int y, int size, float alpha) {
        if (alpha < 0.5f) return; // faces cannot fade; hide them during transitions instead
        PlayerFaceRenderer.draw(g, Skins.of(id), x, y, size);
    }

    // ---- input ----------------------------------------------------------------------------------

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (popoverOpen && input.isMouseOver(mouseX, mouseY)) {
            input.setFocused(true);
            return input.mouseClicked(mouseX, mouseY, button);
        }
        for (Hit hit : hits) {
            if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                hit.action.run();
                return true;
            }
        }
        if (popoverOpen) {
            setPopover(false);
            return true;
        }
        return false;
    }

    /** @return true when the key was consumed (typing into the name field, Enter, Escape) */
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!popoverOpen) return false;
        if (key == 256) { // escape
            setPopover(false);
            return true;
        }
        if (key == 257 || key == 335) { // enter
            submit();
            return true;
        }
        input.keyPressed(key, scanCode, modifiers);
        return true;
    }

    public boolean charTyped(char c, int modifiers) {
        return popoverOpen && input.charTyped(c, modifiers);
    }

    private void submit() {
        String name = input.getValue().trim();
        if (name.isEmpty()) return;
        send(PartyPackets.Action.INVITE, name);
        input.setValue("");
        setPopover(false);
    }

    private void setPopover(boolean open) {
        popoverOpen = open;
        input.setFocused(open);
        if (open) input.setValue("");
        Anim.to(popover, open ? 1 : 0, open ? 0.35 : 0.15).ease(open ? Ease.BACK_OUT : Ease.QUAD_IN);
    }

    private static void send(PartyPackets.Action action, String argument) {
        BLNetwork.sendToServer(new PartyPackets.C2SAction(action, argument));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private float spring(String key, boolean on) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static int statusColor(PartyPackets.Status status) {
        return switch (status) {
            case LOBBY -> INFO;
            case QUEUED -> ACCENT;
            case IN_GAME -> OK;
        };
    }

    private static String statusLabel(PartyPackets.Status status) {
        return switch (status) {
            case LOBBY -> "";
            case QUEUED -> tr("blockland.party.status.queued");
            case IN_GAME -> tr("blockland.party.status.in_game");
        };
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
