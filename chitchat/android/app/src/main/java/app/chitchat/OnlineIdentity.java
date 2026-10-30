package app.chitchat;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import com.google.crypto.tink.*;
import com.google.crypto.tink.hybrid.HybridConfig;
import com.google.crypto.tink.integration.android.AndroidKeystore;
import java.io.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

/** Keystore-backed auth key and strictly encrypted HPKE keyset. No plaintext
 * fallback, cloud backup or recovery password is silently introduced. */
final class OnlineIdentity {
    static final String AUTH="chitchat-online-auth-v1", WRAP="chitchat-online-wrap-v1";
    private final Context context;
    final OnlineCrypto crypto;
    final Aead storage;
    OnlineIdentity(Context context) throws Exception {
        this.context=context.getApplicationContext();HybridConfig.register();
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        File file=new File(context.getNoBackupFilesDir(),"online-keyset-v1");
        if(file.exists()&&(!keys.containsAlias(AUTH)||!keys.containsAlias(WRAP)))
            throw new GeneralSecurityException("Online identity key is unavailable. It was not reset.");
        if(!keys.containsAlias(AUTH)){
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC","AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(AUTH,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        if(!keys.containsAlias(WRAP))AndroidKeystore.generateNewAes256GcmKey(WRAP);
        storage=AndroidKeystore.getAead(WRAP);
        AtomicFile atomic=new AtomicFile(file);KeysetHandle handle;
        if(file.exists())handle=TinkProtoKeysetFormat.parseEncryptedKeyset(atomic.readFully(),storage,OnlineCrypto.utf8("ChitChat-keyset/v1"));
        else {
            if(context.getSharedPreferences(OnlineStore.PREFS,0).getBoolean("registered",false))throw new GeneralSecurityException("Online identity is unavailable");
            handle=OnlineCrypto.newEncryptionKey();
            write(atomic,TinkProtoKeysetFormat.serializeEncryptedKeyset(handle,storage,OnlineCrypto.utf8("ChitChat-keyset/v1")));
        }
        crypto=new OnlineCrypto(new KeyPair(keys.getCertificate(AUTH).getPublicKey(),(PrivateKey)keys.getKey(AUTH,null)),handle);
    }
    static void write(AtomicFile file,byte[] data) throws IOException {
        FileOutputStream out=null;
        try {out=file.startWrite();out.write(data);file.finishWrite(out);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}
    }
    void destroy() throws Exception {
        new AtomicFile(new File(context.getNoBackupFilesDir(),"online-keyset-v1")).delete();
        AndroidKeystore.deleteKey(WRAP);AndroidKeystore.deleteKey(AUTH);
    }
}
