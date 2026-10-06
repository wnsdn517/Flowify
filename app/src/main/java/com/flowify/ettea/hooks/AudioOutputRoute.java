package com.flowify.ettea.hooks;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import com.flowify.ettea.ui.ActionIconDrawable;

import java.util.List;

/**
 * Where this phone's own music is coming out right now: earbuds by their Bluetooth name, a wired
 * or USB headset, HDMI, or the phone itself. Local playback used to be "This phone" whatever was
 * connected, so moving between earbuds and speaker never said so, and earbuds read as the phone.
 */
final class AudioOutputRoute {
    final String label;
    final ActionIconDrawable.Kind kind;

    private AudioOutputRoute(String label, ActionIconDrawable.Kind kind) {
        this.label = label;
        this.kind = kind;
    }

    interface Labels {
        String get(String key, String fallback);
    }

    static AudioOutputRoute current(Context context, Labels labels) {
        AudioDeviceInfo device = null;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) device = mediaDevice(audio);
        } catch (Throwable ignored) {
        }
        return describe(device == null ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : device.getType(),
                device == null ? null : String.valueOf(device.getProductName()), labels);
    }

    /** The device media is routed to: asked directly on Android 13+, else the best connected one. */
    private static AudioDeviceInfo mediaDevice(AudioManager audio) {
        if (Build.VERSION.SDK_INT >= 33) {
            List<AudioDeviceInfo> routed = audio.getAudioDevicesForAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (routed != null && !routed.isEmpty()) return routed.get(0);
        }
        AudioDeviceInfo best = null;
        int bestRank = Integer.MAX_VALUE;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int rank = rank(device.getType());
            if (rank < bestRank) {
                bestRank = rank;
                best = device;
            }
        }
        return best;
    }

    /** Android routes media to the most recently connected external device; this mirrors that. */
    private static int rank(int type) {
        if (isBluetooth(type)) return 0;
        if (isWired(type)) return 1;
        if (type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_LINE_ANALOG
                || type == AudioDeviceInfo.TYPE_LINE_DIGITAL) return 2;
        if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return 3;
        return 9;
    }

    static AudioOutputRoute describe(int type, String productName, Labels labels) {
        String name = productName == null ? "" : productName.trim();
        if (isBluetooth(type)) {
            return new AudioOutputRoute(name.isEmpty()
                    ? labels.get("lyrics_device_bluetooth", "Bluetooth device") : name,
                    ActionIconDrawable.Kind.BLUETOOTH);
        }
        if (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
            return new AudioOutputRoute(labels.get("lyrics_device_usb", "USB audio"),
                    ActionIconDrawable.Kind.HEADPHONES);
        }
        if (isWired(type)) {
            return new AudioOutputRoute(labels.get("lyrics_device_wired", "Wired headphones"),
                    ActionIconDrawable.Kind.HEADPHONES);
        }
        if (type == AudioDeviceInfo.TYPE_HDMI) {
            return new AudioOutputRoute("HDMI", ActionIconDrawable.Kind.SPEAKER);
        }
        return new AudioOutputRoute(labels.get("lyrics_device_phone", "This phone"),
                ActionIconDrawable.Kind.SMARTPHONE);
    }

    private static boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_HEARING_AID
                || (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                || (Build.VERSION.SDK_INT >= 33 && type == AudioDeviceInfo.TYPE_BLE_BROADCAST);
    }

    private static boolean isWired(int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }
}
