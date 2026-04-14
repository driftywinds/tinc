package edu.brown.cs.catan;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import edu.brown.cs.api.CatanGroupSelector;
import edu.brown.cs.networking.GCT;
import edu.brown.cs.networking.GCT.GCTBuilder;
import edu.brown.cs.networking.Networking;
import freemarker.template.Configuration;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.TemplateViewRoute;
import spark.embeddedserver.EmbeddedServers;
import spark.embeddedserver.jetty.EmbeddedJettyFactory;
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
    // ── Jetty HTTP compliance ──────────────────────────────────────────────
    // JVM system properties like -Dorg.eclipse.jetty.http.HttpCompliance=LEGACY
    // are NOT read by Jetty 9.4 — HttpCompliance is an enum, not a property.
    // The only working way is to register a custom EmbeddedJettyFactory with
    // an HttpConfiguration before any other Spark call is made.
    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setHttpCompliance(HttpCompliance.LEGACY);
    httpConfig.setSendServerVersion(false);
    EmbeddedServers.add(EmbeddedServers.Identifiers.JETTY,
        new EmbeddedJettyFactory().withHttpConfiguration(httpConfig));

    // 1. Pure config — must come before init() but does not trigger route mapping.
    Spark.port(getHerokuAssignedPort());
    Spark.threadPool(NUM_THREADS, MIN_THREADS, TIMEOUT);
    Spark.staticFileLocation("/static");

    // 2. WebSocket registration — MUST happen before any Spark.before() or
    //    Spark.get() call, because those trigger route-mapping which permanently
    //    closes the window for WebSocket handler registration.
    gct = new GCTBuilder("/action")
        .withGroupSelector(new CatanGroupSelector())
        .withGroupViewRoute("/groups")
        .build();

    // 3. Freemarker — pure Java config, order doesn't matter.
    Configuration config = new Configuration();
    config.setClassForTemplateLoading(Main.class, "/spark/template/freemarker");
    FreeMarkerEngine freeMarker = new FreeMarkerEngine(config);

    // 4. Reverse-proxy header filter — safe to add after WebSocket registration.
    Spark.before((req, res) -> {
      String forwardedFor = req.headers("X-Forwarded-For");
      if (forwardedFor != null && !forwardedFor.isEmpty()) {
        req.attribute("client-ip", forwardedFor.split(",")[0].trim());
      }
      String proto = req.headers("X-Forwarded-Proto");
      if (proto != null && !proto.isEmpty()) {
        req.attribute("spark.requestedScheme", proto.trim());
      }
    });

    // 5. Routes.
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


  /** Reads PORT env var; falls back to 4567 for local runs. */
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