package mavsreclaim;

import io.javalin.Javalin;
import java.util.Map;

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

    // Protected — the queue of lost reports waiting on a decision.
    app.get("/admin", ctx -> {
      if (!requireAdmin(ctx))
        return;
      // Bind to a String first — sessionAttribute is generic, so inlining it
      // into Map.of() makes javac infer the wrong type and fail at runtime.
      String username = ctx.sessionAttribute("username");
      ctx.render("templates/admin.html", Map.of(
          "username", username,
          "claims", Db.pendingClaims()));
    });

    // One lost report, plus the found items that might be it.
    app.get("/admin/claim/{id}", ctx -> {
      if (!requireAdmin(ctx))
        return;
      Claim claim = Db.findClaim(Integer.parseInt(ctx.pathParam("id")));
      if (claim == null) {
        ctx.status(404).result("No such claim");
        return;
      }
      String username = ctx.sessionAttribute("username");
      ctx.render("templates/claim.html", Map.of(
          "username", username,
          "claim", claim,
          "matches", Db.matchesFor(claim)));
    });

    // Admin picked which found item this claim refers to.
    app.post("/admin/claim/{id}/approve", ctx -> {
      if (!requireAdmin(ctx))
        return;
      int claimId = Integer.parseInt(ctx.pathParam("id"));
      int itemId = Integer.parseInt(ctx.formParam("itemId"));

      Claim claim = Db.findClaim(claimId);
      FoundItem item = Db.findItem(itemId);
      if (claim == null || item == null) {
        ctx.status(404).result("No such claim or item");
        return;
      }
      // Someone else may have already handed this item to a different claimant.
      if (!"stored".equals(item.status())) {
        ctx.status(409).result("That item is no longer available");
        return;
      }

      // Update first: if the mail fails it only logs, and the admin can resend.
      // The other order could hand out a PIN for an item still marked stored.
      if (Db.approveClaim(claimId, itemId))
        Emailer.sendPickupInstructions(claim.claimantEmail(), item);

      ctx.redirect("/admin");
    });

    // Nothing in the building matched — take it out of the queue.
    app.post("/admin/claim/{id}/reject", ctx -> {
      if (!requireAdmin(ctx))
        return;
      Db.rejectClaim(Integer.parseInt(ctx.pathParam("id")));
      ctx.redirect("/admin");
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

    // Stand-in for the /lost form until it exists — gives /admin a queue to show.
    app.get("/test/lost", ctx -> {
      Db.addClaim("Blue metal water bottle", "bottle",
          "Nedderman Hall", "student1@mavs.uta.edu");
      Db.addClaim("White wireless earbuds in a case", "headphones",
          "Nedderman Hall", "student2@mavs.uta.edu");
      Db.addClaim("Black North Face backpack", "Backpack / Bag",
          "University Hall", "student3@mavs.uta.edu");
      ctx.result("3 lost reports added — open /admin");
    });

    app.get("/test/claim/{id}", ctx -> {
      int id = Integer.parseInt(ctx.pathParam("id"));
      FoundItem item = Db.findItem(id);
      Emailer.sendPickupInstructions("kxm2572@mavs.uta.edu", item);
      Db.markClaimed(id);
      ctx.result("claimed item " + id);
    });
  }

  // Every /admin route needs the same two checks, so they live here.
  // Returns false once it has already sent a redirect.
  private static boolean requireAdmin(io.javalin.http.Context ctx) {
    if (ctx.sessionAttribute("userId") == null) {
      ctx.redirect("/signin");
      return false;
    }
    if (!"admin".equals(ctx.sessionAttribute("role"))) {
      ctx.redirect("/"); // logged in, but not an admin
      return false;
    }
    return true;
  }
}
