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
    private BleLink link; private Session session; private String nickname,code,friend="Friend", pendingAction="";
    private boolean active,localApproved,peerApproved,chatting; private int epoch;
    private AlertDialog verification; private Button sendButton;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> receipts=new HashMap<>(); private final Set<String> received=new HashSet<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); nickname=getPreferences(0).getString("nickname",""); link=new BleLink(this,this);
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
        new AlertDialog.Builder(this).setTitle("What should we call you?").setMessage("A nickname is enough. It stays on your phone until you approve a connection.").setView(n).setPositiveButton("That's me",(d,w)->{ nickname=n.getText().toString().trim(); if(nickname.isEmpty()) nickname="Sweet Tooth"; getPreferences(0).edit().putString("nickname",nickname).apply(); if(!active) home(); }).setNegativeButton("Later",null).show();
    }
    private void begin(String action) {
        pendingAction=action;
        String[] perms=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        ArrayList<String> missing=new ArrayList<>(); for(String p:perms) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) missing.add(p);
        if(!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]),10); return; }
        if(!link.enabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),11); } catch(Exception e) { notice("Turn on Bluetooth in Settings, then try again."); } return;
        }
        link.stop(); link=new BleLink(this,this); resetSession(); active=true; getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); waiting(action);
        if(action.equals("host")) { byte[] b=new byte[2]; new SecureRandom().nextBytes(b); String r=String.format(Locale.ROOT,"%02X%02X",b[0]&255,b[1]&255); link.host(r); } else link.browse();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==10) { boolean ok=results.length>0; for(int n:results) ok&=n==PackageManager.PERMISSION_GRANTED; if(ok) begin(pendingAction); else new AlertDialog.Builder(this).setTitle("Bluetooth needs permission").setMessage("Allow Nearby devices in Settings → Apps → ChitChat → Permissions. Android 8–11 uses Location permission for Bluetooth discovery; ChitChat doesn't collect your location.").setPositiveButton("OK",null).show(); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) { super.onActivityResult(request,result,data); if(request==11 && result==RESULT_OK) begin(pendingAction); }
    private void waiting(String action) {
        base(action.equals("host")?"your room":"find a friend"); space(body,30); body.addView(text(action.equals("host")?"Room for two.":"Who's around?",36,WHITE)); space(body,16);
        status=text("Starting Bluetooth…",16,DIM); status.setLineSpacing(dp(4),1); body.addView(status); space(body,24);
        body.addView(mono("( (  ·  ) )",32,WHITE)); space(body,24);
        ScrollView list=new ScrollView(this); rooms=column(); list.addView(rooms); body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        if(action.equals("find")) { body.addView(button("Search again",false,()->{ rooms.removeAllViews(); link.browse(); })); space(body,12); }
        body.addView(button("Back home",false,this::leave));
    }
    @Override public void status(String s) { if(status!=null) status.setText(s); }
    @Override public void found(String id,String title) {
        if(rooms==null) return; rooms.addView(button(title+"     →",false,()->link.join(id)),new LinearLayout.LayoutParams(-1,dp(64))); space(rooms,12);
    }
    @Override public void connected() {
        try { session=new Session(); link.send(session.hello()); int e=epoch; main.postDelayed(()->{ if(active && !chatting && epoch==e) closeWith("Verification timed out. Start a new room when you're both ready."); },180000); }
        catch(Exception e) { closeWith("Couldn't create a secure session. Please reconnect."); }
    }
    @Override public void packet(byte[] p) {
        try {
            if(session==null) throw new IllegalStateException();
            if(p[0]==1) {
                code=session.receiveHello(p); verification=new AlertDialog.Builder(this).setTitle("Same little secret?").setMessage("Compare this code on both phones:\n\n"+code+"\n\nOnly tap Match if every character is identical. Different codes? Disconnect.").setCancelable(false).setPositiveButton("Codes match",(d,w)->{
                    try { localApproved=true; link.send(session.seal(new JSONObject().put("t","approve").put("name",nickname.isEmpty()?"Sweet Tooth":nickname).toString())); maybeChat(); }
                    catch(Exception e) { closeWith("Couldn't verify this connection."); }
                }).setNegativeButton("Disconnect",(d,w)->leave()).create(); verification.show(); return;
            }
            JSONObject o=new JSONObject(session.open(p)); String t=o.getString("t");
            if(t.equals("approve")) {
                if(peerApproved) throw new IllegalArgumentException(); peerApproved=true; friend=o.getString("name").replaceAll("[\\p{Cntrl}]",""); if(friend.length()>24) friend=friend.substring(0,24); if(friend.isEmpty()) friend="Friend"; maybeChat();
            } else if(t.equals("message")) {
                if(!chatting) throw new IllegalArgumentException(); String id=o.getString("id"), content=o.getString("text"); UUID.fromString(id);
                if(content.trim().isEmpty() || content.getBytes(StandardCharsets.UTF_8).length>1000 || received.size()>=500) throw new IllegalArgumentException();
                if(received.add(id)) addMessage(content,false,null);
                link.send(session.seal(new JSONObject().put("t","ack").put("id",id).toString()));
            } else if(t.equals("ack")) {
                if(!chatting) throw new IllegalArgumentException(); TextView r=receipts.remove(o.getString("id")); if(r!=null) r.setText("delivered  ✓");
            } else throw new IllegalArgumentException();
        } catch(Exception e) { closeWith("This connection couldn't be verified or a packet was invalid. Start a new room."); }
    }
    private void maybeChat() {
        if(!localApproved || !peerApproved) { status("Waiting for your friend to compare the code…"); return; }
        if(chatting) return; chatting=true; base("conversation"); space(body,16);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); chatLabel=text(friend,26,WHITE); row.addView(chatLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button end=button("Leave",false,this::confirmLeave); end.setTextSize(13); row.addView(end,new LinearLayout.LayoutParams(dp(90),dp(52))); body.addView(row); space(body,8);
        TextView badge=mono("BLUETOOTH · VERIFIED · "+code,10,DIM); badge.setOnClickListener(v->notice("Verified connection\n"+code)); body.addView(badge); space(body,14); rule(body);
        scroll=new ScrollView(this); scroll.setFillViewport(true); messages=column(); messages.setPadding(0,dp(12),0,dp(12)); scroll.addView(messages); body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView beginning=mono("Just the two of you. Say something sweet.",11,DIM); beginning.setGravity(Gravity.CENTER); beginning.setPadding(0,dp(20),0,dp(20)); messages.addView(beginning);
        LinearLayout composer=new LinearLayout(this); composer.setGravity(Gravity.BOTTOM);
        input=new EditText(this); input.setHint("A little hello…"); input.setHintTextColor(DIM); input.setTextColor(WHITE); input.setTextSize(16); input.setMaxLines(4); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)}); input.setPadding(dp(14),dp(12),dp(14),dp(12)); input.setBackground(box(Color.BLACK,LINE,14)); composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        sendButton=button("↑",true,this::sendMessage); sendButton.setTextSize(25); sendButton.setGravity(Gravity.CENTER); sendButton.setContentDescription("Send message"); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(58),dp(58)); bp.leftMargin=dp(10); composer.addView(sendButton,bp); body.addView(composer); space(body,8);
        body.addView(mono("Messages disappear when you leave.",10,DIM));
    }
    private void sendMessage() {
        String content=input.getText().toString().trim(); if(content.isEmpty()) return;
        if(content.getBytes(StandardCharsets.UTF_8).length>1000) { notice("Keep it short: up to 1,000 UTF-8 bytes per message."); return; }
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
        String time=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()); TextView receipt=mono(own?"sending…":time,10,DIM); receipt.setPadding(dp(3),dp(6),dp(3),0); receipt.setGravity(own?Gravity.END:Gravity.START); block.addView(receipt); scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); return receipt;
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("Less signal. More connection.").setMessage("1. Both install ChitChat and enable Bluetooth.\n2. One opens a room; the other finds it.\n3. Compare the code, then both tap Match.\n4. Chat nearby with both apps on screen.\n\nNo internet, account, contacts, or server. Direct Bluetooth only: no mesh, long-distance delivery, photos, or calls.\n\nText is encrypted in transit after verification. Messages stay in memory and disappear when you leave or the app closes. Your keyboard and phone can still retain data.\n\nRange varies by phone and obstacles. Start within 1–2 metres. This version disconnects when backgrounded.\n\nChitChat uses its own protocol and doesn't connect to BitChat.").setPositiveButton("Got it",null).show();
    }
    private void notice(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void shareApp() {
        android.net.Uri uri=android.net.Uri.parse("content://app.chitchat.apk/ChitChat.apk");
        Intent share=new Intent(Intent.ACTION_SEND); share.setType("application/vnd.android.package-archive"); share.putExtra(Intent.EXTRA_STREAM,uri); share.setClipData(ClipData.newUri(getContentResolver(),"ChitChat APK",uri)); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(share,"Send ChitChat to your friend's Android")); } catch(Exception e) { notice("Use your phone's Files app to share the original APK instead."); }
    }
    private void resetSession() { epoch++; if(verification!=null) verification.dismiss(); verification=null; if(session!=null) session.destroy(); session=null; localApproved=false; peerApproved=false; chatting=false; receipts.clear(); received.clear(); code=null; }
    private void leave() { link.stop(); active=false; resetSession(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0); home(); }
    private void closeWith(String reason) { leave(); new AlertDialog.Builder(this).setTitle("Room closed").setMessage(reason+"\n\nThis session's messages have been cleared.").setPositiveButton("OK",null).show(); }
    @Override public void closed(String reason) { closeWith(reason); }
    private void confirmLeave() { new AlertDialog.Builder(this).setTitle("Leave this room?").setMessage("The connection will close and this phone's messages will disappear.").setPositiveButton("Leave",(d,w)->leave()).setNegativeButton("Stay",null).show(); }
    @Override public void onBackPressed() { if(active) confirmLeave(); else super.onBackPressed(); }
    @Override protected void onStop() { super.onStop(); if(active) { leave(); } }
    @Override protected void onDestroy() { link.stop(); resetSession(); super.onDestroy(); }
}
