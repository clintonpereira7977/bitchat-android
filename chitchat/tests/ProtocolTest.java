package app.chitchat;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProtocolTest {
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean ok,String label) { checks++; if(!ok) throw new AssertionError(label); }
    static void reject(Action a,String label) throws Exception { boolean threw=false; try { a.run(); } catch(Exception e) { threw=true; } check(threw,label); }
    public static void main(String[] args) throws Exception {
        for(int trial=0;trial<30;trial++) {
            Session a=new Session(),b=new Session();
            String left=a.receiveHello(b.hello()),right=b.receiveHello(a.hello()); check(left.equals(right),"same verification code");
            check(left.matches("[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}"),"code format");
            String[] texts={"hello","नमस्ते दोस्त 👋","你好\nline two\t\"\\",String.join("",Collections.nCopies(1000,"x"))};
            for(String s:texts) {
                byte[] sealed=a.seal(s); Wire.Decoder decoder=new Wire.Decoder(); byte[] packet=null;
                for(byte[] f:Wire.split(sealed)) { check(f.length<=20,"MTU 23 compatibility"); packet=decoder.accept(f,1000); }
                check(s.equals(b.open(packet)),"encrypted fragment round trip");
                byte[] replay=packet; reject(()->b.open(replay),"replay rejected");
                check(s.equals(a.open(b.seal(s))),"bidirectional encryption");
            }
            byte[] tampered=a.seal("do not modify"); tampered[tampered.length-1]^=1;
            reject(()->b.open(tampered),"GCM authentication");
            byte[] reflection=a.seal("outbound only"); reject(()->a.open(reflection),"direction reflection rejected");
            reject(()->a.receiveHello(b.hello()),"duplicate hello rejected");
            a.destroy(); reject(()->a.seal("closed"),"destroyed session cannot send");
        }
        Session fresh=new Session(); reject(()->fresh.receiveHello(fresh.hello()),"reflected key rejected"); reject(()->fresh.seal("early"),"plaintext session disallowed");
        reject(()->new Session().receiveHello(new byte[66]),"malformed public key rejected");
        reject(()->Wire.split(new byte[4097]),"bounded frame size"); reject(()->Wire.split(new byte[0]),"empty frame rejected");
        Random random=new Random(42);
        for(int n=1;n<=4096;n+=13) {
            byte[] payload=new byte[n]; random.nextBytes(payload); Wire.Decoder d=new Wire.Decoder(); byte[] output=null;
            for(byte[] f:Wire.split(payload)) output=d.accept(f,100);
            check(Arrays.equals(payload,output),"arbitrary binary framing "+n);
        }
        List<byte[]> frames=Wire.split(new byte[40]);
        Wire.Decoder missing=new Wire.Decoder(); missing.accept(frames.get(0),0); reject(()->missing.accept(frames.get(2),1),"missing fragment rejected");
        Wire.Decoder timeout=new Wire.Decoder(); timeout.accept(frames.get(0),0); reject(()->timeout.accept(frames.get(1),15001),"stale fragments rejected");
        reject(()->new Wire.Decoder().accept(new byte[]{4,0,0,1},0),"unknown flags rejected");
        Wire.Decoder orphan=new Wire.Decoder(); reject(()->orphan.accept(new byte[]{2,0,0,1},0),"orphan fragment rejected");
        System.out.println("PASS: "+checks+" protocol checks (encryption, Unicode, framing, tampering, replay, bounds, timeout)");
    }
}
