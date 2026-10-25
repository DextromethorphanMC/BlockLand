package com.blockland.games.bomb;

import com.blockland.BlockLand;
import com.blockland.bomb.BombCatalog;
import com.blockland.bomb.BombContent;
import com.blockland.bomb.BombData;
import com.blockland.bomb.PlantedBomb;
import com.blockland.games.common.MatchText;
import com.blockland.games.common.TeamArenaGame;
import com.blockland.movement.action.BodyActions;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.BombPackets;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 枪战爆破 on top of the shared team arena flow. Team 0 (spawn group 1) attacks: one attacker carries the bomb, plants it
 * by holding the attack button inside a site, and wins when it goes off; team 1 defends: it wins when the round time
 * runs out before a plant, or by defusing the planted bomb. Eliminating the other side wins too — except that a planted
 * bomb still has to be defused.
 * <p>
 * Everything is decided here, on the server: who carries the bomb, whether a plant or a defuse is allowed (in a site,
 * on the ground, bomb in hand / close enough and looking at it, nobody else at it), their progress and the fuse, all
 * counted in server ticks. The clients only report that a button is held ({@code C2SPlant} / {@code C2SDefuse}) and get
 * back what they may know ({@code S2CState}: defenders never learn who carries the bomb or where it was dropped).
 */
final class BombGame extends TeamArenaGame {
    static final int ATTACKERS = 0, DEFENDERS = 1;
    /** Planting and defusing hold the player in place (like kneeling at it). */
    private static final ResourceLocation ROOTED = BlockLand.id("bomb_rooted");

    private final BombSettings settings;
    private final BombSites sites;
    private final BombData data = BombCatalog.get();
    private final List<BombPackets.Site> worldSites = new ArrayList<>();

    /** Who has the bomb in their inventory (null: dropped, planted, or nobody got it yet). */
    @Nullable
    private UUID carrier;
    /** The bomb lying on the ground (an item nobody can pick up the vanilla way; attackers take it by walking over it). */
    @Nullable
    private ItemEntity dropped;
    @Nullable
    private PlantedBomb planted;
    private String plantedSite = "";
    private int bombTicks = -1;
    @Nullable
    private Progress planting;
    @Nullable
    private Progress defusing;
    /** The blast is being dealt: deaths it causes must not end the match before the attackers win by it. */
    private boolean detonating;
    private BombPackets.Phase outcome = BombPackets.Phase.ENDED;
    private int playingTicks;
    private boolean dirty = true;

    /** A plant or a defuse in progress (server ticks). */
    private static final class Progress {
        final UUID player;
        final Vec3 start;
        final String site;
        int done;

        Progress(UUID player, Vec3 start, String site) {
            this.player = player;
            this.start = start;
            this.site = site;
        }
    }

    BombGame(GameServices services, GameModeDef mode, BombSettings settings, MapDef map, BombSites sites, MatchRoster roster) {
        super(services, mode, settings.team(), map, roster);
        this.settings = settings;
        this.sites = sites;
    }

    // ---- team arena hooks -----------------------------------------------------------------------------

    @Override
    protected Component gameName() {
        return Component.translatable("blockland.game.bomb");
    }

    @Override
    protected String teamKey(int index) {
        return index == ATTACKERS ? "blockland.bomb.team.attackers" : index == DEFENDERS ? "blockland.bomb.team.defenders" : super.teamKey(index);
    }

    /** Spawn group 1 attacks, group 2 defends, whatever else the map offers. */
    @Override
    protected int spawnGroupOf(int index, int groups) {
        return Math.min(index, groups - 1);
    }

