package com.blockland.server.anticheat;

import com.blockland.BlockLand;
import com.blockland.server.anticheat.api.MotionAllowance;
import com.blockland.server.anticheat.api.Suspicion;
import com.blockland.server.anticheat.check.CheckCatalog;
import com.blockland.server.anticheat.check.Checks;
import com.blockland.server.anticheat.check.CombatChecks;
import com.blockland.server.anticheat.check.Judge;
import com.blockland.server.anticheat.check.MovementChecks;
import com.blockland.server.anticheat.check.PlaceCheck;
import com.blockland.server.anticheat.enforce.Enforcer;
import com.blockland.server.anticheat.evidence.Confidence;
import com.blockland.server.anticheat.evidence.EvidenceReporter;
import com.blockland.server.anticheat.net.Arrivals;
import com.blockland.server.anticheat.net.NetHooks;
import com.blockland.server.anticheat.net.PacketGuard;
import com.blockland.server.anticheat.predict.Physics;
import com.blockland.server.anticheat.rules.CheckSettings;
import com.blockland.server.anticheat.rules.RuleResolver;
import com.blockland.server.anticheat.rules.RuleSet;
import com.blockland.server.anticheat.track.MovingBlocks;
import com.blockland.server.anticheat.track.PlayerTrack;
import com.blockland.server.anticheat.track.ServerVelocity;
import com.blockland.server.anticheat.track.StateWindow;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.session.PlayerDirectory;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The anti-cheat (anti-cheat spec): composes its layers — packet security ({@link NetHooks} from the mixins,
 * {@link PacketGuard} through the network's inbound guard, pings and the tick clock), state tracking ({@link PlayerTrack}),
 * the physics envelope, the mode rule engine ({@link RuleResolver}), evidence and confidence ({@link Confidence},
 * {@link EvidenceReporter}) and enforcement ({@link Enforcer}) — and is the {@link Judge} every check reports to and the
 * {@link Suspicion} sink other modules report to. Created and wired by the server's composition root; knows the rest of
 * the server only through the functions it is given. Server thread, except {@link #tickEndArrived}.
 */
public final class AntiCheatService implements NetHooks.Listener, Suspicion.Sink, Judge {
    /** Ticks between the pings that measure every player's round trip. */
    private static final int PING_INTERVAL = 10;
    /** A ping unanswered for this long (× tolerance) while the client keeps talking: it holds its answers back. */
    private static final double TRANSACTION_TIMEOUT_SECONDS = 20;
    private static final int MAX_VELOCITIES = 16;

    private final MinecraftServer server;
    private final PlayerDirectory players;
    private final RuleResolver rules;
    private final Function<UUID, String> mapOf;
    private final EvidenceReporter reporter;
    private final Enforcer enforcer;
    private final PacketGuard guard = new PacketGuard();
    private final Arrivals arrivals = new Arrivals();
    private final List<MotionAllowance> allowances = new CopyOnWriteArrayList<>();
    private final MovingBlocks pistons = new MovingBlocks();
    private final MovementChecks movement;
    private final CombatChecks combat;
    private final PlaceCheck place;
    private final Map<UUID, PlayerTrack> tracks = new HashMap<>();

    // cost of the anti-cheat on the server thread (/bl ac stats)
    private long tickNanos;
    private double averageNanos;
    private long maxNanos;
    private long maxWindowStart;

    /** What {@code /bl ac stats} shows. */
    public record Stats(int tracked, double averageMicros, double maxMicros, int queuedReports) {
    }

    /**
     * @param modeOf  the mode of the match a player is in, null outside matches
     * @param mapOf   the map id of a player's match, null when none
     * @param history where entities stood over the last ticks (lag compensation)
     */
    public AntiCheatService(MinecraftServer server, BackendClient backend, PlayerDirectory players, Supplier<ContentSnapshot> content,
                            Function<UUID, GameModeDef> modeOf, Function<UUID, String> mapOf, CombatChecks.PositionHistory history) {
        this.server = server;
        this.players = players;
        this.rules = new RuleResolver(content, modeOf);
        this.mapOf = mapOf;
        this.reporter = new EvidenceReporter(backend);
        this.enforcer = new Enforcer(server, players, reporter);
        this.movement = new MovementChecks(this, allowances, pistons);
        this.combat = new CombatChecks(this, history);
        this.place = new PlaceCheck(this);
    }

    /** A module's movement mechanic the physics does not know (anti-cheat spec §11). */
    public void addAllowance(MotionAllowance allowance) {
        allowances.add(allowance);
    }

    /** A piston started to move (the blocks it moves carry players along: those moves are not judged). */
    public void onPiston(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        pistons.moved(level, pos, server.getTickCount());
    }

    /** Tells the backend which checks exist (the admin UI's list). */
    public void publishRegistry() {
        reporter.publishRegistry();
    }

    private PlayerTrack track(UUID id) {
        return tracks.computeIfAbsent(id, k -> new PlayerTrack(k, CheckCatalog.get().size()));
    }

    // ---- packet security: BlockLand packets -----------------------------------------------------------

    /** The network's inbound guard: rate limits per packet class ({@code packet.rate}). */
    public boolean admit(UUID player, Object packet) {
        long start = System.nanoTime();
        RuleSet r = rules.rulesFor(player);
        boolean admitted = !r.on(Checks.PACKET_RATE) || guard.admit(player, packet.getClass(), r.tolerance(Checks.PACKET_RATE), start);
        if (!admitted) {
            flag(player, players.inWorld(player), track(player), Checks.PACKET_RATE, 1, "数据包 " + PacketGuard.key(packet.getClass()) + " 超过频率上限");
        }
        spent(start);
        return admitted;
    }

    // ---- packet security: vanilla packets (NetHooks) ----------------------------------------------------

    @Override
    public boolean move(ServerPlayer player, ServerboundMovePlayerPacket packet, boolean awaitingTeleport) {
        if (server.isSingleplayerOwner(player.getGameProfile())) return true;
        long start = System.nanoTime();
        boolean keep = movement.move(player, track(player.getUUID()), packet, awaitingTeleport, server.getTickCount());
        spent(start);
        return keep;
    }

    @Override
    public boolean onGround(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        PlayerTrack t = tracks.get(player.getUUID());
        return t != null && t.groundPacket == packet ? t.groundVerified : packet.isOnGround();
    }

    @Override
    public void tickEndArrived(UUID player, long nanos) {
        arrivals.push(player, nanos);
    }

    @Override
    public void tickEnd(ServerPlayer player) {
        long start = System.nanoTime();
        UUID id = player.getUUID();
        long arrived = arrivals.pop(id);
        PlayerTrack t = track(id);
        t.tickEndsSinceMove++;
        t.clientTicks++;
        RuleSet r = rules.rulesFor(id);
        if (r.on(Checks.TIMER) && !player.isCreative() && !player.isSpectator() && !server.isSingleplayerOwner(player.getGameProfile())) {
            double tol = r.tolerance(Checks.TIMER);
            double rtt = rtt(player, t);
            double bank = Math.max(1500, 2 * t.latency.recentMax(rtt)) * tol;
            double floor = (120 + 2 * t.latency.jitter()) * tol;
            double over = t.clock.tick(arrived, bank, floor);
            if (over > 0) {
                Outcome outcome = flag(id, player, t, Checks.TIMER, over / 50.0,
                        String.format(Locale.ROOT, "客户端 tick 比真实时间多出 %.0f 毫秒", over));
                if (outcome == Outcome.MITIGATE && t.anchored && !t.teleportPending) MovementChecks.setback(player, t);
            }
        } else {
            t.clock.tick(arrived, Double.MAX_VALUE, Double.MAX_VALUE);
        }
        movement.silentTick(player, t, server.getTickCount());
        spent(start);
    }

    @Override
    public void pong(UUID id, int ping, long nanos) {
        PlayerTrack t = tracks.get(id);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (t == null || player == null) return;
        int position = t.pings.position(ping);
        if (position < 0) {
            if (!t.pings.stale(ping)) flag(id, player, t, Checks.TRANSACTION, 1, "应答了没有发出的 ping " + ping);
            return;
        }
        // the client answers pings in the order it got them: skipping some is not a vanilla client
        if (position > 0) flag(id, player, t, Checks.TRANSACTION, position, "跳过了 " + position + " 个 ping 的应答");
        for (int i = 0; i <= position; i++) {
            int answered = t.pings.oldestId();
            long sent = t.pings.oldestSent();
            t.pings.pop();
            for (ServerVelocity v : t.velocities) if (v.ping == answered) v.acknowledged = true;
            if (i == position) t.latency.add((nanos - sent) / 1e6);
        }
    }

    @Override
    public void sending(ServerPlayer player, Packet<?> packet) {
        if (!server.isSameThread()) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> part : bundle.subPackets()) sending(player, part);
            return;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion && motion.getId() == player.getId()) {
            addVelocity(track(player.getUUID()), motion.getXa(), motion.getYa(), motion.getZa(), false);
        } else if (packet instanceof ClientboundExplodePacket explode && explode.playerKnockback().isPresent()) {
            Vec3 push = explode.playerKnockback().get();
            addVelocity(track(player.getUUID()), push.x, push.y, push.z, true);
        }
    }

    private static void addVelocity(PlayerTrack t, double x, double y, double z, boolean additive) {
        ServerVelocity v = new ServerVelocity(x, y, z, additive);
        v.carried = Math.hypot(t.prevDx, t.prevDz);
        t.velocities.add(v);
        if (t.velocities.size() > MAX_VELOCITIES) t.velocities.remove(0);
        t.wantsPing = true;
    }

    // ---- per tick ---------------------------------------------------------------------------------

    public void tick() {
        long start = System.nanoTime();
        int tick = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerTrack t = track(player.getUUID());
            if (t.entityId != player.getId()) {
                // a respawn is a new entity: positions start over
                t.entityId = player.getId();
                t.anchored = false;
                t.velocities.clear();
                t.window.clear();
            }
            sample(player, t);
            if ((t.wantsPing || (tick + player.getId()) % PING_INTERVAL == 0) && !t.pings.full()) ping(player, t, start);
            if (t.pings.size() > 0) checkTimeout(player, t, start);
        }
        reporter.tick();
        spent(start);
        // the server thread's cost of the whole tick
        averageNanos += (tickNanos - averageNanos) * 0.05;
        // the largest over the last ten seconds
        if (tick - maxWindowStart > 200) {
            maxWindowStart = tick;
            maxNanos = 0;
        }
        maxNanos = Math.max(maxNanos, tickNanos);
        tickNanos = 0;
    }

    /** One tick's movement-relevant state for the latency window. */
    private static void sample(ServerPlayer p, PlayerTrack t) {
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED) * (p.isSprinting() ? 1 : Physics.SPRINT);
        MobEffectInstance jump = p.getEffect(MobEffects.JUMP_BOOST);
        int flags = 0;
        if (p.hasEffect(MobEffects.SLOW_FALLING)) flags |= StateWindow.SLOW_FALLING;
        if (p.hasEffect(MobEffects.LEVITATION)) flags |= StateWindow.LEVITATION;
        if (p.hasEffect(MobEffects.DOLPHINS_GRACE)) flags |= StateWindow.DOLPHINS_GRACE;
        if (p.isNoGravity()) flags |= StateWindow.NO_GRAVITY;
        if (p.isShiftKeyDown()) flags |= StateWindow.SNEAKING;
        if (!p.isAlive() || p.isSpectator() || p.isCreative() || p.getAbilities().flying || p.isFallFlying() || p.isPassenger()
                || p.isAutoSpinAttack() || p.isSleeping()) flags |= StateWindow.EXEMPT;
        t.window.push(speed, p.getAttributeValue(Attributes.JUMP_STRENGTH), p.maxUpStep(), p.getAttributeValue(Attributes.GRAVITY),
                p.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY), jump == null ? -1 : jump.getAmplifier(), flags);
    }

    private static void ping(ServerPlayer player, PlayerTrack t, long now) {
        int id = t.pings.nextId();
        t.pings.push(id, now);
        for (ServerVelocity v : t.velocities) if (v.ping == 0) v.ping = id;
        t.wantsPing = false;
        player.connection.send(new ClientboundPingPacket(id));
    }

    private void checkTimeout(ServerPlayer player, PlayerTrack t, long now) {
        RuleSet r = rules.rulesFor(player.getUUID());
        double age = (now - t.pings.oldestSent()) / 1e9;
        if (!r.on(Checks.TRANSACTION) || age < TRANSACTION_TIMEOUT_SECONDS * r.tolerance(Checks.TRANSACTION)) return;
        if (now - t.lastTimeoutReportNanos < 5_000_000_000L) return;
        t.lastTimeoutReportNanos = now;
        flag(player.getUUID(), player, t, Checks.TRANSACTION, 5, String.format(Locale.ROOT, "ping 已 %.0f 秒没有应答", age));
    }

    private void spent(long start) {
        tickNanos += System.nanoTime() - start;
    }

    // ---- combat and world (Forge events, through the composition root) -----------------------------

    /** A vanilla melee attack; returns true to cancel it. */
    public boolean onAttack(ServerPlayer attacker, Entity target) {
        if (server.isSingleplayerOwner(attacker.getGameProfile())) return false;
        long start = System.nanoTime();
        boolean cancel = combat.attack(attacker, track(attacker.getUUID()), target);
        spent(start);
        return cancel;
    }

    /** A block placed against a clicked face. */
    public void onPlace(ServerPlayer player, BlockHitResult hit) {
        if (server.isSingleplayerOwner(player.getGameProfile())) return;
        long start = System.nanoTime();
        place.place(player, track(player.getUUID()), hit);
        spent(start);
    }

    // ---- other modules' signals ------------------------------------------------------------------

    @Override
    public void report(ServerPlayer player, String check, double amount, String detail) {
        CheckCatalog.Entry entry = CheckCatalog.get().byId(check);
        if (entry == null) return;
        RuleSet r = rules.rulesFor(player.getUUID());
        flag(player.getUUID(), player, track(player.getUUID()), entry.index(), amount / r.tolerance(entry.index()), detail);
    }

    // ---- judging (evidence, confidence, enforcement) --------------------------------------------------

    @Override
    public RuleSet rules(UUID player) {
        return rules.rulesFor(player);
    }

    @Override
    public int window(ServerPlayer player, PlayerTrack track, RuleSet r) {
        int window = track.latency.windowTicks(player.connection.latency(), r.maxCompensationMs, r.jitterMultiplier, r.extraTicks);
        return Math.min(StateWindow.SIZE - 1, window);
    }

    @Override
    public double rtt(ServerPlayer player, PlayerTrack track) {
        return track.latency.rtt(player.connection.latency());
    }

    @Override
    public Outcome flag(UUID id, @Nullable ServerPlayer player, PlayerTrack t, int check, double amount, String detail) {
        if (check < 0) return Outcome.NONE;
        RuleSet r = rules.rulesFor(id);
        CheckSettings settings = r.check(check);
        if (!settings.enabled()) return Outcome.NONE;
        CheckCatalog.Entry entry = CheckCatalog.get().at(check);
        long now = System.nanoTime();
        double vl = t.ledger.add(check, amount, settings.decay(), now);
        // signals were refused by their module already; the anti-cheat only scores them
        Outcome outcome = !entry.signal() && r.setback && (settings.setbackVl() <= 0 || vl >= settings.setbackVl())
                ? Outcome.MITIGATE : Outcome.NONE;
        boolean kick = r.kick && settings.kickVl() > 0 && vl >= settings.kickVl();
        double rtt = player != null ? rtt(player, t) : t.latency.rtt(0);
        Confidence.Result confidence = Confidence.of(t.ledger, r, rtt, now);
        String name = players.name(id);
        if (name == null) name = id.toString();
        GameModeDef mode = rules.modeOf(id);
        RuleResolver.Global global = rules.global();
        String action = kick ? "kick" : outcome == Outcome.MITIGATE ? (isMovement(entry) ? "setback" : "cancel") : "none";

        if (kick || t.ledger.shouldReport(check, vl, settings.setbackVl(), now, global.reportCooldownSeconds() * 1_000_000_000L)) {
            reporter.add(new EvidenceReporter.Report(id.toString(), name, mode == null ? null : mode.id, mode == null ? null : mode.title,
                    mapOf.apply(id), entry.id(), entry.category(), round(vl), round(confidence.value()), confidence.value() >= r.review,
                    action, detail, (int) Math.round(rtt), (int) Math.round(t.latency.jitter()),
                    player == null ? null : player.level().dimension().location().toString(),
                    player == null ? 0 : round(player.getX()), player == null ? 0 : round(player.getY()), player == null ? 0 : round(player.getZ()),
                    EvidenceReporter.now()));
            BlockLand.LOGGER.info("[BlockLand] Anti-cheat: {} {} VL {} ({} %, {} ms) {} — {}", name, entry.id(), round(vl),
                    Math.round(confidence.value() * 100), Math.round(rtt), action, detail);
        }
        if (global.staffAlerts() && confidence.value() >= r.alert && now - t.lastAlertNanos > global.alertCooldownSeconds() * 1_000_000_000L) {
            t.lastAlertNanos = now;
            enforcer.alert(name, confidence.top() >= 0 ? confidence.top() : check, vl, confidence.value(), rtt, modeName(mode));
        }
        // an automatic ban needs every switch, a confident decisive check and a link fast enough to trust the timing
        if (!t.banRequested && global.autoBan() && r.autoBanAllowed && confidence.value() >= r.autoBan && rtt <= r.highPingMs
                && confidence.top() >= 0 && r.check(confidence.top()).punish() && confidence.topValue() >= 0.5) {
            t.banRequested = true;
            enforcer.requestBan(t, id, name, mode == null ? null : mode.id, topChecks(t, r, now), confidence.value(), detail);
        }
        if (kick) {
            BlockLand.LOGGER.info("[BlockLand] Anti-cheat kicked {} ({} VL {})", name, entry.id(), round(vl));
            enforcer.kick(id, check);
            return Outcome.KICKED;
        }
        return outcome;
    }

    private static boolean isMovement(CheckCatalog.Entry entry) {
        return "movement".equals(entry.category()) || "packet.timer".equals(entry.id());
    }

    /** The checks with a level now, most confident first (what a ban request cites). */
    private static List<String> topChecks(PlayerTrack t, RuleSet r, long now) {
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < t.ledger.size(); i++) {
            CheckSettings s = r.check(i);
            double vl = t.ledger.get(i, s.decay(), now);
            if (vl > 0) scored.add(new double[]{i, Confidence.single(s, vl, 1)});
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < 5; i++) ids.add(CheckCatalog.get().at((int) scored.get(i)[0]).id());
        return ids;
    }

    private static Component modeName(@Nullable GameModeDef mode) {
        return mode == null ? Component.translatable("blockland.anticheat.mode.none") : Component.literal(mode.title == null ? mode.id : mode.title);
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    // ---- lifecycle and operators ----------------------------------------------------------------

    /** The player left the world (lobby, disconnect): positional state starts over next time. */
    public void leftWorld(ServerPlayer player) {
        PlayerTrack t = tracks.get(player.getUUID());
        if (t != null) {
            t.anchored = false;
            t.entityId = -1;
            t.velocities.clear();
            t.pings.clear();
            t.clock.reset();
            t.window.clear();
            t.tickEndsSinceMove = 1;
        }
        arrivals.reset(player.getUUID());
    }

    /** The player left the server. */
    public void forget(UUID player) {
        tracks.remove(player);
        guard.forget(player);
        arrivals.forget(player);
        enforcer.forget(player);
    }

    public void clear() {
        reporter.flush();
        tracks.clear();
        guard.clear();
        arrivals.clear();
        pistons.clear();
    }

    public boolean toggleAlerts(UUID staff) {
        return enforcer.toggleAlerts(staff);
    }

    /** Exempts a player from the movement checks for {@code seconds}. */
    public void exempt(UUID player, int seconds) {
        track(player).exemptUntil = server.getTickCount() + Math.max(0, seconds) * 20;
    }

    public void reset(UUID player) {
        PlayerTrack t = track(player);
        t.ledger.reset();
        t.banRequested = false;
    }

    public Stats stats() {
        return new Stats(tracks.size(), averageNanos / 1000.0, maxNanos / 1000.0, reporter.queued());
    }

    /** {@code /bl ac status}: the player's rules, latency, confidence and every check with a level. */
    public List<Component> status(UUID id) {
        List<Component> lines = new ArrayList<>();
        PlayerTrack t = track(id);
        RuleSet r = rules.rulesFor(id);
        ServerPlayer player = players.inWorld(id);
        long now = System.nanoTime();
        double rtt = player != null ? rtt(player, t) : t.latency.rtt(0);
        int window = player != null ? window(player, t, r) : 0;
        Confidence.Result confidence = Confidence.of(t.ledger, r, rtt, now);
        String name = players.name(id);
        lines.add(Component.translatable("blockland.anticheat.cmd.status.header", name == null ? id.toString() : name,
                r.enabled ? r.preset : "-", modeName(rules.modeOf(id)), Math.round(rtt), Math.round(t.latency.jitter()), window,
                String.format(Locale.ROOT, "%.0f%%", confidence.value() * 100)));
        boolean any = false;
        for (int i = 0; i < t.ledger.size(); i++) {
            double vl = t.ledger.get(i, r.check(i).decay(), now);
            if (vl <= 0.05) continue;
            any = true;
            lines.add(Component.translatable("blockland.anticheat.cmd.status.line", Enforcer.checkName(i), String.format(Locale.ROOT, "%.1f", vl)));
        }
        if (!any) lines.add(Component.translatable("blockland.anticheat.cmd.status.clean"));
        return lines;
    }

    public PlayerDirectory players() {
        return players;
    }
}
