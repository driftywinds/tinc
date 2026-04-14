package edu.brown.cs.catan;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import edu.brown.cs.api.CatanGroupSelector;
import edu.brown.cs.networking.GCT;
import edu.brown.cs.networking.GCT.GCTBuilder;
import edu.brown.cs.networking.Networking;
import freemarker.template.Configuration;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.TemplateViewRoute;
import spark.template.freemarker.FreeMarkerEngine;

public class Main {

  private static final int NUM_THREADS = 8;
  private static final int MIN_THREADS = 2;
  private static final int TIMEOUT     = 3_600_000;

  private GCT gct;


  public static void main(String[] args) {
    new Main().run();
  }


  private Main() {
    // Serve static assets from the classpath (/src/main/resources/static →
    // packed into the fat jar at /static/).  No filesystem path needed.
    Spark.staticFileLocation("/static");

    Spark.port(getHerokuAssignedPort());
    Spark.threadPool(NUM_THREADS, MIN_THREADS, TIMEOUT);

    // ── Reverse-proxy support ──────────────────────────────────────────────
    // When running behind nginx / Caddy / Traefik the real client IP and
    // scheme arrive in these standard headers.  Rewrite the request so that
    // Spark's req.ip() / req.scheme() return the correct values downstream.
    Spark.before((req, res) -> {
      String forwardedFor = req.headers("X-Forwarded-For");
      if (forwardedFor != null && !forwardedFor.isEmpty()) {
        // X-Forwarded-For may be a comma-separated list; the first entry is
        // the original client.
        req.attribute("client-ip", forwardedFor.split(",")[0].trim());
      }

      String proto = req.headers("X-Forwarded-Proto");
      if (proto != null && !proto.isEmpty()) {
        // Force any absolute redirects to use the correct scheme.
        req.attribute("spark.requestedScheme", proto.trim());
      }
    });

    gct = new GCTBuilder("/action")
        .withGroupSelector(new CatanGroupSelector())
        .withGroupViewRoute("/groups")
        .build();

    // Load Freemarker templates from the classpath so the fat jar is
    // self-contained — no filesystem template directory required at runtime.
    Configuration config = new Configuration(Configuration.VERSION_2_3_33);
    config.setClassForTemplateLoading(Main.class, "/spark/template/freemarker");
    FreeMarkerEngine freeMarker = new FreeMarkerEngine(config);

    Spark.get("/board", new BoardHandler(), freeMarker);
    Spark.get("/home",  new HomeHandler(),  freeMarker);
    Spark.get("/stats", new StatsHandler(), freeMarker);
    Spark.before("/", (request, response) -> {
      System.out.println(
          "Redirect causes an extra open/close on GroupView. Disregard.");
      response.redirect("/home");
    });

    Spark.init();
  }


  private void run() {}


  /** Reads PORT env var so the image works with any host port mapping. */
  private static int getHerokuAssignedPort() {
    String port = System.getenv("PORT");
    return port != null ? Integer.parseInt(port) : 4567;
  }


  private class StatsHandler implements TemplateViewRoute {

    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>()
              .put("title",        "Catan Stats")
              .put("openGroups",   gct.openGroups().toString())
              .put("closedGroups", gct.closedGroups().toString())
              .put("limit",        gct.groupLimit())
              .build();
      return new ModelAndView(variables, "stats.ftl");
    }
  }


  private static class BoardHandler implements TemplateViewRoute {

    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, Object> variables = ImmutableMap.of("title", "Play Catan");
      return new ModelAndView(variables, "board.ftl");
    }
  }


  private class HomeHandler implements TemplateViewRoute {

    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, String> cookies = req.cookies();
      if (cookies.containsKey(Networking.USER_IDENTIFIER)) {
        System.out.println("1");
        if (Main.this.gct
            .userIDIsValid(cookies.get(Networking.USER_IDENTIFIER))) {
          System.out.println("2");
          res.redirect("/board");
          return new BoardHandler().handle(req, res);
        }
      }

      Map<String, Object> variables =
          ImmutableMap.of("title", "Catan : Home");
      return new ModelAndView(variables, "home.ftl");
    }
  }

}