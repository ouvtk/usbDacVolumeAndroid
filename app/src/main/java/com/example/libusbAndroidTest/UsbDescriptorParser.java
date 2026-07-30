package com.example.libusbAndroidTest;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsbDescriptorParser {
    private static final String TAG = "UsbDescriptorParser";

    private static final int GET_DESCRIPTOR = 0x06;
    private static final int USB_DT_CONFIG = 0x02;
    private static final int USB_DIR_IN = UsbConstants.USB_DIR_IN;
    private static final int USB_TYPE_STANDARD = 0x00;
    private static final int USB_RECIP_DEVICE = 0x00;

    // Class-specific types
    private static final int DESC_TYPE_CS_INTERFACE = 0x24; // class-specific interface
    private static final int CS_SUBTYPE_INPUT_TERMINAL = 0x02;
    private static final int CS_SUBTYPE_FEATURE_UNIT = 0x06;

    public static class FeatureUnitInfo {
        public int interfaceNumber;
        public int unitId;
        public int sourceId;
        public int controlSize;
        public int channelCount; // number of audio channels (not counting master)
        @Override public String toString() {
            return "FeatureUnit(unitId=" + unitId + ", iface=" + interfaceNumber +
                   ", sourceId=" + sourceId + ", controlSize=" + controlSize +
                   ", channelCount=" + channelCount + ")";
        }
    }

    public static List<FeatureUnitInfo> dumpAudioFeatureUnits(UsbDeviceConnection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("connection == null");
        }

        // Step 1: read 9-byte config header to get total length
        byte[] header = new byte[9];
        int got = conn.controlTransfer(
                USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
                GET_DESCRIPTOR,
                (USB_DT_CONFIG << 8) | 0, // wValue = (CONFIG << 8) | index
                0, // wIndex = language for string, 0 for config
                header,
                header.length,
                2000);
        if (got < 9) {
            Log.e(TAG, "Failed to read config header, got=" + got);
            return new ArrayList<>();
        }
        int wTotalLength = (header[2] & 0xff) | ((header[3] & 0xff) << 8);
        if (wTotalLength <= 0) {
            Log.e(TAG, "Invalid wTotalLength=" + wTotalLength);
            return new ArrayList<>();
        }

        // Step 2: read full configuration descriptor block
        byte[] full = new byte[wTotalLength];
        got = conn.controlTransfer(
                USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
                GET_DESCRIPTOR,
                (USB_DT_CONFIG << 8) | 0,
                0,
                full,
                full.length,
                3000);
        if (got < 9) {
            Log.e(TAG, "Failed to read full config, got=" + got);
            return new ArrayList<>();
        }

        // Step 3: walk descriptors
        List<FeatureUnitInfo> featureUnits = new ArrayList<>();
        Map<Integer, Integer> inputTerminalChannels = new HashMap<>(); // terminalID -> nrChannels

        int i = 0;
        int currentInterface = -1;
        int currentIfaceClass = -1;
        int currentIfaceSubClass = -1;

        while (i + 2 <= full.length) {
            int bLength = full[i] & 0xff;
            if (bLength < 2) break; // invalid, stop
            if (i + bLength > full.length) break; // truncated descriptor

            int bDescriptorType = full[i + 1] & 0xff;

            if (bDescriptorType == 0x04 && bLength >= 9) { // Standard INTERFACE descriptor
                // offset mapping according to USB standard
                currentInterface = full[i + 2] & 0xff;            // bInterfaceNumber
                currentIfaceClass = full[i + 5] & 0xff;           // bInterfaceClass
                currentIfaceSubClass = full[i + 6] & 0xff;        // bInterfaceSubClass
                //Log.d(TAG, "Found interface " + currentInterface + " class=" + currentIfaceClass + " sub=" + currentIfaceSubClass);
            } else if (bDescriptorType == DESC_TYPE_CS_INTERFACE) {
                int bDescriptorSubtype = full[i + 2] & 0xff;
                if (currentIfaceClass == 0x01 && currentIfaceSubClass == 0x01) {
                    // inside Audio Control interface's class-specific descriptors
                    if (bDescriptorSubtype == CS_SUBTYPE_INPUT_TERMINAL && bLength >= 8) {
                        // Input Terminal: bTerminalID at offset i+3, bNrChannels at offset i+7 (CS interface layout)
                        int bTerminalID = full[i + 3] & 0xff;
                        int bNrChannels = full[i + 7] & 0xff;
                        inputTerminalChannels.put(bTerminalID, bNrChannels);
                        //Log.d(TAG, "InputTerminal id=" + bTerminalID + " channels=" + bNrChannels);
                    } else if (bDescriptorSubtype == CS_SUBTYPE_FEATURE_UNIT && bLength >= 7) {
                        // Feature Unit layout (UAC1):
                        // offset +3 = bUnitID, +4 = bSourceID, +5 = bControlSize, then bmaControls ( (nrChannels+1) * bControlSize ), then iFeature
                        int bUnitID = full[i + 3] & 0xff;
                        int bSourceID = full[i + 4] & 0xff;
                        int bControlSize = full[i + 5] & 0xff;

                        FeatureUnitInfo fu = new FeatureUnitInfo();
                        fu.unitId = bUnitID;
                        fu.sourceId = bSourceID;
                        fu.controlSize = bControlSize;
                        fu.interfaceNumber = currentInterface;

                        // try to figure channel count from source (Input Terminal)
                        Integer nrChannels = inputTerminalChannels.get(bSourceID);
                        if (nrChannels != null) {
                            fu.channelCount = nrChannels;
                        } else {
                            // fallback: infer from descriptor length: bmaControls length = bLength - 7 - 1(iFeature)
                            int bmaLen = bLength - 7 - 1;
                            if (bControlSize > 0) {
                                int entries = bmaLen / bControlSize; // expected = nrChannels + 1
                                if (entries >= 1) {
                                    fu.channelCount = Math.max(0, entries - 1);
                                } else {
                                    fu.channelCount = 0;
                                }
                            } else {
                                fu.channelCount = 0;
                            }
                        }
                        featureUnits.add(fu);
                        //Log.d(TAG, "Found FU: " + fu);
                    }
                }
            }

            i += bLength;
        }

        return featureUnits;
    }
}