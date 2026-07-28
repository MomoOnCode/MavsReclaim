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
  private static final String URL = "jdbc:sqlite:mavsreclaim.db";

  public static Connection connect() throws SQLException {
    return DriverManager.getConnection(URL);
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

  // Insert a found item, assign it a free locker + PIN. Returns the new item.
  public static FoundItem addFoundItem(String desc, String category,
      String building, String finderEmail) {
    String pin = String.format("%04d", new Random().nextInt(10000));

    try (Connection c = connect()) {
      Integer lockerId = claimFreeLocker(c, building);

      String sql = """
          INSERT INTO items
            (description, category, building, finder_email, locker_id, pin)
          VALUES (?, ?, ?, ?, ?, ?)
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
        p.executeUpdate();

        ResultSet keys = p.getGeneratedKeys();
        int id = keys.next() ? keys.getInt(1) : -1;
        return new FoundItem(id, desc, category, building, finderEmail, lockerId, pin, "stored", null);
      }
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
    return new FoundItem(
        rs.getInt("id"), rs.getString("description"), rs.getString("category"),
        rs.getString("building"), rs.getString("finder_email"),
        rs.wasNull() ? null : locker, rs.getString("pin"), rs.getString("status"), rs.getString("created_at"));
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
      if (!rs.next())
        return null;
      return new User(
          rs.getInt("id"), rs.getString("username"), rs.getString("email"),
          rs.getString("password_hash"), rs.getString("role"));
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
