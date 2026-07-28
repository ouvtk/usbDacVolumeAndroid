package com.example.libusbAndroidTest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
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

    private CheckBox autoApply;
    private CheckBox quitAfterApply;

    private int deviceDescriptor = -1;

    // Store device mapping: display name -> UsbDevice
    private HashMap<String, UsbDevice> deviceMap = new HashMap<>();
    private List<String> deviceDisplayNames = new ArrayList<>();

    // Track connected devices to prevent duplicate connections
    private Set<String> connectedDeviceNames = new HashSet<>();

    // Debounce mechanism
    private long lastDeviceListRefresh = 0;
    private static final long REFRESH_DEBOUNCE_MS = 500; // Wait 500ms before updating device list again

    private static final int APPLE_VENDOR_ID = 1452;
    private static final int APPLE_DONGLE_PRODUCT_ID = 4362;

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;
    private static final String PREFS_KEY = "myPrefs";

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
                            if (isDebounceExpired()) {
                                refreshDeviceList();
                            }
                            // Connect now that permission is granted
                            runOnUiThread(() -> {
                                try {
                                    connectDevice(device);
                                } catch (Exception e) {
                                    logError("❌ Error connecting after permission: " + e.getMessage(), e);
                                }
                            });
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
                    addDebugLog("🔌 USB device detached: " + device.getDeviceName());
                    connectedDeviceNames.remove(device.getDeviceName());
                    if (isDebounceExpired()) {
                        refreshDeviceList();
                    }
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

    // @Nullable
    private UsbInterface getAudioInterface(UsbDevice device) {
        // Audio device class = 0x01, Audio subclass = 0x02
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 0x01 &&
                    intf.getInterfaceSubclass() == 0x02) {
                return intf;
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
                if (!deviceMap.containsKey(displayName)) {
                    listChanged = true;
                }

                if (!usbManager.hasPermission(device)) {
                    requestDevicePermission(device);
                }
            }

            // Check if any devices were removed
            if (!listChanged && newDeviceMap.size() != deviceMap.size()) {
                listChanged = true;
            }

            // Only update UI if the device list actually changed
            if (listChanged) {
                deviceMap = newDeviceMap;
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

    private void refreshVolumeDisplay(int fileDescriptor) {
        new Thread(() -> {
            try {
                byte[] vol = getDeviceVolume(fileDescriptor); // native
                final String text;
                if (vol == null || vol.length < 4) {
                    text = "Volume: unknown";
                    addDebugLog("⚠ Volume read returned null or invalid length");
                } else {
                    int left = ((vol[1] & 0xFF) << 8) | (vol[0] & 0xFF);
                    int right = ((vol[3] & 0xFF) << 8) | (vol[2] & 0xFF);
                    String leftHex = String.format("%04X", left & 0xFFFF);
                    String rightHex = String.format("%04X", right & 0xFFFF);
                    text = "Left: 0x" + leftHex + " (" + left + ")  Right: 0x" + rightHex + " (" + right + ")";
                    addDebugLog("📊 Volume read: L=0x" + leftHex + " R=0x" + rightHex);
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

        if (connectedDeviceNames.contains(deviceName)) {
            logAction("⚠ Device already connected, skipping: " + deviceName);
            return;
        }

        try {
            addDebugLog("🔌 Connecting device: " + deviceName);
            connectedDeviceNames.add(deviceName);

            // Find the audio interface
            UsbInterface audioInterface = getAudioInterface(device);
            if (audioInterface == null) {
                addDebugLog("❌ Not an audio device");
                tvDeviceName.setText("Not an audio device");
                connectedDeviceNames.remove(deviceName);
                return;
            }
            
            addDebugLog("✓ Found audio interface");
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                addDebugLog("❌ Failed to open device connection");
                Log.e(TAG, "Failed to open device connection");
                tvDeviceName.setText("Failed to open device");
                connectedDeviceNames.remove(deviceName);
                return;
            }

            addDebugLog("✓ Device connection opened");
            connection.claimInterface(audioInterface, true);
            addDebugLog("✓ Interface claimed");

            int fileDescriptor = connection.getFileDescriptor();
            addDebugLog("✓ File descriptor: " + fileDescriptor);

            String initResult = initializeNativeDevice(fileDescriptor);
            logAction("✓ Native device initialized: " + initResult);

            deviceDescriptor = fileDescriptor;
            refreshVolumeDisplay(deviceDescriptor);

            if (autoApply.isChecked()) {
                addDebugLog("→ Auto-apply enabled, setting volume...");
                setDeviceVolume(fileDescriptor);
                if (quitAfterApply.isChecked()) {
                    addDebugLog("→ Quit after apply enabled, closing app...");
                    finishAndRemoveTask();
                }
            }
        } catch (Exception e) {
            logError("❌ Error connecting device: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            tvDeviceName.setText("Error: " + e.getMessage());
            connectedDeviceNames.remove(deviceName); // Remove on error
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
        autoApply = binding.autoApply;
        quitAfterApply = binding.quitAfterApply;
        debugLogView = binding.debugLog;
        debugScrollView = binding.debugScroll;

        addDebugLog("🚀 Application started");

        // Load saved preferences
        SharedPreferences settings = getApplicationContext().getSharedPreferences(PREFS_KEY, 0);
        volInput.setText(settings.getString("volume", "007f"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        quitAfterApply.setChecked(settings.getBoolean("quitAfterApply", false));

        // Setup device spinner listener
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    String selectedDeviceName = (String) parent.getItemAtPosition(position);
                    UsbDevice selectedDevice = deviceMap.get(selectedDeviceName);
                    if (selectedDevice != null) {
                        logAction("👆 User selected device: " + selectedDeviceName);

                        // If we already have permission, connect immediately.
                        if (usbManager.hasPermission(selectedDevice)) {
                            connectDevice(selectedDevice);
                        } else {
                            // Request permission and wait for the ACTION_USB_PERMISSION broadcast.
                            requestDevicePermission(selectedDevice);
                        }
                    }
                } catch (Exception e) {
                    logError("❌ Error selecting device: " + e.getMessage(), e);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        binding.refreshVolumeButton.setOnClickListener(v -> {
            try {
                if (deviceDescriptor >= 0) {
                    addDebugLog("🔄 Refreshing volume display...");
                    refreshVolumeDisplay(deviceDescriptor);
                } else {
                    addDebugLog("⚠ No device connected");
                    tvCurrentVolume.setText("No device");
                }
            } catch (Exception e) {
                logError("❌ Error refreshing volume: " + e.getMessage(), e);
            }
        });

        // Toggle debug button
        binding.toggleDebugBtn.setOnClickListener(v -> toggleDebugViewVisibility());

        // Checkbox listeners (registered after setChecked to avoid firing on init)
        autoApply.setOnClickListener(v ->
                onCheckboxChanged(autoApply, "autoApply", "Auto-apply"));
        quitAfterApply.setOnClickListener(v ->
                onCheckboxChanged(quitAfterApply, "quitAfterApply", "Quit-after-apply"));

        // Apply button listener
        binding.mountBtn.setOnClickListener(v -> applyButtonPressed());

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

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh device list when resuming to catch any changes
        refreshDeviceList();
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
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                logAction("✓ RECORD_AUDIO permission granted");
            } else {
                logAction("✗ RECORD_AUDIO permission denied");
            }
        }
    }

    private void applyButtonPressed() {
        String volume = volInput.getText().toString();

        if (deviceDescriptor < 0) {
            addDebugLog("❌ No device selected");
            tvDeviceName.setBackgroundColor(Color.RED);
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            addDebugLog("→ Setting volume to: 0x" + volume);
            setDeviceVolume(deviceDescriptor);
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

    /**
     * A native method that is implemented by the 'lib' native library,
     * which is packaged with this application.
     */
    public native String initializeNativeDevice(int fileDescriptor);

    public native byte[] getDeviceVolume(int fileDescriptor);

    public native void setDeviceVolume(int fileDescriptor, byte[] volume);

    public void setDeviceVolume(int fileDescriptor) {
        String volume = volInput.getText().toString();

        if (!volume.matches("[0-9A-Fa-f]{4}")) {
            throw new IllegalArgumentException("Volume must be 4 hex characters");
        }

        setDeviceVolume(fileDescriptor, hexStringToByteArray(volume));
        Toast.makeText(getApplicationContext(), "Volume set for DAC!", Toast.LENGTH_LONG).show();
    }
}
