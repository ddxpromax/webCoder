package io.github.ddxpromax.webcoder.desktop;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Token-gated Majo Tauri sidecar shutdown endpoint. */
@RestController @RequestMapping("/api") @CrossOrigin(origins="*")
public class DesktopController {
    static final String TOKEN_ENV="MAJO_DESKTOP_SHUTDOWN_TOKEN", TOKEN_HEADER="X-Majo-Desktop-Shutdown-Token";
    private final ApplicationContext context; public DesktopController(ApplicationContext context){this.context=context;}
    @PostMapping("/desktop/shutdown") public ResponseEntity<Map<String,Object>> shutdown(@RequestHeader(value=TOKEN_HEADER,required=false) String token){String expected=System.getenv(TOKEN_ENV);if(expected==null||expected.isEmpty())return ResponseEntity.status(403).body(Map.of("error","shutdown token not configured"));if(!expected.equals(token))return ResponseEntity.status(401).body(Map.of("error","invalid shutdown token"));Thread closer=new Thread(()->{int code=SpringApplication.exit(context,()->0);System.exit(code);},"desktop-shutdown");closer.setDaemon(true);closer.start();return ResponseEntity.ok(Map.of("status","shutting_down"));}
}
