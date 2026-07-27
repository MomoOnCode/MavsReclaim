package mavsreclaim;

public class Request
{
    private int id;
    private String description;
    private String category;
    private String building;
    private String lostOn;
    private String requesterEmail;
    private String status;
    private int matchedItemId;

    public Request(int id, String description, String category, String building, String lostOn, String requesterEmail, String status, int matchedItemId)
    {
        this.id = id;
        this.description = description;
        this.category = category;
        this.building = building;
        this.lostOn = lostOn;
        this.requesterEmail = requesterEmail;
        this.status = status;
        this.matchedItemId = matchedItemId;
    }

    public int id() { return id; }
    public String description() { return description; }
    public String category() { return category; }
    public String building() { return building; }
    public String lostOn() { return lostOn; }
    public String requesterEmail() { return requesterEmail; }
    public String status() { return status; }
    public Integer matchedItemId() { return matchedItemId; }
}