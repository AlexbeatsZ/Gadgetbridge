// SPDX-License-Identifier: AGPL-3.0-or-later
// Read-only research agent for an already running, debuggable Gadgetbridge.
// No card provisioning, routing changes, key access, or arbitrary APDU input.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <cctype>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "GBNfcProbe", __VA_ARGS__)
static bool check(JNIEnv* env, const char* step) {
    if (!env->ExceptionCheck()) return true;
    LOG("Java exception at %s; aborting probe", step);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
}

static std::vector<jbyte> command(const std::string& mode) {
    if (mode == "capability") return {8,5,16,10,58,0};
    std::vector<jbyte> apdu;
    int op;
    if (mode == "open") op = 192;
    else if (mode == "close") op = 193;
    else if (mode == "cplc") {
        op = 194;
        const unsigned char bytes[] = {0x80,0xCA,0x9F,0x7F,0x00};
        for (auto b : bytes) apdu.push_back(static_cast<jbyte>(b));
    }
    else if (mode == "select-test") {
        op = 194;
        // Unregistered, fictitious AID F0010203040506. SELECT only.
        const unsigned char bytes[] = {0x00,0xA4,0x04,0x00,0x07,
            0xF0,0x01,0x02,0x03,0x04,0x05,0x06,0x00};
        for (auto b : bytes) apdu.push_back(static_cast<jbyte>(b));
    } else return {};
    // Outer Command(type=5, subtype=0), field 7=Nfc, field 1=command.
    // Command/status and length are big endian (official ByteBuffer default).
    std::vector<jbyte> out = {8,5,16,0,58,static_cast<jbyte>(8+apdu.size()),
        10,static_cast<jbyte>(6+apdu.size()),0,0,0,static_cast<jbyte>(op),
        0,static_cast<jbyte>(apdu.size())};
    out.insert(out.end(),apdu.begin(),apdu.end());
    return out;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) {
    LOG("Agent attached; starting allowlisted probe");
    const std::string input = options ? options : "";
    const auto separator = input.find('@');
    if(separator == std::string::npos) { LOG("Explicit target address required"); return JNI_ERR; }
    const std::string mode = input.substr(0, separator);
    const std::string address = input.substr(separator + 1);
    if(address.size()!=17) return JNI_ERR;
    for(size_t i=0;i<address.size();i++) {
        if(i%3==2 ? address[i]!=':' : !std::isxdigit(static_cast<unsigned char>(address[i]))) return JNI_ERR;
    }
    const auto bytes = command(mode);
    if (bytes.empty()) { LOG("Rejected mode; allowlist: capability/open/select-test/cplc/close"); return JNI_ERR; }
    JNIEnv* env = nullptr;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK) return JNI_ERR;
    // Retrieve the live service without heap iteration or class redefinition.
