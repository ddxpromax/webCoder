package io.github.ddxpromax.webcoder.desktop;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Emits Majo's stdout ready signal only when started by a desktop shell. */
@Component
public class DesktopReadyPrinter implements ApplicationListener<WebServerInitializedEvent> {
    @Override public void onApplicationEvent(WebServerInitializedEvent event){if(System.getenv("MAJO_DESKTOP_APP")==null)return;System.out.println("MAJO_BACKEND_READY {\"port\":"+event.getWebServer().getPort()+"}");System.out.flush();}
}
