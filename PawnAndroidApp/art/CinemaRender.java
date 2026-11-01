import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * The phone app's 3D assets, rendered for real: a path tracer over signed-distance models, with
 * microfacet metal, refracting gems, a studio of soft boxes for the metal to reflect, a mirror floor,
 * bloom, the ACES film curve, and a 33-point 3D LUT of its own for every asset (written to luts/).
 *
 * Masters are 4K (3840 wide, or 2160 square for an object); finish.py cuts them down for the APK.
 *
 *   javac -d out CinemaRender.java
 *   java -cp out CinemaRender masters [name ...]            full size
 *   java -cp out CinemaRender preview [name ...]            quarter size, quick, to look at
 *
 * No renderer was installed on this machine and the assets have to be rebuildable, hence one file.
 */
public class CinemaRender {

    // ------------------------------------------------------------------ distance functions

    interface Sdf { double d(double x, double y, double z); }

    static double len(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }
    static double len(double x, double y) { return Math.sqrt(x * x + y * y); }
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
    static double mix(double a, double b, double t) { return a + (b - a) * t; }
    static double smooth(double a, double b, double x) { double t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }

    static Sdf sphere(double r) { return (x, y, z) -> len(x, y, z) - r; }

    static Sdf box(double hx, double hy, double hz, double round) {
        return (x, y, z) -> {
            double qx = Math.abs(x) - hx + round, qy = Math.abs(y) - hy + round, qz = Math.abs(z) - hz + round;
            return len(Math.max(qx, 0), Math.max(qy, 0), Math.max(qz, 0)) + Math.min(Math.max(qx, Math.max(qy, qz)), 0) - round;
        };
    }

    /** A cylinder standing on Y. */
    static Sdf cyl(double r, double h, double round) {
        return (x, y, z) -> {
            double dr = len(x, z) - r + round, dy = Math.abs(y) - h + round;
            return Math.min(Math.max(dr, dy), 0) + len(Math.max(dr, 0), Math.max(dy, 0)) - round;
        };
    }

    /** A ring lying flat. */
    static Sdf torus(double major, double minor) {
        return (x, y, z) -> len(len(x, z) - major, y) - minor;
    }

    /** A ring lying flat, its section flattened: a bangle's band. */
    static Sdf band(double major, double halfThick, double halfHeight, double round) {
        return (x, y, z) -> {
            double dr = Math.abs(len(x, z) - major) - halfThick + round, dy = Math.abs(y) - halfHeight + round;
            return Math.min(Math.max(dr, dy), 0) + len(Math.max(dr, 0), Math.max(dy, 0)) - round;
        };
    }

    static Sdf capsule(double ax, double ay, double az, double bx, double by, double bz, double r) {
        double bax = bx - ax, bay = by - ay, baz = bz - az, bb = bax * bax + bay * bay + baz * baz;
        return (x, y, z) -> {
            double pax = x - ax, pay = y - ay, paz = z - az;
            double h = clamp((pax * bax + pay * bay + paz * baz) / bb, 0, 1);
            return len(pax - bax * h, pay - bay * h, paz - baz * h) - r;
        };
    }

    /** Between two radii along Y (a rounded cone): bells, busts, columns. */
    static Sdf cone(double r1, double r2, double h) {
        double b = (r1 - r2) / h, a = Math.sqrt(1 - b * b);
        return (x, y, z) -> {
            double qx = len(x, z), qy = y;
            double k = qx * -b + qy * a;
            if (k < 0) return len(qx, qy) - r1;
            if (k > a * h) return len(qx, qy - h) - r2;
            return qx * a + qy * b - r1;
        };
    }

    static Sdf union(Sdf... s) {
        return (x, y, z) -> { double d = 1e9; for (Sdf f : s) d = Math.min(d, f.d(x, y, z)); return d; };
    }

    static Sdf smoothUnion(double k, Sdf a, Sdf b) {
        return (x, y, z) -> {
            double d1 = a.d(x, y, z), d2 = b.d(x, y, z), h = clamp(.5 + .5 * (d2 - d1) / k, 0, 1);
            return mix(d2, d1, h) - k * h * (1 - h);
        };
    }

    static Sdf cut(Sdf a, Sdf b) { return (x, y, z) -> Math.max(a.d(x, y, z), -b.d(x, y, z)); }
    static Sdf both(Sdf a, Sdf b) { return (x, y, z) -> Math.max(a.d(x, y, z), b.d(x, y, z)); }

    /** n copies around Y. The copy sits along +X. */
    static Sdf around(int n, Sdf s) {
        double step = 2 * Math.PI / n;
        return (x, y, z) -> {
            double a = Math.atan2(z, x);
            a = a - step * Math.floor(a / step + .5);
            double r = len(x, z);
            return s.d(r * Math.cos(a), y, r * Math.sin(a));
        };
    }

    /** Bumps on a surface: reeding on a coin's edge, chasing on a bangle. */
    static Sdf bumped(Sdf s, double amp, int count) {
        return (x, y, z) -> s.d(x, y, z) - amp * Math.sin(Math.atan2(z, x) * count);
    }

    static Sdf at(Sdf s, double tx, double ty, double tz) { return at(s, tx, ty, tz, 0, 0, 0); }

    /** Moved and turned (degrees: X, then Y, then Z). */
    static Sdf at(Sdf s, double tx, double ty, double tz, double rx, double ry, double rz) {
        double cx = Math.cos(Math.toRadians(rx)), sx = Math.sin(Math.toRadians(rx));
        double cy = Math.cos(Math.toRadians(ry)), sy = Math.sin(Math.toRadians(ry));
        double cz = Math.cos(Math.toRadians(rz)), sz = Math.sin(Math.toRadians(rz));
        return (x, y, z) -> {
            double px = x - tx, py = y - ty, pz = z - tz;
            double ax = px * cz + py * sz, ay = -px * sz + py * cz;          // undo Z
            double bx = ax * cy - pz * sy, bz = ax * sy + pz * cy;           // undo Y
            double cy2 = ay * cx + bz * sx, cz2 = -ay * sx + bz * cx;        // undo X
            return s.d(bx, cy2, cz2);
        };
    }

    /** A flat 2D shape in (x, z), raised to half-height h. */
    interface Flat { double d(double u, double v); }

    static Sdf raise(Flat f, double h) {
        return (x, y, z) -> {
            double a = f.d(x, z), b = Math.abs(y) - h;
            return Math.min(Math.max(a, b), 0) + len(Math.max(a, 0), Math.max(b, 0));
        };
    }

    static double seg(double u, double v, double ax, double ay, double bx, double by) {
        double pax = u - ax, pay = v - ay, bax = bx - ax, bay = by - ay;
        double h = clamp((pax * bax + pay * bay) / (bax * bax + bay * bay), 0, 1);
        return len(pax - bax * h, pay - bay * h);
    }

    /** The rupee sign, about 1 unit tall, top towards -v. */
    static double rupee(double u, double v) {
        double t = .058;
        double d = seg(u, v, -.32, -.42, .34, -.42) - t;                              // top bar
        d = Math.min(d, seg(u, v, -.32, -.2, .34, -.2) - t);                          // second bar
        double a = Math.atan2(v + .2, u + .04);                                       // the bowl
        double arc = (a > -Math.PI / 2 - .02 && a < Math.PI / 2 + .02)
                ? Math.abs(len(u + .04, v + .2) - .22) - t : 1e9;
        d = Math.min(d, Math.min(arc, seg(u, v, -.32, .02, -.04, .02) - t));
        d = Math.min(d, seg(u, v, -.04, .02, .3, .48) - t * 1.05);                     // the leg
        double top = (a < 0 && u > -.04) ? Math.abs(len(u + .04, v + .2) - .22) - t : 1e9;
        return Math.min(d, top);
    }