#define REQUIRE(value, step) if(!check(env, step) || !(value)) { LOG("Missing %s", step); return JNI_ERR; }
    auto threadClass=env->FindClass("android/app/ActivityThread");
    REQUIRE(threadClass,"ActivityThread class");
    auto current=env->GetStaticMethodID(threadClass,"currentActivityThread","()Landroid/app/ActivityThread;");
    if(!check(env,"ActivityThread lookup") || !current) return JNI_ERR;
    auto thread=env->CallStaticObjectMethod(threadClass,current);
    REQUIRE(thread,"ActivityThread instance");
    auto servicesField=env->GetFieldID(threadClass,"mServices","Landroid/util/ArrayMap;");
    if(!check(env,"mServices lookup") || !servicesField) return JNI_ERR;
    auto services=env->GetObjectField(thread,servicesField);
    REQUIRE(services,"mServices");
    auto mapClass=env->FindClass("android/util/ArrayMap");
    REQUIRE(mapClass,"ArrayMap class");
    auto mapSize=env->GetMethodID(mapClass,"size","()I");
    REQUIRE(mapSize,"ArrayMap size");
    auto valueAt=env->GetMethodID(mapClass,"valueAt","(I)Ljava/lang/Object;");
    REQUIRE(valueAt,"ArrayMap valueAt");
    auto classClass=env->FindClass("java/lang/Class");
    REQUIRE(classClass,"Class class");
    auto getName=env->GetMethodID(classClass,"getName","()Ljava/lang/String;");
    REQUIRE(getName,"Class getName");
    jobject service=nullptr;
    for(int i=0;i<env->CallIntMethod(services,mapSize);i++) {
        auto item=env->CallObjectMethod(services,valueAt,i);
        REQUIRE(item,"service instance");
        auto cls=env->GetObjectClass(item);
        auto name=static_cast<jstring>(env->CallObjectMethod(cls,getName));
        REQUIRE(name,"service name");
        const char* text=env->GetStringUTFChars(name,nullptr);
        REQUIRE(text,"service name UTF8");
        bool matches=!strcmp(text,"nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService");
        env->ReleaseStringUTFChars(name,text);
        if(matches) { service=item; break; }
    }
    if(!service) { LOG("Running DeviceCommunicationService absent"); return JNI_ERR; }
    auto serviceClass=env->GetObjectClass(service);
    auto getLoader=env->GetMethodID(classClass,"getClassLoader","()Ljava/lang/ClassLoader;");
    REQUIRE(getLoader,"getClassLoader");
    auto loader=env->CallObjectMethod(serviceClass,getLoader);
    REQUIRE(loader,"app class loader");
    auto loaderClass=env->FindClass("java/lang/ClassLoader");
    REQUIRE(loaderClass,"ClassLoader class");
    auto load=env->GetMethodID(loaderClass,"loadClass","(Ljava/lang/String;)Ljava/lang/Class;");
    REQUIRE(load,"loadClass");
    auto commandClass=static_cast<jclass>(env->CallObjectMethod(loader,load,env->NewStringUTF("nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto$Command")));
    if(!check(env,"Command class lookup") || !commandClass) return JNI_ERR;
    auto field=env->GetFieldID(serviceClass,"deviceStructs","Ljava/util/ArrayList;");
    REQUIRE(field,"deviceStructs field");
    auto listClass=env->FindClass("java/util/ArrayList");
    REQUIRE(listClass,"ArrayList class");
    auto size=env->GetMethodID(listClass,"size","()I");
    REQUIRE(size,"ArrayList size");
    auto get=env->GetMethodID(listClass,"get","(I)Ljava/lang/Object;");
    REQUIRE(get,"ArrayList get");
    jobject support=nullptr;
    {
        auto list=env->GetObjectField(service,field);
        if (!check(env,"deviceStructs") || !list) return JNI_ERR;
        for(int k=0;k<env->CallIntMethod(list,size);k++) {
            auto item=env->CallObjectMethod(list,get,k);
            REQUIRE(item,"device struct");
            auto cls=env->GetObjectClass(item);
            auto getter=env->GetMethodID(cls,"getDeviceSupport","()Lnodomain/freeyourgadget/gadgetbridge/service/DeviceSupport;");
            REQUIRE(getter,"getDeviceSupport lookup");
            auto candidate=env->CallObjectMethod(item,getter);
            if (!check(env,"getDeviceSupport") || !candidate) continue;
            auto wrapper=env->GetObjectClass(candidate);
            auto connected=env->GetMethodID(wrapper,"isConnected","()Z");
            REQUIRE(connected,"isConnected lookup");
            if (!env->CallBooleanMethod(candidate,connected)) continue;
            auto delegate=env->GetFieldID(wrapper,"delegate","Lnodomain/freeyourgadget/gadgetbridge/service/DeviceSupport;");
            if (!check(env,"delegate") || !delegate) continue;
            auto target=env->GetObjectField(candidate,delegate);
            REQUIRE(target,"Xiaomi support delegate");
            auto targetClass=env->GetObjectClass(target);
            auto deviceGetter=env->GetMethodID(targetClass,"getDevice","()Lnodomain/freeyourgadget/gadgetbridge/impl/GBDevice;");
            REQUIRE(deviceGetter,"getDevice lookup");
            auto device=env->CallObjectMethod(target,deviceGetter);
            REQUIRE(device,"GBDevice");
            auto dc=env->GetObjectClass(device);
            auto addressGetter=env->GetMethodID(dc,"getAddress","()Ljava/lang/String;");
            REQUIRE(addressGetter,"getAddress lookup");
            auto actual=static_cast<jstring>(env->CallObjectMethod(device,addressGetter));
            REQUIRE(actual,"target address");
            auto text=env->GetStringUTFChars(actual,nullptr);
            REQUIRE(text,"target address UTF8");
            bool matches=address==text;
            env->ReleaseStringUTFChars(actual,text);
            if(!matches) continue;
            auto sender=env->GetMethodID(targetClass,"sendCommand","(Ljava/lang/String;Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/XiaomiProto$Command;)V");
            if (!check(env,"sendCommand lookup") || !sender) continue;
            if(support) { LOG("Ambiguous target; nothing sent"); return JNI_ERR; }
            support=target;
        }
    }
    jint result=JNI_ERR;
    if(support) {
        auto supportClass=env->GetObjectClass(support);
        auto firmware=env->GetMethodID(supportClass,"getCachedFirmwareVersion","()Ljava/lang/String;");
        REQUIRE(firmware,"getCachedFirmwareVersion lookup");
        if(firmware) {
            auto version=static_cast<jstring>(env->CallObjectMethod(support,firmware));
            if(version) { const char* v=env->GetStringUTFChars(version,nullptr); REQUIRE(v,"firmware UTF8"); LOG("Cached watch firmware=%s",v); env->ReleaseStringUTFChars(version,v); }
        }
        if(!check(env,"firmware")) return JNI_ERR;
        auto parse=env->GetStaticMethodID(commandClass,"parseFrom","([B)Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/XiaomiProto$Command;");
        REQUIRE(parse,"parseFrom lookup");
        auto array=env->NewByteArray(bytes.size());
        REQUIRE(array,"command array");
        env->SetByteArrayRegion(array,0,bytes.size(),bytes.data());
        auto cmd=env->CallStaticObjectMethod(commandClass,parse,array);
        if (check(env,"parseFrom") && cmd) {
            auto cls=env->GetObjectClass(support);
            auto sender=env->GetMethodID(cls,"sendCommand","(Ljava/lang/String;Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/XiaomiProto$Command;)V");
            REQUIRE(sender,"sendCommand lookup");
            std::string name="read-only NFC research "+mode;
            env->CallVoidMethod(support,sender,env->NewStringUTF(name.c_str()),cmd);
            if(check(env,"sendCommand")) { LOG("Queued mode=%s bytes=%zu (watch response must be checked separately)",mode.c_str(),bytes.size()); result=JNI_OK; }
        }
    } else LOG("No connected Xiaomi support found; nothing sent");
    return result;
}
