package mavsreclaim;

public record User(int id, String username, String email, String passwordHash, String role) {}
