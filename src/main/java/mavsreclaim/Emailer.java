
package mavsreclaim;

import io.github.cdimascio.dotenv.Dotenv;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;

public class Emailer {
  private static final Dotenv env = Dotenv.configure().ignoreIfMissing().load();

  private static final String USER = env.get("GMAIL_USER");
  private static final String PASS = env.get("GMAIL_APP_PASSWORD");
  private static final boolean ENABLED = !"false".equals(env.get("MAIL_ENABLED"));

  public static void send(String to, String subject, String body) {
    if (!ENABLED || USER == null) {
      // if you set email off then itll print the email you were trying to send
      System.out.println("[MAIL OFF] to=" + to + " | " + subject + "\n" + body);
      return;
    }

    Properties props = new Properties();
    props.put("mail.smtp.host", "smtp.gmail.com");
    props.put("mail.smtp.port", "587");
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.starttls.enable", "true");

    Session session = Session.getInstance(props, new Authenticator() {
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(USER, PASS);
      }
    });

    try {
      Message msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(USER));
      msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
      msg.setSubject(subject);
      msg.setText(body);
      Transport.send(msg);
      System.out.println("[MAIL SENT] " + to);
    } catch (MessagingException e) {
      System.err.println("[MAIL FAILED] " + to + " — " + e.getMessage());
    }
  }

  // Sent to the finder immediately after they submit a found-item report.
  public static void sendDropoffInstructions(FoundItem item) {
    String body;
    if (item.lockerId() == null) {
      body = """
          Thanks for reporting a found item on MavsReclaim.

          Item: %s
          Found at: %s

          No lockers are currently free in that building — please take the
          item to the Lost and Found Office instead.
          """.formatted(item.description(), item.building());
    } else {
      body = """
          Thanks for reporting a found item on MavsReclaim.

          Item: %s
          Found at: %s

          Please place it in LOCKER %d in %s.

          The owner will be given a PIN once their claim is verified.
          """.formatted(item.description(), item.building(), item.lockerId(), item.building());
    }
    send(item.finderEmail(), "MavsReclaim — where to drop off your found item", body);
  }

  // Sent to the claimant AFTER an admin approves their claim.
  public static void sendPickupInstructions(String claimantEmail, FoundItem item) {
    String body;
    if (item.lockerId() == null) {
      // No locker was free when it was dropped off, so it went to the office
      // instead — there is no PIN to hand out.
      body = """
          Good news — your claim on MavsReclaim was approved.

          Item: %s
          Found at: %s

          This item is being held at the Lost and Found Office rather than a
          locker. Bring your student ID to the office to pick it up.
          """.formatted(item.description(), item.building());
    } else {
      body = """
          Good news — your claim on MavsReclaim was approved.

          Item: %s
          Location: LOCKER %d, %s
          PIN: %s

          Enter the PIN on the locker keypad to retrieve your item.
          """.formatted(item.description(), item.lockerId(), item.building(), item.pin());
    }

    send(claimantEmail, "MavsReclaim — your item is ready for pickup", body);
  }
}
