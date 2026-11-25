// SPDX-License-Identifier: AGPL-3.0-or-later
// Diagnostic lifecycle agent for the existing temporary face/RPK only.
// Fixed package and face IDs. No NFC commands, app patching, or card access.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <cctype>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "GBWatchProbe", __VA_ARGS__)
static bool rfParameters(const std::string& mode,unsigned& seq,unsigned& epoch) {
    const std::string prefix="spp-rf-reply-";
    if(mode.rfind(prefix,0)!=0)return false;
    auto dash=mode.find('-',prefix.size());
    if(dash!=prefix.size()+1 || mode[prefix.size()]<'1' || mode[prefix.size()]>'3')return false;
    seq=static_cast<unsigned>(mode[prefix.size()]-'0');epoch=0;
    if(dash+1>=mode.size() || mode.size()-dash-1>5)return false;
    for(size_t i=dash+1;i<mode.size();++i) {
        if(mode[i]<'0' || mode[i]>'9')return false;
        epoch=epoch*10+static_cast<unsigned>(mode[i]-'0');
    }
    return epoch>0 && epoch<=65535;
}
static bool check(JNIEnv* env, const char* step) {
    if (!env->ExceptionCheck()) return true;
    LOG("Java exception at %s; aborting probe", step);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
}

static std::vector<jbyte> command(const std::string& mode) {
 unsigned seq=0,epoch=0;
 if(mode=="spp-rf-start" || mode=="spp-rf-stop" || rfParameters(mode,seq,epoch))return {8,20,16,0};
 if(mode=="capability" || mode=="reconnect-probe") return {8,4,16,0};
 if(mode=="rpk-list") return {8,20,16,0};
 if(mode=="spp-test-start" || mode=="spp-test-reply-1" || mode=="spp-test-reply-2" || mode=="spp-test-reply-3") return {8,20,16,0};
 if(mode=="install-probe-rpk" || mode=="install-probe-face" || mode=="rpk-launch" || mode=="rpk-delete") return {8,20,16,0};
 std::string id;
 int sub;
 if(mode=="restore-face") {id="120917345464";sub=1;}
 else if(mode=="delete-probe-face") {id="362991121";sub=2;}
 else return {};
 std::vector<jbyte> out={8,4,16,static_cast<jbyte>(sub),50,static_cast<jbyte>(2+id.size()),18,static_cast<jbyte>(id.size())};
 out.insert(out.end(),id.begin(),id.end());return out;
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
    if (bytes.empty()) { LOG("Rejected mode; allowlist: capability/restore-face/delete-probe-face/rpk-list/rpk-launch/rpk-delete/install-probe-rpk/install-probe-face"); return JNI_ERR; }
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
    if(mode=="reconnect-probe") {
        auto helperClass=static_cast<jclass>(env->CallObjectMethod(loader,load,env->NewStringUTF("nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper")));
        REQUIRE(helperClass,"DeviceHelper class");
        auto instanceMethod=env->GetStaticMethodID(helperClass,"getInstance","()Lnodomain/freeyourgadget/gadgetbridge/util/DeviceHelper;");
        REQUIRE(instanceMethod,"DeviceHelper getInstance");
        auto helper=env->CallStaticObjectMethod(helperClass,instanceMethod);
        REQUIRE(helper,"DeviceHelper instance");
        auto find=env->GetMethodID(helperClass,"findAvailableDevice","(Ljava/lang/String;)Lnodomain/freeyourgadget/gadgetbridge/impl/GBDevice;");
        REQUIRE(find,"findAvailableDevice");
        auto device=env->CallObjectMethod(helper,find,env->NewStringUTF(address.c_str()));
        REQUIRE(device,"existing bound target");
        auto appClass=static_cast<jclass>(env->CallObjectMethod(loader,load,env->NewStringUTF("nodomain.freeyourgadget.gadgetbridge.GBApplication")));
        REQUIRE(appClass,"GBApplication class");
        auto getDeviceService=env->GetStaticMethodID(appClass,"deviceService","(Lnodomain/freeyourgadget/gadgetbridge/impl/GBDevice;)Lnodomain/freeyourgadget/gadgetbridge/model/DeviceService;");
        REQUIRE(getDeviceService,"deviceService");
        auto deviceService=env->CallStaticObjectMethod(appClass,getDeviceService,device);
        REQUIRE(deviceService,"target device service");
        auto deviceServiceClass=env->GetObjectClass(deviceService);
        REQUIRE(deviceServiceClass,"target device service class");
        auto connect=env->GetMethodID(deviceServiceClass,"connect","()V");
        REQUIRE(connect,"existing binding connect");
        env->CallVoidMethod(deviceService,connect);
        if(!check(env,"request existing binding reconnect"))return JNI_ERR;
        LOG("Requested existing binding reconnect; authentication must be checked separately");
        return JNI_OK;
    }
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
        if(mode=="install-probe-rpk" || mode=="install-probe-face") {
            const char* uri= mode=="install-probe-rpk" ? "file:///sdcard/Android/data/nodomain.freeyourgadget.gadgetbridge/files/s4nativeprobe5.rpk" : "file:///sdcard/Android/data/nodomain.freeyourgadget.gadgetbridge/files/NativeReadProbe3.face";
            auto uc=env->FindClass("android/net/Uri");
            REQUIRE(uc,"Uri class");
            auto parse=env->GetStaticMethodID(uc,"parse","(Ljava/lang/String;)Landroid/net/Uri;");
            REQUIRE(parse,"Uri parse");
            auto u=env->CallStaticObjectMethod(uc,parse,env->NewStringUTF(uri));
            REQUIRE(u,"install URI");
            auto bc=env->FindClass("android/os/Bundle");
            REQUIRE(bc,"Bundle class");
            auto ctor=env->GetMethodID(bc,"<init>","()V");
            REQUIRE(ctor,"Bundle ctor");
            auto bundle=env->NewObject(bc,ctor);
            REQUIRE(bundle,"install bundle");
            auto install=env->GetMethodID(supportClass,"onInstallApp","(Landroid/net/Uri;Landroid/os/Bundle;)V");
            REQUIRE(install,"onInstallApp");env->CallVoidMethod(support,install,u,bundle);
            return check(env,"install own diagnostic package") ? JNI_OK : JNI_ERR;
        }
        if(mode=="rpk-launch" || mode=="rpk-delete" || mode.rfind("spp-test-",0)==0 || mode.rfind("spp-rf-",0)==0) {
            auto getRpk=env->GetMethodID(supportClass,"getRpkService","()Lnodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiRpkService;");
            REQUIRE(getRpk,"getRpkService");
            auto rpk=env->CallObjectMethod(support,getRpk);
            REQUIRE(rpk,"RPK service");
            auto cls=env->GetObjectClass(rpk);
            if(mode.rfind("spp-test-",0)==0 || mode.rfind("spp-rf-",0)==0) {
                const bool start=mode=="spp-test-start" || mode=="spp-rf-start";
                unsigned seq=0,epoch=0;const bool rf=rfParameters(mode,seq,epoch);
                const std::string body=mode=="spp-rf-stop" ?
                    "{\"probe\":\"s4-spp-test\",\"op\":\"rf-stop\",\"nonce\":\"s4-spp-rf-20261125\"}" : mode=="spp-rf-start" ?
                    "{\"probe\":\"s4-spp-test\",\"op\":\"rf-start\",\"nonce\":\"s4-spp-rf-20261125\"}" : rf ?
                    "{\"probe\":\"s4-spp-test\",\"op\":\"response\",\"nonce\":\"s4-spp-rf-20261125\",\"seq\":"+
                    std::to_string(seq)+",\"epoch\":"+std::to_string(epoch)+",\"rapdu\":\"5334544553549000\"}" : start ?
                    "{\"probe\":\"s4-spp-test\",\"op\":\"start\",\"nonce\":\"s4-spp-test-20261125\"}" :
                    "{\"probe\":\"s4-spp-test\",\"op\":\"response\",\"nonce\":\"s4-spp-test-20261125\",\"seq\":"+
                    mode.substr(mode.size()-1)+",\"rapdu\":\"5334544553549000\"}";
                auto payload=env->NewByteArray(body.size());REQUIRE(payload,"fixed SPP payload");
                env->SetByteArrayRegion(payload,0,body.size(),reinterpret_cast<const jbyte*>(body.data()));
                if(start) {
                    if(mode=="spp-rf-start") {
                        // This bounded probe auto-exits. The production app's
                        // recent-open heuristic can outlive it; invalidate only
                        // this probe's ephemeral entry before its explicit start.
                        auto seenField=env->GetFieldID(cls,"lastSeenOpen","Ljava/util/Map;");
                        REQUIRE(seenField,"recent probe lifecycle map");
                        auto seen=env->GetObjectField(rpk,seenField);REQUIRE(seen,"recent lifecycle instance");
                        auto map=env->FindClass("java/util/Map");REQUIRE(map,"Map class");
                        auto remove=env->GetMethodID(map,"remove","(Ljava/lang/Object;)Ljava/lang/Object;");
                        REQUIRE(remove,"remove own recent lifecycle entry");
                        env->CallObjectMethod(seen,remove,env->NewStringUTF("com.meta.s4nativeprobe"));
                        if(!check(env,"expire only auto-exited diagnostic lifecycle"))return JNI_ERR;
                    }
                    auto send=env->GetMethodID(cls,"sendWearAppMessage","(Ljava/lang/String;[B)V");
                    REQUIRE(send,"sendWearAppMessage");
                    env->CallVoidMethod(rpk,send,env->NewStringUTF("com.meta.s4nativeprobe"),payload);
                } else {
                    // The installed pre-fix app cannot relaunch an already-open
                    // RPK. Reuse its original message/session builders directly,
                    // with only the cached ref of this diagnostic package.
                    auto shaField=env->GetFieldID(cls,"shaMap","Ljava/util/Map;");REQUIRE(shaField,"shaMap");
                    auto shaMap=env->GetObjectField(rpk,shaField);REQUIRE(shaMap,"shaMap instance");
                    auto map=env->FindClass("java/util/Map");REQUIRE(map,"Map class");
                    auto getSha=env->GetMethodID(map,"get","(Ljava/lang/Object;)Ljava/lang/Object;");REQUIRE(getSha,"Map get");
                    auto sha=env->CallObjectMethod(shaMap,getSha,env->NewStringUTF("com.meta.s4nativeprobe"));REQUIRE(sha,"cached own RPK ref");
                    auto refMethod=env->GetStaticMethodID(cls,"rpkRef","(Ljava/lang/String;Lcom/google/protobuf/ByteString;)Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/RpkInfoList;");REQUIRE(refMethod,"rpkRef");
                    auto ref=env->CallStaticObjectMethod(cls,refMethod,env->NewStringUTF("com.meta.s4nativeprobe"),sha);REQUIRE(ref,"own RPK ref");
                    auto build=env->GetStaticMethodID(cls,"buildMessageCommand","(Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/RpkInfoList;[B)Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/XiaomiProto$Command;");REQUIRE(build,"message builder");
                    auto end=env->GetStaticMethodID(cls,"buildSessionEndCommand","(Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/RpkInfoList;)Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/XiaomiProto$Command;");REQUIRE(end,"session builder");
                    auto cmd=env->CallStaticObjectMethod(cls,build,ref,payload);REQUIRE(cmd,"fixed response command");
                    auto session=env->CallStaticObjectMethod(cls,end,ref);REQUIRE(session,"session command");
                    auto send=env->GetMethodID(supportClass,"sendCommand","(Ljava/lang/String;Lnodomain/freeyourgadget/gadgetbridge/proto/xiaomi/XiaomiProto$Command;)V");REQUIRE(send,"sendCommand");
                    env->CallVoidMethod(support,send,env->NewStringUTF("synthetic SPP APDU reply"),cmd);
                    if(!check(env,"fixed response submit"))return JNI_ERR;
                    env->CallVoidMethod(support,send,env->NewStringUTF("synthetic SPP session end"),session);
                }
                LOG("Synthetic SPP operation=%s; submission only",mode.c_str());
            } else if(mode=="rpk-launch") {
                auto send=env->GetMethodID(cls,"sendWearAppMessage","(Ljava/lang/String;[B)V");
                REQUIRE(send,"sendWearAppMessage");auto payload=env->NewByteArray(2);REQUIRE(payload,"RPK ping");
                const jbyte ping[]={123,125};env->SetByteArrayRegion(payload,0,2,ping);
                env->CallVoidMethod(rpk,send,env->NewStringUTF("com.meta.s4nativeprobe"),payload);
            } else {
                auto cacheMethod=env->GetMethodID(cls,"getInstalledAppsCache","()Ljava/util/Collection;");
                if(!check(env,"getInstalledAppsCache lookup") || !cacheMethod) return JNI_ERR;
                auto apps=env->CallObjectMethod(rpk,cacheMethod);
                if(!check(env,"getInstalledAppsCache") || !apps) return JNI_ERR;
                auto lc=env->FindClass("java/util/Collection");
                REQUIRE(lc,"Collection class");
                auto toArray=env->GetMethodID(lc,"toArray","()[Ljava/lang/Object;");
                REQUIRE(toArray,"Collection toArray");
                auto array=static_cast<jobjectArray>(env->CallObjectMethod(apps,toArray));
                REQUIRE(array,"installed apps array");
                bool found=false;
                for(int i=0;i<env->GetArrayLength(array);i++) {
                    auto app=env->GetObjectArrayElement(array,i);
                    REQUIRE(app,"installed app");
                    auto ac=env->GetObjectClass(app);
                    auto creatorMethod=env->GetMethodID(ac,"getCreator","()Ljava/lang/String;");
                    REQUIRE(creatorMethod,"getCreator lookup");auto creator=static_cast<jstring>(env->CallObjectMethod(app,creatorMethod));
                    REQUIRE(creator,"app creator");auto c=env->GetStringUTFChars(creator,nullptr);REQUIRE(c,"creator UTF8");bool match=!strcmp(c,"com.meta.s4nativeprobe");env->ReleaseStringUTFChars(creator,c);
                    if(match) {
                        auto uuidMethod=env->GetMethodID(ac,"getUUID","()Ljava/util/UUID;");REQUIRE(uuidMethod,"getUUID");auto uuid=env->CallObjectMethod(app,uuidMethod);REQUIRE(uuid,"app UUID");
                        auto del=env->GetMethodID(cls,"deleteRpk","(Ljava/util/UUID;)V");REQUIRE(del,"deleteRpk");env->CallVoidMethod(rpk,del,uuid);found=true;break;
                    }
                }
                LOG("Probe app found for cleanup=%d",found);
            }
            return check(env,"rpk diagnostic operation") ? JNI_OK : JNI_ERR;
        }
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
