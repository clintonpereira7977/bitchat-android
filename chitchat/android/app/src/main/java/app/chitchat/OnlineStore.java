package app.chitchat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import com.google.crypto.tink.Aead;
import org.json.*;
import java.io.*;
import java.util.*;

/** Only public contact pins and encrypted unopened deliveries persist. Opened
 * plaintext, drafts and transcripts are never written to disk. */
final class OnlineStore {
    static final String PREFS="chitchat_online";
    final SharedPreferences prefs;
    private final AtomicFile file;
    private final Aead storage;
    private JSONObject pending, seen;
    OnlineStore(Context context,Aead storage) throws Exception {
        prefs=context.getSharedPreferences(PREFS,0);this.storage=storage;
        file=new AtomicFile(new File(context.getNoBackupFilesDir(),"online-pending-v1"));
        JSONObject saved=file.getBaseFile().exists()?new JSONObject(new String(storage.decrypt(file.readFully(),OnlineCrypto.utf8("ChitChat-pending/v1")),java.nio.charset.StandardCharsets.UTF_8)):new JSONObject();
        pending=saved.optJSONObject("pending");if(pending==null)pending=new JSONObject();
        seen=saved.optJSONObject("seen");if(seen==null)seen=new JSONObject();purge();
    }
    boolean registered(){return prefs.getBoolean("registered",false);}
    void registered(String nickname){prefs.edit().putBoolean("registered",true).putString("nickname",nickname).commit();}
    static boolean lastOnline(Context c){return c.getSharedPreferences(PREFS,0).getBoolean("last_online",false);}
    static void lastOnline(Context c,boolean value){c.getSharedPreferences(PREFS,0).edit().putBoolean("last_online",value).commit();}
    synchronized JSONObject pins() throws JSONException {return new JSONObject(prefs.getString("pins","{}"));}
    synchronized void pin(OnlineCrypto.Contact contact) throws Exception {
        JSONObject pins=pins(),previous=pins.optJSONObject(contact.id);
        if(previous!=null&&!new OnlineCrypto.Contact(previous).sameKey(contact))throw new java.security.GeneralSecurityException("Contact key changed");
        pins.put(contact.id,contact.profile);prefs.edit().putString("pins",pins.toString()).commit();
    }
    synchronized void forget(String id) throws Exception {JSONObject pins=pins();pins.remove(id);prefs.edit().putString("pins",pins.toString()).commit();for(String key:keys(pending))if(id.equals(pending.getJSONObject(key).getString("sender")))pending.remove(key);save();}
    synchronized OnlineCrypto.Contact pinned(String id) throws Exception {JSONObject p=pins().optJSONObject(id);return p==null?null:new OnlineCrypto.Contact(p);}
    synchronized boolean stage(JSONObject message) throws Exception {
        purge();String id=message.getString("id");OnlineCrypto.checkMessageId(id);
        if(seen.has(id))return true;
        if(pending.has(id)){
            JSONObject old=pending.getJSONObject(id);
            if(!old.getString("envelope").equals(message.getString("envelope"))||!old.getString("sender").equals(message.getString("sender")))throw new java.security.GeneralSecurityException("Conflicting delivery");
            return true;
        }
        if(pending.length()>=200||seen.length()>=20000)return false;
        long expires=message.getLong("expires"),now=System.currentTimeMillis();
        if(expires<=now)return true;
        if(expires>now+90000000)throw new java.security.GeneralSecurityException("Invalid delivery expiry");
        pending.put(id,new JSONObject(message.toString()));save();return true;
    }
    synchronized List<JSONObject> pending(String sender) throws Exception {
        purge();List<JSONObject> messages=new ArrayList<>();for(String key:keys(pending)){JSONObject m=pending.getJSONObject(key);if(sender.equals(m.getString("sender")))messages.add(new JSONObject(m.toString()));}
        Collections.sort(messages,(a,b)->{int c=Long.compare(a.optLong("created"),b.optLong("created"));return c!=0?c:a.optString("id").compareTo(b.optString("id"));});return messages;
    }
    synchronized int count(String sender) throws Exception{return pending(sender).size();}
    synchronized void consumed(String id) throws Exception {
        JSONObject m=pending.optJSONObject(id);if(m==null)return;
        if(seen.length()>=20000)throw new IOException("Opened-message limit reached");
        // Do not let an untrusted relay shorten replay protection by supplying
        // an early expiry. Retain only the ID, for longer than the accepted
        // signed message-age window; never evict unexpired replay entries.
        seen.put(id,System.currentTimeMillis()+90000000);pending.remove(id);save();
    }
    private void purge() throws Exception {
        long now=System.currentTimeMillis();boolean dirty=false;
        for(String id:keys(pending))if(pending.getJSONObject(id).getLong("expires")<=now){pending.remove(id);dirty=true;}
        for(String id:keys(seen))if(seen.optLong(id)<=now){seen.remove(id);dirty=true;}
        if(dirty)save();
    }
    private void save() throws Exception {
        byte[] plaintext=OnlineCrypto.utf8(new JSONObject().put("pending",pending).put("seen",seen).toString());
        try{OnlineIdentity.write(file,storage.encrypt(plaintext,OnlineCrypto.utf8("ChitChat-pending/v1")));}finally{Arrays.fill(plaintext,(byte)0);}
    }
    synchronized void clear(){pending=new JSONObject();seen=new JSONObject();file.delete();prefs.edit().clear().commit();}
    private static List<String> keys(JSONObject object){List<String> keys=new ArrayList<>();Iterator<String> it=object.keys();while(it.hasNext())keys.add(it.next());return keys;}
}
