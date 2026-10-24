package ru.hud.supermini;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;
import android.os.Handler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Upload of cams.bin to the HUD flash. Same control protocol as OTA (see OtaTransfer), but the
 * data goes as a stream of Write Without Response packets in windows of WINDOW packets: after each
 * window the status is read, and if the HUD lost a packet the transfer resumes from the HUD's
 * "received" offset. This is ~10-20x faster than an acknowledged write plus a read per packet.
 */
@SuppressLint("MissingPermission")
final class CamTransfer {
    static final UUID SERVICE=UUID.fromString("74d0a200-3d92-4f50-9b1a-478142000011");
    static final UUID CONTROL=UUID.fromString("74d0a200-3d92-4f50-9b1a-478142000012");
    static final UUID DATA=UUID.fromString("74d0a200-3d92-4f50-9b1a-478142000013");
    private static final int WINDOW=8, MAX_STALLS=40;
    interface Listener {
        void status(int key, Object... args);
        void progress(int received, int total);
        void finished(boolean done);
        void error(int key, Object... args);
    }
    private enum Step { MTU, INFO, START_WRITE, START_READ, WIN_WRITE, WIN_READ,
        END_WRITE, END_READ, COMMIT_WRITE, COMMIT_READ, ABORT_WRITE, ABORT_READ }
    private final BluetoothGatt gatt;
    private final BluetoothGattCharacteristic control, data;
    private final Handler main;
    private final Listener listener;
    private final byte[] image, digest;
    private final Runnable timeout;
    private Step step;
    private boolean busy, cancelRequested, commitRequested;
    private int offset, windowEnd, stalls, chunk=240;

