package app.chitchat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Fresh P-256 ECDH per connection, HKDF-SHA256, AES-256-GCM, versioned encrypted sessions. */
public final class Session {
    private final KeyPair pair; private final byte[] pub;
    private byte[] key,sendKey,receiveKey; private final Set<String> seen=new HashSet<>();
    public Session() throws GeneralSecurityException {
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); pair=g.generateKeyPair();
        ECPublicKey p=(ECPublicKey)pair.getPublic(); pub=new byte[65]; pub[0]=4;
        System.arraycopy(fixed(p.getW().getAffineX()),0,pub,1,32); System.arraycopy(fixed(p.getW().getAffineY()),0,pub,33,32);
    }
    private static byte[] fixed(BigInteger i) { byte[] b=i.toByteArray(), o=new byte[32]; System.arraycopy(b,Math.max(0,b.length-32),o,Math.max(0,32-b.length),Math.min(32,b.length)); return o; }
    public byte[] hello() { byte[] b=new byte[66]; b[0]=3; System.arraycopy(pub,0,b,1,65); return b; }
    public String receiveHello(byte[] packet) throws GeneralSecurityException {
        if(key!=null || packet.length!=66 || packet[0]!=3 || packet[1]!=4) throw new GeneralSecurityException("Invalid handshake");
        byte[] other=Arrays.copyOfRange(packet,1,66);
        if(Arrays.equals(pub,other)) throw new GeneralSecurityException("Reflected handshake");
        AlgorithmParameters params=AlgorithmParameters.getInstance("EC"); params.init(new ECGenParameterSpec("secp256r1"));
        ECPoint point=new ECPoint(new BigInteger(1,Arrays.copyOfRange(other,1,33)),new BigInteger(1,Arrays.copyOfRange(other,33,65)));
        PublicKey remote=KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point,params.getParameterSpec(ECParameterSpec.class)));
        KeyAgreement agree=KeyAgreement.getInstance("ECDH"); agree.init(pair.getPrivate()); agree.doPhase(remote,true);
        boolean first=false; for(int i=0;i<65;i++) { if(pub[i]!=other[i]) { first=(pub[i]&255)<(other[i]&255); break; } }
        byte[] sorted=new byte[130]; System.arraycopy(first?pub:other,0,sorted,0,65); System.arraycopy(first?other:pub,0,sorted,65,65);
        byte[] salt=MessageDigest.getInstance("SHA-256").digest(sorted);
        byte[] secret=agree.generateSecret(), prk=hmac(salt,secret);
        key=hmac(prk,"ChitChat/v2/session\u0001".getBytes(StandardCharsets.UTF_8));
        sendKey=hmac(key,(first?"ChitChat/v2/low-to-high":"ChitChat/v2/high-to-low").getBytes(StandardCharsets.UTF_8));
        receiveKey=hmac(key,(first?"ChitChat/v2/high-to-low":"ChitChat/v2/low-to-high").getBytes(StandardCharsets.UTF_8));
        Arrays.fill(secret,(byte)0); Arrays.fill(prk,(byte)0);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(key); StringBuilder s=new StringBuilder();
        for(int i=0;i<6;i++) { if(i>0 && i%2==0) s.append(" "); s.append(String.format(Locale.ROOT,"%02X",hash[i]&255)); }
        return s.toString();
    }
    private static byte[] hmac(byte[] k,byte[] data) throws GeneralSecurityException { Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(k,"HmacSHA256")); return m.doFinal(data); }
    public byte[] seal(String text) throws GeneralSecurityException {
        if(key==null) throw new GeneralSecurityException("Handshake required");
        byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(sendKey,"AES"),new GCMParameterSpec(128,nonce)); c.updateAAD("ChitChat/v2".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=c.doFinal(text.getBytes(StandardCharsets.UTF_8)), out=new byte[13+encrypted.length]; out[0]=2;
        System.arraycopy(nonce,0,out,1,12); System.arraycopy(encrypted,0,out,13,encrypted.length); return out;
    }
    public String open(byte[] packet) throws GeneralSecurityException {
        if(key==null || packet.length<29 || packet[0]!=2) throw new GeneralSecurityException("Invalid encrypted packet");
        String nonce=Base64.getEncoder().encodeToString(Arrays.copyOfRange(packet,1,13));
        if(seen.contains(nonce) || seen.size()>=10000) throw new GeneralSecurityException("Replay or session limit; reconnect");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(receiveKey,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(packet,1,13))); c.updateAAD("ChitChat/v2".getBytes(StandardCharsets.UTF_8));
        String result=new String(c.doFinal(Arrays.copyOfRange(packet,13,packet.length)),StandardCharsets.UTF_8); seen.add(nonce); return result;
    }
    public void destroy() { if(key!=null) Arrays.fill(key,(byte)0); if(sendKey!=null) Arrays.fill(sendKey,(byte)0); if(receiveKey!=null) Arrays.fill(receiveKey,(byte)0); key=null; sendKey=null; receiveKey=null; seen.clear(); }
}
