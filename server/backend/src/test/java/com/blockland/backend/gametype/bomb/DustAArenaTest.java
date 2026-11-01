package com.blockland.backend.gametype.bomb;

import com.blockland.backend.map.GameMap;
import com.blockland.backend.map.MapFileAnalyzer;
import com.blockland.backend.map.StructureBuilder;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walks the generated DustA map the way a player can (one-block jumps, drops, the ladder): both sites are reached from
 * both spawns, nothing reachable is a trap, no roof but the two meant to be stood on is reachable, and the walks to the
 * sites are fair. With {@code -Ddusta.png=<file>} it also draws the map from above.
 */
class DustAArenaTest {
    private static final DustAArena.Result MAP = DustAArena.generate();
    private static final int X = DustAArena.SIZE_X, Y = DustAArena.SIZE_Y, Z = DustAArena.SIZE_Z;

    private final Graph graph = new Graph(MAP.blocks());

    @Test
    void sizeSitesAndSpawnsMatchTheFile() {
        MapFileAnalyzer.Result analysis = new MapFileAnalyzer().analyze(MAP.data(), "nbt", null);
        assertThat(analysis.size()).isEqualTo(new GameMap.Size(X, Y, Z));
        assertThat(MAP.sites()).extracting(BombMapSettings.Site::name).containsExactly("A", "B");
        assertThat(MAP.sites()).noneMatch(s -> s.leaves(analysis.size()));
        assertThat(MAP.spawns()).hasSize(2);
        for (GameMap.TeamSpawn team : MAP.spawns()) {
            assertThat(team.points()).hasSize(5);
            for (GameMap.SpawnPoint p : team.points()) {
                assertThat(graph.standing((int) p.x(), (int) p.y(), (int) p.z())).as("spawn %s stands", p).isTrue();
            }
        }
    }

    @Test
    void everythingReachableIsConnectedAndNoRoofIsAnExploit() {
        long attackers = spawn(0), defenders = spawn(1);
        Set<Long> reach = graph.reach(attackers, false);
        Set<Long> back = graph.reach(attackers, true);
        assertThat(reach).contains(defenders);
        // no traps: from everywhere one can get to, one can get back
        Set<Long> traps = new HashSet<>(reach);
        traps.removeAll(back);
        assertThat(traps).as("cells one cannot leave: %s, reached by %s", describe(traps),
                traps.isEmpty() ? "" : graph.path(attackers, traps.iterator().next())).isEmpty();
        // only the two roofs meant for it, their stairs and the ladder are high up; nothing outside the town
        List<String> high = new ArrayList<>();
        for (long n : reach) {
            int x = x(n), y = y(n), z = z(n);
            assertThat(x).isBetween(1, X - 2);
            assertThat(z).isBetween(1, Z - 2);
            if (y < DustAArena.LIFT + 8) continue;
            boolean dye = x >= 22 && x <= 39 && z >= 46 && z <= 63;
            boolean lantern = x >= 99 && x <= 113 && z >= 4 && z <= 17;
            if (!dye && !lantern) high.add(x + "," + y + "," + z);
        }
        assertThat(high).as("high places one can reach").isEmpty();
    }

    @Test
    void walksToTheSitesAreFair() {
        Map<Long, Double> fromAttackers = graph.distances(spawn(0)), fromDefenders = graph.distances(spawn(1));
        double ta = toSite(fromAttackers, 0), tb = toSite(fromAttackers, 1), ca = toSite(fromDefenders, 0), cb = toSite(fromDefenders, 1);
        double rotate = graph.distances(siteCentre(0)).getOrDefault(siteCentre(1), Double.NaN);
        System.out.printf("DustA walks (blocks): attackers->A %.0f, attackers->B %.0f, defenders->A %.0f, defenders->B %.0f, A<->B %.0f%n",
                ta, tb, ca, cb, rotate);
        assertThat(Math.abs(ta - tb) / Math.max(ta, tb)).as("attackers' walks to A and B alike").isLessThan(0.15);
        assertThat(Math.abs(ca - cb) / Math.max(ca, cb)).as("defenders' walks to A and B alike").isLessThan(0.15);
        // the defenders are there well first, the attackers still arrive with time to plant
        assertThat(Math.min(ta, tb) - Math.max(ca, cb)).isGreaterThan(25);
        assertThat(Math.max(ta, tb)).isLessThan(140);
        if (System.getProperty("dusta.png") != null) draw(new File(System.getProperty("dusta.png")));
    }

