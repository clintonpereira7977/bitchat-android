package app.chitchat;
import java.io.*;
import java.util.Base64;
public final class InteropHarness {
    public static void main(String[] args) throws Exception {
        Session s=new Session(); BufferedReader in=new BufferedReader(new InputStreamReader(System.in));
        System.out.println(Base64.getEncoder().encodeToString(s.hello()));
        System.out.println(s.receiveHello(Base64.getDecoder().decode(in.readLine())));
        System.out.println(s.open(Base64.getDecoder().decode(in.readLine())));
        System.out.println(Base64.getEncoder().encodeToString(s.seal("Hello from Java. नमस्ते 👋")));
    }
}