    @Override
    protected void onArenaReady() {
        Arena arena = arena();
        worldSites.clear();
        for (BombSites.Site s : sites.sites()) {
            BlockPos min = arena.toWorld(s.minX(), s.minY(), s.minZ()), max = arena.toWorld(s.maxX(), s.maxY(), s.maxZ());
            worldSites.add(new BombPackets.Site(s.name(), min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        }
        List<UUID> attackers = teamMembers(ATTACKERS);
        if (!attackers.isEmpty()) carrier = attackers.get(random.nextInt(attackers.size()));
    }

    @Override
    protected void onPlayerSpawned(ServerPlayer player) {
        services.kits().apply(player, mode);
        if (player.getUUID().equals(carrier)) giveBomb(player);
        dirty = true;
    }

    @Override
    protected boolean dropsItemsOnElimination() {
        return false;
    }

    @Override
    protected GameType playingGameMode() {
        return settings.allowBlockBreak() || settings.allowBlockPlace() ? GameType.SURVIVAL : GameType.ADVENTURE;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return settings.allowBlockBreak() && super.allowBlockBreak(player, pos);
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return settings.allowBlockPlace() && super.allowBlockPlace(player, pos);
    }

    /** A planted bomb must be defused: defenders standing alone (or nobody) is not a win yet. */
    @Override
    protected boolean eliminationEnds(List<Integer> survivors) {
        if (detonating) return false;
        if (planted != null) return survivors.equals(List.of(ATTACKERS));
        return true;
    }

    /** The round time is the time to plant: run out without a plant, the defenders win; a planted bomb plays on. */
    @Override
    protected boolean onTimeUp() {
        if (planted == null) endMatch(DEFENDERS, Component.translatable("blockland.bomb.reason.time_up"));
        return true;
    }

    @Override
    protected Component clockText() {
        if (planted != null) return Component.translatable("blockland.bomb.hud.clock_planted", PlayerUtil.formatTime((bombTicks + 19) / 20));
        return super.clockText();
    }

    @Override
    protected Component actionBar(ServerPlayer player) {
        return Component.empty()
                .append(teamName(ATTACKERS)).append(" " + aliveInTeam(ATTACKERS))
                .append(Component.literal(" : ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(aliveInTeam(DEFENDERS) + " ").withStyle(ChatFormatting.WHITE)).append(teamName(DEFENDERS))
                .append(MatchText.SEPARATOR)
                .append(clockText().copy().withStyle(planted != null ? ChatFormatting.RED : ChatFormatting.YELLOW));
    }

    @Override
    protected void onEliminated(ServerPlayer player) {
        letGo(player.getUUID(), player);
    }

    @Override
    protected void onPlayerLeft(UUID id, @Nullable ServerPlayer player) {
        letGo(id, player);
    }

    @Override
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        super.removePlayer(id, player, disconnected);
        if (player != null && !disconnected) {
            unroot(player);
            BLNetwork.sendTo(player, BombPackets.S2CState.NONE);
        }
        dirty = true;
    }

    @Override
    public void dispose() {
        forEachOnline(p -> {
            unroot(p);
            BLNetwork.sendTo(p, BombPackets.S2CState.NONE);
        });
        if (dropped != null) dropped.discard();
        if (planted != null) planted.discard();
        dropped = null;
        planted = null;
        super.dispose();
    }

    // ---- ticking --------------------------------------------------------------------------------------

    @Override
    public void tick() {
        Phase before = phase();
        super.tick();
        if (phase() == Phase.PLAYING) {
            playingTicks++;
            tickBomb();
        } else if (planting != null || defusing != null) {
            // the match was decided some other way meanwhile
            stopPlanting();
            stopDefusing();
        }
        if (phase() != before) dirty = true;
        if (phase() == Phase.FINISHED) return;
        if (dirty || services.server().getTickCount() % 20 == 0) sendStates();
    }

    private void tickBomb() {
        if (planting != null) tickPlanting(planting);
        if (defusing != null) tickDefusing(defusing);
        if (phase() != Phase.PLAYING) return;
        if (planted != null && --bombTicks <= 0) {
            detonate();
            return;
        }
        if (dropped != null) tickDropped();
        // whoever should have the bomb but never got it (left before it was handed out): another attacker takes it
        if (planted == null && dropped == null && (carrier == null || online(carrier) == null || !isAlive(carrier))) reassign();
    }

    private void tickPlanting(Progress progress) {
        ServerPlayer player = online(progress.player);
        if (player == null || !canKeepPlanting(player, progress)) {
            stopPlanting();
            return;
        }
        // the planter hears the keys from their own hands (the clip's events)
        if (++progress.done % 16 == 1) playAt(player, player.position(), "bomb.key", 0.7f, 1);
        if (progress.done >= settings.plantTicks()) plant(player, progress.site);
    }

    private void tickDefusing(Progress progress) {
        ServerPlayer player = online(progress.player);
        if (player == null || planted == null || !canDefuse(player)) {
            stopDefusing();
            return;
        }
        if (++progress.done % 20 == 1) playAt(planted.position(), "bomb.defuse", 0.8f, 1);
        if (progress.done >= settings.defuseTicks()) defused(player);
    }

    private void tickDropped() {
        ItemEntity item = dropped;
        if (item == null) return;
        if (!item.isAlive() || item.getY() < voidY()) {
            // fell out of the map: it comes back to a living attacker
            item.discard();
            dropped = null;
            reassign();
            return;
        }
        for (UUID id : teamMembers(ATTACKERS)) {
            ServerPlayer player = online(id);
            if (player == null || !isAlive(id) || player.isSpectator()) continue;
            if (player.distanceToSqr(item.position()) <= data.pickupRange * data.pickupRange) {
                item.discard();
                dropped = null;
                carrier = id;
                giveBomb(player);
                PlayerUtil.sound(player, BombContent.sound("bomb.pickup"), 1, 1);
                tellAttackers(Component.translatable("blockland.bomb.picked_up", coloredName(id)).withStyle(ChatFormatting.YELLOW));
                dirty = true;
                return;
            }
        }
    }

    // ---- input (from the clients) ---------------------------------------------------------------------

    void onPlant(ServerPlayer player, boolean holding) {
        if (!holding) {
            if (planting != null && planting.player.equals(player.getUUID())) stopPlanting();
            return;
        }
        if (planting != null || phase() != Phase.PLAYING || planted != null || !player.getUUID().equals(carrier)) return;
        BombPackets.Site site = siteAt(player.position());
        if (site == null || !canPlant(player)) {
            dirty = true;
            return;
        }
        planting = new Progress(player.getUUID(), player.position(), site.name());
        root(player);
        dirty = true;
    }

    void onDefuse(ServerPlayer player, boolean holding) {
        if (!holding) {
            if (defusing != null && defusing.player.equals(player.getUUID())) stopDefusing();
            return;
        }
        if (defusing != null || phase() != Phase.PLAYING || planted == null || !canDefuse(player)) return;
        defusing = new Progress(player.getUUID(), player.position(), plantedSite);
        planted.startDefuse(planted.level().getGameTime() + settings.defuseTicks(), settings.defuseTicks());
        root(player);
        playAt(planted.position(), "bomb.defuse_start", 1, 1);
        dirty = true;
    }

    /** The carrier tossed the bomb (Q): it lands in front of them. */
    boolean onToss(ServerPlayer player) {
        if (phase() != Phase.PLAYING || !player.getUUID().equals(carrier) || planting != null) {
            // keep it in the hand: the client already took it out of the slot
            player.containerMenu.sendAllDataToRemote();
            return true;
        }
        takeBomb(player);
        Vec3 look = player.getLookAngle();
        drop(BodyActions.eyePosition(player).add(0, -0.3, 0), new Vec3(look.x * 0.3, 0.15, look.z * 0.3));
        return true;
    }

    // ---- rules ----------------------------------------------------------------------------------------

    private boolean canPlant(ServerPlayer player) {
        return isAlive(player.getUUID()) && !player.isSpectator() && player.onGround() && holdsBomb(player);
    }

    private boolean canKeepPlanting(ServerPlayer player, Progress progress) {
        if (phase() != Phase.PLAYING || planted != null || !player.getUUID().equals(carrier) || !canPlant(player)) return false;
        BombPackets.Site site = siteAt(player.position());
        if (site == null || !site.name().equals(progress.site)) return false;
        double dx = player.getX() - progress.start.x, dz = player.getZ() - progress.start.z;
        return dx * dx + dz * dz <= data.plantDrift * data.plantDrift;
    }

    /** A living defender, close to the bomb and looking at it. */
    private boolean canDefuse(ServerPlayer player) {
        if (planted == null || teamIndexOf(player.getUUID()) != DEFENDERS || !isAlive(player.getUUID()) || player.isSpectator()) return false;
        Vec3 eye = BodyActions.eyePosition(player), target = planted.position().add(0, 0.1, 0);
        Vec3 to = target.subtract(eye);
        double distance = to.length();
        if (distance > data.defuseRange) return false;
        if (distance < 0.6) return true;
        double cos = player.getLookAngle().dot(to.scale(1 / distance));
        return cos >= Math.cos(Math.toRadians(data.defuseAngle));
    }

    private static boolean holdsBomb(ServerPlayer player) {
        return BombContent.isBomb(player.getMainHandItem());
    }

    @Nullable
    private BombPackets.Site siteAt(Vec3 feet) {
        for (BombPackets.Site site : worldSites) {
            if (site.contains(feet.x, feet.y + 0.1, feet.z)) return site;
        }
        return null;
    }

    // ---- what happens ---------------------------------------------------------------------------------

    private void plant(ServerPlayer player, String site) {
        stopPlanting();
        ServerLevel level = arena().level();
        PlantedBomb bomb = BombContent.PLANTED_BOMB.get().create(level, EntitySpawnReason.TRIGGERED);
        if (bomb == null) return;
        takeBomb(player);
        Vec3 at = player.position();
        bomb.setPos(at.x, at.y, at.z);
        bomb.setYRot(player.getYRot());
        bomb.arm(level.getGameTime(), settings.fuseTicks(), site);
        level.addFreshEntity(bomb);
        planted = bomb;
        plantedSite = site;
        bombTicks = settings.fuseTicks();
        carrier = null;
        playAt(at, "bomb.planted", 1.5f, 1);
        Component siteName = Component.literal(site).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        forEachOnline(p -> {
            boolean attacker = teamIndexOf(p.getUUID()) == ATTACKERS;
            PlayerUtil.title(p, Component.translatable("blockland.bomb.planted.title").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.translatable(attacker ? "blockland.bomb.planted.attackers" : "blockland.bomb.planted.defenders", siteName)
                            .withStyle(ChatFormatting.GRAY), 3, 40, 10);
        });
        broadcast(Component.translatable("blockland.bomb.planted.chat", coloredName(player.getUUID()), siteName).withStyle(ChatFormatting.GRAY));
        dirty = true;
    }

    private void defused(ServerPlayer player) {
        stopDefusing();
        if (planted == null) return;
        planted.setState(PlantedBomb.State.DEFUSED);
        playAt(planted.position(), "bomb.defused", 1.5f, 1);
        broadcast(Component.translatable("blockland.bomb.defused.chat", coloredName(player.getUUID())).withStyle(ChatFormatting.GRAY));
        outcome = BombPackets.Phase.DEFUSED;
        bombTicks = -1;
        endMatch(DEFENDERS, Component.translatable("blockland.bomb.reason.defused"));
        dirty = true;
    }

    private void detonate() {
        PlantedBomb bomb = planted;
        if (bomb == null) return;
        stopDefusing();
        ServerLevel level = arena().level();
        Vec3 at = bomb.position();
        bomb.setState(PlantedBomb.State.EXPLODED);
        level.playSound(null, at.x, at.y, at.z, BombContent.sound("bomb.explode"), SoundSource.PLAYERS, 12f, 0.95f + level.random.nextFloat() * 0.1f);
        BombPackets.S2CDetonate packet = new BombPackets.S2CDetonate(at.x, at.y + 0.1, at.z, data.blast.radius, data.blast.shake);
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(at) < 256 * 256) BLNetwork.sendTo(viewer, packet);
        }
        detonating = true;
        try {
            BombBlast.explode(level, bomb, data.blast);
        } finally {
            detonating = false;
        }
        bomb.discard();
        planted = null;
        bombTicks = -1;
        outcome = BombPackets.Phase.EXPLODED;
        endMatch(ATTACKERS, Component.translatable("blockland.bomb.reason.exploded"));
        dirty = true;
    }

    /** The player is out of the fight: what they were doing stops and the bomb falls where they stood. */
    private void letGo(UUID id, @Nullable ServerPlayer player) {
        if (planting != null && planting.player.equals(id)) stopPlanting();
        if (defusing != null && defusing.player.equals(id)) stopDefusing();
        if (!id.equals(carrier)) return;
        carrier = null;
        if (player != null) {
            takeBomb(player);
            // fallen into the void: nothing to drop, another attacker gets it next tick
            if (player.getY() >= voidY()) drop(player.position().add(0, 0.4, 0), Vec3.ZERO);
        }
        dirty = true;
    }

    /** World Y below which something has left the map (the team arena's void line). */
    private double voidY() {
        return arena().origin().getY() + (map.voidY == null ? -10 : map.voidY);
    }

    private void reassign() {
        List<UUID> candidates = new ArrayList<>();
        for (UUID id : teamMembers(ATTACKERS)) {
            ServerPlayer player = online(id);
            if (player != null && isAlive(id) && !player.isSpectator()) candidates.add(id);
        }
        carrier = null;
        if (candidates.isEmpty()) return;
        UUID chosen = candidates.get(random.nextInt(candidates.size()));
        ServerPlayer player = online(chosen);
        if (player == null) return;
        carrier = chosen;
        giveBomb(player);
        PlayerUtil.sound(player, BombContent.sound("bomb.pickup"), 1, 1);
        dirty = true;
    }

    private void giveBomb(ServerPlayer player) {
        if (hasBomb(player)) return;
        if (!services.kits().giveRoleItem(player, mode, BombContent.ITEM_KEY)) {
            player.getInventory().add(new ItemStack(BombContent.C4.get()));
            player.containerMenu.broadcastChanges();
        }
    }

    private static boolean hasBomb(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (BombContent.isBomb(inventory.getItem(i))) return true;
        }
        return false;
    }

