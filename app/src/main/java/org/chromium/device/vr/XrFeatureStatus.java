package org.chromium.device.vr;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;

@JNINamespace("device")
public class XrFeatureStatus {
    @CalledByNative
    public static boolean isXrDevice() {
        return false;
    }
}
