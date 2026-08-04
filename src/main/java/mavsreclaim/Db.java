package mavsreclaim;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import at.favre.lib.crypto.bcrypt.BCrypt;

public class Db {
  private static String url = "jdbc:sqlite:mavsreclaim.db";

  public static Connection connect() throws SQLException {
    return DriverManager.getConnection(url);
  }

  // Point every later connect() at a different SQLite file. The tests call this
  // with a temp file so they never touch the real mavsreclaim.db.
  public static void useDatabase(String path) {
    url = "jdbc:sqlite:" + path;
  }

  public static void init() {
    try (var in = Db.class.getResourceAsStream("/schema.sql")) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      try (Connection c = connect(); Statement s = c.createStatement()) {
        for (String stmt : sql.split(";")) {
          if (!stmt.isBlank())
            s.execute(stmt);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("schema init failed", e);
    }
  }

  // No-photo convenience overload (used by the test/seed routes).
  public static FoundItem addFoundItem(String desc, String category,
      String building, String finderEmail) {
    return addFoundItem(desc, category, building, finderEmail, null, null);
  }

  // Insert a found item, assign it a free locker + PIN. Returns the new item.
  // photo/photoType may be null when the finder didn't upload an image.
  public static FoundItem addFoundItem(String desc, String category,
      String building, String finderEmail, byte[] photo, String photoType) {
    String pin = String.format("%04d", new Random().nextInt(10000));

    try (Connection c = connect()) {
      Integer lockerId = claimFreeLocker(c, building);

      String sql = """
          INSERT INTO items
            (description, category, building, finder_email, locker_id, pin, photo, photo_type)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """;
      try (PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
        p.setString(1, desc);
        p.setString(2, category);
        p.setString(3, building);
        p.setString(4, finderEmail);
        if (lockerId != null)
          p.setInt(5, lockerId);
        else
          p.setNull(5, Types.INTEGER);
        p.setString(6, pin);
        if (photo != null)
          p.setBytes(7, photo);
        else
          p.setNull(7, Types.BLOB);
        p.setString(8, photoType);
        p.executeUpdate();

        ResultSet keys = p.getGeneratedKeys();
        int id = keys.next() ? keys.getInt(1) : -1;
        return new FoundItem(id, desc, category, building, finderEmail, lockerId, pin,
            "stored", null, photo != null);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // Raw bytes of a found item's photo, or null if it has none.
  public static Photo itemPhoto(int id) {
    return fetchPhoto("SELECT photo, photo_type FROM items WHERE id = ?", id);
  }

  // Raw bytes of a claim's photo, or null if it has none.
  public static Photo claimPhoto(int id) {
    return fetchPhoto("SELECT photo, photo_type FROM claims WHERE id = ?", id);
  }

  private static Photo fetchPhoto(String sql, int id) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql)) {
      p.setInt(1, id);
      ResultSet rs = p.executeQuery();
      if (!rs.next())
        return null;
      byte[] data = rs.getBytes(1);
      if (data == null)
        return null;
      return new Photo(data, rs.getString(2));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // Grab the first unused locker in that building and mark it taken.
  private static Integer claimFreeLocker(Connection c, String building) throws SQLException {
    String find = "SELECT id FROM lockers WHERE building = ? AND in_use = 0 LIMIT 1";
    try (PreparedStatement p = c.prepareStatement(find)) {
      p.setString(1, building);
      ResultSet rs = p.executeQuery();
      if (!rs.next())
        return null; // no free locker in that building
      int id = rs.getInt("id");

      try (PreparedStatement u = c.prepareStatement("UPDATE lockers SET in_use = 1 WHERE id = ?")) {
        u.setInt(1, id);
        u.executeUpdate();
      }
      return id;
    }
  }

  public static List<FoundItem> allFoundItems() {
    List<FoundItem> out = new ArrayList<>();
    String sql = "SELECT * FROM items ORDER BY created_at DESC";
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql);
        ResultSet rs = p.executeQuery()) {
      while (rs.next())
        out.add(fromRow(rs));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }

  public static FoundItem findItem(int id) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("SELECT * FROM items WHERE id = ?")) {
      p.setInt(1, id);
      ResultSet rs = p.executeQuery();
      return rs.next() ? fromRow(rs) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // Mark claimed and free the locker.
  public static void markClaimed(int itemId) {
    try (Connection c = connect()) {
      FoundItem item = findItem(itemId);
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE items SET status = 'claimed' WHERE id = ?")) {
        p.setInt(1, itemId);
        p.executeUpdate();
      }
      if (item != null && item.lockerId() != null) {
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE lockers SET in_use = 0 WHERE id = ?")) {
          p.setInt(1, item.lockerId());
          p.executeUpdate();
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // Dev/testing only — not part of the user flow.
  public static void deleteItem(int id) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("DELETE FROM items WHERE id = ?")) {
      p.setInt(1, id);
      p.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static FoundItem fromRow(ResultSet rs) throws SQLException {
    int locker = rs.getInt("locker_id");
    Integer lockerId = rs.wasNull() ? null : locker;
    boolean hasPhoto = rs.getBytes("photo") != null;
    return new FoundItem(
        rs.getInt("id"), rs.getString("description"), rs.getString("category"),
        rs.getString("building"), rs.getString("finder_email"),
        lockerId, rs.getString("pin"), rs.getString("status"), rs.getString("created_at"),
        hasPhoto);
  }

  // ---------- claims (lost-item reports) ----------

  // Insert a lost-item report. Starts out 'pending' with no matched item.
  // No-photo convenience overload (used by the test/seed routes).
  public static Claim addClaim(String desc, String category, String building,
      String claimantEmail, String lostOn) {
    return addClaim(desc, category, building, claimantEmail, lostOn, null, null);
  }

  public static Claim addClaim(String desc, String category, String building,
      String claimantEmail, String lostOn, byte[] photo, String photoType) {
    String sql = """
        INSERT INTO claims (description, category, building, claimant_email, lost_on, photo, photo_type)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      p.setString(1, desc);
      p.setString(2, category);
      p.setString(3, building);
      p.setString(4, claimantEmail);
      p.setString(5, lostOn);
      if (photo != null)
        p.setBytes(6, photo);
      else
        p.setNull(6, Types.BLOB);
      p.setString(7, photoType);
      p.executeUpdate();

      ResultSet keys = p.getGeneratedKeys();
      int id = keys.next() ? keys.getInt(1) : -1;
      return findClaim(id);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // The queue the admin works through, oldest first so nobody waits forever.
  public static List<Claim> pendingClaims() {
    List<Claim> out = new ArrayList<>();
    String sql = "SELECT * FROM claims WHERE status = 'pending' ORDER BY created_at ASC";
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql);
        ResultSet rs = p.executeQuery()) {
      while (rs.next())
        out.add(claimFromRow(rs));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }

  public static Claim findClaim(int id) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("SELECT * FROM claims WHERE id = ?")) {
      p.setInt(1, id);
      ResultSet rs = p.executeQuery();
      return rs.next() ? claimFromRow(rs) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // Found items that could plausibly be what this claim is describing.
  // Rank by the date the student says they lost it; fall back to the report
  // date for older claims that predate the lost_on field. searchItems wants a
  // bare yyyy-MM-dd, and created_at is "yyyy-MM-dd HH:mm:ss".
  public static List<FoundItem> matchesFor(Claim claim) {
    String onDate = claim.lostOn() != null ? claim.lostOn() : claim.createdAt().substring(0, 10);
    return searchItems(claim.building(), claim.category(), onDate);
  }

  // Link the claim to the item and hand the locker back. Guarded so a
  // double-submit can't approve the same claim twice or release a locker that
  // has since been reassigned.
  public static boolean approveClaim(int claimId, int itemId) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("""
            UPDATE claims SET status = 'approved', matched_item = ?
            WHERE id = ? AND status = 'pending'
            """)) {
      p.setInt(1, itemId);
      p.setInt(2, claimId);
      if (p.executeUpdate() == 0)
        return false; // already handled by someone else
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    markClaimed(itemId);
    return true;
  }

  // Close out a claim the admin decided isn't a match, so it leaves the queue.
  public static void rejectClaim(int claimId) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(
            "UPDATE claims SET status = 'rejected' WHERE id = ? AND status = 'pending'")) {
      p.setInt(1, claimId);
      p.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static Claim claimFromRow(ResultSet rs) throws SQLException {
    int matched = rs.getInt("matched_item");
    Integer matchedItem = rs.wasNull() ? null : matched;
    boolean hasPhoto = rs.getBytes("photo") != null;
    return new Claim(
        rs.getInt("id"), rs.getString("description"), rs.getString("category"),
        rs.getString("building"), rs.getString("claimant_email"),
        rs.getString("status"), matchedItem,
        rs.getString("lost_on"), rs.getString("created_at"), hasPhoto);
  }

  public static void seedAdmin() {
    try (Connection c = connect()) {
      try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM users WHERE role = 'admin'")) {
        if (rs.getInt(1) > 0)
          return; // an admin already exists, nothing to seed
      }

      String hash = BCrypt.withDefaults().hashToString(12, "admin".toCharArray());

      try (PreparedStatement p = c.prepareStatement(
          "INSERT INTO users (username, email, password_hash, role) VALUES (?, ?, ?, 'admin')")) {
        p.setString(1, "admin");
        p.setString(2, "admin@mavsreclaim.com");
        p.setString(3, hash);
        p.executeUpdate();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // Create a normal (non-admin) account. Returns false if the email is taken.
  public static boolean addUser(String username, String email, String password) {
    String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(
            "INSERT INTO users (username, email, password_hash, role) VALUES (?, ?, ?, 'user')")) {
      p.setString(1, username);
      p.setString(2, email);
      p.setString(3, hash);
      p.executeUpdate();
      return true;
    } catch (SQLException e) {
      // UNIQUE constraint on email -> duplicate signup
      if (e.getMessage() != null && e.getMessage().contains("UNIQUE"))
        return false;
      throw new RuntimeException(e);
    }
  }

  public static User findUserByUsername(String username) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("SELECT * FROM users WHERE username = ?")) {
      p.setString(1, username);
      ResultSet rs = p.executeQuery();
      return rs.next() ? userFromRow(rs) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public static User findUserById(int id) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("SELECT * FROM users WHERE id = ?")) {
      p.setInt(1, id);
      ResultSet rs = p.executeQuery();
      return rs.next() ? userFromRow(rs) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static User userFromRow(ResultSet rs) throws SQLException {
    return new User(
        rs.getInt("id"), rs.getString("username"), rs.getString("email"),
        rs.getString("password_hash"), rs.getString("role"), rs.getInt("points"));
  }

  // points + rewards 

  // points are awarded to whoever's account matches the finder_email on a found item once a claim against it is approved. 
  public static final int POINTS_PER_CLAIMED_ITEM = 25;

  // guest reports which are ones without an email don't earn points since there is nowhere to credit them
  public static void awardPoints(String email, int amount) {
    if (email == null)
      return;
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(
            "UPDATE users SET points = points + ? WHERE lower(email) = lower(?)")) {
      p.setInt(1, amount);
      p.setString(2, email);
      p.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<Reward> activeRewards() {
    List<Reward> out = new ArrayList<>();
    String sql = "SELECT * FROM rewards WHERE active = 1 ORDER BY cost ASC";
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql);
        ResultSet rs = p.executeQuery()) {
      while (rs.next())
        out.add(rewardFromRow(rs));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }

  public static Reward findReward(int id) {
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement("SELECT * FROM rewards WHERE id = ?")) {
      p.setInt(1, id);
      ResultSet rs = p.executeQuery();
      return rs.next() ? rewardFromRow(rs) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static Reward rewardFromRow(ResultSet rs) throws SQLException {
    int stock = rs.getInt("stock");
    Integer stockVal = rs.wasNull() ? null : stock;
    return new Reward(
        rs.getInt("id"), rs.getString("name"), rs.getString("description"),
        rs.getInt("cost"), stockVal, rs.getInt("active") == 1);
  }

  // redeems a reward for a user if they can afford it and it's in stock
  // both the stock and points deductions are guarded UPDATEs
  // can't overdraw either one, it will return a message meant to be shown to the user.
  public static String redeem(int userId, int rewardId) {
    User user = findUserById(userId);
    Reward reward = findReward(rewardId);
    if (user == null || reward == null || !reward.active())
      return "That reward isn't available";
    if (user.points() < reward.cost())
      return "You don't have enough points";

    try (Connection c = connect()) {
      if (reward.stock() != null) {
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE rewards SET stock = stock - 1 WHERE id = ? AND stock > 0")) {
          p.setInt(1, rewardId);
          if (p.executeUpdate() == 0)
            return "Sorry, that reward just sold out.";
        }
      }
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE users SET points = points - ? WHERE id = ? AND points >= ?")) {
        p.setInt(1, reward.cost());
        p.setInt(2, userId);
        p.setInt(3, reward.cost());
        if (p.executeUpdate() == 0)
          return "You don't have enough points for that yet.";
      }
      try (PreparedStatement p = c.prepareStatement(
          "INSERT INTO redemptions (user_id, reward_id) VALUES (?, ?)")) {
        p.setInt(1, userId);
        p.setInt(2, rewardId);
        p.executeUpdate();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return "Redeemed, show this to the admin " + reward.name() + ".";
  }

  public static List<Redemption> myRedemptions(int userId) {
    List<Redemption> out = new ArrayList<>();
    String sql = """
        SELECT redemptions.id, rewards.name, rewards.cost, redemptions.redeemed_at
        FROM redemptions
        JOIN rewards ON rewards.id = redemptions.reward_id
        WHERE redemptions.user_id = ?
        ORDER BY redemptions.redeemed_at DESC
        """;
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql)) {
      p.setInt(1, userId);
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next())
          out.add(new Redemption(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }

  public static void seedRewards() {
    try (Connection c = connect()) {
      try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM rewards")) {
        if (rs.getInt(1) > 0)
          return;
      }

      Object[][] seed = {
          // name, description, cost, stock (null = unlimited)
          { "MavsReclaim Sticker", "A laptop sticker", 20, null },
          { "MavsReclaim T-Shirt", "Limited while supplies last", 60, 50 },
          { "Inclusion Coffee Coupon", "Free Coffee at Inclusion", 200, 20 },
          { "Chick Fil A Free meal", "Redeemable only on campus", 350, 15 },
      };

      try (PreparedStatement p = c.prepareStatement(
          "INSERT INTO rewards (name, description, cost, stock) VALUES (?, ?, ?, ?)")) {
        for (Object[] r : seed) {
          p.setString(1, (String) r[0]);
          p.setString(2, (String) r[1]);
          p.setInt(3, (Integer) r[2]);
          if (r[3] == null)
            p.setNull(4, Types.INTEGER);
          else
            p.setInt(4, (Integer) r[3]);
          p.addBatch();
        }
        p.executeBatch();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public static void seedLockers() {
    try (Connection c = connect()) {
      try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM lockers")) {
        if (rs.getInt(1) > 0)
          return;
      }

      Map<String, Integer> lockersPerBuilding = Map.ofEntries(
          Map.entry("Central Library", 5),
          Map.entry("University Center", 5),
          Map.entry("Nedderman Hall", 5),
          Map.entry("Engineering Lab Building", 5),
          Map.entry("Engineering Research Building", 5),
          Map.entry("Woolf Hall", 5),
          Map.entry("Science Hall", 5),
          Map.entry("Life Science Building", 5),
          Map.entry("Chemistry & Physics Building", 5),
          Map.entry("Business Building", 5),
          Map.entry("University Hall", 5),
          Map.entry("Trimble Hall", 5),
          Map.entry("Hammond Hall", 5),
          Map.entry("Pickard Hall", 5),
          Map.entry("Preston Hall", 5),
          Map.entry("Ransom Hall", 5),
          Map.entry("Carlisle Hall", 5),
          Map.entry("College Hall", 5),
          Map.entry("Texas Hall", 5),
          Map.entry("Maverick Activities Center", 5),
          Map.entry("Fine Arts Building", 5),
          Map.entry("Physical Education Building", 5));

      try (PreparedStatement p = c.prepareStatement(
          "INSERT INTO lockers (building, in_use) VALUES (?, 0)")) {
        for (var e : lockersPerBuilding.entrySet()) {
          for (int i = 0; i < e.getValue(); i++) {
            p.setString(1, e.getKey());
            p.addBatch();
          }
        }
        p.executeBatch();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<String> allBuildings() {
    List<String> out = new ArrayList<>();
    String sql = "SELECT DISTINCT building FROM lockers ORDER BY building";
    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql);
        ResultSet rs = p.executeQuery()) {
      while (rs.next())
        out.add(rs.getString("building"));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }

  // My submission for our query for the admin seartch panel
  // it should rnak items closest to the date the they lostOn and list exact
  // category matches first
  // but should still show others in case someone labels airpods misc and not
  // headphones for example
  public static List<FoundItem> searchItems(String building, String category, String lostOn) {
    try {
      LocalDate.parse(lostOn);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid date: " + lostOn);
    }
    List<FoundItem> out = new ArrayList<>();
    String sql = """
           SELECT *, (category = ?) as cat_match,
           abs(julianday(created_at) - julianday(?)) as gap
           FROM items
           WHERE building = ? AND status = 'stored'
           ORDER BY cat_match DESC, gap ASC
        """;

    try (Connection c = connect();
        PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, category);
      p.setString(2, lostOn);
      p.setString(3, building);
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          out.add(fromRow(rs));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }
}