    private static long spawn(int team) {
        GameMap.SpawnPoint p = MAP.spawns().get(team).points().get(2);
        return key((int) p.x(), (int) p.y(), (int) p.z());
    }

    private long siteCentre(int index) {
        BombMapSettings.Site s = MAP.sites().get(index);
        int cx = (s.x1() + s.x2()) / 2, cz = (s.z1() + s.z2()) / 2;
        for (int dx = 0; dx < 6; dx++) {
            for (int dz = 0; dz < 6; dz++) {
                for (int y = s.y1(); y <= s.y2(); y++) if (graph.standing(cx + dx, y, cz + dz)) return key(cx + dx, y, cz + dz);
            }
        }
        throw new AssertionError("no standing place near the middle of site " + s.name());
    }

    /** The walk to the nearest standing place inside the site's box. */
    private static double toSite(Map<Long, Double> distances, int index) {
        BombMapSettings.Site s = MAP.sites().get(index);
        double best = Double.MAX_VALUE;
        for (Map.Entry<Long, Double> e : distances.entrySet()) {
            long n = e.getKey();
            if (x(n) >= s.x1() && x(n) <= s.x2() && z(n) >= s.z1() && z(n) <= s.z2() && y(n) >= s.y1() && y(n) <= s.y2()) {
                best = Math.min(best, e.getValue());
            }
        }
        assertThat(best).as("site %s reached", s.name()).isLessThan(Double.MAX_VALUE);
        return best;
    }

    private static String describe(Set<Long> nodes) {
        return nodes.stream().limit(20).map(n -> x(n) + "," + y(n) + "," + z(n)).toList().toString();
    }

    static long key(int x, int y, int z) {
        return ((long) x << 32) | ((long) y << 16) | z;
    }

    static int x(long n) {
        return (int) (n >>> 32);
    }

    static int y(long n) {
        return (int) ((n >>> 16) & 0xFFFF);
    }

    static int z(long n) {
        return (int) (n & 0xFFFF);
    }

    /** Where a player can be (feet in a block with room for the body) and how they move between such places. */
    static final class Graph {
        private final StructureBuilder b;
        private final Map<Long, List<long[]>> edges = new HashMap<>();
        private final Map<Long, List<long[]>> reverse = new HashMap<>();

        Graph(StructureBuilder b) {
            this.b = b;
            for (int x = 0; x < X; x++) {
                for (int z = 0; z < Z; z++) {
                    for (int y = 1; y < Y - 1; y++) if (standing(x, y, z) || ladder(x, y, z)) link(x, y, z);
                }
            }
        }

        private String at(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= X || z >= Z || y < 0) return "minecraft:sandstone";
            return y >= Y ? null : b.get(x, y, z);
        }

        boolean passable(int x, int y, int z) {
            String s = at(x, y, z);
            return s == null || s.equals("minecraft:air") || s.equals("minecraft:dead_bush") || s.equals("minecraft:water") || s.startsWith("minecraft:ladder");
        }

        private boolean floor(int x, int y, int z) {
            String s = at(x, y, z);
            if (s == null || passable(x, y, z)) return false;
            return !s.contains("fence") && !s.contains("_wall") && !s.contains("lantern") && !s.contains("door");
        }

        boolean ladder(int x, int y, int z) {
            String s = at(x, y, z);
            return s != null && s.startsWith("minecraft:ladder");
        }

        boolean standing(int x, int y, int z) {
            return floor(x, y - 1, z) && passable(x, y, z) && passable(x, y + 1, z);
        }

