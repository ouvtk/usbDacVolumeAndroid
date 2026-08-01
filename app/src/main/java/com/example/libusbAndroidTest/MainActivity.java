package com.example.libusbAndroidTest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;
import com.example.libusbAndroidTest.UsbDescriptorParser.*;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("libusbAndroidTest");
    }

    private ActivityMainBinding binding;
    private UsbManager usbManager;

    private TextView tvCurrentVolume;
    private TextView tvDeviceName;
    private EditText volInput;
    private Spinner deviceSpinner;
    private Spinner interfaceSpinner;

    private CheckBox autoApply;
    private CheckBox quitAfterApply;
    private Button permissionBtn;
    private Button connectBtn;

    private UsbDevice selectedDevice;
    private UsbInterface selectedInterface;

    // Store device mapping: display name -> UsbDevice
    private HashMap<String, UsbDevice> devices = new HashMap<>();
    private List<String> deviceDisplayNames = new ArrayList<>();

    // Store interface mapping: display name -> UsbInterface
    private HashMap<String, UsbInterface> interfaces = new HashMap<>();
    private List<String> interfaceDisplayNames = new ArrayList<>();

    // Track connected devices to prevent duplicate connections
    private HashMap<String, UsbDeviceConnection> connectedDevices = new HashMap<>();

    // Debounce mechanism
    private long lastDeviceListRefresh = 0;
    private static final long REFRESH_DEBOUNCE_MS = 500; // Wait 500ms before updating device list again

    private static final int APPLE_VENDOR_ID = 1452;
    private static final int APPLE_DONGLE_PRODUCT_ID = 4362;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;
    private static final String PREFS_KEY = "myPrefs";
    private static final int USB_RECIP_DEVICE = 0x00;
    private static final int USB_RECIP_INTERFACE = 0x01;

    // Debug logging
    private TextView debugLogView;
    private ScrollView debugScrollView;
    private StringBuilder debugLog = new StringBuilder();
    private boolean debugVisible = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            logAction("✓ Permission granted for device: " + device.getDeviceName());
                            runOnUiThread(() -> updateButtonStates());
                        }
                    } else {
                        logAction("✗ Permission denied for device " + device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // Handle device attachment
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    addDebugLog("🔌 USB device attached: " + device.getDeviceName());
                    if (isDebounceExpired()) {
                        refreshDeviceList();
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // Handle device detachment
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    String deviceName = device.getDeviceName();
                    addDebugLog("🔌 USB device detached: " + deviceName);
                    UsbDeviceConnection connection = connectedDevices.remove(deviceName);
                    connection.close();

                    connectedDevices.remove(deviceName);
                    if (selectedDevice != null &&
                            selectedDevice.getDeviceName().equals(deviceName)) {
                        selectedDevice = null;
                        selectedInterface = null;
                        updateInterfaceList(null);
                    }
                    if (isDebounceExpired()) {
                        refreshDeviceList();
                    }
                    runOnUiThread(() -> updateButtonStates());
                }
            }
        }
    };

    /**
     * Checks if enough time has passed since last device list refresh.
     * Updates the refresh timestamp if expired.
     */
    private boolean isDebounceExpired() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDeviceListRefresh > REFRESH_DEBOUNCE_MS) {
            lastDeviceListRefresh = currentTime;
            return true;
        }
        return false;
    }

    /**
     * Logs both to debug log and to Android Logcat.
     */
    private void logAction(String message) {
        addDebugLog(message);
        Log.d(TAG, message);
    }

    /**
     * Logs an error to both debug log and Android Logcat.
     */
    private void logError(String message, Throwable e) {
        addDebugLog(message);
        Log.e(TAG, message, e);
    }

    /**
     * Requests USB permission for a device.
     */
    private void requestDevicePermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String displayName = getDeviceDisplayName(device);
        addDebugLog("→ Requesting permission for: " + displayName);
        usbManager.requestPermission(device, permissionIntent);
    }

    /**
     * Saves a preference value (String or Boolean).
     */
    private void savePref(String key, Object value) {
        SharedPreferences settings = getApplicationContext().getSharedPreferences(PREFS_KEY, 0);
        SharedPreferences.Editor editor = settings.edit();
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        }
        editor.apply();
    }

    /**
     * Clears error state from input and device name display.
     */
    private void clearErrorState() {
        volInput.setBackgroundColor(Color.TRANSPARENT);
        tvDeviceName.setBackgroundColor(Color.TRANSPARENT);
    }

    private void addDebugLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";
        debugLog.append(logEntry);

        if (debugLogView != null) {
            runOnUiThread(() -> {
                debugLogView.setText(debugLog.toString());
                // Auto-scroll to bottom
                if (debugScrollView != null) {
                    debugScrollView.post(() -> debugScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            });
        }
    }

    private boolean isAudioInterface(UsbInterface iface) {
        return iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO;
    }


    // @Nullable
    private UsbInterface getAudioInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (isAudioInterface(iface)) {
                return iface;
            }
        }
        return null;
    }

    private boolean isAppleDongle(UsbDevice device) {
        return device.getVendorId() == APPLE_VENDOR_ID &&
                device.getProductId() == APPLE_DONGLE_PRODUCT_ID;
    }

    private String getDeviceDisplayName(UsbDevice device) {
        String vendorId = "0x" + Integer.toHexString(device.getVendorId()).toUpperCase();
        String productId = "0x" + Integer.toHexString(device.getProductId()).toUpperCase();

        if (isAppleDongle(device)) {
            return "Apple Dongle - " + device.getDeviceName();
        }

        String type = getAudioInterface(device) != null ? "🔊 Audio" : "⚙️ Control";
        return type + " Vendor: " + vendorId + " Product: " + productId + " (" + device.getDeviceName() + ")";
    }

    private String getInterfaceDisplayName(UsbInterface iface) {
        String name = iface.getName();
        StringBuilder sb = new StringBuilder();
        sb.append("Interface ").append(iface.getId());
        if (name != null && !name.trim().isEmpty()) {
            sb.append(": ").append(name);
        }
        sb.append(" (Class: ").append(iface.getInterfaceClass())
                .append(", Subclass: ").append(iface.getInterfaceSubclass()).append(")");
        return sb.toString();
    }

    private void updateInterfaceList(UsbDevice device) {
        interfaces.clear();
        interfaceDisplayNames.clear();

        if (device != null) {
            // Deduplicate interfaces based on mId
            HashMap<Integer, UsbInterface> dedupedMap = new HashMap<>();
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                int id = iface.getId();
                if (!dedupedMap.containsKey(id)) {
                    dedupedMap.put(id, iface);
                } else {
                    // If an interface with mId already exists, prefer one with a non-null/non-empty
                    // name
                    UsbInterface existing = dedupedMap.get(id);
                    if ((existing.getName() == null || existing.getName().trim().isEmpty())
                            && (iface.getName() != null && !iface.getName().trim().isEmpty())) {
                        dedupedMap.put(id, iface);
                    }
                }
            }

            List<Integer> ids = new ArrayList<>(dedupedMap.keySet());
            java.util.Collections.sort(ids);

            for (int id : ids) {
                UsbInterface iface = dedupedMap.get(id);
                String displayName = getInterfaceDisplayName(iface);
                interfaces.put(displayName, iface);
                interfaceDisplayNames.add(displayName);
            }
        }

        runOnUiThread(() -> {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                    android.R.layout.simple_spinner_item, interfaceDisplayNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            interfaceSpinner.setAdapter(adapter);

            if (!interfaceDisplayNames.isEmpty()) {
                interfaceSpinner.setSelection(0);
                selectedInterface = interfaces.get(interfaceDisplayNames.get(0));
                addDebugLog("📋 Found " + interfaceDisplayNames.size() + " distinct interface(s)");
            } else {
                selectedInterface = null;
                addDebugLog("📋 No interfaces found");
            }
        });
    }

    private void refreshDeviceList() {
        try {
            // Build new device list
            HashMap<String, UsbDevice> newDeviceMap = new HashMap<>();
            List<String> newDeviceDisplayNames = new ArrayList<>();
            boolean listChanged = false;

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            for (UsbDevice device : deviceList.values()) {
                String displayName = getDeviceDisplayName(device);
                newDeviceMap.put(displayName, device);
                newDeviceDisplayNames.add(displayName);

                // Check if this is a new device
                if (!devices.containsKey(displayName)) {
                    listChanged = true;
                    break;
                }
            }

            // Check if any devices were removed
            if (newDeviceMap.size() != devices.size()) {
                listChanged = true;
            }

            // Only update UI if the device list actually changed
            if (listChanged) {
                devices = newDeviceMap;
                deviceDisplayNames = newDeviceDisplayNames;

                // Update spinner only if list changed
                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this,
                            android.R.layout.simple_spinner_item, deviceDisplayNames);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    deviceSpinner.setAdapter(adapter);

                    logAction("📋 Device list updated. Total devices: " + deviceDisplayNames.size());

                    if (deviceDisplayNames.size() > 0) {
                        tvDeviceName.setText("Devices found: " + deviceDisplayNames.size());
                    } else {
                        tvDeviceName.setText("No USB devices found");
                    }
                });
            }
        } catch (Exception e) {
            logError("❌ Error refreshing device list: " + e.getMessage(), e);
        }
    }

    private void refreshVolumeDisplay(UsbDeviceConnection connection) {
        new Thread(() -> {
            try {
                byte[] vol = getDeviceVolume(connection);
                final String text;
                if (vol == null || vol.length < 6) {
                    text = "Volume: unknown";
                    addDebugLog("⚠ Volume read returned null or invalid length");
                } else {
                    int master = ((vol[1] & 0xFF) << 8) | (vol[0] & 0xFF);
                    int left = ((vol[3] & 0xFF) << 8) | (vol[2] & 0xFF);
                    int right = ((vol[4] & 0xFF) << 8) | (vol[5] & 0xFF);
                    String masterHex = String.format("%04X", master & 0xFFFF);
                    String leftHex = String.format("%04X", left & 0xFFFF);
                    String rightHex = String.format("%04X", right & 0xFFFF);
                    text = "Master: 0x" + masterHex + " (" + master + ") Left: 0x" + leftHex + " (" + left
                            + ")  Right: 0x" + rightHex + " (" + right + ")";
                    addDebugLog("📊 Volume read: M=0x " + masterHex + " L=0x" + leftHex + " R=0x" + rightHex);
                }
                runOnUiThread(() -> tvCurrentVolume.setText(text));
            } catch (Exception e) {
                logError("❌ Error reading volume: " + e.getMessage(), e);
                runOnUiThread(() -> tvCurrentVolume.setText("Volume read error"));
            }
        }).start();
    }

    protected void connectDevice(UsbDevice device) {
        String deviceName = device.getDeviceName();
        if (connectedDevices.containsKey(deviceName)) {
            logAction("⚠ Device already connected, skipping: " + deviceName);
            return;
        }

        try {
            addDebugLog("🔌 Connecting device: " + deviceName);
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                addDebugLog("❌ Failed to open device connection");
                tvDeviceName.setText("Failed to open device");
                return;
            }

            addDebugLog("✓ Device connection opened");
            // Claim all Audio interfaces
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                addDebugLog("✓ Using interface: " + getInterfaceDisplayName(iface));
                if (isAudioInterface(iface)) {
                    boolean isClaimed = connection.claimInterface(iface, true);
                    addDebugLog("✓ Audio interface (ID " + iface.getId() + ") claimed: " + isClaimed);
                }
            }

            connectedDevices.put(deviceName, connection);
            if (autoApply.isChecked()) {
                addDebugLog("→ Auto-apply enabled, setting volume...");
                setDeviceVolume(connection);
                if (quitAfterApply.isChecked()) {
                    addDebugLog("→ Quit after apply enabled, closing app...");
                    finishAndRemoveTask();
                }
            }
        } catch (Exception e) {
            logError("❌ Error connecting device: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            tvDeviceName.setText("Error: " + e.getMessage());
            connectedDevices.remove(deviceName); // Remove on error
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        for (UsbDeviceConnection connection : connectedDevices.values()) {
            connection.close();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        tvCurrentVolume = binding.currentVolume;
        tvDeviceName = binding.deviceName;
        volInput = binding.volume;
        deviceSpinner = binding.deviceSpinner;
        interfaceSpinner = binding.interfaceSpinner;
        autoApply = binding.autoApply;
        quitAfterApply = binding.quitAfterApply;
        permissionBtn = binding.permissionBtn;
        connectBtn = binding.connectBtn;
        debugLogView = binding.debugLog;
        debugScrollView = binding.debugScroll;

        addDebugLog("🚀 Application started");

        // Load saved preferences
        SharedPreferences settings = getApplicationContext().getSharedPreferences(PREFS_KEY, 0);
        volInput.setText(settings.getString("volume", "007f"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        quitAfterApply.setChecked(settings.getBoolean("quitAfterApply", false));

        // Setup device spinner listener - only tracks selection, no auto-connect
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    String name = (String) parent.getItemAtPosition(position);
                    UsbDevice device = devices.get(name);
                    if (device != null) {
                        logAction("👆 User selected device: " + name);
                        selectedDevice = device;
                        updateInterfaceList(selectedDevice);
                    } else {
                        selectedDevice = null;
                        updateInterfaceList(null);
                    }
                    updateButtonStates();
                } catch (Exception e) {
                    logError("❌ Error selecting device: " + e.getMessage(), e);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedDevice = null;
                updateInterfaceList(null);
                updateButtonStates();
            }
        });

        // Setup interface spinner listener - tracks selected interface
        interfaceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    String name = (String) parent.getItemAtPosition(position);
                    UsbInterface iface = interfaces.get(name);
                    if (iface != null) {
                        logAction("👆 User selected interface: " + name);
                        selectedInterface = iface;
                    } else {
                        selectedInterface = null;
                    }
                } catch (Exception e) {
                    logError("❌ Error selecting interface: " + e.getMessage(), e);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedInterface = null;
            }
        });

        binding.refreshVolumeButton.setOnClickListener(v -> {
            try {
                if (selectedDevice == null) {
                    addDebugLog("⚠ No selected device");
                    tvCurrentVolume.setText("No selected device");
                    return;
                }

                UsbDeviceConnection connection = connectedDevices.get(selectedDevice.getDeviceName());
                if (connection == null) {
                    addDebugLog("⚠ No connection");
                    tvCurrentVolume.setText("No connection");
                    return;
                }

                addDebugLog("🔄 Refreshing volume display...");
                refreshVolumeDisplay(connection);

            } catch (Exception e) {
                logError("❌ Error refreshing volume: " + e.getMessage(), e);
            }
        });

        // Toggle debug button
        binding.toggleDebugBtn.setOnClickListener(v -> toggleDebugViewVisibility());

        // Checkbox listeners (registered after setChecked to avoid firing on init)
        autoApply.setOnClickListener(v -> onCheckboxChanged(autoApply, "autoApply", "Auto-apply"));
        quitAfterApply.setOnClickListener(v -> onCheckboxChanged(quitAfterApply, "quitAfterApply", "Quit-after-apply"));

        // Apply button listener
        binding.mountBtn.setOnClickListener(v -> applyButtonPressed());

        // Permission button listener
        permissionBtn.setOnClickListener(v -> {
            if (selectedDevice != null) {
                requestDevicePermission(selectedDevice);
            }
        });

        // Connect button listener
        connectBtn.setOnClickListener(v -> {
            if (selectedDevice != null && usbManager.hasPermission(selectedDevice)) {
                connectDevice(selectedDevice);
                updateButtonStates();
            }
        });

        // Initialize UsbManager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize the receiver for getting the device permission
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // Refresh device list on startup
        refreshDeviceList();
        lastDeviceListRefresh = System.currentTimeMillis();

        requestRecordAudioPermission();
    }

    private void toggleDebugViewVisibility() {
        debugVisible = !debugVisible;
        debugScrollView.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
        binding.toggleDebugBtn.setText(debugVisible ? "Hide Debug" : "Debug");
    }

    /**
     * Updates the permission and connect button text/enabled state
     * based on the currently selected device.
     */
    private void updateButtonStates() {
        if (selectedDevice == null) {
            permissionBtn.setText("Request Permission");
            permissionBtn.setEnabled(false);
            connectBtn.setText("Connect Device");
            connectBtn.setEnabled(false);
            return;
        }

        // Permission button
        if (usbManager.hasPermission(selectedDevice)) {
            permissionBtn.setText("Permission Granted");
            permissionBtn.setEnabled(false);
        } else {
            permissionBtn.setText("Request Permission");
            permissionBtn.setEnabled(true);
        }

        // Connect button
        if (connectedDevices.containsKey(selectedDevice.getDeviceName())) {
            connectBtn.setText("Device Connected");
            connectBtn.setEnabled(false);
        } else if (usbManager.hasPermission(selectedDevice)) {
            connectBtn.setText("Connect Device");
            connectBtn.setEnabled(true);
        } else {
            connectBtn.setText("Connect Device");
            connectBtn.setEnabled(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh device list when resuming to catch any changes
        refreshDeviceList();
        updateButtonStates();
    }

    private void requestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { android.Manifest.permission.RECORD_AUDIO },
                    RECORD_AUDIO_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                logAction("✓ RECORD_AUDIO permission granted");
            } else {
                logAction("✗ RECORD_AUDIO permission denied");
            }
        }
    }

    private void applyButtonPressed() {
        String volume = volInput.getText().toString();

        if (selectedDevice == null) {
            addDebugLog("❌ No device selected");
            tvDeviceName.setText("No device selected");
            tvDeviceName.setBackgroundColor(Color.RED);
            return;
        }

        UsbDeviceConnection connection = connectedDevices.get(selectedDevice.getDeviceName());
        if (connection == null) {
            addDebugLog("⚠ No connection");
            tvDeviceName.setText("No connection");
            return;
        }

        try {
            addDebugLog("→ Setting volume to: 0x" + volume);
            setDeviceVolume(connection);
            addDebugLog("✓ Volume set successfully");
            clearErrorState();
            savePref("volume", volume);
        } catch (IllegalArgumentException e) {
            addDebugLog("❌ Invalid volume format: " + e.getMessage());
            volInput.setText("");
            volInput.setBackgroundColor(Color.RED);
        } catch (Exception e) {
            logError("❌ Error setting volume: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            tvDeviceName.setBackgroundColor(Color.RED);
        }
    }

    private void onCheckboxChanged(CheckBox checkBox, String prefKey, String label) {
        try {
            savePref(prefKey, checkBox.isChecked());
            addDebugLog("⚙️ " + label + " set to: " + checkBox.isChecked());
        } catch (Exception e) {
            logError("❌ Error toggling " + label + ": " + e.getMessage(), e);
        }
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public byte[] getDeviceVolume(UsbDeviceConnection usbConnection) {
        if (usbConnection == null) {
            addDebugLog("⚠ usbConnection missing or null");
            return null;
        }

        try {
            byte[] master = new byte[2];
            byte[] left = new byte[2];
            byte[] right = new byte[2];
            List<FeatureUnitInfo> funits = UsbDescriptorParser.dumpAudioFeatureUnits(usbConnection);
            if (funits == null || funits.size() <= 0) {
                addDebugLog("⚠ No feature units returned from descriptor parser");
                return null;
            }

            FeatureUnitInfo funit = funits.get(0);
            addDebugLog("🔍 Feature Unit info: " + funit.toString());

            // Target recipient scope MUST be USB_RECIP_INTERFACE (0x01), not
            // USB_RECIP_DEVICE (0x00)
            int requestType = UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE; // 0xA1
            int request = 0x81; // UAC1 GET_CUR
            int controlSelector = 0x02; // VOLUME control
            int wIndex = (funit.unitId << 8) | funit.interfaceNumber;

            // Try Channel 0 (Master)
            int value = (controlSelector << 8) | 0;
            addDebugLog(String.format(Locale.US, "→ Read Master: reqType=0x%02X req=0x%02X val=0x%04X idx=0x%04X",
                    requestType, request, value, wIndex));
            int len = usbConnection.controlTransfer(requestType, request, value, wIndex, master, master.length, 1000);

            // Fallback for UAC2 if UAC1 (0x81) returned error (-1)
            if (len < 0) {
                addDebugLog("⚠ UAC1 GET_CUR (0x81) failed ret=" + len + ", trying UAC2 CUR (0x01)...");
                request = 0x01; // UAC2 CUR request
                len = usbConnection.controlTransfer(requestType, request, value, wIndex, master, master.length, 1000);
            }

            if (len < 0) {
                addDebugLog("⚠ controlTransfer read master error ret=" + len);
            } else {
                addDebugLog("✓ controlTransfer read master success ret=" + len + " bytes: "
                        + String.format("%02X %02X", master[0], master[1]));
            }

            // Channel 1 (Left)
            value = (controlSelector << 8) | 1;
            len = usbConnection.controlTransfer(requestType, request, value, wIndex, left, left.length, 1000);
            if (len < 0) {
                addDebugLog("⚠ controlTransfer read left error ret=" + len);
            } else {
                addDebugLog("✓ controlTransfer read left success ret=" + len);
            }

            // Channel 2 (Right)
            value = (controlSelector << 8) | 2;
            len = usbConnection.controlTransfer(requestType, request, value, wIndex, right, right.length, 1000);
            if (len < 0) {
                addDebugLog("⚠ controlTransfer read right error ret=" + len);
            } else {
                addDebugLog("✓ controlTransfer read right success ret=" + len);
            }

            byte[] out = new byte[6];
            out[0] = master[0];
            out[1] = master[1];
            out[2] = left[0];
            out[3] = left[1];
            out[4] = right[0];
            out[5] = right[1];
            return out;
        } catch (Exception e) {
            logError("❌ getDeviceVolume exception: " + e.getMessage(), e);
            return null;
        }
    }

    public void setDeviceVolume(UsbDeviceConnection usbConnection, byte[] volume) {
        if (usbConnection == null) {
            addDebugLog("⚠ usbConnection missing or null on set");
            return;
        }
        if (volume == null || volume.length < 2) {
            throw new IllegalArgumentException("Volume bytes must be length >= 2");
        }
        try {
            byte[] two = new byte[2];
            two[0] = volume[0];
            two[1] = volume[1];

            // Resolve Feature Unit and interface from descriptor parser
            int unitId = 2; // default fallback
            int ifaceNum = 0; // default fallback
            List<FeatureUnitInfo> funits = UsbDescriptorParser.dumpAudioFeatureUnits(usbConnection);
            if (funits != null && !funits.isEmpty()) {
                FeatureUnitInfo funit = funits.get(0);
                unitId = funit.unitId;
                ifaceNum = funit.interfaceNumber;
                addDebugLog("🔍 Parsed Feature Unit for Set Volume: Unit ID=" + unitId + ", Iface=" + ifaceNum);
            } else {
                addDebugLog("⚠ No feature units found, falling back to Unit ID=2, Iface=0");
            }

            int wIndex = (unitId << 8) | ifaceNum;
            int requestType = UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE; // 0x21
            int request = 0x01; // SET_CUR (UAC1 and UAC2)

            // Channel 0 (Master)
            int valueMaster = (0x02 << 8) | 0x00; // VOLUME control, Master Channel
            addDebugLog(
                    String.format(Locale.US, "→ Transfer Set Master: reqType=0x%02X req=0x%02X val=0x%04X idx=0x%04X",
                            requestType, request, valueMaster, wIndex));
            int retMaster = usbConnection.controlTransfer(requestType, request, valueMaster, wIndex, two, two.length,
                    1000);
            if (retMaster >= 0) {
                addDebugLog("✓ controlTransfer write master ret=" + retMaster);
            } else {
                addDebugLog("⚠ controlTransfer write master error ret=" + retMaster);
            }

            // Channel 1 (Left)
            int valueLeft = (0x02 << 8) | 0x01; // VOLUME control, Channel 1
            addDebugLog(String.format(Locale.US, "→ Transfer Set Left: reqType=0x%02X req=0x%02X val=0x%04X idx=0x%04X",
                    requestType, request, valueLeft, wIndex));
            int retLeft = usbConnection.controlTransfer(requestType, request, valueLeft, wIndex, two, two.length, 1000);
            if (retLeft >= 0) {
                addDebugLog("✓ controlTransfer write left ret=" + retLeft);
            } else {
                addDebugLog("⚠ controlTransfer write left error ret=" + retLeft);
            }

            // Channel 2 (Right)
            int valueRight = (0x02 << 8) | 0x02; // VOLUME control, Channel 2
            addDebugLog(
                    String.format(Locale.US, "→ Transfer Set Right: reqType=0x%02X req=0x%02X val=0x%04X idx=0x%04X",
                            requestType, request, valueRight, wIndex));
            int retRight = usbConnection.controlTransfer(requestType, request, valueRight, wIndex, two, two.length,
                    1000);
            if (retRight >= 0) {
                addDebugLog("✓ controlTransfer write right ret=" + retRight);
            } else {
                addDebugLog("⚠ controlTransfer write right error ret=" + retRight);
            }
        } catch (Exception e) {
            logError("❌ setDeviceVolume exception: " + e.getMessage(), e);
        }
    }

    public void setDeviceVolume(UsbDeviceConnection connection) {
        String volume = volInput.getText().toString();

        if (!volume.matches("[0-9A-Fa-f]{4}")) {
            throw new IllegalArgumentException("Volume must be 4 hex characters");
        }

        setDeviceVolume(connection, hexStringToByteArray(volume));
        Toast.makeText(getApplicationContext(), "Volume set for DAC!", Toast.LENGTH_LONG).show();
    }
}
