package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Majo's ffmpeg-assisted video key-frame tool with a metadata fallback. */
public class ViewVideoTool {
    private static final long MAX_BYTES = 500L * 1024 * 1024;
    private static final int MAX_EDGE = 1024;
    private final Path workspace;

    public ViewVideoTool(Path workspace) { this.workspace = workspace.toAbsolutePath().normalize(); }

    @Tool(name = "view_video", description = "Extract video key frames for visual analysis", readOnly = true)
    public ToolResultBlock viewVideo(
        @ToolParam(name = "path", description = "Workspace-relative video path") String path,
        @ToolParam(name = "frames", description = "Optional frames, default 4, maximum 10", required = false) Integer frames
    ) {
        if (path == null || path.isBlank()) return ToolResultBlock.text("path is required.");
        try {
            Path video = workspace.resolve(path).normalize();
            if (!video.startsWith(workspace)) return ToolResultBlock.text("Video path is outside the workspace.");
            if (!Files.isRegularFile(video)) return ToolResultBlock.text("Video file not found: " + path);
            long size = Files.size(video); if (size > MAX_BYTES) return ToolResultBlock.text("Video exceeds the 500 MB limit.");
            if (!video.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mov|mkv|webm|avi|flv)$")) return ToolResultBlock.text("Unsupported video format: " + video.getFileName());
            String ffmpeg = binary("ffmpeg");
            if (ffmpeg == null) return metadata(path, size, "ffmpeg is not installed, so key-frame extraction is unavailable.");
            double duration = duration(video);
            if (duration <= 0) return metadata(path, size, "Could not determine video duration, so key-frame extraction was skipped.");
            int requested = frames == null ? 4 : Math.max(1, Math.min(frames, 10));
            Path temporary = Files.createTempDirectory("majo-video-");
            try {
                List<ContentBlock> output = new ArrayList<>();
                for (int index = 0; index < requested; index++) {
                    Path frame = temporary.resolve("frame-" + index + ".png");
                    if (!frame(ffmpeg, video, duration * (index + .5d) / requested, frame)) continue;
                    byte[] png = png(frame); if (png == null) continue;
                    if (output.isEmpty()) output.add(TextBlock.builder().text("Video key frames loaded into context: " + path + " (" + size + " bytes, " + String.format(Locale.ROOT, "%.1f", duration) + " seconds).").build());
                    output.add(ImageBlock.builder().source(new Base64Source("image/png", Base64.getEncoder().encodeToString(png))).maxPixels(MAX_EDGE * MAX_EDGE).build());
                }
                return output.isEmpty() ? metadata(path, size, "ffmpeg did not produce readable key frames.") : ToolResultBlock.of(output);
            } finally { delete(temporary); }
        } catch (Exception exception) { return ToolResultBlock.text("Could not load video: " + exception.getMessage()); }
    }

    private static ToolResultBlock metadata(String path, long size, String note) { return ToolResultBlock.text("Video information: " + path + " (" + size + " bytes)\n" + note); }
    private static String binary(String name) { String raw = System.getenv("PATH"); if (raw == null) return null; for (String directory : raw.split(java.io.File.pathSeparator)) { Path p = Path.of(directory, name); if (Files.isExecutable(p)) return p.toString(); } return null; }
    private static double duration(Path video) { String probe = binary("ffprobe"); if (probe == null) return -1; try { Process process = new ProcessBuilder(probe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.toString()).redirectErrorStream(true).start(); if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); return -1; } return process.exitValue() == 0 ? Double.parseDouble(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip()) : -1; } catch (Exception ignored) { return -1; } }
    private static boolean frame(String ffmpeg, Path video, double seconds, Path target) { try { Process process = new ProcessBuilder(ffmpeg, "-y", "-ss", String.format(Locale.ROOT, "%.3f", seconds), "-i", video.toString(), "-frames:v", "1", "-vf", "scale='min(" + MAX_EDGE + ",iw)':'min(" + MAX_EDGE + ",ih)':force_original_aspect_ratio=decrease", target.toString()).redirectErrorStream(true).start(); if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; } return process.exitValue() == 0 && Files.isRegularFile(target); } catch (Exception ignored) { return false; } }
    private static byte[] png(Path source) { try { BufferedImage image = ImageIO.read(source.toFile()); if (image == null) return null; if (image.getWidth() > MAX_EDGE || image.getHeight() > MAX_EDGE) { double s = (double) MAX_EDGE / Math.max(image.getWidth(), image.getHeight()); BufferedImage resized = new BufferedImage(Math.max(1, (int) (image.getWidth() * s)), Math.max(1, (int) (image.getHeight() * s)), BufferedImage.TYPE_INT_RGB); Graphics2D g = resized.createGraphics(); g.drawImage(image, 0, 0, null); g.dispose(); image = resized; } try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { ImageIO.write(image, "png", out); return out.toByteArray(); } } catch (Exception ignored) { return null; } }
    private static void delete(Path root) { try (var all = Files.walk(root)) { all.sorted((a, b) -> b.compareTo(a)).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); } catch (Exception ignored) {} }
}
