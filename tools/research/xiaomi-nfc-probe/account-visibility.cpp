// SPDX-License-Identifier: AGPL-3.0-or-later
// Count only accounts visible to this application; never read names or tokens.
#include <jni.h>
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO,"GBAccountVisibility",__VA_ARGS__)
#define CHECK(x) if(env->ExceptionCheck() || !(x)) {env->ExceptionClear();LOG("Lookup/call refused at line %d",__LINE__);return JNI_ERR;}
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm,char*,void*) {
 JNIEnv* env=nullptr;if(vm->GetEnv((void**)&env,JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
 auto tc=env->FindClass("android/app/ActivityThread");CHECK(tc);
 auto ca=env->GetStaticMethodID(tc,"currentApplication","()Landroid/app/Application;");CHECK(ca);
 auto app=env->CallStaticObjectMethod(tc,ca);CHECK(app);
 auto ac=env->FindClass("android/accounts/AccountManager");CHECK(ac);
 auto get=env->GetStaticMethodID(ac,"get","(Landroid/content/Context;)Landroid/accounts/AccountManager;");CHECK(get);
 auto am=env->CallStaticObjectMethod(ac,get,app);CHECK(am);
 auto bytype=env->GetMethodID(ac,"getAccountsByType","(Ljava/lang/String;)[Landroid/accounts/Account;");CHECK(bytype);
 auto accounts=(jobjectArray)env->CallObjectMethod(am,bytype,env->NewStringUTF("com.xiaomi"));CHECK(accounts);
 LOG("Xiaomi accounts visible to actual Gadgetbridge UID=%d; no names or tokens read",env->GetArrayLength(accounts));
 return JNI_OK;
}
