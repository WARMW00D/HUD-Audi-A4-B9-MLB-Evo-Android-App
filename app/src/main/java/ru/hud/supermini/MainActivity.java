package ru.hud.supermini;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.widget.ProgressBar;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.LocaleList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.text.InputType;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** All connection state and GATT operations are serialized on the main thread. */
@SuppressLint("MissingPermission")
public final class MainActivity extends Activity {
    private static final int PERMISSIONS = 20, ENABLE_BT = 21, PICK_BIN = 22;
    private static final int MAX_BIN_SIZE = 0x1f0000;
    private static final UUID SERVICE = UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000001");
    private static final UUID[] UUIDS = {
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000002"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000003"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000004"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000005"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000006"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000007"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000008"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-478142000009"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-47814200000A"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-47814200000B"),
        UUID.fromString("74d0a100-3d92-4f50-9b1a-47814200000C")
    };
    private static final int[] TITLES = {R.string.psd, R.string.vze, R.string.hud_language, R.string.units, R.string.fuel, R.string.tank, R.string.accel, R.string.tolerance, R.string.light_sensor, R.string.light_dark, R.string.light_bright};
    private static final int[][] CHOICES = {
        {R.string.off, R.string.on}, {R.string.off, R.string.on},
        {R.string.russian, R.string.english}, {R.string.metric, R.string.imperial}, {R.string.litres, R.string.gallons}, null, {R.string.off, R.string.on}, null, {R.string.off, R.string.on}, null, null
    };
    private static final int[] DETAILS = {
        R.string.psd_detail,
        R.string.vze_detail,
        R.string.language_detail,
        R.string.units_detail,
        R.string.fuel_detail, R.string.tank_detail, R.string.accel_detail, R.string.tolerance_detail,
        R.string.light_sensor_detail, R.string.light_dark_detail, R.string.light_bright_detail
    };
    private static final int BG = Color.rgb(16, 24, 32), CARD = Color.rgb(26, 38, 49);
    private static final int ACCENT = Color.rgb(84, 221, 232), MUTED = Color.rgb(171, 186, 197);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int[] values = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
    private final BluetoothGattCharacteristic[] characteristics = new BluetoothGattCharacteristic[UUIDS.length];
    private final Button[][] choices = new Button[UUIDS.length][2];
    private final TextView[] current = new TextView[UUIDS.length];
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final ArrayDeque<Op> queue = new ArrayDeque<>();
    private SharedPreferences prefs;
    private Context localized;
    private String appLanguage;
    private int statusKey = R.string.initial;
    private Object[] statusArgs = new Object[0];
    private Button languageRu, languageEn;
    private final Button[] numberEdits = new Button[UUIDS.length];
    private static final int TANK = 5, TOLERANCE = 7, LIGHT = 8, DARK = 9, BRIGHT = 10;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private String displayAddress = "";
    private LinearLayout results;
    private TextView status, deviceLabel;
    private Button connect, paired, scan, disconnect, refresh;
    private Button chooseBin, updateFirmware, cancelOta;
    private TextView firmwareLabel, otaStatus, otaProgressLabel;
    private ProgressBar otaProgress;
    private int otaStatusKey = R.string.ota_select_hint;
    private Object[] otaStatusArgs = new Object[0];
    private int otaReceived, otaTotal;
    private byte[] firmwareBytes, firmwareSha;
    private String firmwareName = "";
    private boolean loadingBin;
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private BluetoothGattCharacteristic otaControl, otaData;
    private OtaTransfer ota;
    private boolean scanning, connected, waitingBond, discovering, ready, destroyed;
    private Runnable pendingAction;
    private Op active;
    private final Runnable operationTimeout = () -> fail(R.string.operation_timeout);
    private final Runnable connectionTimeout = () -> fail(R.string.connection_timeout);
    private final Runnable scanTimeout = () -> stopScan(true);

    private static final class Op {
        final int index, value;
        final boolean write;
        // For reads: -1 = ordinary read; 0..200 = expected write confirmation.
        Op(int index, boolean write, int value) {
            this.index = index; this.write = write; this.value = value;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("hud", MODE_PRIVATE);
        String savedLanguage = prefs.getString("app_language", "");
        appLanguage = savedLanguage.equals("ru") || savedLanguage.equals("en") ? savedLanguage
            : (Locale.getDefault().getLanguage().equals("ru") ? "ru" : "en");
        updateLanguageContext();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Bluetooth system broadcasts may come from a privileged non-system UID.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
        render();
        note(adapter == null ? R.string.no_ble : R.string.initial);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(14)); return d;
    }
    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4)); return t;
    }
    private Button button(String title) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(Color.WHITE); b.setBackground(bg(CARD)); b.setMinHeight(dp(48));
        b.setPadding(dp(12), dp(8), dp(12), dp(8)); return b;
    }
    private void add(LinearLayout layout, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10); layout.addView(child, p);
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20)); scroll.addView(root);
        // Include system bars on Android 15+, where edge-to-edge is enforced.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top=i.top; bottom=i.bottom; left=i.left; right=i.right;
            } else {
                top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom();
                left=insets.getSystemWindowInsetLeft(); right=insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom); return insets;
        });
        TextView title = text("HUD Control", 30, Color.WHITE); title.setTypeface(null, Typeface.BOLD);
        add(root, title); add(root, text("Super Mini · NV3007", 14, MUTED));
        add(root, text(s(R.string.app_language), 16, Color.WHITE));
        LinearLayout languages = new LinearLayout(this);
        languageRu = button("RU"); languageEn = button("EN");
        LinearLayout.LayoutParams ruParams = new LinearLayout.LayoutParams(0, -2, 1);
        ruParams.rightMargin = dp(8); languages.addView(languageRu, ruParams);
        languages.addView(languageEn, new LinearLayout.LayoutParams(0, -2, 1));
        languageRu.setOnClickListener(v -> changeAppLanguage("ru"));
        languageEn.setOnClickListener(v -> changeAppLanguage("en"));
        add(root, languages); add(root, text(s(R.string.app_language_hint), 12, MUTED));
        status = text("", 16, ACCENT); add(root, status);
        deviceLabel = text("", 12, MUTED); add(root, deviceLabel);
        connect = button(s(R.string.connect_saved));
        connect.setOnClickListener(v -> ensure(() -> {
            String address = prefs.getString("address", "");
            if (BluetoothAdapter.checkBluetoothAddress(address)) connectTo(adapter.getRemoteDevice(address));
            else note(R.string.choose_first);
        })); add(root, connect);
        paired = button(s(R.string.paired)); paired.setOnClickListener(v -> ensure(this::showPaired)); add(root, paired);
        scan = button(s(R.string.find)); scan.setOnClickListener(v -> {
            if (scanning) stopScan(true); else ensure(this::startScan);
        }); add(root, scan);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); add(root, results);
        disconnect = button(s(R.string.disconnect)); disconnect.setOnClickListener(v -> { closeGatt(); note(R.string.disconnected); }); add(root, disconnect);
        refresh = button(s(R.string.refresh)); refresh.setOnClickListener(v -> readAll()); add(root, refresh);
        add(root, text(s(R.string.display_settings), 22, Color.WHITE));
        for (int i=0; i<UUIDS.length; i++) {
            final int index=i;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(12), dp(16), dp(16)); card.setBackground(bg(CARD));
            TextView t = text(s(TITLES[i]), 18, Color.WHITE); t.setTypeface(null, Typeface.BOLD); card.addView(t);
            card.addView(text(s(DETAILS[i]), 12, MUTED));
            current[i]=text(s(R.string.connect_first), 13, ACCENT); card.addView(current[i]);
            LinearLayout row = new LinearLayout(this);
            if (i == TANK || i == TOLERANCE) {
                Button edit = button(s(i == TANK ? R.string.tank_edit : R.string.tolerance_edit));
                numberEdits[i] = edit;
                edit.setOnClickListener(v -> editNumber(index));
                card.addView(edit); add(root, card); continue;
            }
            if (i == DARK || i == BRIGHT) {
                Button action = button(s(i == DARK ? R.string.light_dark_action : R.string.light_bright_action));
                numberEdits[i] = action;
                action.setOnClickListener(v -> change(index, 1));
                card.addView(action); add(root, card); continue;
            }
            for (int j=0; j<2; j++) {
                final int value=j;
                Button b=button(s(CHOICES[i][j])); choices[i][j]=b;
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0, -2, 1);
                if (j==0) p.rightMargin=dp(8); row.addView(b, p);
                b.setOnClickListener(v -> change(index, value));
            }
            card.addView(row); add(root, card);
        }
        add(root, text(s(R.string.nvs_hint), 13, MUTED));
        add(root, text(s(R.string.pair_hint), 13, MUTED));
        add(root, text(s(R.string.brightness_hint), 12, MUTED));
        add(root, text(s(R.string.ota_title), 22, Color.WHITE));
        add(root, text(s(R.string.ota_hint), 13, MUTED));
        firmwareLabel=text("", 13, MUTED); add(root, firmwareLabel);
        otaStatus=text("", 14, ACCENT); add(root, otaStatus);
        otaProgress=new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        otaProgress.setMax(100); add(root, otaProgress);
        otaProgressLabel=text("", 12, MUTED); add(root, otaProgressLabel);
        chooseBin=button(s(R.string.ota_choose));
        chooseBin.setOnClickListener(v -> {
            if (!otaIdle()) return;
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*"); pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(pick,PICK_BIN);
        }); add(root, chooseBin);
        updateFirmware=button(s(R.string.ota_update));
        updateFirmware.setOnClickListener(v -> confirmUpdate()); add(root, updateFirmware);
        cancelOta=button(s(R.string.ota_cancel));
        cancelOta.setOnClickListener(v -> { if (ota!=null) ota.cancel(); }); add(root, cancelOta);
        setContentView(scroll);
    }

    private void editNumber(int index) {
        if (!ready || characteristics[index] == null || active != null || !queue.isEmpty() || otaInProgress()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(values[index] >= 0 ? Integer.toString(values[index]) : (index == TANK ? "54" : "20"));
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(s(TITLES[index]))
            .setMessage(s(DETAILS[index])).setView(input)
            .setNegativeButton(s(R.string.cancel), null)
            .setPositiveButton(s(R.string.apply), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            int value;
            try { value = Integer.parseInt(input.getText().toString().trim()); }
            catch (NumberFormatException e) { input.setError(s(index == TANK ? R.string.tank_invalid : R.string.tolerance_invalid)); return; }
            if (!validSetting(index, value)) { input.setError(s(index == TANK ? R.string.tank_invalid : R.string.tolerance_invalid)); return; }
            change(index, value); dialog.dismiss();
        }));
        dialog.show();
    }
    private static boolean validSetting(int index, int value) {
        if (index == TANK) return value >= 1 && value <= 200;
        if (index == TOLERANCE) return value >= 0 && value <= 100;
        return value == 0 || value == 1;
    }
    private String settingText(int index, int value) {
        if (index == TANK) return s(R.string.tank_value, value);
        if (index == TOLERANCE) return s(R.string.tolerance_value, value);
        if (index == DARK || index == BRIGHT) return value == 1 ? s(R.string.light_calibrated) : s(R.string.light_not_calibrated);
        return s(CHOICES[index][value]);
    }

    private static final class TextArg {
        final int resource;
        TextArg(int resource) { this.resource = resource; }
    }
    private void updateLanguageContext() {
        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.forLanguageTag(appLanguage)));
        localized = createConfigurationContext(config);
    }
    private String s(int resource, Object... args) {
        Object[] translated = args.clone();
        for (int i=0; i<translated.length; i++) {
            if (translated[i] instanceof TextArg) translated[i] = localized.getString(((TextArg)translated[i]).resource);
        }
        return localized.getString(resource, translated);
    }
    private void note(int resource, Object... args) {
        statusKey = resource; statusArgs = args.clone();
        status.setText(s(statusKey, statusArgs));
    }
    private void changeAppLanguage(String language) {
        if (language.equals(appLanguage)) return;
        boolean wasScanning = scanning;
        stopScan(false);
        appLanguage = language;
        prefs.edit().putString("app_language", language).apply();
        updateLanguageContext();
        // Rebuild only views; keep the BLE link, operations and read values.
        buildUi();
        for (BluetoothDevice d : found.values()) {
            Button b = button("HUD · " + d.getAddress());
            b.setOnClickListener(v -> ensure(() -> connectTo(d))); add(results, b);
        }
        if (wasScanning) { statusKey = R.string.search_stopped; statusArgs = new Object[0]; }
        render(); status.setText(s(statusKey, statusArgs));
    }
    private void render() {
        for (Button b : new Button[]{languageRu, languageEn}) {
            boolean selected = (b == languageRu) == appLanguage.equals("ru");
            b.setBackground(bg(selected ? ACCENT : CARD)); b.setTextColor(selected ? BG : Color.WHITE);
        }
        boolean busy = gatt != null;
        boolean idle = ready && active == null && queue.isEmpty() && !otaInProgress();
        connect.setEnabled(!busy && !scanning && !prefs.getString("address", "").isEmpty());
        paired.setEnabled(!busy && !scanning); scan.setEnabled(!busy);
        scan.setText(scanning ? s(R.string.stop_search) : s(R.string.find));
        disconnect.setEnabled(busy && !otaInProgress()); refresh.setEnabled(idle);
        chooseBin.setEnabled(otaIdle());
        updateFirmware.setEnabled(otaIdle() && firmwareBytes!=null);
        cancelOta.setEnabled(ota!=null && ota.busy() && !ota.commitRequested());
        firmwareLabel.setText(firmwareBytes==null ? s(R.string.ota_no_file) : s(R.string.ota_file,firmwareName,firmwareBytes.length));
        otaStatus.setText(gatt!=null && ready && otaControl==null ? s(R.string.ota_old_firmware) : s(otaStatusKey,otaStatusArgs));
        otaProgress.setProgress(otaTotal==0 ? 0 : (int)((long)otaReceived*100/otaTotal));
        otaProgressLabel.setText(otaTotal==0 ? "" : s(R.string.ota_progress,(int)((long)otaReceived*100/otaTotal),otaReceived,otaTotal));
        String address = displayAddress.isEmpty() ? prefs.getString("address", "") : displayAddress;
        deviceLabel.setText(address.isEmpty() ? s(R.string.not_selected) : "HUD · " + address);
        for (int i=0; i<UUIDS.length; i++) {
            boolean supported = characteristics[i] != null;
            current[i].setText(ready && !supported ? s(R.string.new_settings_firmware) :
                values[i] < 0 ? s(R.string.not_read) : s(R.string.current_value, settingText(i, values[i])));
            if (i == TANK || i == TOLERANCE || i == DARK || i == BRIGHT) { numberEdits[i].setEnabled(idle && supported); continue; }
            for (int j=0; j<2; j++) {
                Button b=choices[i][j]; b.setEnabled(idle && supported); b.setAlpha(idle && supported ? 1f : 0.55f);
                b.setBackground(bg(values[i] == j ? ACCENT : BG));
                b.setTextColor(values[i] == j ? BG : Color.WHITE);
            }
        }
    }

    private String[] requiredPermissions() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private boolean permitted() {
        for (String p : requiredPermissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private void ensure(Runnable action) {
        if (adapter == null) { note(R.string.bt_unavailable); return; }
        if (!permitted()) {
            pendingAction=action; requestPermissions(requiredPermissions(), PERMISSIONS); return;
        }
        if (!adapter.isEnabled()) {
            pendingAction=action; startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT); return;
        }
        action.run();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(request, permissions, granted);
        if (request != PERMISSIONS) return;
        Runnable next=pendingAction; pendingAction=null;
        if (permitted() && next != null) ensure(next);
        else note(R.string.permissions);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request==PICK_BIN) {
            if (result==RESULT_OK && data!=null && data.getData()!=null) loadFirmware(data.getData());
            return;
        }
        if (request != ENABLE_BT) return;
        Runnable next=pendingAction; pendingAction=null;
        if (result == RESULT_OK && next != null) ensure(next); else note(R.string.enable_bt);
    }

    private void showPaired() {
        Map<String, BluetoothDevice> devices=new LinkedHashMap<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (d.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) devices.put(d.getAddress(), d);
        }
        if (devices.isEmpty()) { note(R.string.no_paired); return; }
        BluetoothDevice[] list=devices.values().toArray(new BluetoothDevice[0]);
        String[] labels=new String[list.length];
        for (int i=0; i<list.length; i++) labels[i]=label(list[i]);
        new AlertDialog.Builder(this).setTitle(s(R.string.choose_hud))
            .setItems(labels, (dialog, index) -> ensure(() -> connectTo(list[index])))
            .setNegativeButton(s(R.string.cancel), null).show();
    }
    private String label(BluetoothDevice d) {
        String name=d.getName(); return (name == null || name.isEmpty() ? s(R.string.unnamed) : name) + "\n" + d.getAddress();
    }
    private void startScan() {
        if (gatt != null) return;
        found.clear(); results.removeAllViews(); scanner=adapter.getBluetoothLeScanner();
        if (scanner == null) { note(R.string.no_scanner); return; }
        scanning=true;
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            main.postDelayed(scanTimeout, 12000);
            note(Build.VERSION.SDK_INT < 31 ? R.string.search_legacy : R.string.searching);
        } catch (RuntimeException e) { scanning=false; note(R.string.scan_error, e.getMessage()); }
        render();
    }
    private void stopScan(boolean report) {
        main.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { }
        }
        boolean was=scanning; scanning=false;
        if (!destroyed) render();
        if (report && was && !destroyed) note(found.isEmpty() ? R.string.not_found : R.string.select_found);
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (!scanning || destroyed || !permitted()) return;
                ScanRecord record=result.getScanRecord(); BluetoothDevice d=result.getDevice();
                String name=record == null ? null : record.getDeviceName();
                boolean match=name != null && name.toUpperCase(Locale.ROOT).startsWith("HUD");
                if (record != null && record.getServiceUuids() != null) match |= record.getServiceUuids().contains(new ParcelUuid(SERVICE));
                match |= d.getAddress().equals(prefs.getString("address", ""));
                if (!match || found.containsKey(d.getAddress())) return;
                found.put(d.getAddress(), d);
                Button b=button((name == null ? "HUD" : name) + " · " + d.getAddress());
                b.setOnClickListener(v -> ensure(() -> connectTo(d))); add(results, b);
            });
        }
        @Override public void onScanFailed(int error) {
            main.post(() -> { if (!destroyed && scanning) { stopScan(false); note(R.string.scan_error_code, error); } });
        }
    };

    private void connectTo(BluetoothDevice d) {
        if (gatt != null) return;
        stopScan(false); results.removeAllViews(); device=d; displayAddress=d.getAddress();
        ota=null;
        Arrays.fill(values, -1); ready=false; connected=false; waitingBond=false; discovering=false;
        note(R.string.connecting);
        try {
            gatt=d.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail(R.string.no_gatt); return; }
            main.postDelayed(connectionTimeout, 60000);
        } catch (RuntimeException e) { fail(R.string.connect_error, e.getMessage()); }
        render();
    }
    private void startDiscovery() {
        if (gatt == null || !connected || discovering) return;
        waitingBond=false; discovering=true; note(R.string.checking_service);
        BluetoothGatt expected=gatt;
        // The firmware starts security on connect, also for previously bonded phones.
        main.postDelayed(() -> {
            if (expected != gatt || !connected) return;
            try { if (!gatt.discoverServices()) fail(R.string.discovery_failed); }
            catch (RuntimeException e) { fail(R.string.bt_error, e.getMessage()); }
        }, 1200);
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                    stopScan(false); closeGatt(); note(R.string.bt_off);
                }
                return;
            }
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()) || !permitted()) return;
            BluetoothDevice d;
            if (Build.VERSION.SDK_INT >= 33) d=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            else d=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || d == null || !device.getAddress().equals(d.getAddress()) || gatt == null) return;
            // Query the actual device state rather than trusting broadcast extras.
            int state=device.getBondState();
            if (state == BluetoothDevice.BOND_BONDED && waitingBond) startDiscovery();
            else if (state == BluetoothDevice.BOND_NONE && waitingBond) fail(R.string.bond_cancelled);
        }
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onServiceChanged(BluetoothGatt g) {
            main.post(() -> {
                if (destroyed || g!=gatt || !connected) return;
                // Initial discovery is already scheduled after encryption.
                if (!ready && active==null && queue.isEmpty() && !otaInProgress()) return;
                boolean mayHaveCommitted=ota!=null && ota.commitRequested();
                closeGatt();
                note(mayHaveCommitted ? R.string.ota_result_unknown : R.string.service_changed_retry);
                otaNote(mayHaveCommitted ? R.string.ota_result_unknown : R.string.service_changed_retry);
            });
        }
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int code) {
            main.post(() -> { if (!destroyed && ota!=null) ota.mtu(g,mtu,code); });
        }
        @Override public void onConnectionStateChange(BluetoothGatt g, int code, int state) {
            main.post(() -> {
                if (g != gatt || destroyed) return;
                if (code != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (ota!=null && ota.commitRequested()) {
                        boolean confirmed=ota.commitConfirmed();
                        closeGatt();
                        note(confirmed ? R.string.ota_success : R.string.ota_result_unknown);
                        otaNote(confirmed ? R.string.ota_success : R.string.ota_result_unknown);
                    } else {
                        boolean transferring=ota!=null && ota.busy();
                        fail(R.string.link_lost, code);
                        if (transferring) otaNote(R.string.ota_disconnected);
                    }
                    return;
                }
                if (state != BluetoothProfile.STATE_CONNECTED) return;
                connected=true;
                try {
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) startDiscovery();
                    else {
                        waitingBond=true;
                        note(R.string.pairing);
                        if (device.getBondState() != BluetoothDevice.BOND_BONDING && !device.createBond()) fail(R.string.bond_start_failed);
                    }
                } catch (RuntimeException e) { fail(R.string.bond_error, e.getMessage()); }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int code) {
            main.post(() -> {
                if (g != gatt || destroyed) return;
                if (code != BluetoothGatt.GATT_SUCCESS) { fail(R.string.discovery_error, code); return; }
                BluetoothGattService service=g.getService(SERVICE);
                if (service == null) { fail(R.string.service_missing); return; }
                for (int i=0; i<UUIDS.length; i++) {
                    characteristics[i]=service.getCharacteristic(UUIDS[i]);
                    if (characteristics[i] == null || (characteristics[i].getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0 ||
                        (characteristics[i].getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                        if (i < 5) { fail(R.string.incomplete_service, new TextArg(TITLES[i])); return; }
                        characteristics[i] = null; // Optional on older firmware.

                    }
                }
                BluetoothGattService otaService=g.getService(OtaTransfer.SERVICE);
                otaControl=otaService==null ? null : otaService.getCharacteristic(OtaTransfer.CONTROL);
                otaData=otaService==null ? null : otaService.getCharacteristic(OtaTransfer.DATA);
                if (otaControl==null || otaData==null ||
                    (otaControl.getProperties() & (BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE)) !=
                        (BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE) ||
                    (otaData.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE)==0) {
                    otaControl=otaData=null;
                }
                BluetoothGatt expected=gatt;
                main.postDelayed(() -> { if (gatt == expected) readAll(); }, 500);
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            if (Build.VERSION.SDK_INT >= 33) return;
            byte[] bytes=c.getValue(); byte[] copy=bytes == null ? null : bytes.clone();
            main.post(() -> readResult(g, c, copy, code));
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] bytes, int code) {
            byte[] copy=bytes == null ? null : bytes.clone(); main.post(() -> readResult(g, c, copy, code));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            main.post(() -> {
                if (ota!=null && ota.wrote(g,c,code)) return;
                if (!matches(g, c, true)) return;
                if (code != BluetoothGatt.GATT_SUCCESS) { fail(R.string.write_error, code); return; }
                Op written=active; active=null; main.removeCallbacks(operationTimeout);
                queue.addFirst(new Op(written.index, false, written.value));
                note(R.string.verifying); pump();
            });
        }
    };

    private boolean matches(BluetoothGatt g, BluetoothGattCharacteristic c, boolean write) {
        return !destroyed && g == gatt && active != null && active.write == write && UUIDS[active.index].equals(c.getUuid());
    }
    private void readResult(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] bytes, int code) {
        if (ota!=null && ota.read(g,c,bytes,code)) return;
        if (!matches(g, c, false)) return;
        if (code != BluetoothGatt.GATT_SUCCESS || bytes == null || bytes.length != 1 || !validSetting(active.index, bytes[0] & 255)) {
            fail(R.string.read_error, code); return;
        }
        Op read=active; active=null; main.removeCallbacks(operationTimeout);
        values[read.index]=bytes[0] & 255;
        if (read.value >= 0) {
            if (values[read.index] == read.value) note(R.string.saved, new TextArg(TITLES[read.index]), settingText(read.index, read.value));
            else note(R.string.write_not_confirmed);
        }
        boolean all=true; for (int i=0; i<UUIDS.length; i++) all &= characteristics[i] == null || values[i] >= 0;
        if (all && !ready) {
            ready=true; main.removeCallbacks(connectionTimeout);
            prefs.edit().putString("address", displayAddress).apply();
            note(R.string.connected);
        }
        pump();
    }
    private void readAll() {
        if (gatt == null || !connected || active != null || !queue.isEmpty() || characteristics[0] == null || otaInProgress()) return;
        ready=false; Arrays.fill(values, -1); note(R.string.reading);
        for (int i=0; i<UUIDS.length; i++) if (characteristics[i] != null) queue.add(new Op(i, false, -1));
        pump();
    }
    private void change(int index, int value) {
        if (!ready || characteristics[index] == null || !validSetting(index, value) || active != null || !queue.isEmpty() || (values[index] == value && index != DARK && index != BRIGHT) || otaInProgress()) return;
        queue.add(new Op(index, true, value)); note(R.string.saving, new TextArg(TITLES[index])); pump();
    }
    private void pump() {
        if (gatt == null || active != null) { render(); return; }
        active=queue.poll(); render(); if (active == null) return;
        BluetoothGattCharacteristic c=characteristics[active.index];
        try {
            boolean started;
            if (!active.write) started=gatt.readCharacteristic(c);
            else if (Build.VERSION.SDK_INT >= 33) {
                started=gatt.writeCharacteristic(c, new byte[]{(byte)active.value}, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                c.setValue(new byte[]{(byte)active.value}); started=gatt.writeCharacteristic(c);
            }
            if (!started) { fail(R.string.operation_not_started); return; }
            main.postDelayed(operationTimeout, 15000);
        } catch (RuntimeException e) { fail(R.string.ble_error, e.getMessage()); }
    }

    private boolean otaInProgress() {
        return ota!=null && (ota.busy() || ota.commitRequested());
    }
    private boolean otaIdle() {
        return ready && active==null && queue.isEmpty() && otaControl!=null && otaData!=null && !otaInProgress() && !loadingBin;
    }
    private void otaNote(int key,Object... args) {
        otaStatusKey=key; otaStatusArgs=args.clone();
        if (!destroyed) render();
    }
    private void loadFirmware(Uri uri) {
        if (!otaIdle()) return;
        firmwareBytes=firmwareSha=null; firmwareName="";
        loadingBin=true; otaNote(R.string.ota_loading); render();
        fileWorker.execute(() -> {
            try {
                String filename="firmware.bin";
                try (Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if (cursor!=null && cursor.moveToFirst()) {
                        String name=cursor.getString(0); if (name!=null) filename=name;
                    }
                }
                byte[] binary;
                try (InputStream input=getContentResolver().openInputStream(uri); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    if (input==null) throw new IllegalArgumentException("No file stream");
                    byte[] buffer=new byte[16384]; int count;
                    while ((count=input.read(buffer))!=-1) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if ((long)output.size()+count>MAX_BIN_SIZE) {
                            main.post(() -> { if (!destroyed) { loadingBin=false; otaNote(R.string.ota_file_too_large,MAX_BIN_SIZE); } }); return;
                        }
                        output.write(buffer,0,count);
                    }
                    binary=output.toByteArray();
                }
                if (binary.length<36 || (binary[0]&255)!=0xe9 || (binary[12]&255)!=9 || binary[13]!=0 ||
                    ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).getInt(32)!=0xabcd5432) {
                    main.post(() -> { if (!destroyed) { loadingBin=false; otaNote(R.string.ota_invalid_file); } }); return;
                }
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(binary);
                String selected=filename;
                main.post(() -> {
                    if (destroyed) return;
                    firmwareName=selected; firmwareBytes=binary; firmwareSha=digest;
                    loadingBin=false; otaReceived=otaTotal=0; otaNote(R.string.ota_file_ready);
                });
            } catch (Exception e) {
                main.post(() -> { if (!destroyed) { loadingBin=false; otaNote(R.string.ota_file_error,e.getMessage()); } });
            }
        });
    }
    private void confirmUpdate() {
        if (!otaIdle() || firmwareBytes==null) return;
        new AlertDialog.Builder(this).setTitle(s(R.string.ota_confirm_title))
            .setMessage(s(R.string.ota_confirm,firmwareName,firmwareBytes.length))
            .setNegativeButton(s(R.string.cancel),null)
            .setPositiveButton(s(R.string.ota_update),(dialog,which) -> startUpdate()).show();
    }
    private void startUpdate() {
        if (!otaIdle() || firmwareBytes==null) return;
        otaReceived=0; otaTotal=firmwareBytes.length;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ota=new OtaTransfer(gatt,otaControl,otaData,main,new OtaTransfer.Listener() {
            @Override public void status(int key,Object... args) { otaNote(key,args); }
            @Override public void progress(int received,int total) {
                otaReceived=received; otaTotal=total; if (!destroyed) render();
            }
            @Override public void finished(boolean committed) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (committed) note(R.string.ota_success);
                if (!destroyed) render();
            }
            @Override public void error(int key,Object... args) {
                closeGatt(); note(key,args); otaNote(key,args);
            }
        },firmwareBytes,firmwareSha);
        ota.start(); render();
    }

    private void fail(int resource, Object... args) { closeGatt(); if (!destroyed) note(resource, args); }
    private void closeGatt() {
        main.removeCallbacks(connectionTimeout); main.removeCallbacks(operationTimeout);
        if (ota!=null) ota.disconnected();
        otaControl=otaData=null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothGatt old=gatt; gatt=null; queue.clear(); active=null;
        ready=false; connected=false; waitingBond=false; discovering=false;
        Arrays.fill(characteristics, null); Arrays.fill(values, -1);
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) { }
            try { old.close(); } catch (RuntimeException ignored) { }
        }
        if (!destroyed) render();
    }
    @Override protected void onPause() { super.onPause(); stopScan(false); }
    @Override protected void onDestroy() {
        destroyed=true; stopScan(false); closeGatt(); main.removeCallbacksAndMessages(null);
        fileWorker.shutdownNow(); unregisterReceiver(receiver); super.onDestroy();
    }
}
