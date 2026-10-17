package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Loads a workspace image into the model context with Majo's size limits. */
public class ViewImageTool {
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final int MAX_EDGE = 1568;
    private static final Map<String, String> MIME = Map.of(".png", "image/png", ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".gif", "image/gif", ".webp", "image/webp", ".bmp", "image/bmp");
    private final Path workspace;

    public ViewImageTool(Path workspace) { this.workspace = workspace.toAbsolutePath().normalize(); }

    @Tool(name = "view_image", description = "Load a workspace image for visual analysis", readOnly = true)
    public ToolResultBlock viewImage(@ToolParam(name = "path", description = "Workspace-relative image path") String path) {
        if (path == null || path.isBlank()) return ToolResultBlock.text("path is required.");
        try {
            Path target = workspace.resolve(path).normalize();
            if (!target.startsWith(workspace)) return ToolResultBlock.text("Image path is outside the workspace.");
            if (!Files.isRegularFile(target)) return ToolResultBlock.text("Image file not found: " + path);
            long size = Files.size(target); if (size > MAX_BYTES) return ToolResultBlock.text("Image exceeds the 15 MB limit.");
            String name = target.getFileName().toString().toLowerCase(Locale.ROOT); int dot = name.lastIndexOf('.'); String mime = MIME.get(dot < 0 ? "" : name.substring(dot));
            if (mime == null) return ToolResultBlock.text("Unsupported image format: " + name);
            byte[] bytes = Files.readAllBytes(target); BufferedImage image = ImageIO.read(target.toFile());
            if (image == null) return ToolResultBlock.text("Could not decode image: " + path);
            if (image.getWidth() > MAX_EDGE || image.getHeight() > MAX_EDGE) { double scale = (double) MAX_EDGE / Math.max(image.getWidth(), image.getHeight()); BufferedImage scaled = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB); Graphics2D graphics = scaled.createGraphics(); graphics.drawImage(image, 0, 0, null); graphics.dispose(); try (ByteArrayOutputStream output = new ByteArrayOutputStream()) { ImageIO.write(scaled, "png", output); bytes = output.toByteArray(); mime = "image/png"; } }
            return ToolResultBlock.of(List.of(TextBlock.builder().text("Image loaded into context: " + path + " (" + bytes.length + " bytes, " + mime + ")").build(), ImageBlock.builder().source(new Base64Source(mime, Base64.getEncoder().encodeToString(bytes))).maxPixels(MAX_EDGE * MAX_EDGE).build()));
        } catch (Exception exception) { return ToolResultBlock.text("Could not load image: " + exception.getMessage()); }
    }
}
