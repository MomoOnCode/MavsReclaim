package mavsreclaim;

// MIGHT HAVE MESSED SOMETHING UP IN GIT, DONT KNOW IF I OVERWROTE SOMEONES WORK OR NOT EDIT AS NEEDED - David

public class FoundItem 
{
    private int id;
    private String description;
    private String category;
    private String building;
    private String finderEmail;
    private Integer lockerId;
    private String pin;
    private String status;

    //Found Item object for building form
    public FoundItem(int id, String description, String category, String building,String finderEmail, Integer lockerId, String pin, String status) 
    {
        this.id = id;
        this.description = description;
        this.category = category;
        this.building = building;
        this.finderEmail = finderEmail;
        this.lockerId = lockerId;
        this.pin = pin;
        this.status = status;
    }

    public int id() { return id; }
    public String description() { return description; }
    public String category() { return category; }
    public String building() { return building; }
    public String finderEmail() { return finderEmail; }
    public Integer lockerId() { return lockerId; }
    public String pin() { return pin; }
    public String status() { return status; }
}