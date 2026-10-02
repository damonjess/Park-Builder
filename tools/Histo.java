import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/** Region histogram: `java tools/Histo.java img.png x0 y0 x1 y1 [n]` */
public class Histo {
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int x0 = Integer.parseInt(args[1]), y0 = Integer.parseInt(args[2]);
        int x1 = Integer.parseInt(args[3]), y1 = Integer.parseInt(args[4]);
        int n = args.length > 5 ? Integer.parseInt(args[5]) : 15;
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (int y = y0; y <= y1 && y < img.getHeight(); y++) {
            for (int x = x0; x <= x1 && x < img.getWidth(); x++) {
                int p = img.getRGB(x, y) & 0xFFFFFF;
                counts.put(p, counts.getOrDefault(p, 0) + 1);
            }
        }
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            int c = list.get(i).getKey();
            System.out.printf("#%06X  %d%n", c, list.get(i).getValue());
        }
    }
}
