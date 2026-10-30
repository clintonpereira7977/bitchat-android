package app.chitchat;

import okhttp3.*;
import org.json.JSONObject;
import java.io.*;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/** Signed transport. No cookies, HTTP logging, redirects or automatic retries
 * of mutations. An ambiguous send can be retried with the SAME id/envelope. */
final class OnlineApi {
    static final String PRODUCTION="https://chitchat-online.clintonpereira7977.workers.dev";
    final OnlineCrypto crypto;
    final String origin;
    final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    OnlineApi(OnlineCrypto crypto,String origin){
        if(!PRODUCTION.equals(origin)&&!(BuildConfig.DEBUG&&origin.matches("http://(127\\.0\\.0\\.1|10\\.0\\.2\\.2):[0-9]{2,5}")))throw new IllegalArgumentException("Unapproved service");
        this.crypto=crypto;this.origin=origin;
    }
    JSONObject request(String method,String path,JSONObject input) throws Exception {return request(method,path,input,null);}
    JSONObject request(String method,String path,JSONObject input,String invite) throws Exception {
        if(!path.startsWith("/v1/")||path.contains("\n")||path.contains("#"))throw new IllegalArgumentException("Invalid path");
        String body=input==null?"":input.toString(),timestamp=String.valueOf(System.currentTimeMillis());byte[] random=new byte[16];new SecureRandom().nextBytes(random);String nonce=OnlineCrypto.b64(random);
        String url=origin+path;
        String signed=String.join("\n","ChitChat-delivery/v1",method,url,timestamp,nonce,OnlineCrypto.b64(OnlineCrypto.hash(OnlineCrypto.utf8(body))));
        Request.Builder builder=new Request.Builder().url(url).header("Content-Type","application/json")
                .header("X-ChitChat-Key",crypto.rawKey).header("X-ChitChat-Time",timestamp)
                .header("X-ChitChat-Nonce",nonce).header("X-ChitChat-Signature",crypto.sign(signed));
        if(invite!=null)builder.header("X-ChitChat-Invite",invite);
        builder.method(method,input==null?null:RequestBody.create(body,MediaType.get("application/json; charset=utf-8")));
        try(Response response=http.newCall(builder.build()).execute()){
            if(response.body()==null)throw new IOException("Service returned no response");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream stream=response.body().byteStream();byte[] buffer=new byte[4096];int n;
            while((n=stream.read(buffer))!=-1){if(bytes.size()+n>262144)throw new IOException("Response too large");bytes.write(buffer,0,n);}
            JSONObject result;
            try{result=new JSONObject(bytes.toString("UTF-8"));}catch(Exception e){throw new IOException("Service returned an invalid response");}
            if(!response.isSuccessful())throw new Failure(response.code(),result.optString("error","service_error"));
            return result;
        }
    }
    WebSocket events(String ticket,WebSocketListener listener){return http.newWebSocket(new Request.Builder().url(origin.replaceFirst("^http","ws")+"/v1/events").header("Sec-WebSocket-Protocol","chitchat.v1, ticket."+ticket).build(),listener);}
    void cancel(){http.dispatcher().cancelAll();}
    static final class Failure extends IOException {
        final int status;final String code;Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    static String friendly(Exception error){
        if(error instanceof Failure){String code=((Failure)error).code;
            switch(code){
                case "invite_required": return "That invitation phrase didn’t match. Ask the person running this pilot.";
                case "pilot_registration_not_configured": return "This pilot isn’t accepting new people yet.";
                case "authentication_required": return "Couldn’t authenticate. Check your phone’s automatic date and time, then try again.";
                case "not_found": return "That ID or request wasn’t found. Check the eight-character ID.";
                case "contact_approval_required": return "Your friend needs to accept your request before you can chat.";
                case "contact_not_available": return "This connection is unavailable.";
                case "pilot_limit_reached":case "pilot_account_limit": return "The pilot’s current limit has been reached. Try again later.";
                case "inbox_full":case "queue_full": return "The delivery queue is full. Try again after your friend opens the app.";
                default:return "The service couldn’t complete that action. Try again shortly.";
            }
        }
        if(error instanceof java.security.GeneralSecurityException)return "A security check failed. This contact’s keys may have changed. No message was opened.";
        return "Couldn’t reach ChitChat. Check your internet connection and try again.";
    }
}
