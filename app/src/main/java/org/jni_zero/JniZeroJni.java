package org.jni_zero;
public class JniZeroJni implements JniZero.Natives {
    private static JniZero.Natives instance = new JniZeroJni();
    public static JniZero.Natives get() { return instance; }
    @Override
    public void setJniClassLoader(ClassLoader classLoader) {
    }
}