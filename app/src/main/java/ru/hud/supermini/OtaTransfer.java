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

/** Stop-and-wait transfer; one GATT operation at a time, including MTU negotiation. */
@SuppressLint("MissingPermission")
final class OtaTransfer {
    static final UUID SERVICE=UUID.fromString("74d0a200-3d92-4f50-9b1a-478142000001");
    static final UUID CONTROL=UUID.fromString("74d0a200-3d92-4f50-9b1a-478142000002");
    static final UUID DATA=UUID.fromString("74d0a200-3d92-4f50-9b1a-478142000003");
    interface Listener {
        void status(int key, Object... args);
        void progress(int received, int total);
        void finished(boolean committed);
        void error(int key, Object... args);
    }
    private enum Step { MTU, INFO, START_WRITE, START_READ, DATA_WRITE, DATA_READ,
        END_WRITE, END_READ, COMMIT_WRITE, COMMIT_READ, ABORT_WRITE, ABORT_READ }
    private final BluetoothGatt gatt;
    private final BluetoothGattCharacteristic control, data;
    private final Handler main;
    private final Listener listener;
    private final byte[] image, digest;
    private final Runnable timeout;
    private Step step;
    private boolean busy, cancelRequested, commitRequested, commitConfirmed;
    private int offset, expectedOffset, chunk=240;

    OtaTransfer(BluetoothGatt g, BluetoothGattCharacteristic c, BluetoothGattCharacteristic d,
                Handler handler, Listener events, byte[] binary, byte[] sha256) {
        gatt=g; control=c; data=d; main=handler; listener=events; image=binary; digest=sha256;
        timeout=() -> error(R.string.ota_timeout);
    }
    boolean busy() { return busy; }
    boolean commitRequested() { return commitRequested; }
    boolean commitConfirmed() { return commitConfirmed; }
    void start() {
        busy=true; step=Step.MTU; listener.progress(0,image.length);
        listener.status(R.string.ota_negotiating);
        try {
            if (!gatt.requestMtu(247)) { error(R.string.ota_mtu); return; }
            arm();
        } catch (RuntimeException e) { error(R.string.ota_transport_error,e.getMessage()); }
    }
    void disconnected() { busy=false; main.removeCallbacks(timeout); }
    void cancel() {
        if (!busy || commitRequested) return;
        cancelRequested=true; listener.status(R.string.ota_cancelling);
        // Wait for the current operation to finish; never overlap GATT operations.
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
        UUID expected=step==Step.DATA_WRITE ? DATA : CONTROL;
        if (!expected.equals(characteristic.getUuid())) return false;
        disarm();
        if (status!=BluetoothGatt.GATT_SUCCESS) { error(R.string.ota_gatt_error,status); return true; }
        if (cancelIfRequested()) return true;
        switch (step) {
            case START_WRITE: read(Step.START_READ); break;
            case DATA_WRITE: read(Step.DATA_READ); break;
            case END_WRITE: read(Step.END_READ); break;
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
        // INFO is allowed to report an earlier aborted session; START resets it.
        if (step!=Step.INFO && state==4) { error(R.string.ota_device_error,code); return true; }
        if (cancelIfRequested()) return true;
        switch (step) {
            case INFO:
                if (capacity<image.length || capacity==0) { error(R.string.ota_capacity,capacity); break; }
                if (state==3) { error(R.string.ota_reboot_wait); break; }
                byte[] start=new byte[37]; start[0]=1;
                ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN).putInt(1,image.length);
                System.arraycopy(digest,0,start,5,32);
                listener.status(R.string.ota_preparing); write(Step.START_WRITE,control,start); break;
            case START_READ:
                if (state!=1 || received!=0 || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                sendNext(); break;
            case DATA_READ:
                if (state!=1 || received!=expectedOffset || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                offset=received; listener.progress(offset,image.length); sendNext(); break;
            case END_READ:
                if (state!=2 || received!=image.length || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                listener.status(R.string.ota_switching); commitRequested=true;
                write(Step.COMMIT_WRITE,control,new byte[]{3}); break;
            case COMMIT_READ:
                if (state!=3 || received!=image.length || total!=image.length || code!=0) { error(R.string.ota_protocol_error); break; }
                commitConfirmed=true; busy=false; listener.progress(image.length,image.length);
                listener.status(R.string.ota_success); listener.finished(true); break;
            case ABORT_READ:
                if (state!=0 || received!=0 || total!=0 || code!=0) { error(R.string.ota_protocol_error); break; }
                busy=false; listener.status(R.string.ota_cancelled); listener.finished(false); break;
            default: error(R.string.ota_protocol_error);
        }
        return true;
    }
    private void sendNext() {
        if (offset==image.length) {
            listener.status(R.string.ota_verifying); write(Step.END_WRITE,control,new byte[]{2}); return;
        }
        int n=Math.min(chunk,image.length-offset);
        byte[] packet=new byte[n+4]; ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(offset);
        System.arraycopy(image,offset,packet,4,n); expectedOffset=offset+n;
        listener.status(R.string.ota_sending); write(Step.DATA_WRITE,data,packet);
    }
    private boolean cancelIfRequested() {
        if (!cancelRequested || step==Step.ABORT_WRITE || step==Step.ABORT_READ || commitRequested) return false;
        cancelRequested=false; write(Step.ABORT_WRITE,control,new byte[]{4}); return true;
    }
    private static boolean isWrite(Step value) {
        return value==Step.START_WRITE || value==Step.DATA_WRITE || value==Step.END_WRITE || value==Step.COMMIT_WRITE || value==Step.ABORT_WRITE;
    }
    private void read(Step next) {
        step=next;
        try {
            if (!gatt.readCharacteristic(control)) { error(R.string.ota_transport_start); return; }
            arm();
        } catch (RuntimeException e) { error(R.string.ota_transport_error,e.getMessage()); }
    }
    private void write(Step next,BluetoothGattCharacteristic characteristic,byte[] bytes) {
        step=next;
        try {
            boolean started;
            if (Build.VERSION.SDK_INT>=33) started=gatt.writeCharacteristic(characteristic,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;
            else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
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
        // After requesting COMMIT, an absent ACK cannot prove that boot selection failed.
        if (commitRequested) listener.error(R.string.ota_result_unknown);
        else listener.error(key,args);
    }
}
