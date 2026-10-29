package app.chitchat;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Bounded, sequential 20-byte BLE framing. No negotiated MTU dependency. */
public final class Wire {
    public static final int MAX = 4096;
    public static List<byte[]> split(byte[] packet) {
        if (packet.length == 0 || packet.length > MAX) throw new IllegalArgumentException("Packet size");
        List<byte[]> out = new ArrayList<>();
        for (int offset=0, seq=0; offset<packet.length; offset+=17, seq++) {
            int n=Math.min(17, packet.length-offset);
            byte[] f=new byte[n+3]; f[0]=(byte)((offset==0?1:0)|(offset+n==packet.length?2:0));
            f[1]=(byte)(seq>>8); f[2]=(byte)seq;
            System.arraycopy(packet,offset,f,3,n); out.add(f);
        }
        return out;
    }
    public static final class Decoder {
        private ByteArrayOutputStream buffer; private int next; private long started;
        public byte[] accept(byte[] f, long now) {
            if(f==null || f.length<4 || f.length>20 || (f[0]&~3)!=0) { reset(); throw new IllegalArgumentException("Invalid fragment"); }
            int seq=((f[1]&255)<<8)|(f[2]&255);
            if((f[0]&1)!=0) { reset(); if(seq!=0) throw new IllegalArgumentException("Bad start"); buffer=new ByteArrayOutputStream(); started=now; }
            if(buffer==null || seq!=next || now-started>15000 || buffer.size()+f.length-3>MAX) { reset(); throw new IllegalArgumentException("Incomplete packet; reconnect"); }
            buffer.write(f,3,f.length-3); next++;
            if((f[0]&2)!=0) { byte[] result=buffer.toByteArray(); reset(); return result; }
            return null;
        }
        public void reset() { buffer=null; next=0; started=0; }
    }
}
