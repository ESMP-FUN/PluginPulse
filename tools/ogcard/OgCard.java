/*
 * Generates docs/assets/og.png — the 1200x630 preview card sites like Discord,
 * Reddit and X show when someone shares a link to the tool. Run it after
 * changing the wording or the palette:
 *
 *   java tools/og-card/OgCard.java
 *
 * Committed output lives at docs/assets/og.png; this file only exists so the
 * card can be regenerated instead of hand-edited in an image editor.
 */

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

public final class OgCard {

    private static final int W = 1200;
    private static final int H = 630;

    // Same palette as docs/style.css.
    private static final Color BG = new Color(0x0f1216);
    private static final Color BG_TOP = new Color(0x141920);
    private static final Color PANEL = new Color(0x171b21);
    private static final Color LINE = new Color(0x262c34);
    private static final Color INK = new Color(0xe7ecf2);
    private static final Color MUTED = new Color(0x9aa7b4);
    private static final Color ACCENT = new Color(0xffb454);

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, H, BG));
        g.fillRect(0, 0, W, H);

        // One faint trace hugging the bottom edge. Anything crossing the headline
        // reads as noise, so the rest of the card stays clean.
        drawPulse(g, 0, 572, W, 68, new Color(0xff, 0xb4, 0x54, 30), 10f);

        int x = 90;

        // Mark: the same rounded tile as the favicon.
        int tile = 96;
        int tileY = 78;
        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, tileY, tile, tile, 26, 26));
        g.setColor(LINE);
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Float(x + 1.5f, tileY + 1.5f, tile - 3, tile - 3, 24, 24));
        drawPulse(g, x + 12, tileY + tile / 2f - 24, tile - 24, 48, ACCENT, 8f);

        // Wordmark.
        g.setFont(font(Font.BOLD, 62));
        int wordY = tileY + 66;
        int wx = x + tile + 26;
        g.setColor(INK);
        g.drawString("Plugin", wx, wordY);
        wx += g.getFontMetrics().stringWidth("Plugin");
        g.setColor(ACCENT);
        g.drawString("Pulse", wx, wordY);

        // Headline — two lines, the promise first.
        g.setColor(INK);
        g.setFont(font(Font.BOLD, 68));
        g.drawString("Add auto-updates to a", x, 300);
        g.drawString("plugin jar you already have", x, 378);

        g.setColor(MUTED);
        g.setFont(font(Font.PLAIN, 34));
        g.drawString("No source code. No build tools. No account.", x, 436);

        // Trust pills.
        String[] pills = {"Runs in your browser", "Your jar is never uploaded", "Free & open source"};
        int px = x;
        int py = 500;
        g.setFont(font(Font.BOLD, 26));
        for (String pill : pills) {
            int tw = g.getFontMetrics().stringWidth(pill);
            int pw = tw + 44;
            int ph = 56;
            g.setColor(PANEL);
            g.fill(new RoundRectangle2D.Float(px, py, pw, ph, ph, ph));
            g.setColor(LINE);
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Float(px + 1, py + 1, pw - 2, ph - 2, ph, ph));
            g.setColor(ACCENT);
            g.fillOval(px + 20, py + ph / 2 - 5, 10, 10);
            g.setColor(MUTED);
            g.drawString(pill, px + 38, py + 37);
            px += pw + 16;
        }

        g.dispose();
        File out = new File("docs/assets/og.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath() + " (" + W + "x" + H + ")");
    }

    /** An ECG-style trace filling the given box, drawn left to right. */
    private static void drawPulse(Graphics2D g, float x, float y, float w, float h, Color color, float stroke) {
        // Fractions of the box: flat, small dip, tall spike, overshoot, flat.
        float[][] pts = {
            {0.00f, 0.55f}, {0.18f, 0.55f}, {0.26f, 0.20f}, {0.34f, 0.55f},
            {0.44f, 0.55f}, {0.52f, 1.00f}, {0.60f, 0.00f}, {0.68f, 0.72f},
            {0.78f, 0.55f}, {0.84f, 0.36f}, {0.90f, 0.55f}, {1.00f, 0.55f},
        };
        Path2D.Float path = new Path2D.Float();
        for (int i = 0; i < pts.length; i++) {
            float px = x + pts[i][0] * w;
            float py = y + pts[i][1] * h;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }

    private static Font font(int style, int size) {
        Font f = new Font("Segoe UI", style, size);
        // Segoe UI is a Windows font; fall back to whatever sans the JDK has.
        return "Dialog".equals(f.getFamily()) ? new Font(Font.SANS_SERIF, style, size) : f;
    }
}
