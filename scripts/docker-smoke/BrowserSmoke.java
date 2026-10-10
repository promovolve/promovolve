import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** The shipped Playwright driver and Chromium can navigate, execute JS, and render. */
public class BrowserSmoke {
  public static void main(String[] args) throws Exception {
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext("/", exchange -> {
      byte[] html = "<html><title>container-smoke</title><body><h1>Ready</h1></body></html>"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, html.length);
      exchange.getResponseBody().write(html);
      exchange.close();
    });
    fixture.start();
    try (Playwright playwright = Playwright.create();
         Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
      Page page = browser.newPage();
      page.setDefaultTimeout(15000);
      var response = page.navigate("http://127.0.0.1:" + fixture.getAddress().getPort() + "/");
      if (response == null || response.status() != 200 || !"container-smoke".equals(page.title())) {
        throw new AssertionError("Packaged browser failed to load the fixture");
      }
      Object result = page.evaluate("() => 6 * 7");
      if (!(result instanceof Number) || ((Number) result).intValue() != 42 || page.screenshot().length == 0) {
        throw new AssertionError("Packaged browser failed to execute or render");
      }
      System.out.println("PASS packaged Chromium: navigation, JavaScript, screenshot");
    } finally {
      fixture.stop(0);
    }
  }
}
