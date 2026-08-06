package mavsreclaim;

import java.sql.*;


public class SeedDemo {

  private static final String TODAY = "2026-08-05";

  private static final String PASSWORD = "demo1234";

  private static final String DEMO_EMAIL = "kxm2572@mavs.uta.edu";

  public static void main(String[] args) {
    if (args.length > 0)
      Db.useDatabase(args[0]);

    Db.init();
    Db.seedLockers();
    Db.seedAdmin();

    seed();
  }

  public static void seed() {
    wipe();
    Db.seedRewards(); 

    seedUsers();
    seedItems();
    seedClaims();
    seedPointsAndRedemptions();

    summarize();
  }

  private static void wipe() {
    exec("DELETE FROM redemptions");
    exec("DELETE FROM claims");
    exec("DELETE FROM items");
    exec("DELETE FROM rewards");
    exec("DELETE FROM users WHERE role <> 'admin'");
    exec("UPDATE lockers SET in_use = 0");
    exec("DELETE FROM sqlite_sequence WHERE name IN ('items','claims','rewards','redemptions')");
  }


  private static void seedUsers() {
    String[][] users = {
        { "kyle", DEMO_EMAIL },
        { "priya.nair", "pnair@mavs.uta.edu" },
        { "alex.tran", "atran@mavs.uta.edu" },
    };
    for (String[] u : users)
      Db.addUser(u[0], u[1], PASSWORD);
  }

 private static final String ITEM_PHOTO_RESOURCE = "/templates/demo_item.png";
  private static final String CLAIM_PHOTO_RESOURCE = "/templates/demo_claim.png";
  private static final String PHOTO_TYPE = "image/png";

  private static final byte[] ITEM_PHOTO = loadPhoto(ITEM_PHOTO_RESOURCE);
  private static final byte[] CLAIM_PHOTO = loadPhoto(CLAIM_PHOTO_RESOURCE);

  private static byte[] loadPhoto(String resource) {
    try (java.io.InputStream in = SeedDemo.class.getResourceAsStream(resource)) {
      if (in == null) {
        System.err.println("[SEED] no placeholder photo at " + resource);
        return null;
      }
      return in.readAllBytes();
    } catch (java.io.IOException e) {
      System.err.println("[SEED] could not read " + resource + " — " + e.getMessage());
      return null;
    }
  }

  // Keeps photo_type null when there are no bytes to go with it.
  private static String photoType(byte[] photo) {
    return photo == null ? null : PHOTO_TYPE;
  }

  private static final String[][] ITEMS = {
      { "Blue Hydro Flask with a UTA sticker on it", "Miscellanious", "Central Library",
          "2026-07-22 09:14:00" },
      { "Black JanSport backpack, calculus notebook inside", "Backpack / Bag", "Nedderman Hall",
          "2026-07-22 16:40:00" },
      { "AirPods Pro in a white charging case", "Headphones", "Central Library",
          "2026-07-23 11:05:00" },
      { "UTA MavID, first name starts with K", "ID", "University Center",
          "2026-07-24 13:22:00" },
      { "Anker USB-C charging brick, no cable", "Charger", "Engineering Lab Building",
          "2026-07-25 08:47:00" },
      { "Grey North Face hoodie, size M", "Clothing", "Maverick Activities Center",
          "2026-07-27 18:03:00" },
      { "Silver MacBook Air in a blue sleeve", "Laptop", "Central Library",
          "2026-07-28 10:31:00" },
      { "Brown leather wallet, cards still inside", "Wallet", "University Center",
          "2026-07-29 12:15:00" },
      { "Organic Chemistry textbook, 8th edition", "Book / Notes", "Science Hall",
          "2026-07-30 15:50:00" },
      { "iPhone 14 in a black case, cracked screen protector", "Phone", "Business Building",
          "2026-07-31 09:26:00" },
      { "Red Beats Studio over-ear headphones", "Headphones", "Maverick Activities Center",
          "2026-08-01 14:12:00" },
      { "Car keys on a Mavericks lanyard", "Miscellanious", "Nedderman Hall",
          "2026-08-02 11:38:00" },
      { "Pink Nalgene bottle covered in stickers", "Miscellanious", "Woolf Hall",
          "2026-08-03 17:20:00" },
      { "TI-84 Plus graphing calculator", "Miscellanious", "Nedderman Hall",
          "2026-08-04 10:05:00" },
      { "Navy blue umbrella, wooden handle", "Miscellanious", "University Center",
          TODAY + " 08:30:00" },
  };

  private static void seedItems() {
    for (String[] i : ITEMS) {
      FoundItem item = Db.addFoundItem(i[0], i[1], i[2], DEMO_EMAIL,
          ITEM_PHOTO, photoType(ITEM_PHOTO));
      backdate("items", item.id(), i[3]);
    }
  }

