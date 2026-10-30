package app.chitchat;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import okhttp3.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground Online client. No transcript/draft is serialized or restored.
 * Persistent encrypted pending deliveries are removed as they are opened. */
public final class OnlineActivity extends Activity {
    static final String SYNC="app.chitchat.ONLINE_HINT";
    static volatile boolean foreground;
    private static final int WHITE=Color.WHITE,DIM=0xFFAAAAAA,LINE=0xFF444444;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private volatile boolean visible;
    private volatile int life,chatEpoch;
    private volatile String chatId;
    private String origin=OnlineApi.PRODUCTION,nickname="Sweet Tooth",draftCode="";
    private OnlineIdentity identity;private OnlineStore store;private volatile OnlineApi api;
    private LinearLayout root,body,messages;private ScrollView scroller;
    private EditText composer,codeInput,inviteInput;private TextView status;
    private WebSocket socket;private int retry=1;private boolean refreshBusy,refreshAgain,metaDirty,drainBusy;
    private long lastMeta;private boolean inboxPrimed,notificationSetupBusy;
    private Map<String,OnlineCrypto.Contact> contacts=new LinkedHashMap<>();
    private List<OnlineCrypto.Contact> requests=new ArrayList<>();
    private final Map<String,PendingSend> sends=new HashMap<>();
    private final Set<String> displayed=new HashSet<>();
    private AlertDialog dialog;
    private final BroadcastReceiver hints=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){requestRefresh(true);}};
    private final Runnable refreshTask=()->refresh();
    private final Runnable heartbeat=new Runnable(){@Override public void run(){if(!visible)return;if(socket!=null)socket.send("ping");main.postDelayed(this,45000);}};
    private final Runnable fallback=new Runnable(){@Override public void run(){if(!visible)return;requestRefresh(true);main.postDelayed(this,30000);}};
    private interface Job<T>{T run()throws Exception;}
    private interface Done<T>{void accept(T value)throws Exception;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        if(BuildConfig.DEBUG){String test=getIntent().getStringExtra("online_test_base");if(test!=null&&test.matches("http://(127\\.0\\.0\\.1|10\\.0\\.2\\.2):[0-9]{2,5}"))origin=test;}
        nickname=getIntent().getStringExtra("nickname");if(nickname==null||nickname.trim().isEmpty())nickname="Sweet Tooth";
        OnlineStore.lastOnline(this,true);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(chatId!=null)closeChat();PushNotifications.clear(this);requestRefresh(true);}
    @Override protected void onStart(){
        super.onStart();visible=true;foreground=true;life++;chatId=null;chatEpoch++;refreshBusy=false;drainBusy=false;refreshAgain=false;lastMeta=0;inboxPrimed=false;notificationSetupBusy=false;
        if(Build.VERSION.SDK_INT>=33)registerReceiver(hints,new IntentFilter(SYNC),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(hints,new IntentFilter(SYNC));
        int generation=life;loading();run(()->{
            OnlineIdentity id=new OnlineIdentity(this);OnlineStore data=new OnlineStore(this,id.storage);OnlineApi transport=new OnlineApi(id.crypto,origin);
            if(!visible||life!=generation){transport.cancel();throw new java.io.InterruptedIOException();}
            api=transport;
            if(!visible||life!=generation){transport.cancel();throw new java.io.InterruptedIOException();}
            if(data.registered()){
                OnlineCrypto.Contact me=new OnlineCrypto.Contact(transport.request("GET","/v1/me",null).getJSONObject("account"));
                OnlineCrypto.Contact own=new OnlineCrypto.Contact(id.crypto.profile(nickname));if(!own.sameKey(me))throw new java.security.GeneralSecurityException("Own key changed");
            }
            return new Object[]{id,data,transport};
        },result->{identity=(OnlineIdentity)result[0];store=(OnlineStore)result[1];api=(OnlineApi)result[2];nickname=store.prefs.getString("nickname",nickname);
            if(store.registered()){lobby();startEvents();requestRefresh(true);resumeNotifications();}else join();
        },error->{base("online");space(body,28);body.addView(text("A moment apart.",34,WHITE));space(body,18);body.addView(text(OnlineApi.friendly(error),16,DIM));space(body,24);body.addView(button("Try again",true,this::recreate));space(body,12);body.addView(button("Use Nearby",false,this::nearby));});
    }
    private <T> void run(Job<T> job,Done<T> done,java.util.function.Consumer<Exception> failed){
        int generation=life;
        work.execute(()->{if(!visible||life!=generation)return;try{T value=job.run();main.post(()->{if(!visible||life!=generation)return;try{done.accept(value);}catch(Exception e){failed.accept(e);}});}catch(Exception e){main.post(()->{if(visible&&life==generation)failed.accept(e);});}});
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);return t;}
    private TextView mono(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(Typeface.MONOSPACE);return t;}
    private void space(LinearLayout layout,int value){layout.addView(new View(this),new LinearLayout.LayoutParams(1,dp(value)));}
    private void rule(LinearLayout layout){View v=new View(this);v.setBackgroundColor(LINE);layout.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    private GradientDrawable box(boolean filled){GradientDrawable d=new GradientDrawable();d.setColor(filled?WHITE:Color.BLACK);d.setCornerRadius(dp(14));d.setStroke(dp(1),filled?WHITE:LINE);return d;}
    private Button button(String value,boolean filled,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(filled?Color.BLACK:WHITE);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setPadding(dp(18),dp(8),dp(18),dp(8));b.setMinHeight(dp(54));b.setBackground(box(filled));b.setOnClickListener(v->action.run());return b;}
    private EditText field(String hint,String tag,int max,boolean secret){EditText e=new EditText(this);e.setTag(tag);e.setContentDescription(hint);e.setHint(hint);e.setTextColor(WHITE);e.setHintTextColor(DIM);e.setTextSize(16);e.setSingleLine(true);e.setPadding(dp(16),dp(12),dp(16),dp(12));e.setBackground(box(false));e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(secret?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));e.setSaveEnabled(false);return e;}
    private void base(String section){
        root=column();root.setBackgroundColor(Color.BLACK);root.setPadding(dp(22),dp(14),dp(22),dp(16));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){Insets a=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()),k=insets.getInsets(WindowInsets.Type.ime());root.setPadding(dp(22)+a.left,dp(14)+a.top,dp(22)+a.right,dp(16)+Math.max(a.bottom,k.bottom));}return insets;});root.requestApplyInsets();
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(mono("chitchat / "+section,13,WHITE),new LinearLayout.LayoutParams(0,dp(44),1));
        TextView info=mono("ⓘ",25,WHITE);info.setGravity(Gravity.CENTER);info.setContentDescription("Online help and privacy");info.setOnClickListener(v->help());top.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(top);rule(root);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
    }
    private LinearLayout page(){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));LinearLayout c=column();scroll.addView(c);return c;}
    private void modes(LinearLayout c){space(c,14);LinearLayout row=new LinearLayout(this);Button a=button("Nearby",false,this::nearby),b=button("Online",true,()->{});a.setGravity(Gravity.CENTER);b.setGravity(Gravity.CENTER);row.addView(a,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.leftMargin=dp(10);row.addView(b,p);c.addView(row);}
    private void loading(){base("online");space(body,34);body.addView(text("A little closer.",38,WHITE));space(body,18);body.addView(text("Opening your Online identity…",15,DIM));space(body,24);body.addView(button("Use Nearby",false,this::nearby));}
    private void join(){
        base("online");LinearLayout c=page();modes(c);space(c,28);c.addView(mono("INTERNET ON. HISTORY OFF.",11,DIM));space(c,18);
        TextView title=text("Small talk.\nAny distance.",46,WHITE);title.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));c.addView(title);space(c,18);
        c.addView(text("A private little pilot.\nA nickname and an invitation are enough.",16,DIM));space(c,24);
        EditText name=field("Your nickname","nickname",24,false);name.setText(nickname);c.addView(name);space(c,12);inviteInput=field("Private pilot invitation","invite",256,true);c.addView(inviteInput);space(c,14);
        Button start=button("Make my ChitChat ID    ↗",true,()->{
            String nick=name.getText().toString().trim(),invite=inviteInput.getText().toString().trim();if(nick.isEmpty()||invite.isEmpty()){notice("Enter a nickname and your pilot invitation.");return;}
            inviteInput.setText("");name.setEnabled(false);status.setText("Making your ID…");View launch=root.findViewWithTag("register");if(launch!=null)launch.setEnabled(false);
            run(()->{JSONObject response=api.request("POST","/v1/register",new JSONObject().put("name",nick).put("keyBundle",identity.crypto.bundle()),invite);OnlineCrypto.Contact me=new OnlineCrypto.Contact(response.getJSONObject("account"));if(!me.sameKey(new OnlineCrypto.Contact(identity.crypto.profile(nick))))throw new java.security.GeneralSecurityException("Registration key mismatch");store.registered(nick);return me;},me->{nickname=nick;lobby();startEvents();requestRefresh(true);},error->{name.setEnabled(true);if(launch!=null)launch.setEnabled(true);status.setText(OnlineApi.friendly(error));});
        });start.setTag("register");c.addView(start);space(c,16);status=text("Your ID stays on this phone. No phone number or email.",13,DIM);c.addView(status);space(c,20);rule(c);space(c,16);
        c.addView(text("Opened chats clear when you leave or background the app. Unopened messages can wait encrypted for up to 24 hours.",13,DIM));
    }
    private void lobby(){
        if(chatId!=null)return;
        boolean focus=codeInput!=null&&codeInput.hasFocus();if(codeInput!=null)draftCode=codeInput.getText().toString();
        if(foreground)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);base("online");LinearLayout c=page();modes(c);space(c,22);
        c.addView(text("Your people.",34,WHITE));space(c,14);
        LinearLayout identityRow=new LinearLayout(this);identityRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView id=mono("ID / "+OnlineCrypto.formattedCode(identity.crypto.code),15,WHITE);id.setTextIsSelectable(true);identityRow.addView(id,new LinearLayout.LayoutParams(0,dp(48),1));id.setGravity(Gravity.CENTER_VERTICAL);
        Button share=button("Share",false,()->{Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Find me on ChitChat: "+OnlineCrypto.formattedCode(identity.crypto.code)+" ("+nickname+"). Open Online → Add a friend. Android beta: https://raw.githubusercontent.com/clintonpereira7977/bitchat-android/refs/heads/codex/chitchat-download/downloads/ChitChat-0.3-beta4.apk");startActivity(Intent.createChooser(intent,"Share your public ChitChat ID"));});share.setContentDescription("Share my ChitChat ID");identityRow.addView(share,new LinearLayout.LayoutParams(dp(90),dp(48)));c.addView(identityRow);space(c,10);
        status=text("Messages clear here when you leave.",13,DIM);c.addView(status);space(c,14);rule(c);
        if(!requests.isEmpty()){space(c,18);c.addView(mono("KNOCK KNOCK / REQUESTS",11,DIM));for(OnlineCrypto.Contact friend:requests){space(c,14);c.addView(text(friend.name+" · "+OnlineCrypto.formattedCode(friend.code),17,WHITE));space(c,8);LinearLayout row=new LinearLayout(this);Button accept=button("Accept",true,()->decide(friend,true)),decline=button("Decline",false,()->decide(friend,false));accept.setTag("accept_"+friend.id);decline.setTag("decline_"+friend.id);row.addView(accept,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);p.leftMargin=dp(10);row.addView(decline,p);c.addView(row);}}
        space(c,20);c.addView(mono("SAY A LITTLE HELLO",11,DIM));
        if(contacts.isEmpty()){space(c,12);c.addView(text("A little quiet here.\nAdd a friend; chat opens after approval.",15,DIM));}
        for(OnlineCrypto.Contact friend:contacts.values()){space(c,12);int n=0;try{n=store.count(friend.id);}catch(Exception ignored){}Button open=button(friend.name+(n>0?"   · "+n+" waiting":"   ↗"),false,()->openChat(friend.id));open.setTag("chat_"+friend.id);c.addView(open);}
        space(c,22);rule(c);space(c,18);c.addView(mono("A NEW CONNECTION",11,DIM));space(c,10);
        codeInput=field("Friend’s eight-character ID","add_code",12,false);codeInput.setText(draftCode);c.addView(codeInput);space(c,10);
        Button add=button("Add a friend    +",true,this::findFriend);add.setTag("add_friend");c.addView(add);
        space(c,20);String notificationLabel=!OnlinePush.enabled(this)?"Enable notifications":OnlinePush.ready(this)?"Notifications on · turn off":"Finish notification setup";
        Button notifications=button(notificationLabel,false,this::notificationChoice);notifications.setTag("notifications");c.addView(notifications);space(c,10);
        if(OnlinePush.enabled(this)){String problem=PushNotifications.problem(this);if(problem!=null){c.addView(text(problem,13,DIM));space(c,8);}Button check=button("Check notifications",false,this::checkNotifications);check.setTag("check_notifications");c.addView(check);space(c,10);}
        c.addView(button("Refresh",false,()->requestRefresh(true)));space(c,12);TextView settings=mono("YOUR ID & PRIVACY   ↗",11,DIM);settings.setPadding(0,dp(16),0,dp(16));settings.setOnClickListener(v->account());c.addView(settings);
        if(focus){codeInput.requestFocus();codeInput.setSelection(codeInput.length());}
    }
    private void findFriend(){
        String code=codeInput.getText().toString().replaceAll("[\\s-]","").toUpperCase(Locale.ROOT);
        if(!code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}")){notice("Enter your friend’s eight-character ID.");return;}
        status.setText("Looking for your friend…");
        run(()->new OnlineCrypto.Contact(api.request("GET","/v1/lookup/"+code,null).getJSONObject("account")),friend->{
            if(friend.id.equals(identity.crypto.id)){notice("That’s your own ID.");return;}
            dialog=new AlertDialog.Builder(this).setTitle("Connect with "+friend.name+"?").setMessage("ID / "+OnlineCrypto.formattedCode(friend.code)+"\n\nYour friend must accept before you can chat. Names alone don’t verify identity.")
                    .setPositiveButton("Send request",(d,w)->sendRequest(friend)).setNeutralButton("Safety number",(d,w)->safety(friend)).setNegativeButton("Cancel",null).show();
        },this::error);
    }
    private void sendRequest(OnlineCrypto.Contact friend){run(()->{store.pin(friend);return api.request("POST","/v1/requests",new JSONObject().put("code",friend.code));},result->{notice(result.optString("state").equals("accepted")?"You’re already connected.":"Request sent. Waiting for your friend to accept.");draftCode="";if(codeInput!=null)codeInput.setText("");requestRefresh(true);},this::error);}
    private void decide(OnlineCrypto.Contact friend,boolean accept){
        run(()->{if(accept)store.pin(friend);return api.request("POST","/v1/requests/"+friend.id+(accept?"/accept":"/decline"),new JSONObject());},result->{notice(accept?"A new connection. Say hello.":"Request declined.");requestRefresh(true);},this::error);
    }
    private void requestRefresh(boolean metadata){
        if(!visible||store==null||!store.registered())return;metaDirty|=metadata;
        if(refreshBusy){refreshAgain=true;return;}
        main.removeCallbacks(refreshTask);main.postDelayed(refreshTask,450);
    }
    private static final class Snapshot {
        Map<String,OnlineCrypto.Contact> contacts;List<OnlineCrypto.Contact> requests;String warning="";boolean more;Set<String> newSenders=new HashSet<>();boolean newRequest;
    }
    private void refresh(){
        if(!visible||api==null||store==null||!store.registered()||refreshBusy)return;
        refreshBusy=true;boolean metadata=metaDirty||System.currentTimeMillis()-lastMeta>30000;metaDirty=false;
        Map<String,OnlineCrypto.Contact> previous=new LinkedHashMap<>(contacts);List<OnlineCrypto.Contact> oldRequests=new ArrayList<>(requests);
        run(()->{
            Snapshot state=new Snapshot();state.contacts=previous;state.requests=oldRequests;
            if(metadata){
                state.contacts=new LinkedHashMap<>();JSONArray list=api.request("GET","/v1/contacts",null).getJSONArray("contacts");
                for(int i=0;i<list.length();i++){
                    try{OnlineCrypto.Contact friend=new OnlineCrypto.Contact(list.getJSONObject(i)),pin=store.pinned(friend.id);if(pin!=null&&pin.sameKey(friend))state.contacts.put(friend.id,friend);else state.warning="A contact is paused because its keys aren’t approved on this phone.";}catch(Exception e){state.warning="A contact failed its key check and was paused.";}
                }
                state.requests=new ArrayList<>();JSONArray incoming=api.request("GET","/v1/requests",null).getJSONArray("requests");
                for(int i=0;i<incoming.length();i++)try{state.requests.add(new OnlineCrypto.Contact(incoming.getJSONObject(i).getJSONObject("account")));}catch(Exception e){state.warning="An incompatible request was ignored.";}
                for(OnlineCrypto.Contact request:state.requests){boolean known=false;for(OnlineCrypto.Contact old:oldRequests)if(old.id.equals(request.id)){known=true;break;}if(!known)state.newRequest=true;}
            }
            JSONArray inbox=api.request("GET","/v1/inbox",null).getJSONArray("messages");
            int confirmed=0;
            for(int i=0;i<inbox.length();i++){
                JSONObject message=inbox.getJSONObject(i);if(!state.contacts.containsKey(message.getString("sender")))continue;
                boolean known=store.hasDelivery(message.getString("id"));
                if(!store.stage(message)){state.warning="Your phone’s pending queue is full. Open a conversation to make room.";break;}
                if(!known&&message.getLong("expires")>System.currentTimeMillis())state.newSenders.add(message.getString("sender"));
                try{api.request("POST","/v1/messages/"+message.getString("id")+"/ack",new JSONObject());confirmed++;}
                catch(Exception e){state.warning="A delivery confirmation will retry when you reconnect.";break;}
                if(confirmed>=10){state.more=true;break;}
            }
            return state;
        },state->{refreshBusy=false;contacts=state.contacts;requests=state.requests;if(metadata)lastMeta=System.currentTimeMillis();
            if(inboxPrimed&&foreground){boolean other=false;for(String sender:state.newSenders)if(!sender.equals(chatId)){other=true;break;}if(other)PushNotifications.show(this,"inbox");else if(state.newRequest)PushNotifications.show(this,"requests");}inboxPrimed=true;
            if(chatId!=null&&!contacts.containsKey(chatId)){closeChat();notice("This connection is no longer available.");}
            else if(chatId==null)lobby();else drainChat();
            if(!state.warning.isEmpty()&&status!=null)status.setText(state.warning);
            if(state.more)main.postDelayed(()->requestRefresh(false),15000);
            if(refreshAgain){refreshAgain=false;requestRefresh(false);}
        },failure->{refreshBusy=false;error(failure);});
    }
    private void startEvents(){
        if(!visible||store==null||!store.registered())return;int generation=life;
        run(()->api.request("POST","/v1/socket-ticket",new JSONObject()).getString("ticket"),ticket->{
            if(socket!=null)socket.cancel();socket=api.events(ticket,new WebSocketListener(){
                @Override public void onOpen(WebSocket ws,Response response){main.post(()->{if(visible&&life==generation){retry=1;requestRefresh(true);}});}
                @Override public void onMessage(WebSocket ws,String text){main.post(()->{
                    if(!visible||life!=generation||"pong".equals(text))return;
                    try{JSONObject event=new JSONObject(text);String type=event.optString("type");if(type.equals("delivered")){PendingSend sent=sends.get(event.optString("id"));if(sent!=null){sent.delivered=true;sent.receipt.setText("delivered to phone");}}else if(type.equals("account-deleted")){notice("This Online account was deleted.");closeChat();}else if(type.equals("inbox"))requestRefresh(false);else if(type.equals("contacts")||type.equals("requests")||type.equals("connected"))requestRefresh(true);}catch(Exception ignored){}
                });}
                @Override public void onFailure(WebSocket ws,Throwable t,Response response){main.post(()->{if(visible&&life==generation&&socket==ws){socket=null;reconnect();}});}
                @Override public void onClosed(WebSocket ws,int code,String reason){main.post(()->{if(visible&&life==generation&&socket==ws){socket=null;reconnect();}});}
            });
            main.removeCallbacks(heartbeat);main.postDelayed(heartbeat,45000);main.removeCallbacks(fallback);main.postDelayed(fallback,30000);
        },failure->reconnect());
    }
    private void reconnect(){if(!visible)return;int generation=life,delay=retry;retry=Math.min(60,retry*2);main.postDelayed(()->{if(visible&&life==generation&&socket==null)startEvents();},delay*1000L);}
    private void openChat(String id){
        OnlineCrypto.Contact friend=contacts.get(id);if(friend==null)return;
        chatId=id;chatEpoch++;displayed.clear();sends.clear();drainBusy=false;PushNotifications.clear(this);
        codeInput=null;
        if(!BuildConfig.DEBUG)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        base("conversation");space(body,16);LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(text(friend.name,26,WHITE),new LinearLayout.LayoutParams(0,-2,1));Button leave=button("Leave",false,this::confirmLeave);leave.setTag("leave");row.addView(leave,new LinearLayout.LayoutParams(dp(90),dp(50)));body.addView(row);space(body,10);
        TextView badge=mono("ONLINE · ENCRYPTED   ⓘ",11,DIM);badge.setOnClickListener(v->safety(friend));body.addView(badge);space(body,12);rule(body);
        scroller=new ScrollView(this);scroller.setFillViewport(true);messages=column();messages.setPadding(0,dp(12),0,dp(12));scroller.addView(messages);body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        TextView beginning=mono("A fresh page. Say something sweet.",11,DIM);beginning.setGravity(Gravity.CENTER);beginning.setPadding(0,dp(20),0,dp(20));messages.addView(beginning);
        LinearLayout compose=new LinearLayout(this);compose.setGravity(Gravity.BOTTOM);composer=field("A little hello…","composer",1000,false);composer.setSingleLine(false);composer.setMaxLines(4);composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        Button send=button("↑",true,this::send);send.setGravity(Gravity.CENTER);send.setTextSize(24);send.setContentDescription("Send message");send.setTag("send");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(56),dp(56));p.leftMargin=dp(10);compose.addView(send,p);body.addView(compose);space(body,8);status=mono("This phone’s chat clears when you leave.",10,DIM);body.addView(status);
        drainChat();requestRefresh(false);
    }
    private TextView bubble(String text,boolean own){
        LinearLayout block=column();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.gravity=own?Gravity.END:Gravity.START;p.topMargin=dp(8);p.bottomMargin=dp(8);p.leftMargin=own?dp(30):0;p.rightMargin=own?0:dp(30);messages.addView(block,p);
        TextView message=text(text,17,own?Color.BLACK:WHITE);message.setTextIsSelectable(true);message.setSaveEnabled(false);message.setPadding(dp(15),dp(12),dp(15),dp(12));message.setBackground(box(own));block.addView(message);
        TextView receipt=mono(own?"sending…":"just now",10,DIM);receipt.setPadding(dp(3),dp(6),dp(3),0);block.addView(receipt);scroller.post(()->{if(scroller!=null&&messages!=null)scroller.smoothScrollTo(0,messages.getBottom());});return receipt;
    }
    private static final class Opened {String id,text;Opened(String id,String text){this.id=id;this.text=text;}}
    private void drainChat(){
        if(chatId==null||drainBusy||store==null)return;String id=chatId;int chat=chatEpoch;OnlineCrypto.Contact friend=contacts.get(id);if(friend==null)return;drainBusy=true;
        run(()->{
            List<Opened> opened=new ArrayList<>();for(JSONObject message:store.pending(id)){
                if(!visible||chatEpoch!=chat||!id.equals(chatId))break;
                String messageId=message.getString("id"),text;
                try{text=identity.crypto.open(friend,messageId,message.getString("envelope"));}
                catch(Exception invalid){store.consumed(messageId);opened.add(new Opened("", "A message failed its security check and was discarded."));continue;}
                if(!visible||chatEpoch!=chat||!id.equals(chatId))break;
                store.consumed(messageId);opened.add(new Opened(messageId,text));
            }return opened;
        },opened->{drainBusy=false;if(chatEpoch!=chat||!id.equals(chatId))return;for(Opened message:opened){if(message.id.isEmpty()){notice(message.text);continue;}if(displayed.add(message.id))bubble(message.text,false);} },failure->{drainBusy=false;error(failure);});
    }
    private static final class PendingSend {String id;volatile JSONObject body;TextView receipt;boolean delivered,busy;}
    private void send(){
        if(chatId==null||composer==null)return;String content=composer.getText().toString().trim();if(content.isEmpty())return;
        if(OnlineCrypto.utf8(content).length>2000){notice("Try a shorter note.");return;}
        int pending=0;for(PendingSend item:sends.values())if(!item.delivered)pending++;
        if(pending>=8){notice("Wait for delivery before sending more.");return;}
        if(messages.getChildCount()>300){notice("This fresh page is full. Leave and reopen to start another.");return;}
        String id=chatId;OnlineCrypto.Contact friend=contacts.get(id);if(friend==null)return;int chat=chatEpoch;
        PendingSend item=new PendingSend();item.id=UUID.randomUUID().toString();item.receipt=bubble(content,true);sends.put(item.id,item);composer.setText("");item.busy=true;
        run(()->{item.body=new JSONObject().put("to",id).put("id",item.id).put("envelope",identity.crypto.seal(friend,item.id,content));return api.request("POST","/v1/messages",item.body);},result->{if(chatEpoch==chat&&id.equals(chatId)){item.busy=false;item.receipt.setText(item.delivered||"delivered".equals(result.optString("state"))?"delivered to phone":"queued · waiting for phone");}},failure->{if(chatEpoch==chat&&id.equals(chatId)){item.busy=false;item.receipt.setText("unconfirmed · tap to retry");item.receipt.setOnClickListener(v->retrySend(item,id,chat));error(failure);}});
    }
    private void retrySend(PendingSend item,String id,int chat){if(chatEpoch!=chat||!id.equals(chatId)||item.busy||item.body==null)return;item.busy=true;item.receipt.setText("retrying…");run(()->api.request("POST","/v1/messages",item.body),response->{if(chatEpoch==chat&&id.equals(chatId)){item.busy=false;item.receipt.setText(item.delivered||"delivered".equals(response.optString("state"))?"delivered to phone":"queued · waiting for phone");}},failure->{item.busy=false;if(chatEpoch==chat&&id.equals(chatId)){item.receipt.setText("unconfirmed · tap to retry");error(failure);}});}
    private void confirmLeave(){dialog=new AlertDialog.Builder(this).setTitle("Leave this conversation?").setMessage("This phone’s opened messages and draft will disappear. Your friend controls their own copy.").setPositiveButton("Leave",(d,w)->closeChat()).setNegativeButton("Stay",null).show();}
    private void closeChat(){chatEpoch++;chatId=null;if(composer!=null)composer.setText("");composer=null;if(messages!=null)messages.removeAllViews();messages=null;scroller=null;displayed.clear();sends.clear();drainBusy=false;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);if(visible&&identity!=null&&store!=null)lobby();}
    private void safety(OnlineCrypto.Contact friend){
        try{OnlineCrypto.Contact own=new OnlineCrypto.Contact(identity.crypto.profile(nickname));String a=own.fingerprint,b=friend.fingerprint;String number=OnlineCrypto.hex(OnlineCrypto.hash(OnlineCrypto.utf8(a.compareTo(b)<0?a+"\n"+b:b+"\n"+a))).replaceAll("(.{4})(?!$)","$1 ");
            dialog=new AlertDialog.Builder(this).setTitle("Your safety number").setMessage("Compare this entire number with your friend in person or over another trusted channel. Both phones should show the same number.\n\n"+number+"\n\nAccepting a request alone doesn’t verify identity.").setPositiveButton("Done",null).setNeutralButton("Block contact",(d,w)->block(friend)).show();
        }catch(Exception e){error(e);}
    }
    private void block(OnlineCrypto.Contact friend){dialog=new AlertDialog.Builder(this).setTitle("Block "+friend.name+"?").setMessage("Messages and requests from this ID will stop. This pilot has no unblock option.").setPositiveButton("Block",(d,w)->run(()->{api.request("POST","/v1/contacts/"+friend.id+"/block",new JSONObject());store.forget(friend.id);return true;},ok->{closeChat();requestRefresh(true);},this::error)).setNegativeButton("Cancel",null).show();}
    private void notificationChoice(){
        if(OnlinePush.enabled(this)&&OnlinePush.ready(this)){disableNotifications();return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},33);return;}enableNotifications();
    }
    private void disableNotifications(){notificationSetupBusy=false;OnlinePush.disable(this);run(()->api.request("DELETE","/v1/push",null),ok->lobby(),failure->{lobby();notice("Alerts are off on this phone. Server cleanup will retry when you choose Nearby.");});}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==33){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)enableNotifications();else notice("You can chat normally. Open the app to check messages.");}}
    private void enableNotifications(){if(!visible||store==null||!store.registered())return;
        run(()->api.request("GET","/v1/health",null),health->{
            if(!health.optBoolean("pushConfigured")||!"data-only-v1".equals(health.optString("pushPayload"))){notice("Notifications aren’t ready yet. You can still chat while the app is open.");return;}
            OnlinePush.enableForRegisteredAccount(this,identity.crypto.id);resumeNotifications();if(chatId==null)lobby();
        },this::error);
    }
    private void resumeNotifications(){
        if(!OnlinePush.enabled(this)||identity==null||notificationSetupBusy)return;int generation=life;notificationSetupBusy=true;
        OnlinePush.requestToken(this,(token,available)->main.post(()->{if(!visible||life!=generation||!OnlinePush.enabled(this))return;if(!available){notificationSetupBusy=false;if(chatId==null)lobby();notice("Google Play services couldn’t register notifications. Check your connection and Play services, then choose Finish notification setup.");return;}
            run(()->api.request("PUT","/v1/push",new JSONObject().put("token",token)),ok->{notificationSetupBusy=false;if(!ok.optBoolean("registered")){if(chatId==null)lobby();notice("Notification registration didn’t complete. Try setup again.");return;}OnlinePush.acknowledgeToken(this,token);if(chatId==null)lobby();notice("Notification registration completed. Use Check notifications to test delivery.");},failure->{notificationSetupBusy=false;if(chatId==null)lobby();error(failure);});
        }));
    }
    private void notificationSettings(){
        Intent settings=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());
        try{startActivity(settings);}catch(ActivityNotFoundException ignored){startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));}
    }
    private void checkNotifications(){
        String problem=PushNotifications.problem(this);
        if(problem!=null){dialog=new AlertDialog.Builder(this).setTitle("Android notification settings").setMessage(problem+"\n\nCheck sound, vibration and pop-up alerts. Android’s Do Not Disturb settings can also silence alerts.").setPositiveButton("Open settings",(d,w)->notificationSettings()).setNeutralButton("Turn off alerts",(d,w)->disableNotifications()).setNegativeButton("Done",null).show();return;}
        run(()->api.request("GET","/v1/push/status",null),result->{
            String message="ANDROID / alerts allowed\nPHONE / "+(OnlinePush.ready(this)?"registration completed":"setup needs attention")+"\nSERVER / "+PushPolicy.result(result.optString("result"))+"\n\n"+PushNotifications.diagnostics(this);
            boolean expired="UNREGISTERED".equals(result.optString("result"));
            dialog=new AlertDialog.Builder(this).setTitle("A little notification check").setMessage(message+"\n\nSend a test through Google to this phone. It contains no chat text. Battery restrictions or Do Not Disturb may delay or silence it.").setPositiveButton(expired?"Refresh registration":"Send test",(d,w)->{if(expired)refreshNotificationRegistration();else sendNotificationTest();}).setNeutralButton("Turn off alerts",(d,w)->disableNotifications()).setNegativeButton("Settings",(d,w)->notificationSettings()).show();
        },failure->{if(failure instanceof OnlineApi.Failure&&((OnlineApi.Failure)failure).status==404)notice("The app owner needs to deploy the latest notification-check server code first.");else error(failure);});
    }
    private void sendNotificationTest(){run(()->api.request("POST","/v1/push/test",new JSONObject()),result->{notice(PushPolicy.result(result.optString("result")));},this::error);}
    private void refreshNotificationRegistration(){
        if(notificationSetupBusy||!OnlinePush.enabled(this))return;notificationSetupBusy=true;int generation=life;
        try{com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener(task->{
            if(!visible||life!=generation||!OnlinePush.enabled(this))return;notificationSetupBusy=false;
            if(task.isSuccessful())resumeNotifications();else notice("Google couldn’t refresh registration. Check your connection and try again.");
        });}catch(RuntimeException failure){notificationSetupBusy=false;notice("Google Play services couldn’t refresh registration.");}
    }
    private void nearby(){
        OnlinePush.disable(this);OnlineStore.lastOnline(this,false);
        OnlineApi transport=api;boolean registered=store!=null&&store.registered();
        if(transport!=null){transport.cancel();}
        // Opt-out is immediate locally. A short independent cleanup request is
        // allowed to finish even while the Online Activity is being destroyed.
        if(registered&&identity!=null){OnlineCrypto crypto=identity.crypto;String endpoint=origin;new Thread(()->{try{new OnlineApi(crypto,endpoint).request("DELETE","/v1/push",null);}catch(Exception ignored){}},"chitchat-push-cleanup").start();}
        Intent intent=new Intent(this,MainActivity.class);startActivity(intent);finish();
    }
    private void account(){dialog=new AlertDialog.Builder(this).setTitle("Your little corner").setMessage("YOU / "+nickname+"\nID / "+OnlineCrypto.formattedCode(identity.crypto.code)+"\n\nThis phone holds your identity. Uninstalling or clearing app data loses your ID. There’s no account recovery in this pilot.\n\nOpened chats aren’t saved as history. Encrypted unopened messages can wait up to 24 hours.").setPositiveButton("Done",null).setNeutralButton("Delete Online account",(d,w)->deleteAccount()).show();}
    private void deleteAccount(){dialog=new AlertDialog.Builder(this).setTitle("Delete your Online account?").setMessage("This removes your server account, connections and queued deliveries. Your ID cannot be recovered. Nearby will still work.").setPositiveButton("Delete",(d,w)->run(()->{api.request("DELETE","/v1/me",null);OnlinePush.disable(this);store.clear();identity.destroy();return true;},ok->{OnlineStore.lastOnline(this,false);startActivity(new Intent(this,MainActivity.class));finish();},this::error)).setNegativeButton("Keep it",null).show();}
    private void help(){dialog=new AlertDialog.Builder(this).setTitle("A little closer. From anywhere.").setMessage("ONLINE\nShare your public eight-character ID. Your friend sends a request; you choose Accept or Decline. No phone number or address book is needed.\n\nOpened chats and drafts clear from this phone when you leave or background the app. Unopened messages can wait encrypted for up to 24 hours on the relay or your phone. Your friend controls their own copy. Photos of the screen, keyboards and compromised devices can retain information.\n\nMessages are encrypted on phones and signed by the sender. For identity verification, compare the full safety number outside ChitChat. This pilot has not had an independent security audit or Signal-style forward-secrecy review.\n\nGeneric Android notifications are optional and use Google Play services. Notification delivery and internet access depend on your phone and network.\n\nNEARBY\nSwitch to Nearby for direct Bluetooth. This closes the Online connection and disables Online alerts on this phone.").setPositiveButton("Got it",null).show();}
    private void error(Exception failure){String message=OnlineApi.friendly(failure);if(status!=null)status.setText(message);notice(message);}
    private void notice(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(chatId!=null)confirmLeave();else finishAffinity();}
    @Override protected void onPause(){
        // HOME, app switching and system overlays can pause an Activity before
        // Android stops it. Clear the opened view at the earlier boundary.
        foreground=false;if(chatId!=null)closeChat();super.onPause();
    }
    @Override protected void onResume(){super.onResume();foreground=visible;if(visible&&chatId==null)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);if(visible&&store!=null&&store.registered())requestRefresh(true);}
    @Override protected void onSaveInstanceState(Bundle state){/* No drafts, transcript or private invite in saved state. */super.onSaveInstanceState(new Bundle());}
    @Override protected void onStop(){
        visible=false;foreground=false;life++;chatEpoch++;chatId=null;main.removeCallbacksAndMessages(null);
        if(socket!=null){socket.cancel();socket=null;}if(api!=null)api.cancel();if(dialog!=null){dialog.dismiss();dialog=null;}
        if(composer!=null)composer.setText("");if(inviteInput!=null)inviteInput.setText("");composer=null;inviteInput=null;codeInput=null;draftCode="";displayed.clear();sends.clear();contacts=new LinkedHashMap<>();requests=new ArrayList<>();
        if(messages!=null)messages.removeAllViews();messages=null;scroller=null;if(root!=null)root.removeAllViews();status=null;
        identity=null;store=null;api=null;
        try{unregisterReceiver(hints);}catch(IllegalArgumentException ignored){}super.onStop();
    }
    @Override protected void onDestroy(){if(api!=null)api.cancel();work.shutdownNow();super.onDestroy();}
}
