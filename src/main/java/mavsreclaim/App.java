package mavsreclaim;

import io.javalin.Javalin;

import io.javalin.rendering.template.JavalinThymeleaf;
import at.favre.lib.crypto.bcrypt.BCrypt;

public class App {
  public static void main(String[] args) {
    Db.init();
    Db.seedLockers();
    Db.seedAdmin();
    Db.seedRewards();

    // Demo only — rebuilds the sample items/claims/users dated across the two
    // weeks ending 2026-08-05. Wipes anything entered through the site, so
    // delete this line once the demo is over. See SeedDemo.
    SeedDemo.seed();

    Javalin app = Javalin.create(config -> {
      config.fileRenderer(new JavalinThymeleaf());
      config.staticFiles.add("/templates");
    }).start(7070);
      
    app.get("/", ctx -> {
      var model = new java.util.HashMap<String, Object>();
      model.put("username", ctx.sessionAttribute("username")); // null when not logged in
      model.put("role", ctx.sessionAttribute("role"));         // "admin" gates the panel button
      Integer userId = ctx.sessionAttribute("userId");
      model.put("points", userId != null ? Db.findUserById(userId).points() : null);
      ctx.render("templates/HomePage.html", model);
    });
    app.get("/faq", ctx -> ctx.render("templates/faq.html"));
    // username is here (and on every render below) purely so the navbar can
    // decide whether to show the Rewards link. null == guest.
    app.get("/signin", ctx -> ctx.render("templates/login.html",
        java.util.Collections.singletonMap("username", ctx.sessionAttribute("username"))));

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
      var model = new java.util.HashMap<String, Object>();
      model.put("username", username);
      model.put("claims", Db.pendingClaims());
      ctx.render("templates/admin.html", model);
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
      var model = new java.util.HashMap<String, Object>();
      model.put("username", username);
      model.put("claim", claim);
      model.put("matches", Db.matchesFor(claim));
      ctx.render("templates/claim.html", model);
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
      if (Db.approveClaim(claimId, itemId)) {
        Emailer.sendPickupInstructions(claim.claimantEmail(), item);
        Db.awardPoints(item.finderEmail(), Db.POINTS_PER_CLAIMED_ITEM);
      }

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
    app.get("/create", ctx -> ctx.render("templates/create-account.html",
        java.util.Collections.singletonMap("username", ctx.sessionAttribute("username"))));

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
    // ---- Found-item report (public — someone turning in an item) ----
    // HashMap, not Map.of — username is null for a guest and Map.of rejects nulls.
    app.get("/found", ctx -> {
      var model = new java.util.HashMap<String, Object>();
      model.put("username", ctx.sessionAttribute("username"));
      model.put("buildings", Db.allBuildings());
      model.put("categories", Categories.ALL);
      ctx.render("templates/found.html", model);
    });

    app.post("/found", ctx -> {
      String desc = ctx.formParam("description");
      String category = ctx.formParam("category");
      String building = ctx.formParam("building");
      String email = ctx.formParam("email");

      // Optional uploaded photo (null if the field was left empty)
      var upload = ctx.uploadedFile("photo");
      byte[] photo = null;
      String photoType = null;
      if (upload != null && upload.size() > 0) {
        photo = upload.content().readAllBytes();
        photoType = upload.contentType();
      }

      FoundItem item = Db.addFoundItem(desc, category, building, email, photo, photoType);
      Emailer.sendDropoffInstructions(item);

      // lockerId can be null (no free locker in that building) -> HashMap, not Map.of
      var model = new java.util.HashMap<String, Object>();
      model.put("username", ctx.sessionAttribute("username"));
      model.put("title", "Thanks for turning in an item!");
      model.put("message", "We've emailed drop-off instructions to " + email + ".");
      model.put("lockerId", item.lockerId());
      model.put("pin", item.pin());
      ctx.render("templates/submitted.html", model);
    });

    // ---- Lost-item report (public — becomes a claim in the admin queue) ----
    app.get("/lost", ctx -> {
      var model = new java.util.HashMap<String, Object>();
      model.put("username", ctx.sessionAttribute("username"));
      model.put("buildings", Db.allBuildings());
      model.put("categories", Categories.ALL);
      ctx.render("templates/lost.html", model);
    });

    app.post("/lost", ctx -> {
      String desc = ctx.formParam("description");
      String category = ctx.formParam("category");
      String building = ctx.formParam("building");
      String email = ctx.formParam("email");
      String lostOn = ctx.formParam("lostOn"); // yyyy-MM-dd from the date input

      // Optional uploaded photo (null if the field was left empty)
      var upload = ctx.uploadedFile("photo");
      byte[] photo = null;
      String photoType = null;
      if (upload != null && upload.size() > 0) {
        photo = upload.content().readAllBytes();
        photoType = upload.contentType();
      }

      Db.addClaim(desc, category, building, email, lostOn, photo, photoType);

      var model = new java.util.HashMap<String, Object>();
      model.put("username", ctx.sessionAttribute("username"));
      model.put("title", "Your lost report was submitted!");
      model.put("message", "An admin will review it and email " + email + " if a match is found.");
      ctx.render("templates/submitted.html", model);
    });

    // ---- Points + rewards (any logged-in user) ----
    app.get("/rewards", ctx -> {
      if (!requireLogin(ctx))
        return;
      int userId = ctx.sessionAttribute("userId");
      User user = Db.findUserById(userId);

      var model = new java.util.HashMap<String, Object>();
      model.put("username", user.username());
      model.put("points", user.points());
      model.put("role", user.role()); // admins see the catalog, but no points balance
      model.put("rewards", Db.activeRewards());
      model.put("redemptions", Db.myRedemptions(userId));
      model.put("message", ctx.queryParam("msg"));
      ctx.render("templates/rewards.html", model);
    });

    app.post("/rewards/{id}/redeem", ctx -> {
      if (!requireLogin(ctx))
        return;
      int userId = ctx.sessionAttribute("userId");
      int rewardId = Integer.parseInt(ctx.pathParam("id"));
      String message = Db.redeem(userId, rewardId);
      ctx.redirect("/rewards?msg=" + java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8));
    });

    // ---- Serve stored photos as image responses ----
    app.get("/items/{id}/photo", ctx -> {
      Photo p = Db.itemPhoto(Integer.parseInt(ctx.pathParam("id")));
      if (p == null) {
        ctx.status(404);
        return;
      }
      ctx.contentType(p.type() != null ? p.type() : "application/octet-stream");
      ctx.result(p.data());
    });

    app.get("/claims/{id}/photo", ctx -> {
      Photo p = Db.claimPhoto(Integer.parseInt(ctx.pathParam("id")));
      if (p == null) {
        ctx.status(404);
        return;
      }
      ctx.contentType(p.type() != null ? p.type() : "application/octet-stream");
      ctx.result(p.data());
    });
    

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
          "Nedderman Hall", "student1@mavs.uta.edu", "2026-07-26");
      Db.addClaim("White wireless earbuds in a case", "headphones",
          "Nedderman Hall", "student2@mavs.uta.edu", "2026-07-25");
      Db.addClaim("Black North Face backpack", "Backpack / Bag",
          "University Hall", "student3@mavs.uta.edu", "2026-07-24");
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

  // Looser than requireAdmin — any signed-in user can redeem rewards.
  private static boolean requireLogin(io.javalin.http.Context ctx) {
    if (ctx.sessionAttribute("userId") == null) {
      ctx.redirect("/signin");
      return false;
    }
    return true;
  }
}
