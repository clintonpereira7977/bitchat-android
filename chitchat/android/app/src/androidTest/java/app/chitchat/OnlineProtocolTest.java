package app.chitchat;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class OnlineProtocolTest {
    static final String RELAY="http://127.0.0.1:8787",INVITE="synthetic-client-invite";
    static OnlineCrypto actor() throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));
        return new OnlineCrypto(g.generateKeyPair(),OnlineCrypto.newEncryptionKey());
    }
    static void register(OnlineApi api,String name) throws Exception {api.request("POST","/v1/register",new JSONObject().put("name",name).put("keyBundle",api.crypto.bundle()),INVITE);}
    interface Checked {void run()throws Exception;}
    static void rejected(Checked job) throws Exception {try{job.run();fail("Security failure expected");}catch(GeneralSecurityException|IllegalArgumentException expected){}}
    @Test public void signedBundlesBindCodeIdentityAndEncryptionKey() throws Exception {
        OnlineCrypto a=actor();JSONObject profile=a.profile("Synthetic A");OnlineCrypto.Contact contact=new OnlineCrypto.Contact(profile);
        assertEquals(a.id,contact.id);assertEquals(a.code,contact.code);assertEquals(64,contact.fingerprint.length());
        final JSONObject wrongCode=new JSONObject(profile.toString()).put("code","AAAAAAAA");rejected(()->new OnlineCrypto.Contact(wrongCode));
        JSONObject changed=new JSONObject(profile.toString());changed.getJSONObject("keyBundle").put("keyset",actor().publicKeyset);final JSONObject tampered=changed;rejected(()->new OnlineCrypto.Contact(tampered));
        for(int i=0;i<40;i++){String sig=a.sign("synthetic-signature-"+i);OnlineCrypto.verify(a.signing.getPublic(),"synthetic-signature-"+i,sig);}
    }
    @Test public void hpkeMessagesRequireCorrectSenderRecipientIdAndUntamperedCiphertext() throws Exception {
        OnlineCrypto a=actor(),b=actor(),c=actor();OnlineCrypto.Contact ac=new OnlineCrypto.Contact(a.profile("A")),bc=new OnlineCrypto.Contact(b.profile("B")),cc=new OnlineCrypto.Contact(c.profile("C"));
        String id=UUID.randomUUID().toString(),text="Synthetic hello नमस्ते 👋 <script>literal</script>";
        String sealed=a.seal(bc,id,text);assertEquals(text,b.open(ac,id,sealed));assertNotEquals(sealed,a.seal(bc,id,text));
        rejected(()->b.open(cc,id,sealed));rejected(()->c.open(ac,id,sealed));rejected(()->b.open(ac,UUID.randomUUID().toString(),sealed));
        JSONObject outer=new JSONObject(new String(OnlineCrypto.unb64(sealed,48,8192),java.nio.charset.StandardCharsets.UTF_8));byte[] bytes=OnlineCrypto.unb64(outer.getString("c"),48,6000);bytes[bytes.length-1]^=1;outer.put("c",OnlineCrypto.b64(bytes));String tampered=OnlineCrypto.b64(OnlineCrypto.utf8(outer.toString()));rejected(()->b.open(ac,id,tampered));
        rejected(()->a.seal(bc,id,String.join("",Collections.nCopies(2001,"x"))));
    }
    @Test public void identityReloadAndEncryptedPendingSpoolNeverStoreOpenedPlaintext() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();OnlineIdentity identity=new OnlineIdentity(context);OnlineStore store=new OnlineStore(context,identity.storage);
        try {
            String id=identity.crypto.id;OnlineIdentity restored=new OnlineIdentity(context);assertEquals(id,restored.crypto.id);assertEquals(identity.crypto.publicKeyset,restored.crypto.publicKeyset);assertNull(identity.crypto.signing.getPrivate().getEncoded());
            OnlineCrypto a=actor();OnlineCrypto.Contact ac=new OnlineCrypto.Contact(a.profile("Synthetic A")),recipient=new OnlineCrypto.Contact(identity.crypto.profile("Synthetic B"));store.pin(ac);
            String mid=UUID.randomUUID().toString(),text="SYNTHETIC_PRIVATE_TEXT_NOT_FOR_DISK";JSONObject message=new JSONObject().put("id",mid).put("sender",a.id).put("envelope",a.seal(recipient,mid,text)).put("created",System.currentTimeMillis()).put("expires",System.currentTimeMillis()+86400000);
            assertTrue(store.stage(message));assertTrue(store.stage(message));assertEquals(1,store.count(a.id));
            byte[] file=java.nio.file.Files.readAllBytes(new java.io.File(context.getNoBackupFilesDir(),"online-pending-v1").toPath());assertFalse(new String(file,java.nio.charset.StandardCharsets.UTF_8).contains(text));assertFalse(new String(file,java.nio.charset.StandardCharsets.UTF_8).contains(mid));
            OnlineStore reload=new OnlineStore(context,restored.storage);assertEquals(text,restored.crypto.open(ac,mid,reload.pending(a.id).get(0).getString("envelope")));reload.consumed(mid);assertEquals(0,reload.count(a.id));
            assertTrue(reload.stage(message));assertEquals(0,reload.count(a.id));
            JSONObject expired=new JSONObject(message.toString()).put("id",UUID.randomUUID().toString()).put("expires",System.currentTimeMillis()-1);assertTrue(reload.stage(expired));assertEquals(0,reload.count(a.id));
        } finally {store.clear();identity.destroy();OnlinePush.disable(context);}
    }
    @Test public void realCloudflareRelayAcceptsJavaSignaturesApprovalEncryptedDeliveryAndAck() throws Exception {
        OnlineCrypto a=actor(),b=actor();OnlineApi aa=new OnlineApi(a,RELAY),bb=new OnlineApi(b,RELAY);WebSocket as=null,bs=null;
        try{
            register(aa,"Native A");register(bb,"Native B");
            OnlineCrypto.Contact ac=new OnlineCrypto.Contact(aa.request("GET","/v1/me",null).getJSONObject("account"));
            OnlineCrypto.Contact bc=new OnlineCrypto.Contact(aa.request("GET","/v1/lookup/"+b.code,null).getJSONObject("account"));
            CountDownLatch opened=new CountDownLatch(2),request=new CountDownLatch(1),delivered=new CountDownLatch(1);
            as=aa.events(aa.request("POST","/v1/socket-ticket",new JSONObject()).getString("ticket"),new WebSocketListener(){@Override public void onOpen(WebSocket w,Response r){opened.countDown();}@Override public void onMessage(WebSocket w,String s){if(s.contains("\"delivered\""))delivered.countDown();}});
            bs=bb.events(bb.request("POST","/v1/socket-ticket",new JSONObject()).getString("ticket"),new WebSocketListener(){@Override public void onOpen(WebSocket w,Response r){opened.countDown();}@Override public void onMessage(WebSocket w,String s){if(s.contains("\"requests\""))request.countDown();}});
            assertTrue(opened.await(10,TimeUnit.SECONDS));
            String id=UUID.randomUUID().toString(),text="SYNTHETIC_RELAY_MESSAGE_नमस्ते";JSONObject send=new JSONObject().put("to",b.id).put("id",id).put("envelope",a.seal(bc,id,text));
            try{aa.request("POST","/v1/messages",send);fail("Approval required");}catch(OnlineApi.Failure e){assertEquals("contact_approval_required",e.code);}
            aa.request("POST","/v1/requests",new JSONObject().put("code",b.code));assertTrue(request.await(10,TimeUnit.SECONDS));
            assertEquals(1,bb.request("GET","/v1/requests",null).getJSONArray("requests").length());bb.request("POST","/v1/requests/"+a.id+"/accept",new JSONObject());
            assertEquals("queued",aa.request("POST","/v1/messages",send).getString("state"));
            JSONArray inbox=bb.request("GET","/v1/inbox",null).getJSONArray("messages");assertEquals(1,inbox.length());assertFalse(inbox.toString().contains(text));assertEquals(text,b.open(ac,id,inbox.getJSONObject(0).getString("envelope")));
            bb.request("POST","/v1/messages/"+id+"/ack",new JSONObject());assertTrue(delivered.await(10,TimeUnit.SECONDS));assertEquals(0,bb.request("GET","/v1/inbox",null).getJSONArray("messages").length());assertEquals("delivered",aa.request("POST","/v1/messages",send).getString("state"));
            bb.request("POST","/v1/contacts/"+a.id+"/block",new JSONObject());
            try{aa.request("POST","/v1/messages",new JSONObject(send.toString()).put("id",UUID.randomUUID().toString()));fail("Block required");}catch(OnlineApi.Failure e){assertEquals(403,e.status);}
        }finally{if(as!=null)as.cancel();if(bs!=null)bs.cancel();try{aa.request("DELETE","/v1/me",null);}catch(Exception ignored){}try{bb.request("DELETE","/v1/me",null);}catch(Exception ignored){}}
    }
}
