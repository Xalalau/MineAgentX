package com.mineagent.engine.construction;

import com.mineagent.api.construction.ConstructionDesign;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import javax.imageio.ImageIO;

/** Pure, headless, bounded voxel surface renderer. No game client, assets, network, or GPU. */
public final class OrthographicRenderer {
    public static final int PIXELS = 256;
    /** Axes and directions refer to design coordinates, not rotated world coordinates. */
    public record Preview(String view, int width, int height, String horizontalAxis,
                          String verticalAxis, String viewDirection, byte[] png) {
        public Preview { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
        public String dataUrl() { return "data:image/png;base64," + Base64.getEncoder().encodeToString(png); }
    }
    private record Camera(String name, int horizontal, int vertical, int depth,
                          boolean flipHorizontal, boolean flipVertical, boolean closestMax,
                          String horizontalAxis, String verticalAxis, String direction) {}
    private static final List<Camera> CAMERAS = List.of(
            new Camera("top", 0, 2, 1, false, false, true, "+x", "+z", "-y"),
            new Camera("bottom", 0, 2, 1, true, false, false, "-x", "+z", "+y"),
            new Camera("front", 0, 1, 2, false, true, false, "+x", "-y", "+z"),
            new Camera("back", 0, 1, 2, true, true, true, "-x", "-y", "-z"),
            new Camera("left", 2, 1, 0, true, true, false, "-z", "-y", "+x"),
            new Camera("right", 2, 1, 0, false, true, true, "+z", "-y", "-x"));
    public List<Preview> render(ConstructionDesign design) throws IOException {
        Objects.requireNonNull(design);
        List<Preview> result = new ArrayList<>(6);
        int[] size = {design.size().x(), design.size().y(), design.size().z()};
        for (Camera camera : CAMERAS) {
            int columns = size[camera.horizontal], rows = size[camera.vertical];
            ConstructionDesign.Cell[][] visible = new ConstructionDesign.Cell[columns][rows];
            int[][] depth = new int[columns][rows];
            for (int[] column : depth) Arrays.fill(column, camera.closestMax ? Integer.MIN_VALUE : Integer.MAX_VALUE);
            for (var cell : design.cells()) {
                if (cell.clear()) continue;
                int[] pos = {cell.position().x(), cell.position().y(), cell.position().z()};
                int u = pos[camera.horizontal], v = pos[camera.vertical], d = pos[camera.depth];
                if ((camera.closestMax && d > depth[u][v]) || (!camera.closestMax && d < depth[u][v])) {
                    visible[u][v] = cell; depth[u][v] = d;
                }
            }
            BufferedImage image = new BufferedImage(PIXELS, PIXELS, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(new Color(244, 246, 249)); g.fillRect(0, 0, PIXELS, PIXELS);
                int scale = Math.max(1, (PIXELS - 32) / Math.max(columns, rows));
                int left = (PIXELS - columns * scale) / 2, top = (PIXELS - rows * scale) / 2;
                for (int u = 0; u < columns; u++) for (int v = 0; v < rows; v++) {
                    var cell = visible[u][v]; if (cell == null) continue;
                    int x = left + (camera.flipHorizontal ? columns - 1 - u : u) * scale;
                    int y = top + (camera.flipVertical ? rows - 1 - v : v) * scale;
                    int hash = cell.block().hashCode();
                    Color color = new Color(65 + (hash & 127), 65 + ((hash >>> 8) & 127), 65 + ((hash >>> 16) & 127));
                    // Depth shading makes stacked/occluded geometry readable without inventing textures.
                    float factor = 0.72f + 0.28f * (depth[u][v] + 1f) / size[camera.depth];
                    g.setColor(new Color(Math.round(color.getRed() * factor), Math.round(color.getGreen() * factor), Math.round(color.getBlue() * factor)));
                    g.fillRect(x, y, scale, scale);
                    g.setColor(color.darker()); g.drawRect(x, y, scale - 1, scale - 1);
                }
            } finally { g.dispose(); }
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("png_encoder");
            result.add(new Preview(camera.name, PIXELS, PIXELS, camera.horizontalAxis, camera.verticalAxis,
                    camera.direction, bytes.toByteArray()));
        }
        return List.copyOf(result);
    }
}
