package com.example.shutterremote

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings

/**
 * HID report descriptor and SDP record describing the virtual remote this phone
 * advertises. The descriptor declares two reports so the camera phone can be
 * triggered by whichever input it honours as a shutter:
 *
 *  - Report id 1 (Consumer Control): a 1-byte bitfield. bit0 = Volume Up,
 *    bit1 = Volume Down, bit2 = Play/Pause. Pixel's Google Camera fires the
 *    shutter on a volume key, so Volume Up is the default trigger.
 *  - Report id 2 (Keyboard): standard 8-byte boot-keyboard report, used only for
 *    the optional Enter fallback (some cameras respond to Enter instead).
 *
 * sendReport() takes the report id separately, so the byte arrays below are the
 * payloads WITHOUT the leading id byte.
 */
object HidConstants {

    const val REPORT_ID_CONSUMER = 1
    const val REPORT_ID_KEYBOARD = 2

    // Consumer payloads (1 byte).
    val CONSUMER_VOLUME_UP = byteArrayOf(0x01)   // bit0
    val CONSUMER_RELEASE = byteArrayOf(0x00)

    // Keyboard payloads (8 bytes: modifier, reserved, key1..key6). Enter = 0x28.
    val KEYBOARD_ENTER =
        byteArrayOf(0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x00, 0x00)
    val KEYBOARD_RELEASE =
        byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    private val REPORT_DESCRIPTOR = byteArrayOf(
        // --- Consumer Control (report id 1) ---
        0x05.toByte(), 0x0C.toByte(),       // Usage Page (Consumer)
        0x09.toByte(), 0x01.toByte(),       // Usage (Consumer Control)
        0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
        0x85.toByte(), 0x01.toByte(),       //   Report ID (1)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
        0x95.toByte(), 0x03.toByte(),       //   Report Count (3)
        0x09.toByte(), 0xE9.toByte(),       //   Usage (Volume Increment)
        0x09.toByte(), 0xEA.toByte(),       //   Usage (Volume Decrement)
        0x09.toByte(), 0xCD.toByte(),       //   Usage (Play/Pause)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data,Var,Abs)
        0x95.toByte(), 0x05.toByte(),       //   Report Count (5)
        0x81.toByte(), 0x01.toByte(),       //   Input (Const) -> pad to 1 byte
        0xC0.toByte(),                      // End Collection

        // --- Keyboard (report id 2) ---
        0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),       // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
        0x85.toByte(), 0x02.toByte(),       //   Report ID (2)
        0x05.toByte(), 0x07.toByte(),       //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(),       //   Usage Minimum (Left Control)
        0x29.toByte(), 0xE7.toByte(),       //   Usage Maximum (Right GUI)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),       //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data,Var,Abs) -> modifiers
        0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x81.toByte(), 0x01.toByte(),       //   Input (Const) -> reserved byte
        0x95.toByte(), 0x06.toByte(),       //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),       //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(),       //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(),       //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(),       //   Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(),       //   Input (Data,Array) -> 6 key slots
        0xC0.toByte()                       // End Collection
    )

    val sdpSettings: BluetoothHidDeviceAppSdpSettings
        get() = BluetoothHidDeviceAppSdpSettings(
            "Pixel Shutter Remote",
            "Remote shutter trigger for astrophotography",
            "ShutterRemote",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            REPORT_DESCRIPTOR
        )
}
