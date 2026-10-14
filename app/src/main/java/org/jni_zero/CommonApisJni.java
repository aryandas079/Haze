package org.jni_zero;
public class CommonApisJni implements CommonApis.Natives {
    private static CommonApis.Natives instance = new CommonApisJni();
    public static CommonApis.Natives get() { return instance; }
    @Override
    public void releaseRawPtr(long ptr) {}
    @Override
    public void deleteDeleterBasePtr(long ptr, long deleterBasePtr) {}
}