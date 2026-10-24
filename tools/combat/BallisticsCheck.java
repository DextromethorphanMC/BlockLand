import com.blockland.combat.Ballistics;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Checks com.blockland.combat.Ballistics (rounds in fluids) against hand-worked cases and a fine CPU march: where a round
 * is spent in water and lava, its energy on the way, skipping off a water surface at a grazing angle, entering from the
 * surface or a side, fired from under water, shallow fluid passed over, and the voxel walk's fluid lengths along random
 * rays through a random pool. Exit code 0 = all pass. Run by tools/combat/check_ballistics.py.
 */
public final class BallisticsCheck {
    private static int failures;

    public static void main(String[] args) {
        Ballistics.Medium rifle = new Ballistics.Medium(2.5f, 0.3f, 7);
        Ballistics.Fluids world = BallisticsCheck::pool;

        // straight into the pool's side at mid depth: spent 2.5 blocks in, half its energy 1.25 in
        Ballistics.Path side = Ballistics.trace(world, new Vec3(5, 61.5, 15.5), new Vec3(1, 0, 0), 40, rifle);
        check("side entry spent", side.spent && !side.skipped, side.length, 7.5, 1e-6);
        check("side entry energy halfway", true, side.energyAt(6.25), 0.5, 1e-6);
        check("side entry not from the surface", side.spans() > 0 && !side.spanFromSurface(0), 0, 0, 0);

        // steeply down into the surface: in through the top, not skipped
        Vec3 steep = new Vec3(1, -1.2, 0).normalize();
        Ballistics.Path down = Ballistics.trace(world, new Vec3(12, 65, 15.5), steep, 40, rifle);
        double surfaceT = (65 - (62 + 8 / 9.0)) / -steep.y;
        check("steep entry from the surface", down.spans() > 0 && down.spanFromSurface(0) && !down.skipped, down.spanStart(0), surfaceT, 1e-6);
        check("steep entry spent 2.5 blocks in", down.spent, down.length, surfaceT + 2.5, 1e-6);

        // grazing: 5 degrees under the horizon skips at the surface
        Vec3 graze = new Vec3(Math.cos(Math.toRadians(5)), -Math.sin(Math.toRadians(5)), 0);
        // (from x 8 it meets the surface over the pool at x 15, not the pool's side)
        Ballistics.Path skip = Ballistics.trace(world, new Vec3(8, 63.5, 15.5), graze, 60, rifle);
        double skipT = (63.5 - (62 + 8 / 9.0)) / Math.sin(Math.toRadians(5));
        check("grazing round skips", skip.skipped && !skip.spent, skip.length, skipT, 1e-6);
        check("skipped round keeps its energy", true, skip.energyAt(skip.length), 1, 1e-6);
        // 10 degrees goes in
        Vec3 ten = new Vec3(Math.cos(Math.toRadians(10)), -Math.sin(Math.toRadians(10)), 0);
        Ballistics.Path in = Ballistics.trace(world, new Vec3(5, 63.5, 15.5), ten, 60, rifle);
        check("10 degrees goes in", !in.skipped && in.spent, 0, 0, 0);

        // water does nothing to a round whose waterRange is 0
        Ballistics.Path free = Ballistics.trace(world, new Vec3(5, 61.5, 15.5), new Vec3(1, 0, 0), 40,
                new Ballistics.Medium(0, 0, 0));
        check("no water drag", !free.spent && !free.skipped, free.length, 40, 1e-9);
        check("no water drag keeps energy", true, free.energyAt(30), 1, 1e-9);

        // fired from under water: starts in the fluid, spent 2.5 on
        Ballistics.Path under = Ballistics.trace(world, new Vec3(12, 61.5, 15.5), new Vec3(1, 0, 0), 40, rifle);
        check("under water start", under.startsInFluid() && under.spent, under.length, 2.5, 1e-6);

        // lava: spent 0.3 in
        Ballistics.Path hot = Ballistics.trace(world, new Vec3(25, 64.5, 30.5), new Vec3(1, 0, 0), 40, rifle);
        check("lava spends it", hot.spent && hot.spans() > 0 && hot.spanLava(0), hot.length, 5.3, 1e-6);

        // over a thin sheet of flowing water (a quarter block deep): above it nothing, in it a span
        Ballistics.Path over = Ballistics.trace(world, new Vec3(38, 70.5, 40.5), new Vec3(1, 0, 0), 20, rifle);
        check("over shallow water", over.spans() == 0 && !over.spent, over.length, 20, 1e-9);
        Ballistics.Path through = Ballistics.trace(world, new Vec3(38, 70.1, 40.5), new Vec3(1, 0, 0), 20,
                new Ballistics.Medium(1000, 0, 7));
        check("through shallow water", through.spans() == 1, through.spanEnd(0) - through.spanStart(0), 5, 1e-6);

        // the walk against a fine march: random rays through a random field of water of random heights
        Random random = new Random(42);
        double[][][] field = new double[12][12][12];
        for (double[][] plane : field) for (double[] row : plane) for (int k = 0; k < row.length; k++) {
            row[k] = random.nextFloat() < 0.35f ? 0.1 + 0.9 * random.nextDouble() : 0;
        }
        Ballistics.Fluids box = (x, y, z) -> x < 0 || y < 0 || z < 0 || x >= 12 || y >= 12 || z >= 12 ? 0 : field[x][y][z];
        Ballistics.Medium none = new Ballistics.Medium(1000, 0, 0);
        double worst = 0;
        for (int i = 0; i < 400; i++) {
            Vec3 from = new Vec3(random.nextDouble() * 12, random.nextDouble() * 12, random.nextDouble() * 12);
            Vec3 dir = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            double distance = 2 + random.nextDouble() * 14;
            Ballistics.Path path = Ballistics.trace(box, from, dir, distance, none);
            double walked = 0;
            for (int s = 0; s < path.spans(); s++) walked += path.spanEnd(s) - path.spanStart(s);
            double marched = 0, step = 0.0005;
            for (double t = step / 2; t < distance; t += step) {
                Vec3 p = from.add(dir.scale(t));
                int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
                double h = box.at(x, y, z);
                if (h > 0 && p.y - y <= h) marched += step;
            }
            worst = Math.max(worst, Math.abs(walked - marched));
            // the energy left matches the fluid gone through
            double energy = path.energyAt(distance);
            worst = Math.max(worst, Math.abs(energy - Math.max(0, 1 - walked / 1000)) * 1000);
        }
        check("walk = fine march (worst difference in blocks)", true, worst, 0, 0.01);

        System.out.println(failures == 0 ? "ALL BALLISTICS CHECKS OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A test world: a pool of still water 10 × 3 blocks deep (x 10..19, y 60..62, z 0..31; the top layer 8/9 high,
     * full below), a lava block at x 30, y 64, z 30, and a sheet of flowing water a quarter block deep at x 40..44, y 70.
     */
    private static double pool(int x, int y, int z) {
        if (x >= 10 && x < 20 && y >= 60 && y <= 62 && z >= 0 && z < 32) return y == 62 ? 8 / 9.0 : 1;
        if (x == 30 && y == 64 && z == 30) return -8 / 9.0;
        if (x >= 40 && x < 45 && y == 70 && z == 40) return 0.25;
        return 0;
    }

    private static void check(String name, boolean condition, double value, double expected, double tolerance) {
        boolean ok = condition && Math.abs(value - expected) <= tolerance;
        System.out.printf("%s  %-48s %s%n", ok ? "ok  " : "FAIL", name, tolerance > 0 ? String.format("%.6f (expected %.6f)", value, expected) : "");
        if (!ok) failures++;
    }
}
