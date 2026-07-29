package mavsreclaim;

//reward for points
public record Reward(int id, String name, String description, int cost, Integer stock, boolean active) {}
