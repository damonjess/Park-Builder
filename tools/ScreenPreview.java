import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Reads a screenshot of the park as text — useful for checking what a device is actually
 * showing without an image viewer.
 *
 * Mode 1, surface map: each cell is matched to the nearest known park colour (`g` grass,
 * `p` path, `w` water, `b` background, `h` HUD blue, `y` brass/cream/stone, `r` roof red,
 * `k` grey/asphalt, `o` wood and foliage) and the letter's case says whether the cell is
 * speckled (UPPER) or a flat fill (lower). That turns "solid colours versus textures" into
 * something measurable.
 *
 *   java ScreenPreview.java shot.png 44 70 [yFrom] [yTo]
 *
 * Mode 2, colour scan: counts pixels belonging to distinctive sprite colours and reports
 * where the reddest ones are, which is how you find out whether a bus or a red roof is
 * really on screen.
 *
 *   java ScreenPreview.java shot.png scan
 */
public class ScreenPreview {

    private static final String[] NAMES = {
        "background", "grass", "path", "water", "hudPanel", "hudLight", "brass", "cream",
        "roofRed", "asphalt", "wood", "leafDark", "stone"
    };
    private static final int[] REFS = {
        0x1E3A18, 0x62B446, 0xBCAB92, 0x2F82D4, 0x1B2F45, 0x3D6088, 0xD9A94C, 0xF6E3B0,
        0xD8452F, 0x4E4A52, 0x9A6B3F, 0x2F7A22, 0xC9BFA9
    };
    private static final char[] GLYPHS = { 'b', 'g', 'p', 'w', 'h', 'h', 'y', 'y', 'r', 'k', 'o', 'o', 'y' };

    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        if (args.length > 1 && args[1].equals("scan")) {
            scan(img);
            return;
        }

        int cols = args.length > 1 ? Integer.parseInt(args[1]) : 44;
        int rows = args.length > 2 ? Integer.parseInt(args[2]) : 70;
        double yFrom = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;
        double yTo = args.length > 4 ? Double.parseDouble(args[4]) : 1.0;
        int bandTop = (int) (img.getHeight() * yFrom);
        int bandBottom = (int) (img.getHeight() * yTo);
        int bandHeight = Math.max(1, bandBottom - bandTop);

        System.out.println("== " + args[0] + "  " + img.getWidth() + "x" + img.getHeight()
            + "  band y=" + bandTop + ".." + bandBottom + "   UPPER = speckled");

        Map<String, Integer> textured = new HashMap<>();
        Map<String, Integer> flat = new HashMap<>();
        Map<String, Integer> counted = new HashMap<>();

        for (int r = 0; r < rows; r++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                int x0 = c * img.getWidth() / cols, x1 = (c + 1) * img.getWidth() / cols;
                int y0 = bandTop + bandHeight * r / rows;
                int y1 = bandTop + bandHeight * (r + 1) / rows;
                long sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        int p = img.getRGB(x, y);
                        sr += (p >> 16) & 255;
                        sg += (p >> 8) & 255;
                        sb += p & 255;
                        n++;
                    }
                }
                if (n == 0) { line.append(' '); continue; }
                int mr = (int) (sr / n), mg = (int) (sg / n), mb = (int) (sb / n);

                double dev = 0;
                for (int y = y0; y < y1; y += 2) {
                    for (int x = x0; x < x1; x += 2) {
                        int p = img.getRGB(x, y);
                        dev += Math.abs(((p >> 16) & 255) - mr)
                            + Math.abs(((p >> 8) & 255) - mg)
                            + Math.abs((p & 255) - mb);
                    }
                }
                dev /= n * 3.0;
                boolean speckled = dev > 6.0;

                int best = 0;
                long bestDist = Long.MAX_VALUE;
                for (int i = 0; i < REFS.length; i++) {
                    int dr = mr - ((REFS[i] >> 16) & 255);
                    int dg = mg - ((REFS[i] >> 8) & 255);
                    int db = mb - (REFS[i] & 255);
                    long d = (long) dr * dr + (long) dg * dg + (long) db * db;
                    if (d < bestDist) { bestDist = d; best = i; }
                }
                String name = NAMES[best];
                (speckled ? textured : flat).merge(name, 1, Integer::sum);
                counted.merge(name, 1, Integer::sum);
                line.append(speckled ? Character.toUpperCase(GLYPHS[best]) : GLYPHS[best]);
            }
            System.out.println(line);
        }

        System.out.println();
        System.out.println("surface      speckled  flat   %speckled  share");
        int cells = cols * rows;
        for (String name : new String[] {"grass", "path", "water", "background", "hudPanel",
                                         "brass", "asphalt", "roofRed", "wood", "stone"}) {
            int t = textured.getOrDefault(name, 0);
            int f = flat.getOrDefault(name, 0);
            if (t + f == 0) continue;
            System.out.printf("%-11s %8d %6d %9.0f%% %5.1f%%%n",
                name, t, f, 100.0 * t / (t + f), 100.0 * counted.getOrDefault(name, 0) / cells);
        }
    }

    /** Counts distinctive sprite colours and reports where the reddest pixels are. */
    private static void scan(BufferedImage img) {
        int red = 0, blue = 0, skin = 0, gold = 0;
        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        int sample = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (r > 140 && r - g > 60 && r - b > 60) {
                    red++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    sample = p & 0xFFFFFF;
                }
                if (b > 150 && b - r > 60) blue++;
                if (r > 195 && g > 150 && g < 220 && b > 105 && b < 185) skin++;
                if (r > 200 && g > 150 && b < 140) gold++;
            }
        }
        double total = (img.getWidth() / 2.0) * (img.getHeight() / 2.0);
        System.out.printf("red-ish  %8d (%.2f%%)%n", red, 100 * red / total);
        System.out.printf("blue-ish %8d (%.2f%%)%n", blue, 100 * blue / total);
        System.out.printf("skin-ish %8d (%.2f%%)%n", skin, 100 * skin / total);
        System.out.printf("gold-ish %8d (%.2f%%)%n", gold, 100 * gold / total);
        if (red > 0) {
            System.out.printf("red bbox x=%d..%d y=%d..%d example=#%06X%n", minX, maxX, minY, maxY, sample);
        }
    }
}
