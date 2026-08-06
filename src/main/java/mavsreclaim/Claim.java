package mavsreclaim;

public record Claim(int id, String description, String category, String building,
                    String claimantEmail, String status, Integer matchedItem,
                    String lostOn, String createdAt, boolean hasPhoto) {}
