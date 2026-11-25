// SPDX-License-Identifier: AGPL-3.0-or-later
// Load an isolated research DEX in the installed debug application's real UID.
#include <jni.h>
#include <android/log.h>

#define CHECK(value) do { if (env->ExceptionCheck() || !(value)) { \
    env->ExceptionClear(); __android_log_print(ANDROID_LOG_ERROR, \
    "GBTsmAuthResearch", "loader failed at line %d", __LINE__); return JNI_ERR; } } while(0)

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char*, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass at = env->FindClass("android/app/ActivityThread"); CHECK(at);
    jmethodID appMethod = env->GetStaticMethodID(at, "currentApplication", "()Landroid/app/Application;"); CHECK(appMethod);
    jobject app = env->CallStaticObjectMethod(at, appMethod); CHECK(app);
    jclass context = env->FindClass("android/content/Context"); CHECK(context);
    jmethodID getLoader = env->GetMethodID(context, "getClassLoader", "()Ljava/lang/ClassLoader;"); CHECK(getLoader);
    jobject parent = env->CallObjectMethod(app, getLoader); CHECK(parent);
    jclass dc = env->FindClass("dalvik/system/DexClassLoader"); CHECK(dc);
    jmethodID ctor = env->GetMethodID(dc, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V"); CHECK(ctor);
    jstring file = env->NewStringUTF("/data/user/0/nodomain.freeyourgadget.gadgetbridge/code_cache/tsm-auth-research.dex"); CHECK(file);
    jobject loader = env->NewObject(dc, ctor, file, nullptr, nullptr, parent); CHECK(loader);
    jclass cl = env->FindClass("java/lang/ClassLoader"); CHECK(cl);
    jmethodID load = env->GetMethodID(cl, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"); CHECK(load);
    jstring name = env->NewStringUTF("research.xiaomi.tsm.TsmAuthProbe"); CHECK(name);
    jclass probe = static_cast<jclass>(env->CallObjectMethod(loader, load, name)); CHECK(probe);
    jmethodID start = env->GetStaticMethodID(probe, "start", "(Landroid/content/Context;)V"); CHECK(start);
    env->CallStaticVoidMethod(probe, start, app);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_ERR; }
    return JNI_OK;
}
