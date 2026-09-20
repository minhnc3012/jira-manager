package com.jiramanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);
        boolean desktopMode = DesktopLauncher.isDesktopMode();
        if (desktopMode) {
            // AWT/SystemTray needs a non-headless JVM; Spring forces headless=true by default.
            app.setHeadless(false);
        }
        try {
            app.run(args);
        } catch (RuntimeException e) {
            // The packaged .exe has no console window, so a startup failure (e.g. the port
            // already being held by a previous instance) would otherwise be silently swallowed
            // by jpackage's native launcher ("Failed to launch JVM") with no clue why.
            if (desktopMode) {
                DesktopLauncher.showStartupError(e);
            }
            throw e;
        }
    }
}