    /** A cut gem: flat facets round a point, so it breaks light as a stone does. */
    static Sdf gem(double r) {
        List<double[]> planes = new ArrayList<>();
        planes.add(new double[] {0, 1, 0, r * .62});                                  // the table
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            planes.add(n(Math.cos(a), .95, Math.sin(a), r * .86));                    // crown
            planes.add(n(Math.cos(a + .39), -1.02, Math.sin(a + .39), r * .78));      // pavilion
            planes.add(n(Math.cos(a + .39), .32, Math.sin(a + .39), r * .98));        // girdle
        }
        double[][] p = planes.toArray(new double[0][]);
        return (x, y, z) -> {
            double d = -1e9;
            for (double[] q : p) d = Math.max(d, x * q[0] + y * q[1] + z * q[2] - q[3]);
            return d;
        };
    }

    static double[] n(double x, double y, double z, double off) {
        double l = len(x, y, z);
        return new double[] {x / l, y / l, z / l, off};
    }

    // ------------------------------------------------------------------ materials

    interface Tex { void at(double x, double y, double z, double[] rgb); }

    static final class Mat {
        double r, g, b, rough;
        boolean metal, glass;
        double ior = 1.5, ar, ag, ab;        // glass: absorption
        double spec = .04;                   // a dielectric's reflectance at normal
        double er, eg, eb;                   // emission
        Tex tex;
        double bump;                         // fine surface noise, for brushed or leather surfaces
        double bumpScale = 40;

        static Mat metal(double r, double g, double b, double rough) {
            Mat m = new Mat(); m.r = r; m.g = g; m.b = b; m.rough = rough; m.metal = true; return m;
        }
        static Mat plain(double r, double g, double b, double rough, double spec) {
            Mat m = new Mat(); m.r = r; m.g = g; m.b = b; m.rough = rough; m.spec = spec; return m;
        }
        static Mat gem(double ar, double ag, double ab, double ior) {
            Mat m = new Mat(); m.glass = true; m.ar = ar; m.ag = ag; m.ab = ab; m.ior = ior; m.rough = 0; return m;
        }
        Mat with(double bump, double scale) { this.bump = bump; this.bumpScale = scale; return this; }
    }

    static final Mat GOLD      = Mat.metal(1.00, .766, .336, .11);
    static final Mat GOLD_SOFT = Mat.metal(1.00, .74, .32, .26);
    static final Mat GOLD_ROSE = Mat.metal(1.00, .66, .42, .15);
    static final Mat SILVER    = Mat.metal(.972, .960, .915, .13);
    static final Mat STEEL     = Mat.metal(.62, .64, .68, .24);
    static final Mat BRASS     = Mat.metal(.91, .71, .35, .19);
    static final Mat RUBY      = Mat.gem(.25, 7.5, 6.5, 1.76);
    static final Mat EMERALD   = Mat.gem(6.5, .35, 3.6, 1.58);
    static final Mat SAPPHIRE  = Mat.gem(7.0, 5.0, .35, 1.76);
    static final Mat DIAMOND   = Mat.gem(.02, .02, .02, 2.42);
    static final Mat CRYSTAL   = Mat.gem(.04, .03, .02, 1.52);
    static final Mat WINE_LACQ = Mat.plain(.20, .018, .035, .05, .06);
    static final Mat LEATHER   = Mat.plain(.17, .018, .03, .34, .05).with(.0022, 70);
    static final Mat NAVY_LEA  = Mat.plain(.018, .034, .085, .34, .05).with(.0022, 70);
    static final Mat PAPER     = Mat.plain(.50, .45, .35, .7, .03);
    static final Mat CREAM     = Mat.plain(.54, .49, .38, .35, .04);
    static final Mat INK       = Mat.plain(.012, .010, .010, .3, .04);
    static final Mat DARK_IRON = Mat.metal(.12, .11, .11, .3);

    // ------------------------------------------------------------------ studio

    /** A soft box: a glowing rectangle on the sky for the metal to mirror. */
    static final class Box {
        double dx, dy, dz, ux, uy, uz, vx, vy, vz, w, h, r, g, b;
        Box(double ax, double ay, double az, double w, double h, double power, double r, double g, double b) {
            double l = len(ax, ay, az);
            dx = ax / l; dy = ay / l; dz = az / l;
            // its sides: level, and up
            double sx = -dz, sz = dx, sl = Math.max(1e-6, len(sx, sz));
            ux = sx / sl; uy = 0; uz = sz / sl;
            vx = uy * dz - uz * dy; vy = uz * dx - ux * dz; vz = ux * dy - uy * dx;
            this.w = w; this.h = h;
            this.r = r * power; this.g = g * power; this.b = b * power;
        }
    }

    static final class Rig {
        final List<Box> boxes = new ArrayList<>();
        double topR, topG, topB, lowR, lowG, lowB;
        Rig sky(double tr, double tg, double tb, double lr, double lg, double lb) {
            topR = tr; topG = tg; topB = tb; lowR = lr; lowG = lg; lowB = lb; return this;
        }
        Rig box(double ax, double ay, double az, double w, double h, double p, double r, double g, double b) {
            boxes.add(new Box(ax, ay, az, w, h, p, r, g, b)); return this;
        }
        /** A wash on the backdrop: what the camera and the floor see behind the subject. */
        Rig glow(double ax, double ay, double az, double k, double r, double g, double b) {
            double l = len(ax, ay, az);
            glowX = ax / l; glowY = ay / l; glowZ = az / l; glowK = k; glowR = r; glowG = g; glowB = b;
            return this;
        }
        double glowX, glowY, glowZ, glowK, glowR, glowG, glowB;
        void sky(double x, double y, double z, double[] out, boolean withBoxes) {
            double t = clamp(y * .5 + .5, 0, 1);
            double ro = mix(lowR, topR, t), go = mix(lowG, topG, t), bo = mix(lowB, topB, t);
            if (glowK > 0) {
                double c = x * glowX + y * glowY + z * glowZ;
                if (c > 0) { double e = Math.pow(c, glowK); ro += glowR * e; go += glowG * e; bo += glowB * e; }
            }
            if (!withBoxes) { out[0] = ro; out[1] = go; out[2] = bo; return; }
            for (Box l : boxes) {
                double c = x * l.dx + y * l.dy + z * l.dz;
                if (c <= 0) continue;
                double u = (x * l.ux + y * l.uy + z * l.uz) / c, v = (x * l.vx + y * l.vy + z * l.vz) / c;
                double e = smooth(l.w, l.w * .82, Math.abs(u)) * smooth(l.h, l.h * .82, Math.abs(v));
                ro += l.r * e; go += l.g * e; bo += l.b * e;
            }
            out[0] = ro; out[1] = go; out[2] = bo;
        }
    }

    /** Warm key above left, cool rim behind right, a long strip so the metal carries a line of light. */
    static Rig royal(double warm, double cool) {
        return new Rig().sky(.20, .14, .13, .030, .016, .018)
                .box(-.8, 1.2, .9, .70, .52, 3.0 * warm, 1, .88, .72)
                .box(1.3, .55, -.9, .26, .9, 4.2 * cool, .58, .78, 1)
                .box(.15, .5, 1.5, 1.3, .07, 5.2 * warm, 1, .93, .82)
                .box(-1.4, .15, -.2, .16, .55, 1.5 * cool, .9, .62, .55)
                .box(.1, .12, 1, 1.1, .55, .40 * warm, 1, .90, .76);     // a big card in front: flat faces read gold, not brown
    }

    /** A plate's studio: the same lamps, the wall dark but for a pool of warm light behind the subject. */
    static Rig studio(double warm, double cool, double glowR, double glowG, double glowB) {
        Rig r = royal(warm, cool).glow(.28, .16, -1, 26, glowR, glowG, glowB);
        r.sky(.11, .07, .07, .018, .009, .012);
        return r;
    }

    // ------------------------------------------------------------------ the grade

    /** One asset's look, baked into its LUT. Works on display values, so a colourist would read it. */
    static final class Grade {
        double temp, tint, contrast = 1, sat = 1, gamma = 1, gain = 1, lift;
        double shR, shG, shB, hiR, hiG, hiB;
        double vignette, grain, bloom = .14, exposure = 1;
        Grade temp(double t, double i) { temp = t; tint = i; return this; }
        Grade tone(double contrast, double sat, double gamma, double gain, double lift) {
            this.contrast = contrast; this.sat = sat; this.gamma = gamma; this.gain = gain; this.lift = lift; return this;
        }
        Grade shadows(double r, double g, double b) { shR = r; shG = g; shB = b; return this; }
        Grade highs(double r, double g, double b) { hiR = r; hiG = g; hiB = b; return this; }
        Grade film(double vignette, double grain, double bloom, double exposure) {
            this.vignette = vignette; this.grain = grain; this.bloom = bloom; this.exposure = exposure; return this;
        }
        void apply(double[] c) {
            double r = c[0] * (1 + temp) , g = c[1] * (1 + tint), b = c[2] * (1 - temp);
            r = Math.pow(clamp(r, 0, 4), gamma) * gain + lift;
            g = Math.pow(clamp(g, 0, 4), gamma) * gain + lift;
            b = Math.pow(clamp(b, 0, 4), gamma) * gain + lift;
            r = .5 + (r - .5) * contrast; g = .5 + (g - .5) * contrast; b = .5 + (b - .5) * contrast;
            double l = clamp(.2126 * r + .7152 * g + .0722 * b, 0, 1);
            double sh = (1 - l) * (1 - l), hi = l * l;
            r += shR * sh + hiR * hi; g += shG * sh + hiG * hi; b += shB * sh + hiB * hi;
            double m = .2126 * r + .7152 * g + .0722 * b;
            c[0] = clamp(m + (r - m) * sat, 0, 1); c[1] = clamp(m + (g - m) * sat, 0, 1); c[2] = clamp(m + (b - m) * sat, 0, 1);
        }
    }

    /** The grade as a real 3D LUT: trilinear lookups from here on, and a .cube any grading tool can open. */
    static final class Lut {
        final int n = 33;
        final float[] t = new float[n * n * n * 3];
        Lut(Grade g, File cube, String title) throws Exception {
            double[] c = new double[3];
            for (int b = 0; b < n; b++) for (int gr = 0; gr < n; gr++) for (int r = 0; r < n; r++) {
                c[0] = r / (n - 1.0); c[1] = gr / (n - 1.0); c[2] = b / (n - 1.0);
                g.apply(c);
                int i = (b * n * n + gr * n + r) * 3;
                t[i] = (float) c[0]; t[i + 1] = (float) c[1]; t[i + 2] = (float) c[2];
            }
            cube.getParentFile().mkdirs();
            try (PrintWriter o = new PrintWriter(cube, "UTF-8")) {
                o.println("TITLE \"" + title + "\"");
                o.println("LUT_3D_SIZE " + n);
                for (int i = 0; i < t.length; i += 3) o.printf(java.util.Locale.ROOT, "%.6f %.6f %.6f%n", t[i], t[i + 1], t[i + 2]);
            }
        }
        void look(double[] c) {
            double fr = clamp(c[0], 0, 1) * (n - 1), fg = clamp(c[1], 0, 1) * (n - 1), fb = clamp(c[2], 0, 1) * (n - 1);
            int r0 = (int) Math.min(fr, n - 2), g0 = (int) Math.min(fg, n - 2), b0 = (int) Math.min(fb, n - 2);
            double dr = fr - r0, dg = fg - g0, db = fb - b0;
            for (int k = 0; k < 3; k++) {
                double c000 = t[((b0) * n * n + (g0) * n + r0) * 3 + k], c100 = t[((b0) * n * n + (g0) * n + r0 + 1) * 3 + k];
                double c010 = t[((b0) * n * n + (g0 + 1) * n + r0) * 3 + k], c110 = t[((b0) * n * n + (g0 + 1) * n + r0 + 1) * 3 + k];
                double c001 = t[((b0 + 1) * n * n + (g0) * n + r0) * 3 + k], c101 = t[((b0 + 1) * n * n + (g0) * n + r0 + 1) * 3 + k];
                double c011 = t[((b0 + 1) * n * n + (g0 + 1) * n + r0) * 3 + k], c111 = t[((b0 + 1) * n * n + (g0 + 1) * n + r0 + 1) * 3 + k];
                double a = mix(mix(c000, c100, dr), mix(c010, c110, dr), dg);
                double b = mix(mix(c001, c101, dr), mix(c011, c111, dr), dg);
                c[k] = mix(a, b, db);
            }
        }
    }

    // ------------------------------------------------------------------ the scene

    static final class Obj {
        final Sdf s; final Mat m; final double cx, cy, cz, br;
        Obj(Sdf s, Mat m, double cx, double cy, double cz, double br) {
            this.s = s; this.m = m; this.cx = cx; this.cy = cy; this.cz = cz; this.br = br;
        }
    }

    static final class Scene {
        String name;
        int w, h, spp = 8;
        final List<Obj> objs = new ArrayList<>();
        Obj[] arr;
        Rig rig = royal(1, 1);
        Grade grade = new Grade();
        double camX, camY = 1, camZ = 5, lookX, lookY = .5, lookZ, fov = 28, aperture, focus = 5;
        boolean bandFit;             // a band across a screen: framed to the right of the title
        boolean cutout;              // the object alone, with its reflection, on nothing: an icon
        Mat floor = WINE_LACQ;       // backdrop scenes: the glossy floor
        boolean hasFloor = true;
        double floorY = 0;
        double fadeAt = 2.6;         // an icon's reflection fades out with height
        Scene add(Sdf s, Mat m, double cx, double cy, double cz, double br) { objs.add(new Obj(s, m, cx, cy, cz, br)); return this; }
    }

    static final class Hit { double t; Obj o; }

    static final double EPS = 0.0007, FAR = 60;

    static void march(Scene sc, double ox, double oy, double oz, double dx, double dy, double dz, double tMax, boolean inside, Hit h) {
        double t = EPS * 3;
        h.o = null;
        Obj[] objs = sc.arr;
        for (int i = 0; i < 180 && t < tMax; i++) {
            double x = ox + dx * t, y = oy + dy * t, z = oz + dz * t;
            double best = 1e9;
            Obj bo = null;
            for (Obj o : objs) {
                double db = len(x - o.cx, y - o.cy, z - o.cz) - o.br;
                if (db > best) continue;
                double d = db > .5 ? db : o.s.d(x, y, z);
                if (inside) d = Math.abs(d);
                if (d < best) { best = d; bo = o; }
            }
            double eps = EPS * (1 + t * .15);
            if (best < eps) { h.t = t; h.o = bo; return; }
            t += best * (inside ? .8 : .95);
        }
    }

    static void normal(Sdf s, double x, double y, double z, double[] n) {
        double e = .00035;
        double a = s.d(x + e, y - e, z - e), b = s.d(x - e, y - e, z + e), c = s.d(x - e, y + e, z - e), d = s.d(x + e, y + e, z + e);
        double nx = a - b - c + d, ny = -a - b + c + d, nz = -a + b - c + d, l = Math.max(1e-12, len(nx, ny, nz));
        n[0] = nx / l; n[1] = ny / l; n[2] = nz / l;
    }

    static double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double a = mix(mix(hash(xi, yi, zi), hash(xi + 1, yi, zi), fx), mix(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), fx), fy);
        double b = mix(mix(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), fx), mix(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), fx), fy);
        return mix(a, b, fz);
    }

    static double hash(int x, int y, int z) {
        long h = x * 374761393L + y * 668265263L + z * 2147483647L;
        h = (h ^ (h >> 13)) * 1274126177L;
        return ((h ^ (h >> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /**
     * Light reaching a matt surface straight from one lamp, shadow included, ready to be multiplied by
     * its colour. Without this the floor only finds a lamp by chance, and that is the speckle.
     */
    static void direct(Scene sc, double x, double y, double z, double nx, double ny, double nz, SplittableRandom rnd, double[] out) {
        out[0] = out[1] = out[2] = 0;
        Hit h = new Hit();
        // Off the surface by more than the march's own tolerance, or a grazing ray finds its own start.
        double sx = x + nx * EPS * 9, sy = y + ny * EPS * 9, sz = z + nz * EPS * 9;
        for (Box l : sc.rig.boxes) {
            double u = (2 * rnd.nextDouble() - 1) * l.w, v = (2 * rnd.nextDouble() - 1) * l.h;
            double dx = l.dx + u * l.ux + v * l.vx, dy = l.dy + u * l.uy + v * l.vy, dz = l.dz + u * l.uz + v * l.vz;
            double dl = len(dx, dy, dz);
            dx /= dl; dy /= dl; dz /= dl;
            double cos = dx * nx + dy * ny + dz * nz;
            if (cos <= 0 || (sc.hasFloor && dy <= 0)) continue;
            march(sc, sx, sy, sz, dx, dy, dz, 14, false, h);
            if (h.o != null) continue;
            double k = cos * (4 * l.w * l.h / (dl * dl * dl)) / Math.PI;
            out[0] += l.r * k; out[1] += l.g * k; out[2] += l.b * k;
        }
    }

    /** One path. rgb = light gathered, rgb[3] = how solid (an icon's alpha). */
    static void path(Scene sc, double ox, double oy, double oz, double dx, double dy, double dz, SplittableRandom rnd, double[] out) {
        double tr = 1, tg = 1, tb = 1, lr = 0, lg = 0, lb = 0, alpha = 1;
        boolean inside = false, first = true, matt = false;
        double[] lamp = new double[3];
        double[] n = new double[3], sky = new double[3], tex = new double[3];
        Hit hit = new Hit();
        for (int bounce = 0; bounce < 7; bounce++) {
            double tPlane = FAR;
            if (sc.hasFloor && !inside && dy < -1e-6) tPlane = (sc.floorY - oy) / dy;
            if (tPlane < 0) tPlane = FAR;
            march(sc, ox, oy, oz, dx, dy, dz, tPlane, inside, hit);

            if (hit.o == null && tPlane < FAR) {
                double x = ox + dx * tPlane, z = oz + dz * tPlane;
                if (sc.cutout) {
                    if (!first) {                   // a reflection of the floor: it is not there
                        sc.rig.sky(dx, dy, dz, sky, !matt);
                        lr += tr * sky[0]; lg += tg * sky[1]; lb += tb * sky[2];
                        break;
                    }
                    // The floor of an icon is only its reflection and its soft shadow.
                    double cos = -dy;
                    double f = .10 + .55 * Math.pow(1 - cos, 3);
                    double rx = dx, ry = -dy, rz = dz;
                    march(sc, x, sc.floorY + .002, z, rx, ry, rz, 12, false, hit);
                    double a = 0, sr = 0, sg = 0, sb = 0;
                    if (hit.o != null) {
                        double hy = sc.floorY + .002 + ry * hit.t;
                        double fade = smooth(sc.fadeAt, .05, hy);
                        double[] q = new double[4];
                        path(sc, x + rx * hit.t * .999, sc.floorY + .002 + ry * hit.t * .999, z + rz * hit.t * .999, rx, ry, rz, rnd, q);
                        double k = fade * Math.min(1, f * 2.2);
                        sr = q[0] * k; sg = q[1] * k; sb = q[2] * k;
                        a = fade * .55;
                    }
                    // contact shadow: how much of the sky the object hides from this bit of floor
                    double u1 = rnd.nextDouble(), u2 = rnd.nextDouble() * 2 * Math.PI, sr1 = Math.sqrt(u1);
                    double sx = sr1 * Math.cos(u2), sz = sr1 * Math.sin(u2), sy = Math.sqrt(1 - u1);
                    march(sc, x, sc.floorY + .002, z, sx, sy, sz, 2.2, false, hit);
                    double shadow = hit.o != null ? .62 * smooth(2.2, .0, hit.t) : 0;
                    out[0] = sr; out[1] = sg; out[2] = sb;
                    out[3] = Math.min(1, a + shadow * (1 - a));
                    return;
                }
                // A polished floor: Fresnel mirror over its own colour.
                Mat m = sc.floor;
                double c = -dy;
                double f = m.spec + (1 - m.spec) * Math.pow(1 - c, 5);
                double wob = .0015 * (noise(x * 3, 0, z * 3) - .5);
                if (rnd.nextDouble() < f) {
                    ox = x; oy = sc.floorY + .001; oz = z;
                    dy = -dy; dx += wob; dz += wob * .6;
                    double l = len(dx, dy, dz); dx /= l; dy /= l; dz /= l;
                } else {
                    tr *= m.r; tg *= m.g; tb *= m.b;
                    direct(sc, x, sc.floorY + .001, z, 0, 1, 0, rnd, lamp);
                    lr += tr * lamp[0]; lg += tg * lamp[1]; lb += tb * lamp[2];
                    matt = true;
                    double u1 = rnd.nextDouble(), u2 = rnd.nextDouble() * 2 * Math.PI, r = Math.sqrt(u1);
                    ox = x; oy = sc.floorY + .001; oz = z;
                    dx = r * Math.cos(u2); dz = r * Math.sin(u2); dy = Math.sqrt(1 - u1);
                }
                first = false;
                continue;
            }

            if (hit.o == null) {
                if (sc.cutout && first) { out[0] = out[1] = out[2] = out[3] = 0; return; }
                sc.rig.sky(dx, dy, dz, sky, !matt);
                lr += tr * sky[0]; lg += tg * sky[1]; lb += tb * sky[2];
                break;
            }

            first = false;
            double x = ox + dx * hit.t, y = oy + dy * hit.t, z = oz + dz * hit.t;
            Mat m = hit.o.m;
            normal(hit.o.s, x, y, z, n);
            if (m.bump > 0) {
                double s = m.bumpScale;
                n[0] += m.bump * s * (noise(x * s, y * s, z * s) - .5);
                n[1] += m.bump * s * (noise(y * s + 7, z * s, x * s) - .5);
                n[2] += m.bump * s * (noise(z * s, x * s + 3, y * s) - .5);
                double l = len(n[0], n[1], n[2]); n[0] /= l; n[1] /= l; n[2] /= l;
            }
            lr += tr * m.er; lg += tg * m.eg; lb += tb * m.eb;

            if (m.glass) {
                double nx = n[0], ny = n[1], nz = n[2];
                if (inside) {                      // light lost on the way through the stone
                    tr *= Math.exp(-m.ar * hit.t * 2.5); tg *= Math.exp(-m.ag * hit.t * 2.5); tb *= Math.exp(-m.ab * hit.t * 2.5);
                }
                double c = -(dx * nx + dy * ny + dz * nz);
                if (c < 0) { nx = -nx; ny = -ny; nz = -nz; c = -c; }
                double eta = inside ? m.ior : 1 / m.ior;
                double k = 1 - eta * eta * (1 - c * c);
                double f0 = Math.pow((m.ior - 1) / (m.ior + 1), 2), f = k < 0 ? 1 : f0 + (1 - f0) * Math.pow(1 - c, 5);
                if (rnd.nextDouble() < f) {
                    double dd = 2 * c; dx += nx * dd; dy += ny * dd; dz += nz * dd;
                    ox = x + nx * EPS * 4; oy = y + ny * EPS * 4; oz = z + nz * EPS * 4;
                } else {
                    double q = eta * c - Math.sqrt(k);
                    dx = eta * dx + q * nx; dy = eta * dy + q * ny; dz = eta * dz + q * nz;
                    ox = x - nx * EPS * 4; oy = y - ny * EPS * 4; oz = z - nz * EPS * 4;
                    inside = !inside;
                }
                continue;
            }

            double vc = -(dx * n[0] + dy * n[1] + dz * n[2]);
            if (vc < 0) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; vc = -vc; }
            double px = x + n[0] * EPS * 3, py = y + n[1] * EPS * 3, pz = z + n[2] * EPS * 3;

            double cr = m.r, cg = m.g, cb = m.b;
            if (m.tex != null) { m.tex.at(x, y, z, tex); cr = tex[0]; cg = tex[1]; cb = tex[2]; }

            boolean specular = m.metal;
            double f = 1;
            if (!m.metal) {
                f = m.spec + (1 - m.spec) * Math.pow(1 - vc, 5);
                specular = rnd.nextDouble() < f;
            }
            if (specular) {
                // GGX: a tilted facet, and the mirror off it.
                double a = m.rough * m.rough, u1 = rnd.nextDouble(), u2 = rnd.nextDouble() * 2 * Math.PI;
                double ct = Math.sqrt((1 - u1) / (1 + (a * a - 1) * u1)), st = Math.sqrt(Math.max(0, 1 - ct * ct));
                double tx, ty, tz;
                if (Math.abs(n[1]) < .9) { tx = -n[2]; ty = 0; tz = n[0]; } else { tx = 0; ty = n[2]; tz = -n[1]; }
                double tl = len(tx, ty, tz); tx /= tl; ty /= tl; tz /= tl;
                double bx = n[1] * tz - n[2] * ty, by = n[2] * tx - n[0] * tz, bz = n[0] * ty - n[1] * tx;
                double hx = st * Math.cos(u2) * tx + st * Math.sin(u2) * bx + ct * n[0];
                double hy = st * Math.cos(u2) * ty + st * Math.sin(u2) * by + ct * n[1];
                double hz = st * Math.cos(u2) * tz + st * Math.sin(u2) * bz + ct * n[2];
                double vh = -(dx * hx + dy * hy + dz * hz);
                if (vh < 0) { hx = -hx; hy = -hy; hz = -hz; vh = -vh; }
                dx += 2 * vh * hx; dy += 2 * vh * hy; dz += 2 * vh * hz;
                if (dx * n[0] + dy * n[1] + dz * n[2] < 0) { double e = 2 * (dx * n[0] + dy * n[1] + dz * n[2]); dx -= e * n[0]; dy -= e * n[1]; dz -= e * n[2]; }
                if (m.metal) {
                    double p = Math.pow(1 - vh, 5);
                    tr *= cr + (1 - cr) * p; tg *= cg + (1 - cg) * p; tb *= cb + (1 - cb) * p;
                }
            } else {
                tr *= cr; tg *= cg; tb *= cb;
                direct(sc, px, py, pz, n[0], n[1], n[2], rnd, lamp);
                lr += tr * lamp[0]; lg += tg * lamp[1]; lb += tb * lamp[2];
                matt = true;
                double u1 = rnd.nextDouble(), u2 = rnd.nextDouble() * 2 * Math.PI, r = Math.sqrt(u1);
                double tx, ty, tz;
                if (Math.abs(n[1]) < .9) { tx = -n[2]; ty = 0; tz = n[0]; } else { tx = 0; ty = n[2]; tz = -n[1]; }
                double tl = len(tx, ty, tz); tx /= tl; ty /= tl; tz /= tl;
                double bx = n[1] * tz - n[2] * ty, by = n[2] * tx - n[0] * tz, bz = n[0] * ty - n[1] * tx;
                double a = r * Math.cos(u2), b = r * Math.sin(u2), c = Math.sqrt(1 - u1);
                dx = a * tx + b * bx + c * n[0]; dy = a * ty + b * by + c * n[1]; dz = a * tz + b * bz + c * n[2];
            }
            double l = len(dx, dy, dz); dx /= l; dy /= l; dz /= l;
            ox = px; oy = py; oz = pz;
        }
        out[0] = lr; out[1] = lg; out[2] = lb; out[3] = alpha;
    }

    /**
     * Frames an icon by looking at it: finds where the object lands on the picture, then turns and
     * zooms the camera until it fills the frame, with room left underneath for its reflection.
     */
    static void autoFit(Scene sc) {
        boolean reflects = sc.hasFloor;
        double marginTop = reflects ? .05 : .04, marginBottom = reflects ? .17 : .04, marginSide = .05;
        double aspect = (double) sc.w / sc.h;
        if (sc.bandFit) { marginTop = .07; marginBottom = .20; }
        double T = 1.2;
        Hit h = new Hit();
        int n = 170;
        for (int iter = 0; iter < 4; iter++) {
            double fx = sc.lookX - sc.camX, fy = sc.lookY - sc.camY, fz = sc.lookZ - sc.camZ, fl = len(fx, fy, fz);
            fx /= fl; fy /= fl; fz /= fl;
            double rx = -fz, rz = fx, rl = len(rx, rz); rx /= rl; rz /= rl;
            double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
            for (int py = 0; py < n; py++) for (int px = 0; px < n; px++) {
                double sx = ((px + .5) / n * 2 - 1) * T * (sc.bandFit ? aspect : 1), sy = (1 - (py + .5) / n * 2) * T;
                double dx = fx + rx * sx + ux * sy, dy = fy + uy * sy, dz = fz + rz * sx + uz * sy, dl = len(dx, dy, dz);
                march(sc, sc.camX, sc.camY, sc.camZ, dx / dl, dy / dl, dz / dl, FAR, false, h);
                if (h.o == null) continue;
                minX = Math.min(minX, sx); maxX = Math.max(maxX, sx); minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
            }
            if (maxX < minX) return;
            double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, hx = (maxX - minX) / 2, hy = (maxY - minY) / 2;
            // A band's subject keeps to the right half: the screen's title has the left.
            double H = sc.bandFit ? Math.max(hy / (1 - marginTop - marginBottom), hx / (.50 * aspect))
                                  : Math.max(hy / (1 - marginTop - marginBottom), hx / (1 - 2 * marginSide));
            double shift = (marginBottom - marginTop) * H;       // the object sits a little high
            double side = sc.bandFit ? cx - .47 * H * aspect : cx;
            double lx = fx + rx * side + ux * (cy - shift), ly = fy + uy * (cy - shift), lz = fz + rz * side + uz * (cy - shift);
            sc.lookX = sc.camX + lx * fl; sc.lookY = sc.camY + ly * fl; sc.lookZ = sc.camZ + lz * fl;
            sc.fov = 2 * Math.toDegrees(Math.atan(H));
            T = H * (sc.bandFit ? 1.3 : 1.25);
            sc.fadeAt = marginBottom * 2 * H * fl * .95;         // an icon's reflection is gone by the frame's edge
        }
    }

    // ------------------------------------------------------------------ rendering one asset

    static void render(Scene sc, File dir, double scale, int sppOverride) throws Exception {
        long t0 = System.currentTimeMillis();
        int w = (int) Math.round(sc.w * scale), h = (int) Math.round(sc.h * scale);
        int spp = sppOverride > 0 ? sppOverride : sc.spp;
        sc.arr = sc.objs.toArray(new Obj[0]);
        if (sc.cutout || sc.bandFit) autoFit(sc);
        float[] fb = new float[w * h * 4];

        double fx = sc.lookX - sc.camX, fy = sc.lookY - sc.camY, fz = sc.lookZ - sc.camZ, fl = len(fx, fy, fz);
        fx /= fl; fy /= fl; fz /= fl;
        double rx = -fz, rz = fx, rl = len(rx, rz); rx /= rl; rz /= rl;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;   // right x forward
        double tan = Math.tan(Math.toRadians(sc.fov) / 2);
        final double frx = rx, frz = rz, fux = ux, fuy = uy, fuz = uz, ffx = fx, ffy = fy, ffz = fz;
        final double aspect = (double) w / h;

        IntStream.range(0, h).parallel().forEach(py -> {
            SplittableRandom rnd = new SplittableRandom(sc.name.hashCode() * 31L + py * 8191L);
            double[] c = new double[4];
            for (int px = 0; px < w; px++) {
                double ar = 0, ag = 0, ab = 0, aa = 0;
                int root = Math.max(1, (int) Math.round(Math.sqrt(spp)));
                for (int a = 0; a < root; a++) for (int b = 0; b < root; b++) {
                    double u = (px + (a + rnd.nextDouble()) / root) / w * 2 - 1;
                    double v = 1 - (py + (b + rnd.nextDouble()) / root) / h * 2;
                    double sx = u * tan * aspect, sy = v * tan;
                    double dx = ffx + frx * sx + fux * sy, dy = ffy + fuy * sy, dz = ffz + frz * sx + fuz * sy;
                    double dl = len(dx, dy, dz); dx /= dl; dy /= dl; dz /= dl;
                    double ox = sc.camX, oy = sc.camY, oz = sc.camZ;
                    if (sc.aperture > 0) {       // depth of field: a real lens, not a blur
                        double tf = sc.focus;
                        double fpx = ox + dx * tf, fpy = oy + dy * tf, fpz = oz + dz * tf;
                        double r = Math.sqrt(rnd.nextDouble()) * sc.aperture, th = rnd.nextDouble() * 2 * Math.PI;
                        ox += frx * r * Math.cos(th) + fux * r * Math.sin(th);
                        oy += fuy * r * Math.sin(th);
                        oz += frz * r * Math.cos(th) + fuz * r * Math.sin(th);
                        dx = fpx - ox; dy = fpy - oy; dz = fpz - oz;
                        dl = len(dx, dy, dz); dx /= dl; dy /= dl; dz /= dl;
                    }
                    path(sc, ox, oy, oz, dx, dy, dz, rnd, c);
                    // Fireflies off a gem: one bright path must not stain a whole pixel.
                    double peak = Math.max(c[0], Math.max(c[1], c[2]));
                    if (peak > 14) { double k = 14 / peak; c[0] *= k; c[1] *= k; c[2] *= k; }
                    ar += c[0]; ag += c[1]; ab += c[2]; aa += c[3];
                }
                int n = root * root, i = (py * w + px) * 4;
                fb[i] = (float) (ar / n); fb[i + 1] = (float) (ag / n); fb[i + 2] = (float) (ab / n); fb[i + 3] = (float) (aa / n);
            }
        });

        finish(sc, fb, w, h, dir, scale);
        System.out.printf("%-20s %dx%d %d spp  %.1fs%n", sc.name, w, h, spp, (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** Bloom, film curve, the LUT, then vignette and grain: the same order a grade runs in. */
    static void finish(Scene sc, float[] fb, int w, int h, File dir, double scale) throws Exception {
        Grade g = sc.grade;
        // Bloom on what is brighter than white, at three widths.
        if (g.bloom > 0) {
            float[] glow = new float[w * h * 3];
            for (int i = 0; i < w * h; i++) {
                for (int k = 0; k < 3; k++) glow[i * 3 + k] = Math.max(0, fb[i * 4 + k] * (float) g.exposure - 1f);
            }
            int base = Math.max(1, Math.max(w, h) / 200);
            float[] total = new float[w * h * 3];
            for (int mul : new int[] {1, 3, 8}) {
                float[] b = glow.clone();
                blur(b, w, h, base * mul);
                blur(b, w, h, base * mul);
                for (int i = 0; i < total.length; i++) total[i] += b[i] / 3f;
            }
            for (int i = 0; i < w * h; i++) {
                float add = 0;
                for (int k = 0; k < 3; k++) { float v = total[i * 3 + k] * (float) g.bloom; fb[i * 4 + k] += v; add = Math.max(add, v); }
                if (sc.cutout) fb[i * 4 + 3] = Math.min(1f, fb[i * 4 + 3] + add);
            }
        }
        File cube = new File(dir.getParentFile(), "luts/" + sc.name + ".cube");
        Lut lut = new Lut(g, cube, sc.name);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        SplittableRandom grain = new SplittableRandom(sc.name.hashCode());
        double[] c = new double[3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            double a = clamp(fb[i + 3], 0, 1);
            double un = sc.cutout ? Math.max(a, 1e-4) : 1;
            for (int k = 0; k < 3; k++) {
                double v = fb[i + k] * g.exposure / un;
                v = clamp(v * (2.51 * v + .03) / (v * (2.43 * v + .59) + .14), 0, 1);             // ACES
                c[k] = v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055;            // sRGB
            }
            lut.look(c);
            if (g.vignette > 0) {
                double dx = (x + .5) / w - .5, dy = (y + .5) / h - .5;
                double vg = 1 - g.vignette * smooth(.25, .85, Math.sqrt(dx * dx + dy * dy) * 1.414);
                c[0] *= vg; c[1] *= vg; c[2] *= vg;
            }
            if (g.grain > 0) {
                double l = .2126 * c[0] + .7152 * c[1] + .0722 * c[2];
                double e = (grain.nextDouble() - .5) * g.grain * (1 - l * .7);
                c[0] += e; c[1] += e; c[2] += e;
            }
            int ia = (int) Math.round(a * 255);
            int ir = (int) Math.round(clamp(c[0], 0, 1) * 255), ig = (int) Math.round(clamp(c[1], 0, 1) * 255), ib = (int) Math.round(clamp(c[2], 0, 1) * 255);
            out.setRGB(x, y, (ia << 24) | (ir << 16) | (ig << 8) | ib);
        }
        dir.mkdirs();
        ImageIO.write(out, "png", new File(dir, sc.name + ".png"));
    }

    static void blur(float[] b, int w, int h, int r) {
        float[] t = new float[b.length];
        for (int p = 0; p < 2; p++) {
            final boolean across = p == 0;
            int len = across ? h : w, wid = across ? w : h;
            float[] from = across ? b : t, to = across ? t : b;
            IntStream.range(0, len).parallel().forEach(l -> {
                for (int k = 0; k < 3; k++) {
                    float sum = 0;
                    for (int i = -r; i <= r; i++) {
                        int q = Math.min(wid - 1, Math.max(0, i));
                        sum += across ? from[(l * w + q) * 3 + k] : from[(q * w + l) * 3 + k];
                    }
                    for (int i = 0; i < wid; i++) {
                        if (across) to[(l * w + i) * 3 + k] = sum / (2 * r + 1);
                        else to[(i * w + l) * 3 + k] = sum / (2 * r + 1);
                        int add = Math.min(wid - 1, i + r + 1), sub = Math.max(0, i - r);
                        sum += across ? from[(l * w + add) * 3 + k] - from[(l * w + sub) * 3 + k]
                                      : from[(add * w + l) * 3 + k] - from[(sub * w + l) * 3 + k];
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ the shop's things

    /** A coin: raised rim, recessed field, reeded edge, the rupee sign struck on the face. */
    static Sdf coin(double r, double h, boolean strike) {
        Sdf body = bumped(cyl(r, h, h * .35), r * .0035, 140);
        Sdf field = at(cyl(r * .86, h * .5, h * .1), 0, h * 1.15, 0);
        Sdf back = at(cyl(r * .86, h * .5, h * .1), 0, -h * 1.15, 0);
        Sdf c = cut(cut(body, field), back);
        if (!strike) return c;
        double s = r * 1.32;
        Sdf sign = at(raise((u, v) -> rupee(u / s, v / s) * s, h * .34), 0, h * .72, 0);
        return union(c, sign);
    }

    /**
     * The coin held up to be looked at: a proof strike, frosted field and mirror sign, because a sign
     * of the same finish as its field shows only its edges.
     */
    static void medal(Scene sc, double x, double y, double z, double rx, double ry, double r, double h) {
        Sdf rim = cut(bumped(cyl(r, h, h * .3), r * .0035, 150), union(at(cyl(r * .87, h * .5, 0), 0, h * .95, 0), at(cyl(r * .87, h * .5, 0), 0, -h * .95, 0)));
        Sdf field = cyl(r * .875, h * .46, 0);
        double s = r * 1.42, b = h * .10, top = h * .30;
        Sdf sign = (px, py, pz) -> {
            double a = rupee(px / s, pz / s) * s + b, c = Math.abs(py - h * .46) - top + b;
            return Math.min(Math.max(a, c), 0) + len(Math.max(a, 0), Math.max(c, 0)) - b;
        };
        sc.add(at(rim, x, y, z, rx, ry, 0), GOLD, x, y, z, r * 1.05);
        sc.add(at(field, x, y, z, rx, ry, 0), Mat.metal(1.0, .73, .31, .42), x, y, z, r * 1.05);
        sc.add(at(sign, x, y, z, rx, ry, 0), Mat.metal(1.0, .80, .40, .07), x, y, z, r);
    }

    /** A coin stack, each slightly out of true, as a stack really sits. */
    static void stack(Scene sc, double x, double z, int count, double r, double h, Mat m, long seed) {
        SplittableRandom q = new SplittableRandom(seed);
        double y = h;
        List<Sdf> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double ox = (q.nextDouble() - .5) * r * .09, oz = (q.nextDouble() - .5) * r * .09;
            parts.add(at(coin(r, h, i == count - 1), x + ox, y, z + oz, 0, q.nextDouble() * 360, 0));
            y += h * 2.02;
        }
        sc.add(union(parts.toArray(new Sdf[0])), m, x, y / 2, z, Math.max(y / 2, r) + r * .3);
    }

    static Sdf ring(double major, double minor) { return band(major, minor, minor * 1.5, minor * .55); }

    /** A ring standing up, stone at the top. */
    static void jewelRing(Scene sc, double x, double y, double z, double ry, double rz, double size, Mat metal, Mat stone) {
        Sdf shank = at(ring(size, size * .11), x, y, z, 90, ry, rz);
        double a = Math.toRadians(rz), yr = Math.toRadians(ry);
        double top = size * 1.09;
        double sx = x - Math.sin(a) * top * Math.cos(yr), sy = y + Math.cos(a) * top, sz = z + Math.sin(a) * top * Math.sin(yr);
        double gr = size * .42;
        Sdf setting = union(
                at(cone(gr * .55, gr * .86, gr * .55), sx, sy - gr * .55, sz),
                at(around(4, capsule(gr * .62, 0, 0, gr * .78, gr * .55, 0, gr * .07)), sx, sy - gr * .1, sz, 0, 45, 0));
        sc.add(union(shank, setting), metal, x, y + size * .15, z, size * 1.6);
        sc.add(at(gem(gr), sx, sy + gr * .36, sz, 0, 15, 0), stone, sx, sy + gr * .36, sz, gr * 1.2);
    }

    /** Bangles stacked at a lean. */
    static void bangles(Scene sc, double x, double y, double z, double rx, double ry, double rz, int count, double major, Mat[] mats) {
        for (int i = 0; i < count; i++) {
            Sdf b = band(major, major * .055, major * .075, major * .03);
            double off = i * major * .165;
            sc.add(at(b, x, y + off, z, rx, ry + i * 20, rz), mats[i % mats.length], x, y + off, z, major * 1.2);
        }
    }

    /** A ledger, lying flat: leather boards, cream pages, gold bands across its spine. */
    static void ledger(Scene sc, double x, double y, double z, double ry, double w, double t, double d, Mat cover) {
        Sdf boards = union(at(box(w, t * .13, d, t * .08), 0, t * .87, 0), at(box(w, t * .13, d, t * .08), 0, -t * .87, 0),
                at(box(t * .16, t, d, t * .12), -w + t * .1, 0, 0));
        Sdf pages = at(box(w * .96, t * .75, d * .96, t * .02), w * .015, 0, 0);
        Sdf bands = union(at(box(t * .19, t * .98, d * .05, t * .04), -w + t * .08, 0, -d * .55),
                at(box(t * .19, t * .98, d * .05, t * .04), -w + t * .08, 0, d * .55),
                at(box(w * .32, t * .012, d * .32, t * .01), w * .15, t, 0));
        double r = len(w, t, d) * 1.1;
        sc.add(at(boards, x, y, z, 0, ry, 0), cover, x, y, z, r);
        sc.add(at(pages, x, y, z, 0, ry, 0), PAPER, x, y, z, r);
        sc.add(at(bands, x, y, z, 0, ry, 0), GOLD, x, y, z, r);
    }

    /** Gold ingots rising left to right: the month by month, in the shop's own metal. */
    static void ingotBars(Scene sc, double x, double z, double[] heights, double w, double gap, Mat m) {
        double cx = x - (heights.length - 1) * (w * 2 + gap) / 2;
        for (double h : heights) {
            Sdf bar = union(box(w, h, w * .8, w * .13), at(box(w * .8, w * .03, w * .62, w * .02), 0, h + w * .02, 0));
            sc.add(at(bar, cx, h, z), m, cx, h, z, len(w, h, w) * 1.15);
            cx += w * 2 + gap;
        }
    }

    /** A vault door, facing the camera: steel disc, gold bolts, a spoked wheel. */
    static void vault(Scene sc, double x, double y, double z, double ry, double r) {
        double rr = r * 1.04;
        Sdf door = union(cyl(r, r * .14, r * .03), band(r * .86, r * .015, r * .17, r * .008), at(cyl(r * .3, r * .03, r * .01), 0, r * .16, 0));
        Sdf frame = band(r * 1.04, r * .1, r * .1, r * .02);
        Sdf bolts = at(around(10, at(cyl(r * .055, r * .03, r * .01), r * .74, 0, 0)), 0, r * .16, 0);
        Sdf wheel = at(union(torus(r * .34, r * .035), at(cyl(r * .1, r * .05, r * .02), 0, 0, 0),
                around(3, capsule(0, 0, 0, r * .34, 0, 0, r * .026))), 0, r * .3, 0);
        Sdf hub = at(cyl(r * .05, r * .13, r * .01), 0, r * .2, 0);
        sc.add(at(door, x, y, z, 90, ry, 0), Mat.metal(.62, .64, .68, .22).with(.0007, 90), x, y, z, rr * 1.2);
        sc.add(at(frame, x, y, z, 90, ry, 0), DARK_IRON, x, y, z, rr * 1.25);
        sc.add(at(union(bolts, wheel, hub), x, y, z, 90, ry, 0), GOLD, x, y, z, rr * 1.2);
    }

    /** Brass balance: base, column, beam, two pans on their chains. */
    static void scales(Scene sc, double x, double z, double h, double tilt, Mat m) {
        double beamY = h, half = h * .62, a = Math.toRadians(tilt);
        double lx = -half * Math.cos(a), ly = beamY + half * Math.sin(a), rx = half * Math.cos(a), ry = beamY - half * Math.sin(a);
        double drop = h * .42, pr = h * .24;
        List<Sdf> parts = new ArrayList<>();
        parts.add(cyl(h * .22, h * .025, h * .01));
        parts.add(at(cyl(h * .13, h * .022, h * .008), 0, h * .05, 0));
        parts.add(cone(h * .08, h * .035, h * .95));
        parts.add(at(sphere(h * .065), 0, beamY + h * .03, 0));
        parts.add(capsule(lx, ly, 0, rx, ry, 0, h * .03));
        for (double[] e : new double[][] {{lx, ly}, {rx, ry}}) {
            parts.add(at(sphere(h * .026), e[0], e[1], 0));
            for (int i = 0; i < 3; i++) {
                double t = i * 2 * Math.PI / 3 + .4;
                parts.add(capsule(e[0], e[1], 0, e[0] + Math.cos(t) * pr * .92, e[1] - drop, Math.sin(t) * pr * .92, h * .009));
            }
            Sdf pan = both(cut(sphere(pr * 1.3), at(sphere(pr * 1.28), 0, 0, 0)), at(box(pr * 2, pr * .4, pr * 2, 0), 0, -pr * 1.0, 0));
            parts.add(at(pan, e[0], e[1] - drop + pr * 1.05, 0));
        }
        sc.add(at(union(parts.toArray(new Sdf[0])), x, h * .025, z), m, x, h * .6, z, h * 1.2);
    }

    /** A pocket watch laid back a little: gold case, cream dial, crystal, chain. */
    static void watch(Scene sc, double x, double y, double z, double rx, double ry, double r) {
        Sdf caseBody = union(cyl(r, r * .14, r * .08), band(r * .97, r * .03, r * .17, r * .02));
        Sdf crown = union(at(cyl(r * .06, r * .1, r * .02), r * 0, 0, 0), at(torus(r * .17, r * .028), 0, r * .1, 0, 90, 0, 0));
        Sdf dial = at(cyl(r * .9, r * .012, 0), 0, r * .135, 0);
        Sdf ticksMoved = (px, py, pz) -> { double a = Math.atan2(pz, px), s = Math.PI / 6; a = a - s * Math.floor(a / s + .5); double l = len(px, pz); return box(r * .07, r * .006, r * .014, 0).d(l * Math.cos(a) - r * .76, py - r * .15, l * Math.sin(a)); };
        Sdf hands = union(capsule(0, r * .16, 0, r * .36, r * .16, -r * .28, r * .022), capsule(0, r * .17, 0, -r * .2, r * .17, -r * .62, r * .015), at(cyl(r * .05, r * .02, 0), 0, r * .17, 0));
        sc.add(at(caseBody, x, y, z, rx, ry, 0), GOLD, x, y, z, r * 1.15);
        sc.add(at(at(crown, 0, 0, -r * 1.08), x, y, z, rx, ry, 0), GOLD, x, y, z, r * 1.5);
        sc.add(at(dial, x, y, z, rx, ry, 0), CREAM, x, y, z, r * 1.1);
        sc.add(at(union(ticksMoved, hands), x, y, z, rx, ry, 0), INK, x, y, z, r * 1.1);
    }

    /** A bell, and its clapper. Bell-metal gold, ring on top. */
    static void bell(Scene sc, double x, double y, double z, double tilt, double s, Mat m) {
        // The profile of a real bell: round shoulders, straight waist, then a flare to the lip.
        Sdf waist = (px, py, pz) -> {
            double r = len(px, pz);
            double t = clamp(py / (s * .72), 0, 1);
            double prof = s * (.36 + .10 * Math.pow(1 - t, 1.5) + .20 * Math.pow(1 - t, 6));
            double d = (r - prof) * .72;
            return Math.max(Math.max(d, -py), py - s * .72);
        };
        Sdf dome = at(sphere(s * .36), 0, s * .72, 0);
        Sdf outer = smoothUnion(s * .04, waist, dome);
        Sdf hollow = (px, py, pz) -> {
            double r = len(px, pz);
            double t = clamp(py / (s * .72), 0, 1);
            double prof = s * (.30 + .10 * Math.pow(1 - t, 1.5) + .20 * Math.pow(1 - t, 6));
            return Math.max((r - prof) * .72, py - s * .7);
        };
        Sdf top = at(torus(s * .1, s * .03), 0, s * 1.14, 0, 90, 0, 0);
        Sdf rim = at(torus(s * .655, s * .028), 0, s * .01, 0);
        Sdf body = union(cut(smoothUnion(s * .02, outer, rim), hollow), top);
        sc.add(at(body, x, y, z, 0, 0, tilt), m, x, y + s * .5, z, s * 1.05);
        sc.add(at(at(sphere(s * .11), s * .0, -s * .13, 0), x, y, z, 0, 0, -tilt * .3), BRASS, x, y, z, s * .4);
    }

    /** Interlocking gears. */
    static Sdf gear(double r, double t, int teeth) {
        Sdf disc = cyl(r * .82, t, t * .15);
        Sdf tooth = at(box(r * .14, t * .92, r * .1, t * .08), r * .9, 0, 0);
        Sdf hub = at(cyl(r * .28, t * 1.3, t * .1), 0, 0, 0);
        Sdf hole = cyl(r * .13, t * 2, 0);
        Sdf windows = around(6, at(cyl(r * .13, t * 2, 0), r * .55, 0, 0));
        return cut(cut(union(disc, around(teeth, tooth), hub), hole), windows);
    }

    static void bust(Scene sc, double x, double z, double s, Mat m) {
        Sdf head = at(sphere(s * .32), 0, s * 1.14, 0);
        Sdf neck = at(cyl(s * .15, s * .16, s * .02), 0, s * .78, 0);
        Sdf torso = both(cone(s * .70, s * .24, s * .54), at(box(s * 2, s * .8, s * 2, 0), 0, s * .8, 0));
        Sdf all = smoothUnion(s * .05, smoothUnion(s * .05, head, neck), torso);
        sc.add(at(all, x, 0, z), m, x, s * .75, z, s * 1.25);
    }

    // ------------------------------------------------------------------ every asset

    static final double SQ = 2160;

    /** An object on nothing: an icon. Its look is set by the caller. */
    static Scene icon(String name, double camX, double camY, double camZ, double lookY, double fov) {
        Scene s = new Scene();
        s.name = name;
        s.w = (int) SQ; s.h = (int) SQ; s.spp = 16;
        s.cutout = true;
        s.camX = camX; s.camY = camY; s.camZ = camZ; s.lookY = lookY; s.fov = fov;
        s.grade = new Grade().tone(1.08, 1.06, .96, 1.05, 0).film(0, 0, .18, 1.25);
        return s;
    }

    /** A wide plate for the top of a screen: glossy floor, dark studio, the thing to the right. */
    static Scene plate(String name, double lookX, double lookY, double camZ) {
        Scene s = new Scene();
        s.name = name;
        s.w = 3840; s.h = 1000; s.spp = 16;
        // Long lens, looking down on the bench: no horizon, the polished top fills the frame, and the
        // subject stands to the right because the screen's title sits on the left.
        s.bandFit = true;
        s.lookX = lookX - .55; s.lookY = lookY; s.camX = s.lookX - .42; s.camY = lookY + camZ * .17; s.camZ = camZ; s.fov = 11.5;
        s.floor = Mat.plain(.030, .004, .008, .05, .06);
        s.rig = studio(1, 1, .15, .06, .045);
        s.grade = new Grade().tone(1.10, 1.04, .98, 1.02, 0).film(.42, .018, .22, 1.2);
        return s;
    }

    static List<Scene> all() {
        List<Scene> a = new ArrayList<>();

        // ---- plates, one a screen, each graded for its own screen -----------------------------
        {   // Home: the shop's whole world on one bench. Tungsten warmth.
            Scene s = plate("plate_home", 1.55, .42, 7.4);
            stack(s, 1.05, .15, 7, .42, .036, GOLD, 1);
            stack(s, 1.92, -.15, 4, .42, .036, GOLD, 2);
            bangles(s, 2.7, .05, .28, 12, 20, 0, 3, .5, new Mat[] {GOLD, GOLD_ROSE});
            jewelRing(s, 3.42, .34, .3, 25, 0, .28, GOLD, RUBY);
            s.grade = new Grade().temp(.03, 0).tone(1.12, 1.08, .97, 1.03, 0).shadows(.018, .002, .006).highs(.02, .012, -.01).film(.45, .018, .24, 1.25);
            a.add(s);
        }
        {   // Bills: ledgers on a stack. Paper-warm, a little quieter.
            Scene s = plate("plate_bills", 1.6, .42, 7.2);
            ledger(s, 1.45, .13, .1, -18, .95, .13, .66, LEATHER);
            ledger(s, 1.5, .39, .05, 8, .87, .12, .62, NAVY_LEA);
            ledger(s, 1.42, .63, .08, -30, .8, .11, .57, LEATHER);
            stack(s, 2.95, .2, 3, .32, .03, GOLD, 3);
            s.grade = new Grade().temp(.045, .005).tone(1.06, .96, 1.0, 1.02, .004).shadows(.014, .004, .0).highs(.018, .012, -.006).film(.45, .02, .16, 1.28);
            a.add(s);
        }
        {   // Search Bill: the glass over a ring. Crisp, cooler shadows, the stone sings.
            Scene s = plate("plate_search", 1.6, .46, 6.8);
            jewelRing(s, 1.6, .36, .2, 30, 0, .3, GOLD, EMERALD);
            Sdf rim = at(band(.46, .04, .05, .014), 2.35, .68, .45, 72, -22, -8);
            Sdf glass = at(cyl(.43, .018, .01), 2.35, .68, .45, 72, -22, -8);
            Sdf handle = capsule(2.72, .22, .55, 3.1, .08, .62, .042);
            s.add(rim, GOLD, 2.35, .68, .45, .6);
            s.add(glass, CRYSTAL, 2.35, .68, .45, .55);
            s.add(handle, GOLD_SOFT, 2.9, .15, .58, .55);
            s.grade = new Grade().temp(-.01, 0).tone(1.14, 1.08, .98, 1.02, 0).shadows(-.004, .006, .018).highs(.018, .012, -.01).film(.45, .016, .22, 1.22);
            a.add(s);
        }
        {   // Today's Account: three stacks, today's cash. Green-gold, money light.
            Scene s = plate("plate_todays", 1.75, .46, 7.2);
            stack(s, 1.05, .2, 4, .4, .036, GOLD, 4);
            stack(s, 1.92, .0, 9, .4, .036, GOLD, 5);
            stack(s, 2.76, -.15, 14, .4, .036, GOLD, 6);
            stack(s, 3.55, .3, 2, .36, .03, SILVER, 7);
            s.grade = new Grade().temp(.015, .012).tone(1.10, 1.05, .98, 1.02, 0).shadows(.0, .01, .006).highs(.018, .014, -.012).film(.45, .018, .2, 1.22);
            a.add(s);
        }
        {   // A day's detail: gold and silver side by side, the coin on its edge.
            Scene s = plate("plate_account", 1.6, .46, 6.8);
            stack(s, 1.1, .1, 8, .4, .036, GOLD, 8);
            stack(s, 2.02, -.05, 5, .38, .034, SILVER, 9);
            medal(s, 2.92, .43, .25, 82, -22, .42, .04);
            s.grade = new Grade().temp(.02, .004).tone(1.09, 1.02, .98, 1.02, 0).shadows(.004, .006, .012).highs(.016, .012, -.008).film(.45, .018, .2, 1.22);
            a.add(s);
        }
        {   // Stock: the vault. Teal steel against gold: the heist-film grade.
            Scene s = plate("plate_stock", 1.7, .62, 7.0);
            vault(s, 1.95, .86, -.1, -24, .84);
            bangles(s, .78, .05, .35, 6, 12, 0, 2, .36, new Mat[] {GOLD});
            s.rig = studio(1, 1.5, .04, .09, .11);
            s.grade = new Grade().temp(-.018, -.002).tone(1.16, 1.0, .96, 1.03, 0).shadows(-.008, .012, .02).highs(.024, .012, -.016).film(.48, .02, .22, 1.3);
            a.add(s);
        }
        {   // MIS: ingots rising as a bar chart. Rich amber.
            Scene s = plate("plate_mis", 1.7, .5, 7.2);
            ingotBars(s, 1.75, .1, new double[] {.16, .28, .24, .42, .36, .6, .74}, .155, .07, GOLD);
            s.grade = new Grade().temp(.03, 0).tone(1.14, 1.12, .96, 1.03, 0).shadows(.016, .002, .002).highs(.018, .01, -.014).film(.45, .016, .26, 1.2);
            a.add(s);
        }
        {   // Trial Balance: the brass balance, tipped a hair. Old-money sepia.
            Scene s = plate("plate_trial", 1.7, .62, 6.9);
            scales(s, 1.95, 0, 1.12, 3, BRASS);
            stack(s, .72, .25, 3, .28, .028, GOLD, 10);
            s.grade = new Grade().temp(.05, .006).tone(1.08, .9, 1.0, 1.04, .006).shadows(.016, .006, -.002).highs(.012, .01, -.012).film(.5, .024, .18, 1.25);
            a.add(s);
        }
        {   // Customers: their things, three rings, three stones.
            Scene s = plate("plate_customers", 1.75, .4, 6.8);
            jewelRing(s, 1.05, .3, .2, 20, 0, .27, GOLD, RUBY);
            jewelRing(s, 1.85, .33, -.05, -15, 0, .3, GOLD_ROSE, DIAMOND);
            jewelRing(s, 2.65, .28, .3, 35, 0, .25, GOLD, SAPPHIRE);
            s.grade = new Grade().temp(.012, -.006).tone(1.12, 1.14, .97, 1.03, 0).shadows(.01, -.002, .014).highs(.016, .01, -.004).film(.46, .016, .28, 1.2);
            a.add(s);
        }
        {   // Who Did What: the watch, and the time on it. Cold morning.
            Scene s = plate("plate_activity", 1.7, .42, 6.5);
            watch(s, 1.95, .72, .1, 64, -16, .72);
            s.grade = new Grade().temp(-.006, 0).tone(1.12, .96, .98, 1.03, 0).shadows(-.002, .006, .018).highs(.016, .012, -.006).film(.48, .02, .2, 1.25);
            a.add(s);
        }
        {   // Notifications: the bell, caught swinging.
            Scene s = plate("plate_notifications", 1.75, .58, 6.8);
            bell(s, 1.95, .34, 0, -10, .78, GOLD);
            s.grade = new Grade().temp(.022, 0).tone(1.12, 1.1, .97, 1.03, 0).shadows(.014, .002, .006).highs(.02, .012, -.01).film(.48, .018, .26, 1.2);
            a.add(s);
        }
        {   // Backups: strongboxes, off-site and safe. Blue steel.
            Scene s = plate("plate_backups", 1.75, .48, 7.0);
            Mat steel = Mat.metal(.7, .73, .78, .2).with(.0006, 110);
            Sdf box1 = union(box(.62, .32, .46, .03), at(box(.65, .03, .49, .012), 0, .12, 0));
            s.add(at(box1, 1.75, .33, .05, 0, -22, 0), steel, 1.75, .33, .05, .95);
            Sdf plate1 = at(box(.15, .1, .012, .008), 0, .07, .47);
            Sdf lock = union(plate1, at(cyl(.035, .01, 0), 0, .07, .485, 90, 0, 0));
            s.add(at(lock, 1.75, .33, .05, 0, -22, 0), GOLD, 1.75, .33, .05, .95);
            Sdf box2 = union(box(.34, .2, .28, .02), at(box(.36, .02, .3, .01), 0, .08, 0));
            s.add(at(box2, 2.9, .21, .25, 0, 14, 0), steel, 2.9, .21, .25, .55);
            s.rig = studio(.9, 1.5, .03, .07, .13);
            s.grade = new Grade().temp(-.03, -.002).tone(1.12, .92, .98, 1.03, 0).shadows(-.008, .004, .026).highs(.012, .012, .002).film(.48, .02, .18, 1.28);
            a.add(s);
        }
        {   // Settings: gears, meshed.
            Scene s = plate("plate_settings", 1.75, .55, 6.8);
            s.add(at(gear(.72, .07, 14), 1.6, .74, 0, 78, 16, 0), Mat.metal(1.0, .75, .33, .28), 1.6, .74, 0, .95);
            s.add(at(gear(.46, .06, 10), 2.66, .47, .05, 78, 16, 0), Mat.metal(.66, .68, .72, .24), 2.66, .47, .05, .62);
            s.grade = new Grade().temp(.0, 0).tone(1.14, .98, .97, 1.03, 0).shadows(.0, .006, .014).highs(.022, .012, -.012).film(.48, .018, .2, 1.25);
            a.add(s);
        }
        {   // Shop picker: pillars and an arch, the shop's front.
            Scene s = plate("plate_shops", 1.75, .78, 7.3);
            Sdf col = union(at(cyl(.11, .68, .02), 0, .78, 0), at(cyl(.16, .05, .02), 0, .06, 0), at(cyl(.16, .05, .02), 0, 1.5, 0));
            Sdf arch = both(at(band(.56, .07, .1, .02), 0, 0, 0, 90, 0, 0), box(1, 1, 1, 0));
            Sdf archTop = both(at(arch, 1.82, 1.5, 0), at(box(2, 1, 2, 0), 1.82, 2.5, 0));
            s.add(union(at(col, 1.26, 0, 0), at(col, 2.38, 0, 0)), GOLD_SOFT, 1.82, .78, 0, 1.2);
            s.add(archTop, GOLD, 1.82, 1.8, 0, .85);
            s.add(at(gem(.14), 1.82, 2.2, 0, 0, 15, 0), RUBY, 1.82, 2.2, 0, .2);
            s.lookY = 1.15; s.camY = 1.5; s.camZ = 11.5; s.fov = 11.5;
            s.rig = studio(1, 1, .06, .024, .018);
            s.grade = new Grade().temp(.03, 0).tone(1.12, 1.06, .97, 1.03, 0).shadows(.014, .002, .006).highs(.02, .012, -.01).film(.48, .018, .22, 1.22);
            a.add(s);
        }

        // ---- the sign-in hero, portrait, 4K ----------------------------------------------------
        {
            Scene s = new Scene();
            s.name = "hero_login";
            s.w = 2160; s.h = 3840; s.spp = 16;
            // Held high in the frame: on the sign-in screen the sheet covers the lower half.
            s.camX = .3; s.camY = 3.1; s.camZ = 12.5; s.lookX = 0; s.lookY = -.75; s.fov = 25;
            s.aperture = .022; s.focus = 12.8;
            stack(s, -1.1, -.25, 12, .55, .045, GOLD, 21);
            stack(s, .05, -1.0, 8, .55, .045, GOLD, 22);
            bangles(s, .95, .1, .55, 14, 25, 0, 4, .72, new Mat[] {GOLD, GOLD_ROSE, GOLD});
            jewelRing(s, -.55, .45, 1.15, 30, 0, .4, GOLD, RUBY);
            s.floor = Mat.plain(.030, .004, .008, .05, .06);
            s.rig = studio(1.15, 1.1, .17, .06, .045);
            s.grade = new Grade().temp(.026, 0).tone(1.14, 1.08, .97, 1.03, 0).shadows(.018, .0, .012).highs(.022, .012, -.012).film(.55, .02, .26, 1.18);
            a.add(s);
        }
        {   // The page behind everything: wine velvet under one pool of light. Quiet, so figures read over it.
            Scene s = new Scene();
            s.name = "page_velvet";
            s.w = 2160; s.h = 3840; s.spp = 9;
            s.camX = 0; s.camY = 11; s.camZ = .01; s.lookX = 0; s.lookY = 0; s.lookZ = 0; s.fov = 30;
            Mat cloth = Mat.plain(.05, .012, .02, .9, .02);
            cloth.tex = (x, y, z, c) -> {
                double fibre = noise(x * 90, 0, z * 34) * .5 + noise(x * 34, 3, z * 90) * .5;
                double fold = noise(x * .7 + z * .28, 1, z * .55) * .5 + .5;
                double k = (.55 + .9 * fibre) * (.6 + .8 * fold);
                c[0] = .052 * k; c[1] = .012 * k; c[2] = .021 * k;
            };
            s.add(at(box(9, .1, 12, 0), 0, -.1, 0), cloth, 0, -.1, 0, 16);
            s.hasFloor = false;
            s.rig = new Rig().sky(.05, .036, .042, .01, .005, .007).box(.1, 1, .3, .55, .8, 2.6, 1, .86, .74);
            s.grade = new Grade().temp(.01, 0).tone(1.0, .95, 1.0, 1.0, 0).film(.62, .012, 0, 1.2);
            a.add(s);
        }
        {   // The same page by day: laid cotton paper under a window. Paler than anything printed on it.
            Scene s = new Scene();
            s.name = "page_parchment";
            s.w = 2160; s.h = 3840; s.spp = 9;
            s.camX = 0; s.camY = 11; s.camZ = .01; s.lookX = 0; s.lookY = 0; s.lookZ = 0; s.fov = 30;
            Mat paper = Mat.plain(.86, .78, .64, .95, .02);
            paper.tex = (x, y, z, c) -> {
                double laid = Math.sin(z * 150) * .5 + .5;
                double fibre = noise(x * 70, 0, z * 22) * .5 + noise(x * 24, 3, z * 70) * .5;
                double cloud = noise(x * .8 + z * .3, 1, z * .6) * .5 + .5;
                double k = .94 + .035 * laid + .05 * fibre + .05 * cloud;
                c[0] = .86 * k; c[1] = .78 * k; c[2] = .64 * k;
            };
            s.add(at(box(9, .1, 12, 0), 0, -.1, 0), paper, 0, -.1, 0, 16);
            s.hasFloor = false;
            s.rig = new Rig().sky(.86, .78, .62, .62, .54, .40).box(.1, 1, .25, 2.4, 3.2, 1.1, 1, .94, .82);
            s.grade = new Grade().temp(.02, 0).tone(.92, 1.75, .92, 1.0, .02).film(.07, .005, 0, 1.5);
            a.add(s);
        }

        // ---- the emblem: the app's own face --------------------------------------------------
        {
            Scene s = icon("emblem", 0, 0.1, 5.6, 0, 26);
            s.hasFloor = false;
            medal(s, 0, 0, 0, 74, -14, 1.42, .14);
            s.grade = new Grade().temp(.02, 0).tone(1.2, 1.1, 1.05, 1.0, 0).highs(.02, .012, -.012).film(0, 0, .18, .74);
            a.add(s);
        }

        // ---- icons, one object each ------------------------------------------------------------
        {
            Scene s = icon("ic3d_search", .2, 1.05, 5.2, .92, 24);
            Sdf rim = at(band(.72, .075, .085, .022), 0, 1.22, 0, 80, -18, 0);
            Sdf glass = at(cyl(.67, .03, .015), 0, 1.22, 0, 80, -18, 0);
            s.add(rim, GOLD, 0, 1.22, 0, .9).add(glass, CRYSTAL, 0, 1.22, 0, .85);
            s.add(capsule(.52, .62, .12, 1.1, .08, .3, .095), GOLD_SOFT, .8, .35, .2, .8);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_stock", .35, 1.05, 5.4, 1.02, 24);
            vault(s, 0, 1.12, 0, -14, 1.03);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_ledger", .7, 1.75, 4.8, .55, 25);
            ledger(s, 0, .32, 0, -26, 1.08, .3, .82, LEATHER);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_chart", .5, 1.15, 5.8, 1.0, 24);
            ingotBars(s, 0, 0, new double[] {.42, .78, 1.2}, .3, .12, GOLD);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_clock", 0, 1.8, 4.5, .6, 28);
            watch(s, 0, 1.0, .1, 62, -10, 1.05);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_bell", .2, 1.05, 5.0, .95, 25);
            bell(s, 0, .26, 0, -8, 1.3, GOLD);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_bills", .6, 2.0, 4.6, .3, 25);
            for (int i = 0; i < 4; i++) {
                s.add(at(box(.86, .018, 1.12, .012), 0, .03 + i * .045, 0, 0, -22 + i * 8, 0), i == 3 ? CREAM : PAPER, 0, .1, 0, 1.5);
            }
            s.add(at(coin(.28, .03, true), .38, .24, .55, 0, 15, 0), GOLD, .38, .24, .55, .4);
            for (int i = 0; i < 4; i++) {
                s.add(at(box(.5 - i * .05, .004, .022, .002), -.12, .205, -.62 + i * .19, 0, 2, 0), INK, -.12, .205, -.3, 1.0);
            }
            a.add(s);
        }
        {
            Scene s = icon("ic3d_customers", 0, 1.1, 6.0, 1.0, 25);
            bust(s, -.82, -.35, .92, SILVER);
            bust(s, .82, -.35, .92, SILVER);
            bust(s, 0, .25, 1.16, GOLD);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_user", 0, 1.1, 5.0, 1.0, 25);
            bust(s, 0, 0, 1.28, GOLD);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_gear", .1, 1.4, 5.2, .95, 25);
            s.add(at(gear(1.08, .13, 12), 0, 1.15, 0, 66, 22, 0), Mat.metal(1.0, .75, .33, .3), 0, 1.15, 0, 1.4);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_home", .75, 1.35, 5.2, .95, 25);
            Sdf walls = box(.82, .52, .72, .03);
            Sdf roof = both(box(1.05, .7, .96, 0), (x, y, z) -> Math.max(y * .75 + Math.abs(x) * .66 - .5, -y));
            s.add(at(walls, 0, .52, 0), CREAM, 0, .52, 0, 1.3);
            s.add(at(roof, 0, 1.03, 0), GOLD, 0, 1.33, 0, 1.4);
            s.add(at(box(.2, .3, .02, .02), 0, .3, .72), LEATHER, 0, .3, .72, .4);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_jewel", .35, 1.1, 4.4, 1.0, 26);
            jewelRing(s, 0, .95, 0, 28, 0, .84, GOLD, RUBY);
            s.grade = new Grade().tone(1.1, 1.14, .96, 1.05, 0).highs(.016, .008, -.004).film(0, 0, .3, 1.15);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_lock", .4, 1.05, 5.0, 1.0, 25);
            s.add(at(box(.72, .58, .28, .1), 0, .6, 0), GOLD, 0, .6, 0, 1.0);
            Sdf shackle = both(at(torus(.44, .09), 0, 1.15, 0, 90, 0, 0), at(box(1, .5, 1, 0), 0, 1.5, 0));
            Sdf legs = union(at(cyl(.09, .15, .01), -.44, 1.05, 0), at(cyl(.09, .15, .01), .44, 1.05, 0));
            s.add(union(shackle, legs), SILVER, 0, 1.4, 0, .8);
            s.add(at(cyl(.11, .012, 0), 0, .68, .285, 90, 0, 0), INK, 0, .68, .285, .15);
            s.add(capsule(0, .68, .29, 0, .38, .29, .045), INK, 0, .52, .29, .25);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_cloud", .2, 1.0, 5.2, .92, 25);
            Sdf cloud = smoothUnion(.12, smoothUnion(.12, at(sphere(.5), -.62, .7, 0), at(sphere(.72), .02, 1.0, 0)),
                    smoothUnion(.12, at(sphere(.54), .68, .74, 0), at(box(1.0, .24, .36, .22), .04, .52, 0)));
            s.add(cloud, Mat.plain(.86, .84, .80, .3, .06), 0, .9, 0, 1.45);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_note", .65, 1.4, 5.0, .55, 25);
            s.add(at(cyl(.32, .92, .03), 0, .34, 0, 0, 0, 90), PAPER, 0, .34, 0, 1.05);
            s.add(at(torus(.34, .05), 0, .34, 0, 0, 0, 90), GOLD, 0, .34, 0, .45);
            s.add(at(gem(.14), .03, .72, .0, 0, 15, 0), RUBY, .03, .72, 0, .2);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_calendar", .5, 1.1, 5.0, .95, 25);
            s.add(at(box(.82, .84, .13, .05), 0, .86, 0), CREAM, 0, .86, 0, 1.3);
            s.add(at(box(.82, .2, .14, .04), 0, 1.48, 0), WINE_LACQ, 0, 1.48, 0, .95);
            s.add(union(at(capsule(0, -.14, 0, 0, .14, 0, .06), -.4, 1.68, 0), at(capsule(0, -.14, 0, 0, .14, 0, .06), .4, 1.68, 0)), GOLD, 0, 1.68, 0, .6);
            for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) {
                s.add(at(box(.1, .08, .01, .01), -.48 + c * .32, 1.02 - r * .28, .135), r == 1 && c == 2 ? GOLD : INK, 0, .75, .13, 1.0);
            }
            a.add(s);
        }
        {
            Scene s = icon("ic3d_rupee", 0, 1.0, 5.2, 1.08, 25);
            medal(s, 0, 1.13, 0, 74, -18, 1.08, .11);
            s.grade = new Grade().temp(.02, 0).tone(1.2, 1.1, 1.05, 1.0, 0).highs(.02, .012, -.012).film(0, 0, .18, .74);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_scale", 0, 1.1, 5.6, 1.05, 25);
            scales(s, 0, 0, 1.55, 3, BRASS);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_shop", .3, 1.15, 5.4, 1.0, 25);
            s.add(at(box(1.05, .56, .55, .02), 0, .56, 0), CREAM, 0, .56, 0, 1.4);
            s.add(at(box(1.18, .09, .68, .03), 0, 1.2, 0), WINE_LACQ, 0, 1.2, 0, 1.5);
            s.add(at(box(1.18, .07, .68, .02), 0, 1.33, 0), GOLD, 0, 1.33, 0, 1.5);
            s.add(at(box(.22, .4, .02, .02), -.45, .4, .55), LEATHER, -.45, .4, .55, .5);
            s.add(at(box(.3, .25, .02, .02), .4, .62, .55), CRYSTAL, .4, .62, .55, .5);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_warning", 0, 1.0, 5.0, 1.0, 25);
            Sdf tri = raise((u, v) -> {
                double k = Math.sqrt(3);
                double px = Math.abs(u) - 1, py = v + 1 / k;
                if (px + k * py > 0) { double a2 = (px - k * py) / 2, b2 = (-k * px - py) / 2; px = a2; py = b2; }
                px -= clamp(px, -2, 0);
                return -len(px, py) * Math.signum(py) - .08;
            }, .14);
            s.add(at(tri, 0, 1.0, 0, 90, -14, 180), Mat.plain(.48, .03, .03, .1, .08), 0, 1.0, 0, 1.4);
            s.add(at(capsule(0, .1, 0, 0, .55, 0, .085), 0, .88, .16, 0, -14, 0), GOLD, 0, 1.2, .16, .6);
            s.add(at(sphere(.1), 0, .75, .17), GOLD, 0, .75, .17, .15);
            a.add(s);
        }
        {   // More: three beads on a line, the three dots made of the shop's own metal.
            Scene s = icon("ic3d_more", 0, .9, 6.0, .4, 25);
            for (int i = -1; i <= 1; i++) s.add(at(sphere(.4), i * 1.02, .4, 0), GOLD, i * 1.02, .4, 0, .45);
            a.add(s);
        }
        {
            Scene s = icon("ic3d_print", .6, 1.35, 5.0, .75, 25);
            s.add(at(box(.98, .36, .62, .08), 0, .54, 0), Mat.metal(.28, .29, .32, .28), 0, .54, 0, 1.4);
            s.add(at(box(.66, .32, .05, .01), 0, 1.15, -.2), PAPER, 0, 1.15, -.2, .85);
            s.add(at(box(.66, .2, .03, .01), 0, .26, .62), CREAM, 0, .26, .62, .75);
            s.add(at(box(.1, .022, .02, .01), .68, .72, .62), GOLD, .68, .72, .62, .18);
            a.add(s);
        }
        return a;
    }

    /** Every preview on one sheet, on the app's own dark ground, so they can be judged together. */
    static void sheet(File dir) throws Exception {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".png") && !n.equals("sheet.png"));
        java.util.Arrays.sort(files);
        int cell = 240, cols = 7, pad = 6, rows = (files.length + cols - 1) / cols;
        BufferedImage out = new BufferedImage(cols * (cell + pad) + pad, rows * (cell + pad) + pad, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(new java.awt.Color(0x17, 0x09, 0x0D));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int i = 0; i < files.length; i++) {
            BufferedImage im = ImageIO.read(files[i]);
            int x = pad + (i % cols) * (cell + pad), y = pad + (i / cols) * (cell + pad);
            double k = Math.min((double) cell / im.getWidth(), (double) cell / im.getHeight());
            int w = (int) (im.getWidth() * k), h = (int) (im.getHeight() * k);
            g.drawImage(im, x + (cell - w) / 2, y + (cell - h) / 2, w, h, null);
            g.setColor(new java.awt.Color(0xE8, 0xC7, 0x66));
            g.drawString(files[i].getName().replace(".png", ""), x + 2, y + 12);
        }
        ImageIO.write(out, "png", new File(dir, "sheet.png"));
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("sheet")) { sheet(new File("preview")); return; }
        boolean preview = args[0].equals("preview");
        File base = new File(preview ? "preview" : "masters");
        List<String> only = new ArrayList<>();
        for (int i = 1; i < args.length; i++) only.add(args[i]);
        for (Scene s : all()) {
            if (!only.isEmpty() && !only.contains(s.name)) continue;
            render(s, base, preview ? .25 : 1, preview ? 9 : 0);
        }
    }
}
