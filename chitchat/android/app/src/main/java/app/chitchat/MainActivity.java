package app.chitchat;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity implements BleLink.Listener {
    private static final int WHITE=Color.WHITE, DIM=0xFFB0B0B0, LINE=0xFF444444;
    private LinearLayout root,body,rooms,messages; private TextView status,chatLabel; private EditText input; private ScrollView scroll;
    private BleLink link; private Session session; private ConsentGate consent; private String nickname,friend="Friend", pendingAction="";
    private boolean active,hosting,chatting,pendingReopen,declinedResult; private int epoch;
    private AlertDialog requestDialog; private Button sendButton,searchAgain,cancelRequest; private TextView waitingTitle;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> receipts=new HashMap<>(); private final Set<String> received=new HashSet<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(OnlineStore.lastOnline(this)) { startActivity(new Intent(this,OnlineActivity.class)); finish(); return; }
        nickname=getPreferences(0).getString("nickname",""); link=new BleLink(this,this);
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        home(); if(nickname.isEmpty()) main.post(this::editName);
    }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }
    private TextView text(String s,int size,int color) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t; }
    private TextView mono(String s,int size,int color) { TextView t=text(s,size,color); t.setTypeface(Typeface.MONOSPACE); return t; }
    private GradientDrawable box(int fill,int stroke,int radius) { GradientDrawable b=new GradientDrawable(); b.setColor(fill); b.setCornerRadius(dp(radius)); b.setStroke(dp(1),stroke); return b; }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void space(LinearLayout l,int height) { l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height))); }
    private void rule(LinearLayout l) { View v=new View(this); v.setBackgroundColor(LINE); l.addView(v,new LinearLayout.LayoutParams(-1,dp(1))); }
    private Button button(String label,boolean filled,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(17); b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START); b.setPadding(dp(20),dp(10),dp(20),dp(10)); b.setTextColor(filled?Color.BLACK:WHITE); b.setBackground(box(filled?WHITE:Color.BLACK,filled?WHITE:LINE,14)); b.setMinHeight(dp(60)); b.setOnClickListener(v->action.run()); return b;
    }
    private void base(String section) {
        root=column(); root.setBackgroundColor(Color.BLACK); root.setPadding(dp(22),dp(12),dp(22),dp(16));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets k=insets.getInsets(WindowInsets.Type.ime()); root.setPadding(dp(22)+i.left,dp(12)+i.top,dp(22)+i.right,dp(16)+Math.max(i.bottom,k.bottom));
            } else root.setPadding(dp(22)+insets.getSystemWindowInsetLeft(),dp(12)+insets.getSystemWindowInsetTop(),dp(22)+insets.getSystemWindowInsetRight(),dp(16)+insets.getSystemWindowInsetBottom());
            return insets;
        }); root.requestApplyInsets();
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=mono("chitchat / "+section,13,WHITE); top.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1)); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView info=mono("ⓘ",24,WHITE); info.setGravity(Gravity.CENTER); info.setContentDescription("Help and privacy"); info.setOnClickListener(v->help()); top.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48))); root.addView(top); rule(root);
        body=column(); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void home() {
        base("nearby"); ScrollView page=new ScrollView(this); page.setFillViewport(true); body.addView(page,new LinearLayout.LayoutParams(-1,-1)); LinearLayout c=column(); page.addView(c);
        space(c,14); LinearLayout modes=new LinearLayout(this);
        Button nearby=button("Nearby",true,()->{});nearby.setGravity(Gravity.CENTER);modes.addView(nearby,new LinearLayout.LayoutParams(0,dp(50),1));
        Button online=button("Online",false,()->{OnlineStore.lastOnline(this,true);startActivity(new Intent(this,OnlineActivity.class).putExtra("nickname",nickname));finish();});online.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams modeParams=new LinearLayout.LayoutParams(0,dp(50),1);modeParams.leftMargin=dp(10);modes.addView(online,modeParams);c.addView(modes);
        space(c,28); c.addView(mono("OFFLINE. ON PURPOSE.",11,DIM)); space(c,22);
        TextView title=text("Small talk.\nZero signal.",49,WHITE); title.setLineSpacing(0,1.02f); title.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); c.addView(title);
        space(c,14); TextView sub=text("A little closer. A little quieter.\nJust you, your friend, and Bluetooth.",16,DIM); sub.setLineSpacing(dp(5),1); c.addView(sub); space(c,28);
        TextView glyph=mono("( (  ···  ) )",32,WHITE); glyph.setGravity(Gravity.CENTER); glyph.setPadding(0,dp(18),0,dp(22)); c.addView(glyph);
        c.addView(button("Open a room                 +",true,()->begin("host")),new LinearLayout.LayoutParams(-1,dp(64))); space(c,12);
        c.addView(button("Find a friend                   ↗",false,()->begin("find")),new LinearLayout.LayoutParams(-1,dp(64))); space(c,26);
        TextView name=mono("YOU / "+(nickname.isEmpty()?"set your nickname":nickname)+"   ✎",12,DIM); name.setPadding(0,dp(12),0,dp(12)); name.setOnClickListener(v->editName()); c.addView(name); rule(c); space(c,18);
        c.addView(text("No account. No internet.\nKeep both apps open while you chat.",13,DIM)); space(c,12);
        TextView share=mono("SHARE CHITCHAT WITH A FRIEND  ↗",12,WHITE); share.setPadding(0,dp(16),0,dp(16)); share.setOnClickListener(v->shareApp()); c.addView(share);
    }
    private void editName() {
        EditText n=new EditText(this); n.setText(nickname); n.setHint("e.g. Sweet Tooth"); n.setSingleLine(true); n.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)}); n.setPadding(dp(24),dp(16),dp(24),dp(16));
        new AlertDialog.Builder(this).setTitle("What should we call you?").setMessage("A nickname is enough. It's shared locally when you send or accept a request.").setView(n).setPositiveButton("That's me",(d,w)->{ nickname=n.getText().toString().trim(); if(nickname.isEmpty()) nickname="Sweet Tooth"; getPreferences(0).edit().putString("nickname",nickname).apply(); if(!active) home(); }).setNegativeButton("Later",null).show();
    }
    private void begin(String action) {
        pendingAction=action;
        String[] perms=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        ArrayList<String> missing=new ArrayList<>(); for(String p:perms) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) missing.add(p);
        if(!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]),10); return; }
        if(!link.enabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),11); } catch(Exception e) { notice("Turn on Bluetooth in Settings, then try again."); } return;
        }
        link.stop(); link=new BleLink(this,this); resetSession(); hosting=action.equals("host"); active=true; getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); waiting(action);
        if(action.equals("host")) { byte[] b=new byte[2]; new SecureRandom().nextBytes(b); String r=String.format(Locale.ROOT,"%02X%02X",b[0]&255,b[1]&255); link.host(r); } else link.browse();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==10) { boolean ok=results.length>0; for(int n:results) ok&=n==PackageManager.PERMISSION_GRANTED; if(ok) begin(pendingAction); else new AlertDialog.Builder(this).setTitle("Bluetooth needs permission").setMessage("Allow Nearby devices in Settings → Apps → ChitChat → Permissions. Android 8–11 uses Location permission for Bluetooth discovery; ChitChat doesn't collect your location.").setPositiveButton("OK",null).show(); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); if(request==11 && result==RESULT_OK) begin(pendingAction); }
    private void waiting(String action) {
        base(action.equals("host")?"your room":"find a friend"); space(body,30); waitingTitle=text(action.equals("host")?"Room for two.":"Who's around?",36,WHITE); body.addView(waitingTitle); space(body,16);
        status=text("Starting Bluetooth…",16,DIM); status.setLineSpacing(dp(4),1); body.addView(status); space(body,24);
        body.addView(mono("( (  ·  ) )",32,WHITE)); space(body,24);
        ScrollView list=new ScrollView(this); rooms=column(); list.addView(rooms); body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        searchAgain=null;
        if(action.equals("find")) { searchAgain=button("Search again",false,()->{ rooms.removeAllViews(); link.browse(); }); body.addView(searchAgain); space(body,12); }
        cancelRequest=button("Back home",false,this::leave); body.addView(cancelRequest);
    }
    @Override public void status(String s) { if(status!=null) status.setText(s); }
    @Override public void found(String id,String title) {
        if(rooms==null) return; rooms.addView(button(title+"     Connect →",false,()->{
            if(searchAgain!=null) searchAgain.setEnabled(false);
            for(int i=0;i<rooms.getChildCount();i++) rooms.getChildAt(i).setEnabled(false);
            cancelRequest.setText("Cancel request"); link.join(id);
        }),new LinearLayout.LayoutParams(-1,dp(64))); space(rooms,12);
    }
    @Override public void connected() {
        try { session=new Session(); consent=new ConsentGate(hosting); link.send(session.hello()); int e=epoch; main.postDelayed(()->{ if(active && !chatting && epoch==e) { if(hosting) { begin("host"); notice("Request expired. Your room is open again."); } else closeWith("The connection request expired. Try again when you're both ready."); } },180000); }
        catch(Exception e) { closeWith("Couldn't create a secure session. Please reconnect."); }
    }
    @Override public void packet(byte[] p) {
        try {
            if(session==null || consent==null) throw new IllegalStateException();
            if(p[0]==1) { closeWith("This phone is using an older ChitChat protocol. Install the latest version on both phones."); return; }
            if(p[0]==3) {
                session.receiveHello(p); consent.keysReady();
                if(hosting) status("Waiting for a connection request…");
                else { waitingTitle.setText("Request sent."); rooms.removeAllViews(); status("Waiting for the room owner to accept…"); sendControl("request",true); }
                return;
            }
            JSONObject o=new JSONObject(session.open(p)); String t=o.getString("t");
            if(t.equals("request")) {
                consent.receiveRequest(); friend=safeName(o.getString("name")); status("Connection request from "+friend+".");
                requestDialog=new AlertDialog.Builder(this).setTitle("Connection request").setMessage(friend+" wants to chat.\n\nOnly accept a request you're expecting. Nicknames aren't verified identities.").setCancelable(false).setPositiveButton("Accept",(d,w)->acceptRequest()).setNegativeButton("Decline",(d,w)->declineRequest()).create(); requestDialog.show();
            } else if(t.equals("accept")) {
                consent.receiveAcceptance(); friend=safeName(o.getString("name")); sendControl("ready",false); maybeChat();
            } else if(t.equals("ready")) {
                consent.receiveReady(); maybeChat();
            } else if(t.equals("decline")) {
                consent.receiveDecline(); declinedResult=true; int e=epoch;
                link.sendThen(session.seal(new JSONObject().put("t","declined").toString()),()->{ if(active && epoch==e) showDeclined(); });
                main.postDelayed(()->{ if(active && epoch==e && declinedResult) showDeclined(); },3000);
            } else if(t.equals("declined")) {
                consent.receiveDeclineAck(); if(!pendingReopen) throw new IllegalStateException(); reopenRoom();
            } else if(t.equals("message")) {
                consent.requireChat(); if(!chatting) throw new IllegalArgumentException(); String id=o.getString("id"), content=o.getString("text"); UUID.fromString(id);
                if(content.trim().isEmpty() || content.getBytes(StandardCharsets.UTF_8).length>1000 || received.size()>=500) throw new IllegalArgumentException();
                if(received.add(id)) addMessage(content,false,null);
                link.send(session.seal(new JSONObject().put("t","ack").put("id",id).toString()));
            } else if(t.equals("ack")) {
                consent.requireChat(); if(!chatting) throw new IllegalArgumentException(); TextView r=receipts.remove(o.getString("id")); if(r!=null) r.setText("delivered  ✓");
            } else throw new IllegalArgumentException();
        } catch(Exception e) { closeWith("The connection request couldn't be completed. Try again with the latest ChitChat on both phones."); }
    }
    private String safeName(String name) { String n=name.replaceAll("[\\p{Cntrl}]","").trim(); int count=n.codePointCount(0,n.length()); if(count>24) n=n.substring(0,n.offsetByCodePoints(0,24)); return n.isEmpty()?"Friend":n; }
    private void sendControl(String type,boolean name) throws Exception {
        JSONObject o=new JSONObject().put("t",type); if(name) o.put("name",nickname.isEmpty()?"Sweet Tooth":nickname); link.send(session.seal(o.toString()));
    }
    private void acceptRequest() {
        try { consent.accept(); status("Accepted. Opening your chat…"); sendControl("accept",true); }
        catch(Exception e) { closeWith("Couldn't accept this request. Please try again."); }
    }
    private void declineRequest() {
        try { consent.decline(); pendingReopen=true; status("Request declined. Reopening your room…"); sendControl("decline",false); int e=epoch; main.postDelayed(()->{ if(active && epoch==e && pendingReopen) begin("host"); },3000); }
        catch(Exception e) { begin("host"); }
    }
    private void showDeclined() { leave(); new AlertDialog.Builder(this).setTitle("Request declined").setMessage("The room owner declined your request. You can try another room.").setPositiveButton("OK",null).show(); }
    private void reopenRoom() {
        int e=epoch; link.stop(); if(requestDialog!=null) requestDialog.dismiss(); requestDialog=null;
        status("Your room is reopening…");
        main.postDelayed(()->{ if(active && hosting && epoch==e) begin("host"); },250);
    }
    private void maybeChat() {
        if(consent==null || !consent.canChat()) return;
        if(chatting) return; chatting=true; base("conversation"); space(body,16);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); chatLabel=text(friend,26,WHITE); row.addView(chatLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button end=button("Leave",false,this::confirmLeave); end.setTextSize(13); row.addView(end,new LinearLayout.LayoutParams(dp(90),dp(52))); body.addView(row); space(body,8);
        TextView badge=mono("BLUETOOTH · ENCRYPTED",10,DIM); badge.setOnClickListener(v->notice("Encrypted nearby chat. Approval doesn't verify a person's identity.")); body.addView(badge); space(body,14); rule(body);
        scroll=new ScrollView(this); scroll.setFillViewport(true); messages=column(); messages.setPadding(0,dp(12),0,dp(12)); scroll.addView(messages); body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView beginning=mono("Just the two of you. Say something sweet.",11,DIM); beginning.setGravity(Gravity.CENTER); beginning.setPadding(0,dp(20),0,dp(20)); messages.addView(beginning);
        LinearLayout composer=new LinearLayout(this); composer.setGravity(Gravity.BOTTOM);
        input=new EditText(this); input.setHint("A little hello…"); input.setHintTextColor(DIM); input.setTextColor(WHITE); input.setTextSize(16); input.setMaxLines(4); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)}); input.setPadding(dp(14),dp(12),dp(14),dp(12)); input.setBackground(box(Color.BLACK,LINE,14)); composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        sendButton=button("↑",true,this::sendMessage); sendButton.setTextSize(25); sendButton.setGravity(Gravity.CENTER); sendButton.setContentDescription("Send message"); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(58),dp(58)); bp.leftMargin=dp(10); composer.addView(sendButton,bp); body.addView(composer); space(body,8);
        body.addView(mono("Messages disappear when you leave.",10,DIM));
    }
    private void sendMessage() {
        if(!chatting || consent==null || !consent.canChat()) return;
        String content=input.getText().toString().trim(); if(content.isEmpty()) return;
        if(content.getBytes(StandardCharsets.UTF_8).length>1000) { notice("This message is too long. Try a shorter note."); return; }
        if(receipts.size()>=8 || messages.getChildCount()>=501) { notice("Wait for delivery, or start a new room if this session is full."); return; }
        try {
            String id=UUID.randomUUID().toString(); TextView receipt=addMessage(content,true,id); receipts.put(id,receipt);
            byte[] packet=session.seal(new JSONObject().put("t","message").put("id",id).put("text",content).toString()); if(packet.length>Wire.MAX) throw new IllegalArgumentException();
            input.setText(""); link.send(packet); int e=epoch;
            main.postDelayed(()->{ if(epoch==e && receipts.containsKey(id)) receipt.setText("unconfirmed · reconnect if needed"); },20000);
        } catch(Exception e) { closeWith("Message couldn't be sent. Reconnect and try again."); }
    }
    private TextView addMessage(String content,boolean own,String id) {
        LinearLayout block=column(); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2); p.gravity=own?Gravity.END:Gravity.START; p.topMargin=dp(8); p.bottomMargin=dp(8); p.leftMargin=own?dp(36):0; p.rightMargin=own?0:dp(36); messages.addView(block,p);
        TextView bubble=text(content,17,own?Color.BLACK:WHITE); bubble.setTextIsSelectable(true); bubble.setPadding(dp(15),dp(12),dp(15),dp(12)); bubble.setBackground(box(own?WHITE:Color.BLACK,own?WHITE:LINE,16)); block.addView(bubble);
        String time=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()); TextView receipt=mono(own?"sending…":time,10,DIM); receipt.setPadding(dp(3),dp(6),dp(3),0); receipt.setGravity(own?Gravity.END:Gravity.START); block.addView(receipt); scroll.post(()->scroll.smoothScrollTo(0,messages.getBottom())); return receipt;
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("Less signal. More connection.").setMessage("1. Both install the latest ChitChat and enable Bluetooth.\n2. One opens a room; the other taps Connect to send a request.\n3. The room owner taps Accept or Decline. Accept opens the chat on both phones.\n4. Chat nearby with both apps on screen.\n\nNo internet, account, contacts, or server. Direct Bluetooth only: no mesh, long-distance delivery, photos, or calls.\n\nMessages are encrypted. Approval gives permission to chat; it doesn't verify a person's identity. Only accept requests you expect.\n\nMessages stay in memory and disappear when you leave or the app closes. Your keyboard and phone can still retain data.\n\nStart within 1–2 metres. Range varies by phone and obstacles. This version disconnects when backgrounded.\n\nChitChat uses its own protocol and doesn't connect to BitChat.").setPositiveButton("Got it",null).show();
    }
    private void notice(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void shareApp() {
        android.net.Uri uri=android.net.Uri.parse("content://app.chitchat.apk/ChitChat.apk");
        Intent share=new Intent(Intent.ACTION_SEND); share.setType("application/vnd.android.package-archive"); share.putExtra(Intent.EXTRA_STREAM,uri); share.setClipData(ClipData.newUri(getContentResolver(),"ChitChat APK",uri)); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(share,"Send ChitChat to your friend's Android")); } catch(Exception e) { notice("Use your phone's Files app to share the original APK instead."); }
    }
    private void resetSession() { epoch++; if(requestDialog!=null) requestDialog.dismiss(); requestDialog=null; if(session!=null) session.destroy(); session=null; consent=null; pendingReopen=false; declinedResult=false; chatting=false; receipts.clear(); received.clear(); }
    private void leave() { link.stop(); active=false; resetSession(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0); home(); }
    private void closeWith(String reason) { leave(); new AlertDialog.Builder(this).setTitle("Room closed").setMessage(reason+"\n\nThis session's messages have been cleared.").setPositiveButton("OK",null).show(); }
    @Override public void closed(String reason) {
        if(declinedResult) { showDeclined(); return; }
        if(hosting && active && consent!=null && !consent.canChat()) { try { if(link.enabled()) { reopenRoom(); return; } } catch(Exception ignored) {} }
        closeWith(reason);
    }
    @Override public void disconnected(String reason) {
        if(hosting && active && !chatting) { try { if(link.enabled()) { reopenRoom(); return; } } catch(Exception ignored) {} }
        closed(reason);
    }
    private void confirmLeave() { new AlertDialog.Builder(this).setTitle("Leave this room?").setMessage("The connection will close and this phone's messages will disappear.").setPositiveButton("Leave",(d,w)->leave()).setNegativeButton("Stay",null).show(); }
    @Override public void onBackPressed() { if(active) confirmLeave(); else super.onBackPressed(); }
    @Override protected void onStop() { super.onStop(); if(active) { leave(); } }
    @Override protected void onDestroy() { if(link!=null)link.stop(); resetSession(); super.onDestroy(); }
}