        private void link(int x, int y, int z) {
            long from = key(x, y, z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    boolean diagonal = dx != 0 && dz != 0;
                    for (int ny = Math.max(1, y - 12); ny <= y + 1; ny++) {
                        int nx = x + dx, nz = z + dz;
                        if (!standing(nx, ny, nz) && !ladder(nx, ny, nz)) continue;
                        if (diagonal && (ny != y || !standing(x + dx, y, z) || !standing(x, y, z + dz))) continue;
                        if (ny == y + 1 && !passable(x, y + 2, z)) continue;
                        boolean clear = true;
                        for (int yy = ny; yy <= Math.max(ny + 1, y + 1); yy++) if (!passable(nx, yy, nz)) clear = false;
                        if (!clear) continue;
                        add(from, key(nx, ny, nz), diagonal ? Math.sqrt(2) : 1);
                    }
                }
            }
            if (ladder(x, y, z) && (ladder(x, y + 1, z) || standing(x, y + 1, z))) {
                add(from, key(x, y + 1, z), 1);
                add(key(x, y + 1, z), from, 1);
            }
        }

        private void add(long from, long to, double cost) {
            edges.computeIfAbsent(from, k -> new ArrayList<>()).add(new long[]{to, Double.doubleToLongBits(cost)});
            reverse.computeIfAbsent(to, k -> new ArrayList<>()).add(new long[]{from, Double.doubleToLongBits(cost)});
        }

        Set<Long> reach(long start, boolean backwards) {
            Map<Long, List<long[]>> g = backwards ? reverse : edges;
            Set<Long> seen = new HashSet<>(List.of(start));
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                for (long[] e : g.getOrDefault(queue.poll(), List.of())) if (seen.add(e[0])) queue.add(e[0]);
            }
            return seen;
        }

        /** The places on the way from {@code start} to {@code target} (the last ones), to tell how a place is reached. */
        String path(long start, long target) {
            Map<Long, Long> parent = new HashMap<>();
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
            parent.put(start, start);
            while (!queue.isEmpty() && !parent.containsKey(target)) {
                long n = queue.poll();
                for (long[] e : edges.getOrDefault(n, List.of())) {
                    if (parent.putIfAbsent(e[0], n) == null) queue.add(e[0]);
                }
            }
            List<String> steps = new ArrayList<>();
            for (Long n = target; n != null && steps.size() < 400; n = n == start ? null : parent.get(n)) {
                steps.add(0, x(n) + "," + y(n) + "," + z(n) + "=" + at(x(n), y(n) - 1, z(n)));
            }
            return String.join(" <- ", steps.reversed());
        }

        Map<Long, Double> distances(long start) {
            Map<Long, Double> dist = new HashMap<>();
            PriorityQueue<double[]> queue = new PriorityQueue<>((a, c) -> Double.compare(a[0], c[0]));
            dist.put(start, 0.0);
            queue.add(new double[]{0, Double.longBitsToDouble(start)});
            while (!queue.isEmpty()) {
                double[] top = queue.poll();
                long n = Double.doubleToRawLongBits(top[1]);
                if (top[0] > dist.getOrDefault(n, Double.MAX_VALUE)) continue;
                for (long[] e : edges.getOrDefault(n, List.of())) {
                    double d = top[0] + Double.longBitsToDouble(e[1]);
                    if (d < dist.getOrDefault(e[0], Double.MAX_VALUE)) {
                        dist.put(e[0], d);
                        queue.add(new double[]{d, Double.longBitsToDouble(e[0])});
                    }
                }
            }
            return dist;
        }
    }

    // ---- the picture ------------------------------------------------------------------------------

    private static final int SCALE = 6;

    private void draw(File out) {
        StructureBuilder b = MAP.blocks();
        BufferedImage image = new BufferedImage(X * SCALE, Z * SCALE, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < X; x++) {
            for (int z = 0; z < Z; z++) {
                int top = -1;
                String state = null;
                for (int y = Y - 1; y >= 0; y--) {
                    String s = b.get(x, y, z);
                    if (s != null && !s.equals("minecraft:air")) {
                        top = y;
                        state = s;
                        break;
                    }
                }
                Color c = colour(state);
                float shade = 0.55f + 0.45f * Math.min(1, (top + 1) / 16f);
                int rgb = new Color(Math.min(255, (int) (c.getRed() * shade)), Math.min(255, (int) (c.getGreen() * shade)),
                        Math.min(255, (int) (c.getBlue() * shade))).getRGB();
                for (int i = 0; i < SCALE; i++) for (int j = 0; j < SCALE; j++) image.setRGB(x * SCALE + i, z * SCALE + j, rgb);
            }
        }
        // the sites' outlines, the spawns
        int[] siteColours = {0xFF3030, 0x3070FF};
        for (int i = 0; i < MAP.sites().size(); i++) {
            BombMapSettings.Site s = MAP.sites().get(i);
            for (int x = s.x1() * SCALE; x < (s.x2() + 1) * SCALE; x++) {
                image.setRGB(x, s.z1() * SCALE, siteColours[i]);
                image.setRGB(x, (s.z2() + 1) * SCALE - 1, siteColours[i]);
            }
            for (int z = s.z1() * SCALE; z < (s.z2() + 1) * SCALE; z++) {
                image.setRGB(s.x1() * SCALE, z, siteColours[i]);
                image.setRGB((s.x2() + 1) * SCALE - 1, z, siteColours[i]);
            }
        }
        int[] teamColours = {0xFF8800, 0x00C8FF};
        for (int t = 0; t < MAP.spawns().size(); t++) {
            for (GameMap.SpawnPoint p : MAP.spawns().get(t).points()) {
                for (int i = 1; i < SCALE - 1; i++) for (int j = 1; j < SCALE - 1; j++) image.setRGB((int) p.x() * SCALE + i, (int) p.z() * SCALE + j, teamColours[t]);
            }
        }
        try {
            ImageIO.write(image, "png", out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Color colour(String state) {
        if (state == null) return Color.BLACK;
        String s = state.replace("minecraft:", "");
        int bracket = s.indexOf('[');
        if (bracket >= 0) s = s.substring(0, bracket);
        Map<String, Color> table = new HashMap<>();
        table.put("sand", new Color(219, 207, 163));
        table.put("red_sand", new Color(190, 102, 33));
        table.put("gravel", new Color(136, 126, 126));
        table.put("water", new Color(64, 110, 220));
        table.put("dead_bush", new Color(120, 90, 50));
        table.put("jungle_leaves", new Color(70, 140, 40));
        table.put("jungle_log", new Color(110, 85, 45));
        table.put("hay_block", new Color(200, 170, 40));
        table.put("barrel", new Color(120, 85, 50));
        table.put("spruce_planks", new Color(115, 85, 50));
        table.put("stripped_spruce_wood", new Color(140, 105, 65));
        table.put("black_stained_glass", new Color(30, 30, 30));
        if (table.containsKey(s)) return table.get(s);
        if (s.contains("wool")) return s.startsWith("red") ? new Color(180, 50, 45) : s.startsWith("orange") ? new Color(230, 120, 30)
                : s.startsWith("cyan") ? new Color(30, 140, 150) : s.startsWith("yellow") ? new Color(240, 200, 50) : new Color(235, 235, 235);
        if (s.contains("red_sandstone")) return new Color(185, 95, 40);
        if (s.contains("terracotta")) {
            return s.startsWith("white") ? new Color(210, 180, 160) : s.startsWith("orange") ? new Color(160, 85, 40)
                    : s.startsWith("light_gray") ? new Color(135, 107, 98) : s.startsWith("red") ? new Color(143, 61, 46)
                    : s.startsWith("blue") ? new Color(74, 60, 91) : new Color(152, 94, 67);
        }
        if (s.contains("sandstone")) return s.contains("smooth") ? new Color(224, 214, 170) : s.contains("cut") ? new Color(216, 203, 155)
                : s.contains("chiseled") ? new Color(205, 190, 140) : new Color(210, 198, 150);
        return new Color(160, 160, 160);
    }
}
