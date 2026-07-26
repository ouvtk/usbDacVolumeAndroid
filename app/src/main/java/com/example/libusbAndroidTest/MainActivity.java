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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.libusbAndroidTest.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d(TAG, "Permission granted for device: " + device.getDeviceName());
                            // Debounce the refresh - only refresh if enough time has passed
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastDeviceListRefresh > REFRESH_DEBOUNCE_MS) {
                                refreshDeviceList();
                                lastDeviceListRefresh = currentTime;
                            }
                        }
                    } else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };

    private boolean isAudioInterface(UsbDevice device) {
        // Audio device class = 0x01, Audio subclass = 0x02
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 0x01 &&
                    intf.getInterfaceSubclass() == 0x02) {
                return true;
            }
        }
        return false;
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

        String type = isAudioInterface(device) ? "🔊 Audio" : "⚙️ Control";
        return type + " Vendor: " + vendorId + " Product: " + productId + " (" + device.getDeviceName() + ")";
    }

    private void refreshDeviceList() {
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

            Log.d(TAG, "Device list updated. Total devices: " + deviceDisplayNames.size());

            if (deviceDisplayNames.size() > 0) {
                tvDeviceName.setText("Devices found: " + deviceDisplayNames.size());
            } else {
                tvDeviceName.setText("No USB devices found");
            }
        }
    }

    private void refreshVolumeDisplay(int fileDescriptor) {
        new Thread(() -> {
            try {
                byte[] vol = getDeviceVolume(fileDescriptor); // native
                final String text;
                if (vol == null || vol.length < 4) {
                    text = "Volume: unknown";
                } else {
                    int left = ((vol[1] & 0xFF) << 8) | (vol[0] & 0xFF);
                    int right = ((vol[3] & 0xFF) << 8) | (vol[2] & 0xFF);
                    String leftHex = String.format("%04X", left & 0xFFFF);
                    String rightHex = String.format("%04X", right & 0xFFFF);
                    text = "Left: 0x" + leftHex + " (" + left + ")  Right: 0x" + rightHex + " (" + right + ")";
                }
                runOnUiThread(() -> tvCurrentVolume.setText(text));
            } catch (Exception e) {
                Log.e(TAG, "Error reading volume: " + e.getMessage());
                runOnUiThread(() -> tvCurrentVolume.setText("Volume read error"));
            }
        }).start();
    }

    protected void connectDevice(UsbDevice device) {
        String deviceName = device.getDeviceName();

        if (connectedDeviceNames.contains(deviceName)) {
            Log.d(TAG, "Device already connected, skipping: " + deviceName);
            return;
        }

        connectedDeviceNames.add(deviceName);

        try {
            // Find the audio interface, don't assume index 0
            UsbInterface audioInterface = null;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                if (isAudioInterface(device)) {
                    audioInterface = intf;
                    break;
                }
            }

            if (audioInterface == null) {
                tvDeviceName.setText("Not an audio device");
                connectedDeviceNames.remove(deviceName);
                return;
            }

            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                Log.e(TAG, "Failed to open device connection");
                tvDeviceName.setText("Failed to open device");
                connectedDeviceNames.remove(deviceName);
                return;
            }

            connection.claimInterface(audioInterface, true);
            int fileDescriptor = connection.getFileDescriptor();

            String initResult = initializeNativeDevice(fileDescriptor);
            Log.d(TAG, "Native device initialized: " + initResult);

            deviceDescriptor = fileDescriptor;
            refreshVolumeDisplay(deviceDescriptor);

            if (autoApply.isChecked()) {
                setDeviceVolume(fileDescriptor);
                if (quitAfterApply.isChecked()) {
                    finishAndRemoveTask();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error connecting device: " + e.getMessage());
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

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        volInput.setText(settings.getString("volume", "007f"));
        autoApply.setChecked(settings.getBoolean("autoApply", false));
        quitAfterApply.setChecked(settings.getBoolean("quitAfterApply", false));

        // Setup device spinner listener
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedDeviceName = (String) parent.getItemAtPosition(position);
                UsbDevice selectedDevice = deviceMap.get(selectedDeviceName);
                if (selectedDevice != null) {
                    Log.d(TAG, "User selected device: " + selectedDeviceName);
                    connectDevice(selectedDevice);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        binding.refreshVolumeButton.setOnClickListener(v -> {
            if (deviceDescriptor >= 0) {
                refreshVolumeDisplay(deviceDescriptor);
            } else {
                tvCurrentVolume.setText("No device");
            }
        });

        // Initialize UsbManager
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize the receiver for getting the device permission
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // Refresh device list on startup
        refreshDeviceList();
        lastDeviceListRefresh = System.currentTimeMillis();

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
                Log.d(TAG, "RECORD_AUDIO permission granted");
            } else {
                Log.d(TAG, "RECORD_AUDIO permission denied");
            }
        }
    }

    public void applyButtonPressed(View view) {
        String volume = volInput.getText().toString();

        if (deviceDescriptor < 0) {
            tvDeviceName.setBackgroundColor(Color.RED);
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            setDeviceVolume(deviceDescriptor);
            volInput.setBackgroundColor(Color.TRANSPARENT);
            tvDeviceName.setBackgroundColor(Color.TRANSPARENT);
        } catch (IllegalArgumentException e) {
            volInput.setText("");
            volInput.setBackgroundColor(Color.RED);
            return;
        }

        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        if (!settings.getString("volume", "").equals(volume)) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("volume", volume);
            editor.apply();
        }
    }

    public void autoApplyCheckboxPressed(View view) {
        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("autoApply", autoApply.isChecked());
        editor.apply();
    }

    public void quitAfterApplyCheckboxPressed(View view) {
        SharedPreferences settings = getApplicationContext().getSharedPreferences("myPrefs", 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("quitAfterApply", quitAfterApply.isChecked());
        editor.apply();
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
