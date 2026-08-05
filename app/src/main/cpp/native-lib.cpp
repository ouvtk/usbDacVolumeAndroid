#include <jni.h>
#include <string>
#include <vector>

#include "libusb_utils.h"
#include <jni.h>
#include <assert.h>

std::string connect_device(int fileDescriptor) {
    libusb_context *ctx;
    libusb_device_handle *devh;
    int r = 0;

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);        //
    libusb_init(nullptr);
    libusb_wrap_sys_device(nullptr, (intptr_t) fileDescriptor, &devh);

    auto device = libusb_get_device(devh);
    print_device(device, devh);

    libusb_reset_device(devh);

    return get_device_name(device, devh);
}

// Helper: convert Java byte[] -> newly-allocated unsigned char[] (caller must delete[])
unsigned char* as_unsigned_char_array(JNIEnv *env, jbyteArray array) {
    int len = env->GetArrayLength(array);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte*>(buf));
    return buf;
}

void setVolume(int fileDescriptor, unsigned char *data) {
    libusb_device_handle *devh;
    libusb_wrap_sys_device(nullptr, (intptr_t) fileDescriptor, &devh);

    // Detach kernel driver if present then claim interface
    int r = libusb_detach_kernel_driver(devh, 0);
    (void)r; // ignore if not attached; check in production
    r = libusb_claim_interface(devh, 0);
    if (r < 0) {
        // handle/return error in production
    }

    // Send feature unit (or vendor) SET requests for left/right
    // bmRequestType: 0x21 (host-to-device, class, interface)
    libusb_control_transfer(devh, 0b00100001, 0x1, 0x0201, 0x0200, data, 2, 500);
    libusb_control_transfer(devh, 0b00100001, 0x1, 0x0202, 0x0200, data, 2, 500);

    // Release after transfers
    libusb_release_interface(devh, 0);
    libusb_reset_device(devh);
}

// Read two 2-byte fields (left/right) via IN control transfers
std::vector<unsigned char> getVolumeRaw(int fileDescriptor) {
    libusb_device_handle *devh;
    libusb_wrap_sys_device(nullptr, (intptr_t) fileDescriptor, &devh);

    // Ensure interface claimed
    int r = libusb_detach_kernel_driver(devh, 0);
    (void)r;
    r = libusb_claim_interface(devh, 0);
    if (r < 0) {
        // In production, return an error or throw via JNI; here return empty vector
        return {};
    }

    unsigned char left[2] = {0,0};
    unsigned char right[2] = {0,0};

    // bmRequestType: 0xA1 (device-to-host, class, interface) -> IN read
    int ret = libusb_control_transfer(devh, 0b10100001, 0x1, 0x0201, 0x0200, left, 2, 500);
    if (ret < 0) {
        // handle error; for now leave left as zeros
    }

    ret = libusb_control_transfer(devh, 0b10100001, 0x1, 0x0202, 0x0200, right, 2, 500);
    if (ret < 0) {
        // handle error; for now leave right as zeros
    }

    libusb_release_interface(devh, 0);
    libusb_reset_device(devh);

    std::vector<unsigned char> out(4);
    out[0] = left[0]; out[1] = left[1];
    out[2] = right[0]; out[3] = right[1];
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_libusbAndroidTest_MainActivity_initializeNativeDevice(
        JNIEnv *env,
        jobject /* this */,
        jint fileDescriptor) {


    std::string deviceName = connect_device(fileDescriptor);

    return env->NewStringUTF(deviceName.c_str());
}


extern "C" JNIEXPORT void JNICALL
Java_com_example_libusbAndroidTest_MainActivity_setDeviceVolume(
        JNIEnv *env,
        jobject /* this */,
        jint fileDescriptor,
        jbyteArray volume) {

            
    setVolume(fileDescriptor, as_unsigned_char_array(env, volume));
}

// New JNI: returns byte[] length 4: left[0], left[1], right[0], right[1]
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_libusbAndroidTest_MainActivity_getDeviceVolume(
        JNIEnv *env,
        jobject /* this */,
        jint fileDescriptor) {

    std::vector<unsigned char> vol = getVolumeRaw(fileDescriptor);
    if (vol.empty()) {
        // return null or a zero-filled array on error; here return null
        return nullptr;
    }

    jbyteArray out = env->NewByteArray((jsize)vol.size());
    env->SetByteArrayRegion(out, 0, (jsize)vol.size(), reinterpret_cast<jbyte*>(vol.data()));
    return out;
}