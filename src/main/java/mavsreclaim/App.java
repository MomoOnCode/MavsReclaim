package mavsreclaim;

import io.javalin.Javalin;
import java.util.Map;
// import java.util.ArrayList;
import java.util.List;

import io.javalin.rendering.template.JavalinThymeleaf;

public class App {
  public static void main(String[] args) {
    Db.init();
    Db.seedLockers();

    Javalin app = Javalin.create(config -> {config.fileRenderer(new JavalinThymeleaf());}).start(7070);
      
    app.get("/", ctx -> ctx.result("hello"));
    app.get("/test", ctx -> ctx.result("Hello, but from a test page"));
    app.get("/buildings", ctx -> ctx.result(Db.allBuildings().toString()));
    /*
     * so like that app.get() above says if you run www.mavsreclaim.com/ serve the
     * result Hello
     * if we did app.get("/found_item" -> ctx.render("report.html"))
     * that would say when someone hits www.mavsreclaim.com/found_item we redner the
     * report forms html file.
     * We dont have a real domain so itd be more like localhost:7070/found_item as
     * the url but same same
     * 
     * so download java 25 sdk and maven. if your not on windows i super recommend
     * sdkman for managing java sdks.
     * download maven
     * 
     * To test: run "mvn compile exec:java"
     * you should then open http:localhost:7070/ and see hello on the screen
     * or go to http:localhost:7070/test and see the other result..
     * 
     * you should also see a .db file appear in the root. that is the sqlite db that
     * gets created on startup.
     * .db is in gitignore so you wont commit your local db. inside Db.java is our
     * db logic,
     * and /resources/schema.sql is the schema being ran by Db.init();
     */

    app.get("/test/add", ctx -> {
      FoundItem item = Db.addFoundItem(
          "Blue Hydroflask",
          "bottle",
          "Nedderman Hall",
          "kxm2572@mavs.uta.edu");
      Emailer.sendDropoffInstructions(item);
      ctx.json(item);
    });

    app.get("/test/add2", ctx -> {
      Db.addFoundItem("Blue Hydroflask",
          "bottle",
          "Nedderman Hall",
          "kxm2572@mavs.uta.edu");
      Db.addFoundItem("Airpods",
          "headphones",
          "Nedderman Hall",
          "kxm2572@mavs.uta.edu");
      Db.addFoundItem("Red Hydroflask",
          "bottle",
          "Nedderman Hall",
          "kxm2572@mavs.uta.edu");
      Db.addFoundItem("Blue Hydroflask",
          "bottle",
          "University Hall",
          "kxm2572@mavs.uta.edu");
      ctx.result("4 items added");
    });

    app.get("test/admin", ctx -> {
      List<FoundItem> results = Db.searchItems("Nedderman Hall", "bottle", "2026-07-15");
      ctx.json(results);
    });

    app.get("test/admin2", ctx -> {
      ctx.render("templates/admin.html", Map.of("results", Db.searchItems("Nedderman Hall", "bottle", "2026-07-15")));
    });
    app.get("/test/claim/{id}", ctx -> {
      int id = Integer.parseInt(ctx.pathParam("id"));
      FoundItem item = Db.findItem(id);
      Emailer.sendPickupInstructions("kxm2572@mavs.uta.edu", item);
      Db.markClaimed(id);
      ctx.result("claimed item " + id);
    });
  }
}
