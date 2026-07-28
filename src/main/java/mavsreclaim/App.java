package mavsreclaim;

import io.javalin.Javalin;
import java.util.Map;
// import java.util.ArrayList;
import java.util.List;

import io.javalin.rendering.template.JavalinThymeleaf;
import at.favre.lib.crypto.bcrypt.BCrypt;

public class App {
  public static void main(String[] args) {
    Db.init();
    Db.seedLockers();
    Db.seedAdmin();

    Javalin app = Javalin.create(config -> {
      config.fileRenderer(new JavalinThymeleaf());
      config.staticFiles.add("/templates");
    }).start(7070);
      
    app.get("/", ctx -> {
      var model = new java.util.HashMap<String, Object>();
      model.put("username", ctx.sessionAttribute("username")); // null when not logged in
      ctx.render("templates/HomePage.html", model);
    });
    app.get("/faq", ctx -> ctx.result("FAQ"));
    app.get("/signin", ctx -> ctx.render("templates/login.html"));

    // Verify credentials, start a session, then send them to the admin panel.
    app.post("/login", ctx -> {
      String username = ctx.formParam("username");
      String password = ctx.formParam("password");

      User user = Db.findUserByUsername(username);
      boolean ok = user != null
          && BCrypt.verifyer().verify(password.toCharArray(), user.passwordHash()).verified;

      if (ok) {
        ctx.sessionAttribute("userId", user.id());
        ctx.sessionAttribute("username", user.username());
        ctx.sessionAttribute("role", user.role());

        // Admins go to the admin panel; regular users land on the home page.
        if ("admin".equals(user.role())) {
          ctx.redirect("/admin");
        } else {
          ctx.redirect("/");
        }
      } else {
        ctx.redirect("/signin?error=1");
      }
    });

    // Protected — must be logged in AND have the admin role.
    app.get("/admin", ctx -> {
      Integer userId = ctx.sessionAttribute("userId");
      String role = ctx.sessionAttribute("role");
      if (userId == null) {
        ctx.redirect("/signin");
        return;
      }
      if (!"admin".equals(role)) {
        ctx.redirect("/"); // logged in, but not an admin
        return;
      }
      String username = ctx.sessionAttribute("username");
      ctx.result("Admin Panel — logged in as " + username);
    });

    app.get("/logout", ctx -> {
      ctx.req().getSession().invalidate();
      ctx.redirect("/");
    });
    app.get("/create", ctx -> ctx.render("templates/create-account.html"));

    // Create the account, then send them to the login page to sign in.
    app.post("/create", ctx -> {
      String username = ctx.formParam("username");
      String email = ctx.formParam("email");
      String password = ctx.formParam("password");

      boolean created = Db.addUser(username, email, password);
      if (created) {
        ctx.redirect("/signin?created=1");
      } else {
        ctx.redirect("/create?error=1");
      }
    });
    app.get("/found", ctx -> ctx.result("Found Item Form"));
    app.get("/lost", ctx -> ctx.result("Lost Item Form"));
    

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
    app.get("/buildings", ctx -> ctx.result(Db.allBuildings().toString()));

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
