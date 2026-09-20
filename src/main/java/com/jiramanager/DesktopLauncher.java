package com.jiramanager;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.List;

/**
 * Only active when packaged and launched as a desktop .exe (see jira.desktop.mode
 * system property, set via jpackage's --java-options). Opens the app in a chromeless
 * Edge/Chrome window (--app mode) on startup and shuts the server down when that
 * window is closed. Falls back to a normal browser tab if no browser is found (in
 * which case closing the tab does NOT stop the server — use the tray icon's Exit).
 * A tray icon is added either way, since the packaged app runs with no console window.
 */
@Component
public class DesktopLauncher {

    private final Environment env;

    public DesktopLauncher(Environment env) {
        this.env = env;
    }

    public static boolean isDesktopMode() {
        return "true".equals(System.getProperty("jira.desktop.mode"));
    }

    public static void showStartupError(Throwable e) {
        String portHint = "";
        Throwable cause = e;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("port")
                    && cause.getMessage().toLowerCase().contains("use")) {
                portHint = "\n\nAnother Jira Manager (or something else) is already using this port.\n"
                        + "Check the system tray for an existing Jira Manager icon before retrying.";
                break;
            }
            cause = cause.getCause();
        }
        String message = "Jira Manager failed to start.\n" + e.getMessage() + portHint;
        try {
            javax.swing.JOptionPane.showMessageDialog(null, message, "Jira Manager",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Exception ignored) {
            // If even the dialog can't show, there's nothing more we can do here.
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!isDesktopMode()) {
            return;
        }
        String url = "http://localhost:" + env.getProperty("server.port", "8888");
        addTrayIcon(url);
        openBrowser(url);
    }

    private void openBrowser(String url) {
        if (launchAppWindow(url)) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            }
        } catch (Exception ignored) {
            // Non-fatal: user can still open the URL manually.
        }
    }

    /**
     * Opens Edge/Chrome in --app mode: a chromeless window instead of a browser tab.
     * Uses a dedicated --user-data-dir so the browser can't fold this into an
     * already-running Edge/Chrome instance — that would make our Process handle
     * exit immediately after messaging the existing instance, and we'd have no way
     * to detect when the window is actually closed. With a dedicated profile we get
     * a real process for the window and can shut the server down when it exits,
     * instead of leaving it running (and the port held) after the window is closed.
     */
    private boolean launchAppWindow(String url) {
        String profileDir = System.getenv("LOCALAPPDATA") + "\\JiraManagerDesktop\\browser-profile";
        for (String exe : browserCandidates()) {
            if (exe != null && new File(exe).isFile()) {
                try {
                    Process process = new ProcessBuilder(
                            exe,
                            "--app=" + url,
                            "--user-data-dir=" + profileDir,
                            "--window-size=1400,900",
                            "--no-first-run"
                    ).start();
                    watchWindowAndExitWhenClosed(process.pid());
                    return true;
                } catch (Exception ignored) {
                    // try the next candidate
                }
            }
        }
        return false;
    }

    /**
     * Watches the launched browser's own window (not just its process) and shuts the
     * server down once it's gone. Waiting for the process to exit isn't reliable: on
     * managed/enterprise machines Edge's "keep running in background" behavior can be
     * policy-forced on regardless of --disable-background-mode, so the process can
     * outlive its last window indefinitely. Instead this polls the window handle and,
     * once it disappears, force-kills the whole browser process tree itself.
     */
    private void watchWindowAndExitWhenClosed(long pid) {
        Thread watcher = new Thread(() -> {
            String script = "$ErrorActionPreference='SilentlyContinue';"
                    + "$seenOpen=$false;"
                    + "while ($true) {"
                    + "  $p = Get-Process -Id " + pid + ";"
                    + "  if (-not $p) { break };"
                    + "  if ($p.MainWindowHandle -ne 0) { $seenOpen=$true }"
                    + "  elseif ($seenOpen) { break };"
                    + "  Start-Sleep -Milliseconds 1000"
                    + "};"
                    + "taskkill /PID " + pid + " /T /F | Out-Null";
            try {
                Process watcherProcess = new ProcessBuilder(
                        "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script
                ).start();
                watcherProcess.waitFor();
            } catch (Exception ignored) {
                return;
            }
            System.exit(0);
        }, "app-window-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private List<String> browserCandidates() {
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        return List.of(
                pf86 + "\\Microsoft\\Edge\\Application\\msedge.exe",
                pf + "\\Microsoft\\Edge\\Application\\msedge.exe",
                pf + "\\Google\\Chrome\\Application\\chrome.exe",
                pf86 + "\\Google\\Chrome\\Application\\chrome.exe",
                localAppData + "\\Google\\Chrome\\Application\\chrome.exe"
        );
    }

    private void addTrayIcon(String url) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open Jira Manager");
            openItem.addActionListener(e -> openBrowser(url));
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> System.exit(0));
            menu.add(openItem);
            menu.addSeparator();
            menu.add(exitItem);

            TrayIcon trayIcon = new TrayIcon(trayImage(), "Jira Manager", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> openBrowser(url));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException ignored) {
            // Non-fatal: app still runs, just without a tray icon.
        }
    }

    private Image trayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 82, 204));
        g.fillOval(0, 0, 16, 16);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 10));
        g.drawString("J", 5, 12);
        g.dispose();
        return image;
    }
}
