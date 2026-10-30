package app.chitchat;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PushPolicyTest {
    @Test public void nearbyModeNeverDisplaysAnOnlineAlert() {
        for (String kind : new String[]{"inbox", "requests", "contacts", "test", "untrusted", null})
            assertNull(PushPolicy.body(false, kind));
    }
    @Test public void onlyRecognizedHintsProduceFixedGenericText() {
        assertEquals("A new message is waiting.", PushPolicy.body(true, "inbox"));
        assertEquals("You have a new chat request.", PushPolicy.body(true, "requests"));
        assertEquals("Your connection request has an update.", PushPolicy.body(true, "contacts"));
        assertEquals("This is a ChitChat notification test.", PushPolicy.body(true, "test"));
        for (String kind : new String[]{null,"","delivered","PRIVATE_MESSAGE_TEXT","INBOX"})
            assertNull(PushPolicy.body(true, kind));
    }
}
