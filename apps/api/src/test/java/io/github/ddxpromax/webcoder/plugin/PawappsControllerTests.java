package io.github.ddxpromax.webcoder.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.agent.MajoWorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PawappsControllerTests {
    @Test void discoversPawappManifestAndContainsStaticFiles() throws Exception {Path root=Files.createTempDirectory("majo-app-");try{Path app=root.resolve("plugins").resolve("notes");Files.createDirectories(app);Files.writeString(app.resolve("plugin.json"),"{\"id\":\"notes\",\"name\":\"Notes\",\"meta\":{\"pawapp\":{\"entry_page\":\"index.html\"}}}");Files.writeString(app.resolve("index.html"),"hello");PawappsController controller=new PawappsController(new MajoWorkingDirectory(root.toString()),new ObjectMapper());assertThat((java.util.List<?>)controller.list().get("apps")).hasSize(1);assertThat(controller.staticFile("notes","index.html").getStatusCode().value()).isEqualTo(200);assertThat(controller.staticFile("notes","../plugin.json").getStatusCode().value()).isEqualTo(404);}finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
}
