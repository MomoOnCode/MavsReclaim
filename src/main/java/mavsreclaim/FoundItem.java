package mavsreclaim;

 
public record FoundItem(int id, String description, String category, String building,
                        String finderEmail, Integer lockerId, String pin, String status, String createdAt) {}
