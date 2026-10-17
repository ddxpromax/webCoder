package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewImageToolTests {
    @TempDir Path workspace;
    @Test void returnsAnImageBlockForAWorkspacePng() throws Exception {
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", workspace.resolve("test.png").toFile());
        var output = new ViewImageTool(workspace).viewImage("test.png").getOutput();
        assertThat(output).hasSize(2);
        assertThat(output.get(1)).isInstanceOf(io.agentscope.core.message.ImageBlock.class);
    }
}
