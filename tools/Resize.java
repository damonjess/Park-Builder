import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Minimal image resizer: `java tools/Resize.java in.png 900 out.jpg`. */
public class Resize {
    public static void main(String[] args) throws Exception {
        BufferedImage full = ImageIO.read(new File(args[0]));
        int x = 0, y = 0, w = full.getWidth(), h = full.getHeight();
        if (args.length >= 6) {
            x = Integer.parseInt(args[3]);
            y = Integer.parseInt(args[4]);
            w = Integer.parseInt(args[5]);
            h = Integer.parseInt(args[6]);
        }
        if (args.length >= 8) h = Integer.parseInt(args[7]);
        BufferedImage src = full.getSubimage(x, y, w, h);
        int width = Integer.parseInt(args[1]);
        int height = Math.round(src.getHeight() * (width / (float) src.getWidth()));
        boolean jpeg = args[2].toLowerCase().endsWith(".jpg") || args[2].toLowerCase().endsWith(".jpeg");
        int type = jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage dst = new BufferedImage(width, height, type);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        if (jpeg) {
            BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(dst, 0, 0, null);
            ImageIO.write(rgb, "jpg", new File(args[2]));
        } else {
            ImageIO.write(dst, "png", new File(args[2]));
        }
    }
}
