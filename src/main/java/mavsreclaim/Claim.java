package mavsreclaim;

// A lost-item report submitted by a student. Stays 'pending' until an admin
// matches it to a found item, at which point it becomes 'approved' and
// matchedItem points at that items row.
public record Claim(int id, String description, String category, String building,
                    String claimantEmail, String status, Integer matchedItem,
                    String lostOn, String createdAt, boolean hasPhoto) {}