    /** Removes every bomb stack the player has. */
    private static void takeBomb(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (BombContent.isBomb(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        }
        player.containerMenu.broadcastChanges();
    }

    private void drop(Vec3 at, Vec3 motion) {
        if (dropped != null) dropped.discard();
        ServerLevel level = arena().level();
        ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(BombContent.C4.get()));
        item.setNeverPickUp();
        item.setUnlimitedLifetime();
        item.setDeltaMovement(motion);
        level.addFreshEntity(item);
        dropped = item;
        carrier = null;
        playAt(at, "bomb.drop", 0.8f, 1);
        tellAttackers(Component.translatable("blockland.bomb.dropped").withStyle(ChatFormatting.YELLOW));
        dirty = true;
    }

    private void stopPlanting() {
        Progress progress = planting;
        planting = null;
        if (progress != null) {
            ServerPlayer player = online(progress.player);
            if (player != null) unroot(player);
        }
        dirty = true;
    }

    private void stopDefusing() {
        Progress progress = defusing;
        defusing = null;
        if (planted != null) planted.stopDefuse();
        if (progress != null) {
            ServerPlayer player = online(progress.player);
            if (player != null) unroot(player);
        }
        dirty = true;
    }

    private static void root(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.addOrUpdateTransientModifier(new AttributeModifier(ROOTED, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) jump.addOrUpdateTransientModifier(new AttributeModifier(ROOTED, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void unroot(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(ROOTED);
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) jump.removeModifier(ROOTED);
    }

    private void playAt(Vec3 at, String sound, float volume, float pitch) {
        playAt(null, at, sound, volume, pitch);
    }

    /** A world sound for everyone around except {@code except}. */
    private void playAt(@Nullable ServerPlayer except, Vec3 at, String sound, float volume, float pitch) {
        if (arena() == null) return;
        arena().level().playSound(except, at.x, at.y, at.z, BombContent.sound(sound), SoundSource.PLAYERS, volume, pitch);
    }

    private void tellAttackers(Component message) {
        for (UUID id : teamMembers(ATTACKERS)) {
            ServerPlayer player = online(id);
            if (player != null) player.sendSystemMessage(message);
        }
    }

    // ---- what each player is told ---------------------------------------------------------------------

    private void sendStates() {
        dirty = false;
        forEachOnline(p -> BLNetwork.sendTo(p, stateFor(p)));
    }

    private BombPackets.S2CState stateFor(ServerPlayer player) {
        UUID id = player.getUUID();
        int team = teamIndexOf(id);
        if (team < 0) return BombPackets.S2CState.NONE;
        boolean attacker = team == ATTACKERS;
        BombPackets.Phase phase = switch (phase()) {
            case LOADING, COUNTDOWN -> BombPackets.Phase.WAITING;
            case PLAYING -> planted != null ? BombPackets.Phase.PLANTED : BombPackets.Phase.LIVE;
            case ENDING, FINISHED -> outcome;
        };
        int limit = mode.timeLimitOrZero();
        int roundTicks = phase() == Phase.PLAYING && planted == null && limit > 0 ? Math.max(0, limit * 20 - playingTicks) : -1;
        Entity drop = attacker ? dropped : null;
        String carrierName = attacker && carrier != null ? playerName(carrier) : "";
        BombPackets.Action action = BombPackets.Action.NONE;
        int actionTicks = 1, actionDone = 0;
        if (planting != null && planting.player.equals(id)) {
            action = BombPackets.Action.PLANTING;
            actionTicks = settings.plantTicks();
            actionDone = planting.done;
        } else if (defusing != null && defusing.player.equals(id)) {
            action = BombPackets.Action.DEFUSING;
            actionTicks = settings.defuseTicks();
            actionDone = defusing.done;
        }
        return new BombPackets.S2CState(attacker ? BombPackets.Side.ATTACKER : BombPackets.Side.DEFENDER, phase, worldSites,
                id.equals(carrier), carrierName, drop != null, drop == null ? 0 : drop.getX(), drop == null ? 0 : drop.getY(),
                drop == null ? 0 : drop.getZ(), planted != null || outcome == BombPackets.Phase.DEFUSED ? plantedSite : "",
                roundTicks, planted != null ? bombTicks : -1, settings.fuseTicks(), action, actionTicks, actionDone);
    }
}
