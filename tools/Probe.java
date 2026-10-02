import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Colour probe: `java tools/Probe.java img.png x0 y0 x1 y1 r g b tol`
 * Prints how many pixels in the region match, and their bounding box.
 */
public class Probe {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int x0 = Integer.parseInt(args[1]), y0 = Integer.parseInt(args[2]);
        int x1 = Integer.parseInt(args[3]), y1 = Integer.parseInt(args[4]);
        int r = Integer.parseInt(args[5]), g = Integer.parseInt(args[6]), b = Integer.parseInt(args[7]);
        int tol = Integer.parseInt(args[8]);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, n = 0;
        for (int y = y0; y <= y1 && y < img.getHeight(); y++) {
            for (int x = x0; x <= x1 && x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
                if (Math.abs(pr - r) <= tol && Math.abs(pg - g) <= tol && Math.abs(pb - b) <= tol) {
                    n++;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (n == 0) {
            System.out.println("count=0");
        } else {
            System.out.println("count=" + n + " bbox=(" + minX + "," + minY + ")-(" + maxX + "," + maxY + ")");
        }
    }
}
