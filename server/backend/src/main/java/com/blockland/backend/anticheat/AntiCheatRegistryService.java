package com.blockland.backend.anticheat;

import com.blockland.backend.common.ApiException;
import com.blockland.backend.config.BlockLandProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The anti-cheat checks as the Minecraft server published them at start-up (its {@code checks.json}), so neither the
 * backend nor the admin UI hardcodes them: the admin's preset and mode tables list them with the mod's defaults. The
 * last published catalogue is kept in {@code anticheat-registry.json}.
 */
@Service
public class AntiCheatRegistryService {
    private static final Pattern CATEGORY = Pattern.compile("^[a-z]{1,16}$");
    static final int MAX_CHECKS = 256;

    /** What a check does when no preset or mode overrides it. */
    public record Defaults(boolean enabled, double tolerance, double setbackVl, double kickVl, double decay, double weight,
                           boolean punish) {
    }

    /**
     * One check.
     *
     * @param latencySensitive its confidence drops for high-ping players
     * @param signal           reported by another module (weapons, parachute) rather than detected by the anti-cheat
     */
    public record Check(String id, String category, String name, String description, boolean latencySensitive,
                        boolean signal, Defaults defaults) {
    }

    /** {@code POST /api/server/registry/anticheat} body and {@code GET /api/admin/registry/anticheat} answer. */
    public record Catalogue(List<Check> checks) {
        public Catalogue {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    private final ObjectMapper mapper;
    private final Path file;
    private volatile Catalogue catalogue;

    public AntiCheatRegistryService(ObjectMapper mapper, BlockLandProperties properties) {
        this.mapper = mapper;
        this.file = properties.dataPath().resolve("anticheat-registry.json");
        this.catalogue = read();
    }

    public Catalogue catalogue() {
        return catalogue;
    }

    /** Replaces the catalogue with what the server published; broken entries are dropped (all broken = rejected). */
    public synchronized int replace(Catalogue published) {
        List<String> errors = new ArrayList<>();
        Map<String, Check> next = new LinkedHashMap<>();
        for (Check check : published == null ? List.<Check>of() : published.checks()) {
            if (check == null || check.id() == null || !AntiCheatSettings.CHECK_ID.matcher(check.id()).matches()) {
                errors.add("无效的检测 ID: " + (check == null ? null : check.id()));
                continue;
            }
            if (next.size() >= MAX_CHECKS) throw ApiException.badRequest("检测项不能超过 " + MAX_CHECKS + " 个");
            next.put(check.id(), clean(check));
        }
        if (next.isEmpty() && !errors.isEmpty()) throw ApiException.invalid(errors);
        Catalogue stored = new Catalogue(List.copyOf(next.values()));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), stored);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        catalogue = stored;
        return next.size();
    }

    /** Texts trimmed, the category checked, the defaults clamped into the ranges the settings allow. */
    private static Check clean(Check check) {
        String category = check.category() != null && CATEGORY.matcher(check.category()).matches() ? check.category() : "other";
        Defaults d = check.defaults() != null ? check.defaults() : new Defaults(true, 1, 0, 0, 0, 0, false);
        Defaults defaults = new Defaults(d.enabled(), clamp(d.tolerance(), AntiCheatSettings.MIN_TOLERANCE, AntiCheatSettings.MAX_TOLERANCE),
                clamp(d.setbackVl(), 0, 1000), clamp(d.kickVl(), 0, 10000), clamp(d.decay(), 0, 100), clamp(d.weight(), 0, 1), d.punish());
        return new Check(check.id(), category, text(check.name(), check.id(), 64), text(check.description(), "", 500),
                check.latencySensitive(), check.signal(), defaults);
    }

    private static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private static String text(String value, String fallback, int maxLength) {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private Catalogue read() {
        if (!Files.isRegularFile(file)) return new Catalogue(List.of());
        try {
            return mapper.readValue(file.toFile(), Catalogue.class);
        } catch (IOException e) {
            return new Catalogue(List.of());
        }
    }
}
