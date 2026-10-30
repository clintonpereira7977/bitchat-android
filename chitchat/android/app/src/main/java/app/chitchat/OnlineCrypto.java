package app.chitchat;

import com.google.crypto.tink.*;
import com.google.crypto.tink.hybrid.*;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;

/** Online/v1: RFC9180 HPKE from Tink, with separate P256 signatures for sender
 * authentication and signed public bundles. No custom encryption primitive.
 * Static recipient keys do NOT provide Signal-style forward secrecy. */
final class OnlineCrypto {
    static final String PROTOCOL = "ChitChat-online/v1";
    static final HpkeParameters PARAMETERS = parameters();
    private static HpkeParameters parameters() {
        try {return HpkeParameters.builder().setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256).setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.NO_PREFIX).build();}
        catch(GeneralSecurityException e){throw new ExceptionInInitializerError(e);}
    }
    final KeyPair signing;
    final KeysetHandle encryption;
    final String id, code, rawKey, publicKeyset;
    OnlineCrypto(KeyPair signing, KeysetHandle encryption) throws Exception {
        HybridConfig.register(); this.signing=signing; this.encryption=encryption;
        rawKey=b64(raw(signing.getPublic())); id=b64(hash(unb64(rawKey,65,65)));
        code=code(hash(unb64(rawKey,65,65)));
        publicKeyset=b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryption.getPublicKeysetHandle()));
    }
    static KeysetHandle newEncryptionKey() throws GeneralSecurityException {
        HybridConfig.register(); return KeysetHandle.generateNew(PARAMETERS);
    }
    JSONObject bundle() throws Exception {
        return new JSONObject().put("protocol",PROTOCOL).put("keyset",publicKeyset)
                .put("signature",sign("ChitChat-key/v1\n"+id+"\n"+publicKeyset));
    }
    JSONObject profile(String name) throws Exception {
        return new JSONObject().put("id",id).put("code",code).put("name",name)
                .put("identityKey",rawKey).put("keyBundle",bundle());
    }
    String sign(String value) throws Exception {
        Signature signature=Signature.getInstance("SHA256withECDSA");
        signature.initSign(signing.getPrivate()); signature.update(utf8(value));
        return b64(derToRaw(signature.sign()));
    }
    static final class Contact {
        final String id,code,name,key,keyset,fingerprint;
        final PublicKey verifier;
        final HybridEncrypt encryptor;
        final JSONObject profile;
        Contact(JSONObject input) throws Exception {
            profile=new JSONObject(input.toString()); id=profile.getString("id");code=profile.getString("code");
            name=profile.optString("name","Friend").replaceAll("[\\p{Cntrl}]","");
            key=profile.getString("identityKey"); byte[] raw=unb64(key,65,65);
            if(!id.equals(b64(hash(raw))) || !code.equals(code(hash(raw)))) throw new GeneralSecurityException("Identity mismatch");
            verifier=publicKey(raw); JSONObject bundle=profile.getJSONObject("keyBundle");
            if(!PROTOCOL.equals(bundle.getString("protocol"))) throw new GeneralSecurityException("Incompatible contact");
            keyset=bundle.getString("keyset"); byte[] serialized=unb64(keyset,32,2048);
            verify(verifier,"ChitChat-key/v1\n"+id+"\n"+keyset,bundle.getString("signature"));
            KeysetHandle handle=TinkProtoKeysetFormat.parseKeysetWithoutSecret(serialized);
            if(handle.size()!=1 || !PARAMETERS.equals(handle.getPrimary().getKey().getParameters()))
                throw new GeneralSecurityException("Unsupported encryption key");
            encryptor=handle.getPrimitive(RegistryConfiguration.get(),HybridEncrypt.class);
            fingerprint=hex(hash(utf8(key+"\n"+keyset)));
        }
        boolean sameKey(Contact other) { return id.equals(other.id)&&key.equals(other.key)&&keyset.equals(other.keyset); }
    }
    String seal(Contact recipient,String messageId,String text) throws Exception {
        if(text==null || utf8(text).length>2000 || text.isEmpty()) throw new IllegalArgumentException("Message length");
        checkMessageId(messageId);
        JSONObject body=new JSONObject().put("v",1).put("id",messageId).put("from",id)
                .put("to",recipient.id).put("at",System.currentTimeMillis()).put("text",text);
        byte[] plaintext=utf8(body.toString());
        String ciphertext;
        try { ciphertext=b64(recipient.encryptor.encrypt(plaintext,context(id,recipient.id,messageId))); }
        finally { Arrays.fill(plaintext,(byte)0); }
        return b64(utf8(new JSONObject().put("v",1).put("c",ciphertext)
                .put("s",sign(envelopeText(id,recipient.id,messageId,ciphertext))).toString()));
    }
    String open(Contact sender,String messageId,String envelope) throws Exception {
        checkMessageId(messageId);
        JSONObject outer=new JSONObject(new String(unb64(envelope,48,8192),StandardCharsets.UTF_8));
        if(outer.getInt("v")!=1) throw new GeneralSecurityException("Unsupported envelope");
        String ciphertext=outer.getString("c");
        verify(sender.verifier,envelopeText(sender.id,id,messageId,ciphertext),outer.getString("s"));
        byte[] plaintext=encryption.getPrimitive(RegistryConfiguration.get(),HybridDecrypt.class)
                .decrypt(unb64(ciphertext,48,6000),context(sender.id,id,messageId));
        try {
            JSONObject body=new JSONObject(new String(plaintext,StandardCharsets.UTF_8));
            long at=body.getLong("at"), now=System.currentTimeMillis(); String text=body.getString("text");
            if(body.getInt("v")!=1 || !id.equals(body.getString("to")) || !sender.id.equals(body.getString("from"))
                    || !messageId.equals(body.getString("id")) || at>now+300000 || at<now-86700000
                    || text.isEmpty() || utf8(text).length>2000) throw new GeneralSecurityException("Invalid message");
            return text;
        } finally { Arrays.fill(plaintext,(byte)0); }
    }
    private static String envelopeText(String from,String to,String id,String ciphertext) {
        return "ChitChat-envelope/v1\n"+from+"\n"+to+"\n"+id+"\n"+ciphertext;
    }
    private static byte[] context(String from,String to,String id) { return utf8("ChitChat-HPKE/v1\n"+from+"\n"+to+"\n"+id); }
    static void verify(PublicKey key,String text,String signature) throws Exception {
        Signature verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(key);verifier.update(utf8(text));
        if(!verifier.verify(rawToDer(unb64(signature,64,64)))) throw new GeneralSecurityException("Signature failed");
    }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static byte[] hash(byte[] data) throws GeneralSecurityException { return MessageDigest.getInstance("SHA-256").digest(data); }
    static String b64(byte[] data) { return Base64.getUrlEncoder().withoutPadding().encodeToString(data); }
    static byte[] unb64(String text,int min,int max) throws GeneralSecurityException {
        try {
            if(text==null || text.length()>max*4/3+4 || !text.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(text);
            if(bytes.length<min || bytes.length>max || !b64(bytes).equals(text)) throw new IllegalArgumentException();
            return bytes;
        } catch(IllegalArgumentException e) { throw new GeneralSecurityException("Invalid encoding"); }
    }
    static String code(byte[] digest) {
        String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";StringBuilder result=new StringBuilder(); int bits=0,buffer=0;
        for(byte b:digest) { buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5&&result.length()<8){bits-=5;result.append(alphabet.charAt((buffer>>>bits)&31));}if(result.length()==8)break; }
        return result.toString();
    }
    static String formattedCode(String code) { return code.substring(0,4)+"-"+code.substring(4); }
    static String hex(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02X",b&255));return out.toString(); }
    static void checkMessageId(String id) { if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid message id"); }
    static byte[] raw(PublicKey publicKey) {
        ECPublicKey key=(ECPublicKey)publicKey;byte[] raw=new byte[65];raw[0]=4;
        put32(key.getW().getAffineX(),raw,1);put32(key.getW().getAffineY(),raw,33);return raw;
    }
    private static void put32(BigInteger value,byte[] out,int offset) {byte[] b=value.toByteArray();int n=Math.min(b.length,32);System.arraycopy(b,b.length-n,out,offset+32-n,n);}
    static PublicKey publicKey(byte[] raw) throws Exception {
        if(raw.length!=65||raw[0]!=4)throw new GeneralSecurityException("Invalid identity");
        AlgorithmParameters params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1,Arrays.copyOfRange(raw,1,33)),new BigInteger(1,Arrays.copyOfRange(raw,33,65))),params.getParameterSpec(ECParameterSpec.class)));
    }
    static byte[] derToRaw(byte[] der) throws GeneralSecurityException {
        if(der.length<8||der.length>72||der[0]!=48||(der[1]&255)!=der.length-2)throw new GeneralSecurityException("Invalid signature");
        byte[] out=new byte[64];int p=2;
        for(int part=0;part<2;part++){
            if(p+2>der.length||der[p++]!=2)throw new GeneralSecurityException("Invalid signature");
            int n=der[p++]&255;if(n<1||n>33||p+n>der.length)throw new GeneralSecurityException("Invalid signature");
            if(n==33){if(der[p++]!=0)throw new GeneralSecurityException("Invalid signature");n--;}
            System.arraycopy(der,p,out,part*32+32-n,n);p+=n;
        }
        if(p!=der.length)throw new GeneralSecurityException("Invalid signature");return out;
    }
    static byte[] rawToDer(byte[] raw) throws GeneralSecurityException {
        if(raw.length!=64)throw new GeneralSecurityException("Invalid signature");
        byte[] r=new BigInteger(1,Arrays.copyOfRange(raw,0,32)).toByteArray(),s=new BigInteger(1,Arrays.copyOfRange(raw,32,64)).toByteArray();
        byte[] out=new byte[6+r.length+s.length];out[0]=48;out[1]=(byte)(out.length-2);out[2]=2;out[3]=(byte)r.length;
        System.arraycopy(r,0,out,4,r.length);out[4+r.length]=2;out[5+r.length]=(byte)s.length;System.arraycopy(s,0,out,6+r.length,s.length);return out;
    }
}
