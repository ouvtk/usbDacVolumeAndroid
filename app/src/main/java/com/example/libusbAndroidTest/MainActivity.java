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
    private static String deviceName;

    // Store device mapping: display name -> UsbDevice
    private HashMap<String, UsbDevice> deviceMap = new HashMap<>();
    private List<String> deviceDisplayNames = new ArrayList<>();

    // Track connected devices to prevent duplicate connections
    private Set<String> connectedDeviceNames = new HashSet<>();

    // Debounce mechanism
    private long lastDeviceListRefresh = 0;
    private static final long REFRESH_DEBOUNCE_MS = 500; // Wait 500ms before updating device list again

    private static final String APPLE_VENDOR_ID = "1452";
    private static final String APPLE_DONGLE_PRODUCT_ID = "4362";

    private static final String TAG = "USB DAC Volume Adjustment";
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;

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
                            addDebugLog("✓ Permission granted for device: " + device.getDeviceName());
                            Log.d(TAG, "Permission granted for device: " + device.getDeviceName());
                            // Debounce the refresh - only refresh if enough time has passed
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastDeviceListRefresh > REFRESH_DEBOUNCE_MS) {
                                refreshDeviceList();
                                lastDeviceListRefresh = currentTime;
                            }
                        }
                    } else {
                        addDebugLog("✗ Permission denied for device " + device);
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };

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
        return device.getVendorId() == Integer.parseInt(APPLE_VENDOR_ID) &&
                device.getProductId() == Integer.parseInt(APPLE_DONGLE_PRODUCT_ID);
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

            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
                    addDebugLog("→ Requesting permission for: " + displayName);
                    Log.d(TAG, "Requesting permission for: " + displayName);
                    usbManager.requestPermission(device, permissionIntent);
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
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, deviceDisplayNames);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                deviceSpinner.setAdapter(adapter);

                addDebugLog("📋 Device list updated. Total devices: " + deviceDisplayNames.size());
                Log.d(TAG, "Device list updated. Total devices: " + deviceDisplayNames.size());

                if (deviceDisplayNames.size() > 0) {
                    tvDeviceName.setText("Devices found: " + deviceDisplayNames.size());
                } else {
                    tvDeviceName.setText("No USB devices found");
                }
            }
        } catch (Exception e) {
            addDebugLog("❌ Error refreshing device list: " + e.getMessage());
            Log.e(TAG, "Error refreshing device list", e);
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
                Log.e(TAG, "Error reading volume: " + e.getMessage());
                addDebugLog("❌ Error reading volume: " + e.getMessage());
                runOnUiThread(() -> tvCurrentVolume.setText("Volume read error"));
            }
        }).start();
    }

    protected void connectDevice(UsbDevice device) {
        String deviceName = device.getDeviceName();

        if (connectedDeviceNames.contains(deviceName)) {
            addDebugLog("⚠ Device already connected, skipping: " + deviceName);
            Log.d(TAG, "Device already connected, skipping: " + deviceName);
            return;
        }

        try {
            addDebugLog("🔌 Connecting device: " + deviceName);
            connectedDeviceNames.add(deviceName);

            // Find the audio interface, don't assume index 0
            UsbInterface audioInterface = null;
            for (int i = 0; i < device.getInterfaceCount(); i++) {   
                audioInterface = getAudioInterface(device);
                if (audioInterface != null) {
                    addDebugLog("✓ Found audio interface at index " + i);
                    break;
                }
            }

            if (audioInterface == null) {
                addDebugLog("❌ Not an audio device");
                tvDeviceName.setText("Not an audio device");
                connectedDeviceNames.remove(deviceName);
                return;
            }

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
            addDebugLog("✓ Native device initialized: " + initResult);
            Log.d(TAG, "Native device initialized: " + initResult);

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
            addDebugLog("❌ Error connecting device: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            Log.e(TAG, "Error connecting device: " + e.getMessage(), e);
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

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
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
                        addDebugLog("👆 User selected device: " + selectedDeviceName);
                        Log.d(TAG, "User selected device: " + selectedDeviceName);
                        connectDevice(selectedDevice);
                    }
                } catch (Exception e) {
                    addDebugLog("❌ Error selecting device: " + e.getMessage());
                    Log.e(TAG, "Error selecting device", e);
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
                addDebugLog("❌ Error refreshing volume: " + e.getMessage());
                Log.e(TAG, "Error refreshing volume", e);
            }
        });

        // Toggle debug button
        binding.toggleDebugBtn.setOnClickListener(v -> {
            debugVisible = !debugVisible;
            debugScrollView.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
            binding.toggleDebugBtn.setText(debugVisible ? "Hide Debug" : "Debug");
        });

        // Initialize UsbManager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize the receiver for getting the device permission
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        requestRecordAudioPermission();
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
                addDebugLog("✓ RECORD_AUDIO permission granted");
                Log.d(TAG, "RECORD_AUDIO permission granted");
            } else {
                addDebugLog("✗ RECORD_AUDIO permission denied");
                Log.d(TAG, "RECORD_AUDIO permission denied");
            }
        }
    }

    public void applyButtonPressed(View view) {
        try {
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
                volInput.setBackgroundColor(Color.TRANSPARENT);
                tvDeviceName.setBackgroundColor(Color.TRANSPARENT);
            } catch (IllegalArgumentException e) {
                addDebugLog("❌ Invalid volume format: " + e.getMessage());
                volInput.setText("");
                volInput.setBackgroundColor(Color.RED);
                return;
            } catch (Exception e) {
                addDebugLog("❌ Error setting volume: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                Log.e(TAG, "Error setting volume", e);
                tvDeviceName.setBackgroundColor(Color.RED);
                return;
            }

            SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
            if (!settings.getString("volume", "").equals(volume)) {
                SharedPreferences.Editor editor = settings.edit();
                editor.putString("volume", volume);
                editor.apply();
            }
        } catch (Exception e) {
            addDebugLog("❌ Unexpected error in applyButtonPressed: " + e.getMessage());
            Log.e(TAG, "Unexpected error in applyButtonPressed", e);
        }
    }

    public void autoApplyCheckboxPressed(View view) {
        try {
            SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("autoApply", autoApply.isChecked());
            editor.apply();
            addDebugLog("⚙️ Auto-apply set to: " + autoApply.isChecked());
        } catch (Exception e) {
            addDebugLog("❌ Error toggling auto-apply: " + e.getMessage());
            Log.e(TAG, "Error toggling auto-apply", e);
        }
    }

    public void quitAfterApplyCheckboxPressed(View view) {
        try {
            SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("quitAfterApply", quitAfterApply.isChecked());
            editor.apply();
            addDebugLog("⚙️ Quit-after-apply set to: " + quitAfterApply.isChecked());
        } catch (Exception e) {
            addDebugLog("❌ Error toggling quit-after-apply: " + e.getMessage());
            Log.e(TAG, "Error toggling quit-after-apply", e);
        }
    }

    public static byte[] hexStringToByteArray(String s) {
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
