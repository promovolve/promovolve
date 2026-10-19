import com.microsoft.playwright.Browser;
import promovolve.browser.BrowserSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** The shipped Playwright driver and Chromium can navigate, execute JS, and render. */
public class BrowserSmoke {
  private static int statusValue(String status, String name) {
    return status.lines().filter(line -> line.startsWith(name + ":"))
        .mapToInt(line -> Integer.parseInt(line.substring(name.length() + 1).trim()))
        .findFirst().orElseThrow(() -> new AssertionError("Missing /proc status field: " + name));
  }

  public static void main(String[] args) throws Exception {
    var options = BrowserSession.launchOptions();
    if (!Boolean.TRUE.equals(options.chromiumSandbox)) {
      throw new AssertionError("The production browser launch must enable the sandbox");
    }
    if (args.length == 1 && args[0].equals("--disable-seccomp-filter-sandbox")) {
      var browserArgs = new ArrayList<>(options.args);
      browserArgs.add(args[0]);
      options.setArgs(browserArgs);
    } else if (args.length != 0) {
      throw new IllegalArgumentException("Unsupported smoke-test argument");
    }
    String parentStatus = Files.readString(Path.of("/proc/self/status"));
    int inheritedFilters = statusValue(parentStatus, "Seccomp_filters");
    if (statusValue(parentStatus, "Seccomp") != 2 || inheritedFilters < 1) {
      throw new AssertionError("Container seccomp filter is missing");
    }
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext("/", exchange -> {
      String document = exchange.getRequestURI().getPath().equals("/frame")
          ? "<html><body><h1 id='frame-ready'>Cross-origin ready</h1></body></html>"
          : "<html><title>container-smoke</title><body><h1>Ready</h1>"
              + "<iframe name='cross-origin' src='http://localhost:"
              + fixture.getAddress().getPort() + "/frame'></iframe></body></html>";
      byte[] html = document.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, html.length);
      exchange.getResponseBody().write(html);
      exchange.close();
    });
    fixture.start();
    try (Playwright playwright = Playwright.create();
         Browser browser = playwright.chromium().launch(options)) {
      Page page = browser.newPage();
      page.setDefaultTimeout(15000);
      var response = page.navigate("http://127.0.0.1:" + fixture.getAddress().getPort() + "/");
      if (response == null || response.status() != 200 || !"container-smoke".equals(page.title())) {
        throw new AssertionError("Packaged browser failed to load the fixture");
      }
      var frame = page.frame("cross-origin");
      if (frame == null || !"Cross-origin ready".equals(frame.locator("#frame-ready").textContent())) {
        throw new AssertionError("Browser cannot inspect cross-origin frame with site isolation enabled");
      }
      Object result = page.evaluate("() => 6 * 7");
      if (!(result instanceof Number) || ((Number) result).intValue() != 42 || page.screenshot().length == 0) {
        throw new AssertionError("Packaged browser failed to execute or render");
      }
      boolean sandboxedRenderer = false;
      for (ProcessHandle process : ProcessHandle.current().descendants().toList()) {
        Path proc = Path.of("/proc", Long.toString(process.pid()));
        if (!Files.exists(proc.resolve("cmdline"))) continue;
        String command = Files.readString(proc.resolve("cmdline"));
        if (!command.contains("--type=renderer")) continue;
        String status = Files.readString(proc.resolve("status"));
        // Filter mode alone also holds when only the container's OCI filter is active.
        if (statusValue(status, "Seccomp_filters") <= inheritedFilters) {
          throw new AssertionError("Chromium renderer seccomp filter is missing");
        }
        if (command.contains("--no-sandbox") || !status.matches("(?s).*Seccomp:\\s+2\n.*")
            || !status.matches("(?s).*NoNewPrivs:\\s+1\n.*")
            || !status.matches("(?s).*NSpid:\\s+\\d+\\s+\\d+.*")) {
          throw new AssertionError("Renderer is missing namespace/seccomp isolation: " + status);
        }
        sandboxedRenderer = true;
      }
      if (!sandboxedRenderer) throw new AssertionError("No sandboxed renderer observed");
      System.out.println("PASS packaged Chromium: sandbox, navigation, JavaScript, screenshot");
    } finally {
      fixture.stop(0);
    }
  }
}