    CamTransfer(BluetoothGatt g, BluetoothGattCharacteristic c, BluetoothGattCharacteristic d,
                Handler handler, Listener events, byte[] binary, byte[] sha256) {
        gatt=g; control=c; data=d; main=handler; listener=events; image=binary; digest=sha256;
        timeout=() -> error(R.string.ota_timeout);
    }
    boolean busy() { return busy; }
    boolean commitRequested() { return commitRequested; }
    void start() {
        busy=true; step=Step.MTU; listener.progress(0,image.length);
        listener.status(R.string.ota_negotiating);
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            if (!gatt.requestMtu(247)) { error(R.string.ota_mtu); return; }
            arm();
        } catch (RuntimeException e) { error(R.string.ota_transport_error,e.getMessage()); }
    }
    void disconnected() { busy=false; main.removeCallbacks(timeout); }
    void cancel() {
        if (!busy || commitRequested) return;
        cancelRequested=true; listener.status(R.string.ota_cancelling);
        // The current GATT operation (or the end of the window) finishes first.
    }
    boolean mtu(BluetoothGatt g,int size,int status) {
        if (g!=gatt || !busy || step!=Step.MTU) return false;
        disarm();
        if (cancelIfRequested()) return true;
        if (status!=BluetoothGatt.GATT_SUCCESS || size<64) { error(R.string.ota_mtu); return true; }
        chunk=Math.min(240,size-7); read(Step.INFO); return true;
    }
    boolean wrote(BluetoothGatt g,BluetoothGattCharacteristic characteristic,int status) {
        if (g!=gatt || !busy || step==null || !isWrite(step)) return false;
        UUID expected=step==Step.WIN_WRITE ? DATA : CONTROL;
        if (!expected.equals(characteristic.getUuid())) return false;
        disarm();
        if (status!=BluetoothGatt.GATT_SUCCESS) { error(R.string.ota_gatt_error,status); return true; }
        switch (step) {
            case START_WRITE: if (!cancelIfRequested()) read(Step.START_READ); break;
            case WIN_WRITE:
                if (cancelIfRequested()) break;
                if (offset<windowEnd) sendPacket(); else read(Step.WIN_READ);
                break;
            case END_WRITE: if (!cancelIfRequested()) read(Step.END_READ); break;
            case COMMIT_WRITE: read(Step.COMMIT_READ); break;
            case ABORT_WRITE: read(Step.ABORT_READ); break;
            default: error(R.string.ota_protocol_error);
        }
        return true;
    }
    boolean read(BluetoothGatt g,BluetoothGattCharacteristic characteristic,byte[] bytes,int status) {
        if (g!=gatt || !busy || step==null || isWrite(step) || step==Step.MTU || !CONTROL.equals(characteristic.getUuid())) return false;
        disarm();
        if (status!=BluetoothGatt.GATT_SUCCESS) { error(R.string.ota_gatt_error,status); return true; }
        if (bytes==null || bytes.length!=16 || bytes[0]!=1 || bytes[3]!=0) { error(R.string.ota_protocol_error); return true; }
        int state=bytes[1]&255, code=bytes[2]&255;
        ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int received=buffer.getInt(4), total=buffer.getInt(8), capacity=buffer.getInt(12);
        if (received<0 || total<0 || capacity<0 || state>4) { error(R.string.ota_protocol_error); return true; }
        if (step!=Step.INFO && state==4) { error(R.string.cam_device_error,code); return true; }
        if (cancelIfRequested()) return true;
        switch (step) {
            case INFO:
                if (capacity==0) { error(R.string.cam_no_flash); break; }
                if (capacity<image.length) { error(R.string.cam_capacity,capacity); break; }
                byte[] start=new byte[37]; start[0]=1;
                ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN).putInt(1,image.length);
                System.arraycopy(digest,0,start,5,32);
                listener.status(R.string.cam_erasing); write(Step.START_WRITE,control,start,false); break;
            case START_READ:
                if (state!=1 || received!=0 || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                offset=0; stalls=0; startWindow(); break;
            case WIN_READ:
                if (state!=1 || total!=image.length || code!=0 || received>windowEnd) { error(R.string.ota_protocol_error); break; }
                if (received==windowEnd) stalls=0;
                else if (++stalls>MAX_STALLS) { error(R.string.cam_stalled); break; }
                offset=received; listener.progress(offset,image.length); startWindow(); break;
            case END_READ:
                if (state!=2 || received!=image.length || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                listener.status(R.string.cam_loading_db); commitRequested=true;
                write(Step.COMMIT_WRITE,control,new byte[]{3},false); break;
            case COMMIT_READ:
                if (state!=3 || received!=image.length || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                busy=false; gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                listener.progress(image.length,image.length);
                listener.status(R.string.cam_success); listener.finished(true); break;
            case ABORT_READ:
                if (state!=0 || received!=0 || total!=0 || code!=0) { error(R.string.ota_protocol_error); break; }
                busy=false; gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                listener.status(R.string.ota_cancelled); listener.finished(false); break;
            default: error(R.string.ota_protocol_error);
        }
        return true;
    }
    private void startWindow() {
        if (offset>=image.length) {
            listener.status(R.string.cam_verifying); write(Step.END_WRITE,control,new byte[]{2},false); return;
        }
        windowEnd=Math.min(image.length,offset+WINDOW*chunk);
        listener.status(R.string.cam_sending); sendPacket();
    }
    /** Next packet of the current window; the following one is sent from the write callback. */
    private void sendPacket() {
        int n=Math.min(chunk,windowEnd-offset);
        byte[] packet=new byte[n+4]; ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(offset);
        System.arraycopy(image,offset,packet,4,n);
        offset+=n;
        write(Step.WIN_WRITE,data,packet,true);
    }
    private boolean cancelIfRequested() {
        if (!cancelRequested || step==Step.ABORT_WRITE || step==Step.ABORT_READ || commitRequested) return false;
        cancelRequested=false; write(Step.ABORT_WRITE,control,new byte[]{4},false); return true;
    }
    private static boolean isWrite(Step value) {
        return value==Step.START_WRITE || value==Step.WIN_WRITE || value==Step.END_WRITE || value==Step.COMMIT_WRITE || value==Step.ABORT_WRITE;
    }
    private void read(Step next) {
        step=next;
        try {
            if (!gatt.readCharacteristic(control)) { error(R.string.ota_transport_start); return; }
            arm();
        } catch (RuntimeException e) { error(R.string.ota_transport_error,e.getMessage()); }
    }
    private void write(Step next,BluetoothGattCharacteristic characteristic,byte[] bytes,boolean noResponse) {
        step=next;
        int type=noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            boolean started;
            if (Build.VERSION.SDK_INT>=33) started=gatt.writeCharacteristic(characteristic,bytes,type)==BluetoothStatusCodes.SUCCESS;
            else {
                characteristic.setWriteType(type);
                characteristic.setValue(bytes); started=gatt.writeCharacteristic(characteristic);
            }
            if (!started) { error(R.string.ota_transport_start); return; }
            arm();
        } catch (RuntimeException e) { error(R.string.ota_transport_error,e.getMessage()); }
    }
    private void arm() { main.removeCallbacks(timeout); main.postDelayed(timeout,30000); }
    private void disarm() { main.removeCallbacks(timeout); }
    private void error(int key,Object... args) {
        busy=false; disarm();
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        // After COMMIT the HUD may already have switched databases, even if the reply was lost.
        if (commitRequested) listener.error(R.string.cam_result_unknown);
        else listener.error(key,args);
    }
}
