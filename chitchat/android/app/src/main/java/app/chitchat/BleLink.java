package app.chitchat;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.util.*;

/** One host + one guest. All mutable state is confined to the main looper. */
@SuppressLint("MissingPermission")
public final class BleLink {
    public static final UUID SERVICE=UUID.fromString("c17c0001-5a7e-4c10-8a21-6d6573680001");
    public static final UUID RX=UUID.fromString("c17c0002-5a7e-4c10-8a21-6d6573680001");
    public static final UUID TX=UUID.fromString("c17c0003-5a7e-4c10-8a21-6d6573680001");
    public static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public interface Listener {
        void status(String s); void found(String id,String title); void connected(); void packet(byte[] p); void closed(String reason); void disconnected(String reason);
    }
    private final Context context; private final Listener listener; private final Handler main=new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter; private BluetoothGattServer server; private BluetoothGatt client;
    private BluetoothGattCharacteristic tx,rx; private BluetoothDevice remote;
    private final ArrayDeque<byte[]> outgoing=new ArrayDeque<>(); private final Wire.Decoder decoder=new Wire.Decoder();
    private final ArrayDeque<byte[]> early=new ArrayDeque<>();
    private final Map<String,BluetoothDevice> devices=new HashMap<>();
    private Runnable afterSend;
    private boolean hosting, running, ready, writing, scanning; private int generation; private String room;
    public BleLink(Context c,Listener l) { context=c; listener=l; BluetoothManager m=(BluetoothManager)c.getSystemService(Context.BLUETOOTH_SERVICE); adapter=m==null?null:m.getAdapter(); }
    public boolean enabled() { return adapter!=null && adapter.isEnabled(); }
    public boolean canHost() { return adapter!=null && adapter.isMultipleAdvertisementSupported(); }
    public void host(String roomCode) {
        stop(); running=true; hosting=true; room=roomCode;
        if(!enabled() || !canHost()) { fail("This phone cannot advertise BLE. Ask your friend to open the room, then choose Find a friend."); return; }
        try {
            BluetoothManager m=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            server=m.openGattServer(context,serverCallback);
            if(server==null) { fail("Couldn't start Bluetooth. Toggle Bluetooth and try again."); return; }
            BluetoothGattService s=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            rx=new BluetoothGattCharacteristic(RX,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE);
            tx=new BluetoothGattCharacteristic(TX,BluetoothGattCharacteristic.PROPERTY_INDICATE,BluetoothGattCharacteristic.PERMISSION_READ);
            tx.addDescriptor(new BluetoothGattDescriptor(CCC,BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE)); s.addCharacteristic(rx); s.addCharacteristic(tx);
            if(!server.addService(s)) fail("Couldn't create the Bluetooth service."); else listener.status("Opening room "+room+"…");
        } catch(Exception e) { fail("Bluetooth couldn't start. Check permissions and try again."); }
    }
    public void browse() {
        stop(); running=true;
        if(!enabled()) { fail("Turn on Bluetooth, then try again."); return; }
        try {
            devices.clear(); scanning=true;
            adapter.getBluetoothLeScanner().startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
            listener.status("Listening for nearby rooms…");
            int g=generation; main.postDelayed(()->{ if(running && scanning && g==generation) { stopScan(); listener.status(devices.isEmpty()?"No rooms yet. Ask your friend to open one, then search again.":"Choose a room below, or search again."); } },30000);
        } catch(Exception e) { fail("Couldn't scan. Allow Nearby devices. On Android 8–11, also enable Location."); }
    }
    public void join(String id) {
        BluetoothDevice d=devices.get(id); if(!running || d==null || client!=null) return;
        stopScan(); remote=d; listener.status("Connecting…");
        try { client=d.connectGatt(context,false,clientCallback,BluetoothDevice.TRANSPORT_LE); watchdog(20000,"Connection timed out. Move closer and try again."); }
        catch(Exception e) { fail("Couldn't connect. Try opening a new room."); }
    }
    private void stopScan() { if(scanning) { scanning=false; try { adapter.getBluetoothLeScanner().stopScan(scanCallback); } catch(Exception ignored) {} } }
    public void send(byte[] p) {
        if(!ready) { fail("The Bluetooth link has closed. Reconnect to send."); return; }
        List<byte[]> parts=Wire.split(p);
        if(outgoing.size()+parts.size()>512) { fail("Bluetooth queue is full. Reconnect and send again."); return; }
        outgoing.addAll(parts); pump();
    }
    public void sendThen(byte[] p,Runnable action) { afterSend=action; send(p); }
    private void pump() {
        if(!running || !ready || writing) return;
        if(outgoing.isEmpty()) { Runnable action=afterSend; afterSend=null; if(action!=null) action.run(); return; }
        writing=true; byte[] f=outgoing.peek(); boolean ok;
        try {
            if(hosting) { tx.setValue(f); ok=server.notifyCharacteristicChanged(remote,tx,true); }
            else { rx.setValue(f); rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); ok=client.writeCharacteristic(rx); }
            if(!ok) { fail("Bluetooth couldn't send. Reconnect and try again."); return; }
            int g=generation; byte[] current=f;
            main.postDelayed(()->{ if(g==generation && writing && outgoing.peek()==current) fail("Bluetooth stopped responding. Reconnect to continue."); },10000);
        } catch(Exception e) { fail("Bluetooth disconnected. Reconnect to continue."); }
    }
    private void sent(int status) {
        if(!running || !writing) return;
        if(status!=BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth transfer failed. Reconnect to continue."); return; }
        outgoing.poll(); writing=false; pump();
    }
    private void receive(byte[] f) {
        if(!running) return;
        if(f==null) { fail("An empty Bluetooth fragment arrived. Reconnect."); return; }
        if(!ready) { if(!hosting && client!=null && early.size()<8) early.add(f); else fail("Unexpected Bluetooth data. Reconnect."); return; }
        try { byte[] p=decoder.accept(f,SystemClock.elapsedRealtime()); if(p!=null) listener.packet(p); }
        catch(Exception e) { fail("A Bluetooth packet was incomplete. Reconnect to continue."); }
    }
    private void established() {
        if(ready || !running) return; ready=true; listener.status("Checking your connection…"); listener.connected();
        while(running && !early.isEmpty()) receive(early.poll());
    }
    private void watchdog(long ms,String message) { int g=generation; main.postDelayed(()->{ if(running && !ready && g==generation) fail(message); },ms); }
    private final AdvertiseCallback advertiseCallback=new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) { main.post(()->{ if(running && hosting) listener.status("Room "+room+" is open. Your friend can find it now."); }); }
        @Override public void onStartFailure(int code) { main.post(()->{ if(running && hosting) fail("Couldn't advertise this room ("+code+"). Try swapping who opens the room."); }); }
    };
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) { main.post(()->{
            if(!running || !scanning || client!=null) return;
            String id=result.getDevice().getAddress(), name="Nearby ChitChat room";
            ScanRecord r=result.getScanRecord();
            if(r!=null) {
                byte[] data=r.getServiceData(new ParcelUuid(SERVICE));
                if(data!=null && data.length==2) name=String.format(Locale.ROOT,"Room %02X%02X",data[0]&255,data[1]&255);
                else if(r.getDeviceName()!=null && r.getDeviceName().startsWith("ChitChat ")) name="Room "+r.getDeviceName().substring(9);
            }
            if(!devices.containsKey(id)) { devices.put(id,result.getDevice()); listener.found(id,name); }
        }); }
        @Override public void onScanFailed(int code) { main.post(()->{ if(running && scanning) fail("Bluetooth scan failed ("+code+"). Wait a few seconds and try again."); }); }
    };
    private final BluetoothGattServerCallback serverCallback=new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status,BluetoothGattService service) { main.post(()->{
            if(!running || !hosting || server==null || !SERVICE.equals(service.getUuid())) return;
            if(status!=BluetoothGatt.GATT_SUCCESS) { fail("Couldn't register the Bluetooth room."); return; }
            try {
                byte[] code={(byte)Integer.parseInt(room.substring(0,2),16),(byte)Integer.parseInt(room.substring(2),16)};
                adapter.getBluetoothLeAdvertiser().startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build(),new AdvertiseData.Builder().addServiceData(new ParcelUuid(SERVICE),code).build(),advertiseCallback);
            } catch(Exception e) { fail("Couldn't broadcast the room. Restart Bluetooth and try again."); }
        }); }
        @Override public void onConnectionStateChange(BluetoothDevice d,int status,int state) { main.post(()->{
            if(!running || !hosting) return;
            if(state==BluetoothProfile.STATE_CONNECTED) {
                if(remote!=null && !remote.equals(d)) { server.cancelConnection(d); return; }
                remote=d; try { adapter.getBluetoothLeAdvertiser().stopAdvertising(advertiseCallback); } catch(Exception ignored) {}
                listener.status("Friend connected. Setting up…"); watchdog(20000,"Your friend couldn't finish connecting. Open a new room.");
            } else if(state==BluetoothProfile.STATE_DISCONNECTED && d.equals(remote)) peerDisconnected("Your friend disconnected. Open a new room to reconnect.");
        }); }
        @Override public void onDescriptorReadRequest(BluetoothDevice d,int requestId,int offset,BluetoothGattDescriptor descriptor) { main.post(()->{
            if(server!=null) server.sendResponse(d,requestId,offset==0?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_INVALID_OFFSET,offset,ready?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        }); }
        @Override public void onDescriptorWriteRequest(BluetoothDevice d,int requestId,BluetoothGattDescriptor descriptor,boolean prepared,boolean response,int offset,byte[] value) { main.post(()->{
            if(!running || server==null) return;
            boolean ok=d.equals(remote) && CCC.equals(descriptor.getUuid()) && !prepared && offset==0 && Arrays.equals(value,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            if(response) server.sendResponse(d,requestId,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null);
            if(ok) established();
        }); }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice d,int requestId,BluetoothGattCharacteristic characteristic,boolean prepared,boolean response,int offset,byte[] value) { main.post(()->{
            if(!running || server==null) return;
            boolean ok=ready && d.equals(remote) && RX.equals(characteristic.getUuid()) && !prepared && offset==0 && value!=null && value.length>=4 && value.length<=20;
            if(response) server.sendResponse(d,requestId,ok?BluetoothGatt.GATT_SUCCESS:BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null);
            if(ok) receive(value);
        }); }
        @Override public void onNotificationSent(BluetoothDevice d,int status) { main.post(()->{ if(d.equals(remote)) sent(status); }); }
    };
    private final BluetoothGattCallback clientCallback=new BluetoothGattCallback() {
        @Override public void onServiceChanged(BluetoothGatt g) { main.post(()->{ if(g==client && running) fail("The room closed or changed. Find a new room to reconnect."); }); }
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) { main.post(()->{
            if(g!=client || !running) return;
            if(state==BluetoothProfile.STATE_DISCONNECTED) { peerDisconnected("Bluetooth disconnected. Keep both apps open and reconnect."); return; }
            if(status!=BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth couldn't connect. Keep both apps open and try again."); return; }
            if(state==BluetoothProfile.STATE_CONNECTED) { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); if(!g.discoverServices()) fail("Couldn't discover the room service."); }
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) { main.post(()->{
            if(g!=client || !running) return;
            BluetoothGattService s=g.getService(SERVICE);
            if(status!=BluetoothGatt.GATT_SUCCESS || s==null) { fail("This isn't a compatible ChitChat room."); return; }
            rx=s.getCharacteristic(RX); tx=s.getCharacteristic(TX);
            if(rx==null || tx==null || tx.getDescriptor(CCC)==null) { fail("The room service is incomplete."); return; }
            BluetoothGattDescriptor c=tx.getDescriptor(CCC); c.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            if(!g.setCharacteristicNotification(tx,true) || !g.writeDescriptor(c)) fail("Couldn't subscribe to messages.");
        }); }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status) { main.post(()->{
            if(g!=client || !running) return;
            if(status==BluetoothGatt.GATT_SUCCESS && CCC.equals(descriptor.getUuid())) established(); else fail("Message subscription failed.");
        }); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) { byte[] v=c.getValue(); byte[] copy=v==null?null:v.clone(); main.post(()->{ if(g==client && TX.equals(c.getUuid())) receive(copy); }); }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status) { main.post(()->{ if(g==client && RX.equals(c.getUuid())) sent(status); }); }
    };
    public void stop() {
        generation++; running=false; ready=false; writing=false; stopScan();
        try { if(adapter!=null && adapter.getBluetoothLeAdvertiser()!=null) adapter.getBluetoothLeAdvertiser().stopAdvertising(advertiseCallback); } catch(Exception ignored) {}
        if(client!=null) { BluetoothGatt c=client; client=null; try { c.disconnect(); c.close(); } catch(Exception ignored) {} }
        if(server!=null) { BluetoothGattServer s=server; server=null; try { if(remote!=null) s.cancelConnection(remote); s.close(); } catch(Exception ignored) {} }
        remote=null; tx=null; rx=null; afterSend=null; outgoing.clear(); early.clear(); decoder.reset(); devices.clear(); hosting=false;
    }
    private void fail(String reason) { stop(); listener.closed(reason); }
    private void peerDisconnected(String reason) { stop(); listener.disconnected(reason); }
}