  private static final String[][] CLAIMS = {
      { "Blue metal water bottle with stickers", "Miscellanious", "Central Library",
          "2026-07-22", "2026-07-23 08:02:00", "approved:1" },
      { "White AirPods in a charging case", "Headphones", "Central Library",
          "2026-07-23", "2026-07-24 09:41:00", "approved:3" },
      { "MacBook Air 13 inch in a blue sleeve", "Laptop", "Central Library",
          "2026-07-28", "2026-07-28 19:10:00", "approved:7" },
      { "Green JanSport backpack", "Backpack / Bag", "Woolf Hall",
          "2026-07-26", "2026-07-27 10:15:00", "rejected" },
      { "Black North Face backpack with a laptop inside", "Backpack / Bag", "Nedderman Hall",
          "2026-07-22", "2026-08-01 12:44:00", "pending" },
      { "My MavID card, I think I dropped it near the food court", "ID", "University Center",
          "2026-07-24", "2026-08-02 15:09:00", "pending" },
      { "iPhone in a black case", "Phone", "Business Building",
          "2026-07-31", "2026-08-03 09:58:00", "pending" },
      { "Red over-ear headphones", "Headphones", "Maverick Activities Center",
          "2026-08-01", "2026-08-04 16:25:00", "pending" },
      { "Graphing calculator, TI-84, name written on the back", "Miscellanious", "Nedderman Hall",
          "2026-08-04", TODAY + " 07:45:00", "pending" },
  };

  private static void seedClaims() {
    for (String[] c : CLAIMS) {
      Claim claim = Db.addClaim(c[0], c[1], c[2], DEMO_EMAIL, c[3],
          CLAIM_PHOTO, photoType(CLAIM_PHOTO));
      backdate("claims", claim.id(), c[4]);

      String outcome = c[5];
      if (outcome.startsWith("approved:")) {
        Db.approveClaim(claim.id(), Integer.parseInt(outcome.substring("approved:".length())));
      } else if (outcome.equals("rejected")) {
        Db.rejectClaim(claim.id());
      }
    }
  }

  private static final Object[][] BALANCES = {
      { DEMO_EMAIL, 145 },
      { "pnair@mavs.uta.edu", 85 },
      { "atran@mavs.uta.edu", 0 },
  };

  private static final String[][] REDEMPTIONS = {
      { DEMO_EMAIL, "MavsReclaim Sticker", "2026-07-26 13:30:00" },
      { "pnair@mavs.uta.edu", "MavsReclaim Sticker", "2026-07-30 09:12:00" },
      { DEMO_EMAIL, "MavsReclaim T-Shirt", "2026-08-03 16:47:00" },
  };

  private static void seedPointsAndRedemptions() {
    try (Connection c = Db.connect()) {
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE users SET points = ? WHERE email = ?")) {
        for (Object[] b : BALANCES) {
          p.setInt(1, (Integer) b[1]);
          p.setString(2, (String) b[0]);
          p.addBatch();
        }
        p.executeBatch();
      }

      String sql = """
          INSERT INTO redemptions (user_id, reward_id, redeemed_at)
          VALUES ((SELECT id FROM users WHERE email = ?),
                  (SELECT id FROM rewards WHERE name = ?), ?)
          """;
      try (PreparedStatement p = c.prepareStatement(sql)) {
        for (String[] r : REDEMPTIONS) {
          p.setString(1, r[0]);
          p.setString(2, r[1]);
          p.setString(3, r[2]);
          p.addBatch();
        }
        p.executeBatch();
      }
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE rewards SET stock = stock - 1 WHERE name = ? AND stock IS NOT NULL")) {
        for (String[] r : REDEMPTIONS) {
          p.setString(1, r[1]);
          p.addBatch();
        }
        p.executeBatch();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }


  private static void backdate(String table, int id, String createdAt) {
    try (Connection c = Db.connect();
        PreparedStatement p = c.prepareStatement(
            "UPDATE " + table + " SET created_at = ? WHERE id = ?")) {
      p.setString(1, createdAt);
      p.setInt(2, id);
      p.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static void exec(String sql) {
    try (Connection c = Db.connect(); Statement s = c.createStatement()) {
      s.executeUpdate(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static void summarize() {
    System.out.println("Seeded demo data for " + TODAY);
    count("items", "SELECT COUNT(*) FROM items");
    count("  stored", "SELECT COUNT(*) FROM items WHERE status = 'stored'");
    count("  claimed", "SELECT COUNT(*) FROM items WHERE status = 'claimed'");
    count("claims", "SELECT COUNT(*) FROM claims");
    count("  pending", "SELECT COUNT(*) FROM claims WHERE status = 'pending'");
    count("  approved", "SELECT COUNT(*) FROM claims WHERE status = 'approved'");
    count("  rejected", "SELECT COUNT(*) FROM claims WHERE status = 'rejected'");
    count("users (non-admin)", "SELECT COUNT(*) FROM users WHERE role <> 'admin'");
    count("redemptions", "SELECT COUNT(*) FROM redemptions");
    System.out.println("Date range: " + first("SELECT MIN(created_at) FROM items")
        + "  ..  " + first("SELECT MAX(created_at) FROM items"));
    System.out.println("All reports filed under: " + DEMO_EMAIL);
    System.out.println("Sign in as any seeded user with password: " + PASSWORD);
  }

  private static void count(String label, String sql) {
    System.out.println("  " + label + ": " + first(sql));
  }

  private static String first(String sql) {
    try (Connection c = Db.connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : "";
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
