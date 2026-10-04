#include <jni.h>
#include <string>
#include <android/log.h>
#include "haze_shield_core.h"

#define LOG_TAG "HazeNativeBridge"

static haze::HazeShieldCore g_shield_core;

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_haze_native_1engine_HazeNativeBridge_nativeAddDomain(
    JNIEnv* env, jobject /* this */, jstring jdomain, jstring jcategory) {
    if (!jdomain || !jcategory) return;
    const char* domain = env->GetStringUTFChars(jdomain, nullptr);
    const char* category = env->GetStringUTFChars(jcategory, nullptr);

    std::string catStr(category);
    if (catStr == "ADS") {
        g_shield_core.AddAdDomain(domain);
    } else if (catStr == "TRACKERS") {
        g_shield_core.AddTrackerDomain(domain);
    } else if (catStr == "MALICIOUS") {
        g_shield_core.AddMalwareDomain(domain);
    } else if (catStr == "PHISHING") {
        g_shield_core.AddPhishingDomain(domain);
    }

    env->ReleaseStringUTFChars(jdomain, domain);
    env->ReleaseStringUTFChars(jcategory, category);
}

JNIEXPORT void JNICALL
Java_com_example_haze_native_1engine_HazeNativeBridge_nativeAddPatternRule(
    JNIEnv* env, jobject /* this */, jstring jpattern, jstring jcategory, jboolean is_exception) {
    if (!jpattern || !jcategory) return;
    const char* pattern = env->GetStringUTFChars(jpattern, nullptr);
    const char* category = env->GetStringUTFChars(jcategory, nullptr);

    g_shield_core.AddPatternRule(pattern, category, is_exception);

    env->ReleaseStringUTFChars(jpattern, pattern);
    env->ReleaseStringUTFChars(jcategory, category);
}

JNIEXPORT void JNICALL
Java_com_example_haze_native_1engine_HazeNativeBridge_nativeClearRules(
    JNIEnv* env, jobject /* this */) {
    g_shield_core.ClearAllRules();
}

JNIEXPORT jobject JNICALL
Java_com_example_haze_native_1engine_HazeNativeBridge_nativeEvaluateRequest(
    JNIEnv* env, jobject /* this */, jstring jrequest_url, jstring jpage_url, jboolean is_main_frame) {
    if (!jrequest_url) return nullptr;
    const char* request_url = env->GetStringUTFChars(jrequest_url, nullptr);
    const char* page_url = jpage_url ? env->GetStringUTFChars(jpage_url, nullptr) : "";

    haze::DecisionResult result = g_shield_core.EvaluateRequest(request_url, page_url, is_main_frame);

    env->ReleaseStringUTFChars(jrequest_url, request_url);
    if (jpage_url) env->ReleaseStringUTFChars(jpage_url, page_url);

    jclass decisionClass = env->FindClass("com/example/haze/native_engine/NativeDecision");
    jmethodID constructor = env->GetMethodID(
        decisionClass, "<init>", "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V");

    jstring jcat = env->NewStringUTF(result.category.c_str());
    jstring jreason = env->NewStringUTF(result.reason.c_str());
    jstring jrule = env->NewStringUTF(result.matched_rule.c_str());

    return env->NewObject(
        decisionClass, constructor,
        result.should_block ? JNI_TRUE : JNI_FALSE,
        jcat, jreason, jrule,
        (jlong)result.bytes_saved, (jlong)result.time_saved_ms);
}

} // extern "C"
