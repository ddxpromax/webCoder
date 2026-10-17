package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

/** Capture a desktop image and expose it through Majo's uploads preview path. */
public class DesktopScreenshotTool {
    private static final int MAX_EDGE = 1568;
    private final MajoWorkingDirectory working;

    public DesktopScreenshotTool(MajoWorkingDirectory working) { this.working = working; }

    @Tool(name = "desktop_screenshot", description = "Capture the desktop for visual analysis")
    public ToolResultBlock desktopScreenshot() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return ToolResultBlock.text("Desktop screenshots are unavailable because this server has no graphical session.");
        try {
            BufferedImage image = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            if (image.getWidth() > MAX_EDGE || image.getHeight() > MAX_EDGE) { double scale = (double) MAX_EDGE / Math.max(image.getWidth(), image.getHeight()); BufferedImage reduced = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB); Graphics2D graphics = reduced.createGraphics(); graphics.drawImage(image, 0, 0, null); graphics.dispose(); image = reduced; }
            byte[] bytes; try (ByteArrayOutputStream output = new ByteArrayOutputStream()) { ImageIO.write(image, "png", output); bytes = output.toByteArray(); }
            String name = "screenshot-" + Instant.now().toEpochMilli() + ".png"; Path uploads = working.resolve("uploads"); Files.createDirectories(uploads); Files.write(uploads.resolve(name), bytes);
            return ToolResultBlock.of(List.of(TextBlock.builder().text("Desktop screenshot saved and loaded into context: " + name + " (" + bytes.length + " bytes)\nPreview: /api/files/preview/" + name).build(), ImageBlock.builder().source(new Base64Source("image/png", Base64.getEncoder().encodeToString(bytes))).maxPixels(MAX_EDGE * MAX_EDGE).build()));
        } catch (Exception exception) { return ToolResultBlock.text("Could not capture desktop screenshot: " + exception.getMessage()); }
    }
}
