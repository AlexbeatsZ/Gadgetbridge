// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 synthetic AID host experiment. No SE session or arbitrary APDU.
// Register/arm/remove run in original NFA GET_ROUTING response callbacks.
// Keep resident until entries + discovery/RF callback ownership are detached,
// original raw callback has returned/cleared, and an original-queue barrier ran.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
#define H(a) (*(const volatile u16 *)(a))
#define B(a) (*(const volatile u8 *)(a))
#define RW(a) (*(volatile u32 *)(a))
#define RB(a) (*(volatile u8 *)(a))
#define DM 0x3c2d46fc
#define NFC 0x3c2d4b8c
#define DISC (NFC+0xa4)
#define RF (NFC+4)
#define ENTRY (DM+0x58)
#define ORIGINAL_DISC 0x2c344699
#define ORIGINAL_RF 0x2c34472d
#define HAL_SLOT 0x3c2d45cc
#define ORIGINAL_HAL 0x2c34cd85
#ifndef HOST_SPP_BRIDGE
#define HOST_SPP_BRIDGE 0
#endif
__attribute__((section(".data.result"),used)) volatile struct {
    u32 h[32];u8 before[40];u32 rf_meta[16];u32 hal_meta[16];u32 ipc[16];
} s4_host_test={{0x53483454},{0},{0},{0},{0x53505049}};
static const u8 select_apdu[]={0,0xa4,4,0,7,0xf0,1,2,3,4,5,6};
static const u8 fixed_response[]={'S','4','T','E','S','T',0x90,0};
static void rf_receive(u8 conn,u16 event,void *data);
static void discovered(u8 event,void *data);
static void arm_reply(u8 event,u16 length,const u8 *packet);
static void remove_reply(u8 event,u16 length,const u8 *packet);
static u8 *fixed_message(void);
static int original_entry(void);
static int owned_entry(void);
static volatile u32 hal_host_active;
static void bridge_pump(void);
static void bridge_invalidate(void) {
    if(HOST_SPP_BRIDGE) {
        s4_host_test.ipc[5]++;s4_host_test.ipc[4]=0;
        __asm__ volatile("dmb" ::: "memory");
    }
}
static int host_guard(void) {
    const u16 handle=H(DM+0x52);
    const int host=(s4_host_test.h[24] && handle==1) || (hal_host_active && handle==0xffff);
    return s4_host_test.h[1]==4 && host && B(DM+0x4c)==5 && original_entry() && owned_entry();
}
static void queue_hal_test_reply(void) {
    // HAL runs outside the NFA owner: enqueue a copied, immutable test response
    // for the ORIGINAL action 0x10c. Never call RF send/drain or change the RF
    // callback here. This message contains no resident-module pointer.
    const u16 handle=H(DM+0x52);
    // Preserve ALL handle bits; RF7's old <<20 diagnostic retained only 12.
    s4_host_test.rf_meta[13]=(s4_host_test.h[1]&15) | ((u32)B(DM+0x4c)<<4) |
        (s4_host_test.h[24]<<8) | (hal_host_active<<9) | ((u32)handle<<16);
    // RF7: exact DATA can arrive in state5 with handleFFFF and no listener
    // activation. Accept that case only with a complete controller-reported
    // DH ISO-DEP Listen-A activation. Never accept an original eSE handle0.
    if(!host_guard())return;
    if(HOST_SPP_BRIDGE) {
        if(s4_host_test.ipc[4] || s4_host_test.ipc[6]>=3){s4_host_test.ipc[13]++;return;}
        s4_host_test.ipc[6]++;s4_host_test.ipc[7]=s4_host_test.ipc[5];
        s4_host_test.ipc[11]=0;s4_host_test.rf_meta[15]=1;
        __asm__ volatile("dmb" ::: "memory");s4_host_test.ipc[4]=1;
        return; // HAL only publishes metadata; no file IO or local response.
    }
    u8 *m=fixed_message();if(!m)return;
    s4_host_test.rf_meta[15]=1; // Suppress a second reply if RF delivery recovers.
    ((void (*)(void *))0x2c34de2d)(m);
    s4_host_test.rf_meta[14]++; // Queue submission, not RF acceptance.
}
static void hal_receive(u16 length,u8 *packet) {
    int test_reply=0;
    s4_host_test.hal_meta[0]++;s4_host_test.hal_meta[2]++;
    if(packet && length>=3) {
        const u32 mt=packet[0]&0xe0;
        s4_host_test.hal_meta[6]=mt;s4_host_test.hal_meta[7]=packet[1];
        if(mt==0) {
            s4_host_test.hal_meta[3]++;s4_host_test.hal_meta[5]=length;
            s4_host_test.hal_meta[12]=W(RF);s4_host_test.hal_meta[13]=packet[0]&15;
            if((length==15 || length==16) && packet[2]==length-3) {
                int match=1;
                for(u32 i=0;i<12;++i)if(packet[3+i]!=select_apdu[i])match=0;
                if(length==16 && packet[15])match=0;
                if(match) {
                    s4_host_test.hal_meta[4]++;
                    test_reply=packet[0]==0 && packet[1]==0;
                }
            }
        } else if(packet[0]==0x61 && packet[1]==5) {
            s4_host_test.hal_meta[8]++;
            bridge_invalidate();
            hal_host_active=length>=7 && packet[2]==length-3 &&
                packet[4]==2 && packet[5]==4 && packet[6]==0x80;
            s4_host_test.rf_meta[15]=0;
        } else if(packet[0]==0x61 && packet[1]==6) {
            s4_host_test.hal_meta[9]++;hal_host_active=0;bridge_invalidate();
        }
        else if(packet[0]==0x61 && packet[1]==7) {
            s4_host_test.hal_meta[10]++;
            if(length>=4)s4_host_test.hal_meta[11]=packet[3];
        } else s4_host_test.hal_meta[15]++;
        // Detach in the callback owner, then forward unmodified. The bounded
        // recovery also sends GET_ROUTING after STOP to guarantee this path.
        if(packet[0]==0x41 && (packet[1]==2 || packet[1]==6) && W(HAL_SLOT)==(u32)hal_receive) {
            hal_host_active=0;
            bridge_invalidate();
            RW(HAL_SLOT)=ORIGINAL_HAL;__asm__ volatile("dmb" ::: "memory");
            s4_host_test.hal_meta[1]=packet[1]==2?2:3;
        }
    }
    // Mark the copied reply before forwarding: original HAL may immediately
    // schedule the RF consumer, which must not submit a duplicate response.
    if(test_reply)queue_hal_test_reply();
    ((void (*)(u16,u8 *))ORIGINAL_HAL)(length,packet);
    s4_host_test.hal_meta[0]--;
}
static int query_ok(u8 event,u16 length,const u8 *p) {
    s4_host_test.h[18]++;
    s4_host_test.h[23]=(p && length==4)?p[3]:0xffffffff;
    return event==0x42 && length==4 && p && p[0]==0x41 && p[1]==2 && p[2]==1 && p[3]==0;
}
static int original_entry(void) {
    for(u32 i=0;i<20;++i)if(B(ENTRY+i)!=s4_host_test.before[i])return 0;
    return W(0x3c2d46d4)==0x8101;
}
static int owned_entry(void) {
    return B(ENTRY+20)==1 && W(ENTRY+24)==(u32)discovered && B(ENTRY+30)==0 && W(ENTRY+32)==0x40000;
}
static void retain_discovery(void) {
    // The original state machine may restart discovery after deactivation and
    // reinstall its default callback. Retain only our bounded session while
    // both registrations still have the expected ownership.
    if(s4_host_test.h[1]==4 && W(DISC)==ORIGINAL_DISC && original_entry() && owned_entry()) {
        RW(DISC)=s4_host_test.h[15];__asm__ volatile("dmb" ::: "memory");
    }
}
static void set_rf(u32 target) {((void (*)(void *))0x2c34dff1)((void *)target);}
static void free_buffer(void *p) {((void (*)(void *))0x2c34311d)(p);}
static u8 *fixed_message(void) {
    u8 *m=((u8 *(*)(u16))0x2c342e85)(20);
    if(!m){s4_host_test.h[12]++;return (u8 *)0;}
    for(u32 i=0;i<20;++i)m[i]=0;
    *(u16 *)m=0x10c;*(u16 *)(m+2)=8;*(u16 *)(m+4)=4;
    for(u32 i=0;i<8;++i)m[12+i]=fixed_response[i];
    return m;
}
static void send_fixed(void) {
    u8 *m=fixed_message();if(!m)return;
    int release=((int (*)(void *))0x2c3420d1)(m);
    if(release)free_buffer(m);
    else s4_host_test.h[10]++; // Submission only, not proof of RF transmission.
}
static char *put_text(char *p,const char *s){while(*s)*p++=*s++;return p;}
static char *put_number(char *p,u32 value) {
    char rev[10];u32 n=0;do{rev[n++]=(char)('0'+value%10);value/=10;}while(value);
    while(n)*p++=rev[--n];return p;
}
static int read_number(const char **p,const char *end,u32 *v) {
    u32 n=0,count=0;while(*p<end && **p>='0' && **p<='9') {
        if(++count>5)return 0;n=n*10+(u32)(*(*p)++-'0');
    }
    if(!count || *p>=end || *(*p)++!=':')return 0;*v=n;return 1;
}
static int ipc_open(const char *path,int flags){return ((int (*)(const char *,int,int))0x2c55d839)(path,flags,0600);}
static void ipc_close(int fd){((int (*)(int))0x2c55af95)(fd);}
static void bridge_pump(void) {
    if(!HOST_SPP_BRIDGE)return;
    s4_host_test.ipc[3]++;
    const u32 seq=s4_host_test.ipc[6],epoch=s4_host_test.ipc[7],stage=s4_host_test.ipc[4];
    if(!stage)goto done;
    if(epoch!=s4_host_test.ipc[5] || !host_guard()){s4_host_test.ipc[4]=0;goto done;}
    if(stage==1) {
        char text[160],*p=text;
        p=put_text(p,"{\"probe\":\"s4-spp-test\",\"op\":\"request\",\"nonce\":\"s4-spp-rf-20261125\",\"seq\":");
        p=put_number(p,seq);p=put_text(p,",\"epoch\":");p=put_number(p,epoch);
        p=put_text(p,",\"apdu\":\"00a4040007f001020304050600\"}");
        int fd=ipc_open("/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-request.txt",0x26);
        if(fd<0){s4_host_test.ipc[12]++;goto done;}
        int n=((int (*)(int,const void *,int))0x2c55d61b)(fd,text,(int)(p-text));ipc_close(fd);
        if(n!=p-text){s4_host_test.ipc[12]++;goto done;}
        s4_host_test.ipc[8]++;
        if(epoch==s4_host_test.ipc[5] && s4_host_test.ipc[6]==seq)s4_host_test.ipc[4]=2;
    } else if(stage==2) {
        if(++s4_host_test.ipc[11]>40){s4_host_test.ipc[14]++;s4_host_test.ipc[4]=0;goto done;}
        char text[64];int fd=ipc_open("/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-response.txt",1);
        if(fd<0)goto done;
        int n=((int (*)(int,void *,int))0x2c55d5e1)(fd,text,sizeof(text));ipc_close(fd);
        if(n<0 || n>63)goto done;
        const char *p=text,*end=text+n;u32 got_seq=0,got_epoch=0;
        const char *prefix="S4RF1:";while(*prefix && p<end && *p==*prefix){p++;prefix++;}
        if(*prefix || !read_number(&p,end,&got_seq) || !read_number(&p,end,&got_epoch) ||
           got_seq!=seq || got_epoch!=epoch || end-p!=16)goto done;
        const char *response="5334544553549000";while(p<end && *p==*response){p++;response++;}
        if(p!=end || epoch!=s4_host_test.ipc[5] || s4_host_test.ipc[6]!=seq || !host_guard())goto done;
        u8 *m=fixed_message();if(!m)goto done;
        s4_host_test.ipc[9]++;
        ((void (*)(void *))0x2c34de2d)(m);s4_host_test.ipc[10]++;
        s4_host_test.ipc[4]=0;
    }
done:s4_host_test.ipc[3]--;
}
static void rf_receive(u8 conn,u16 event,void *data) {
    s4_host_test.h[6]++;
    s4_host_test.rf_meta[0]++;
    s4_host_test.rf_meta[2]=event;s4_host_test.rf_meta[3]=conn;
    s4_host_test.rf_meta[7]=B(DM+0x4c);
    if(event==0x6003) {
        s4_host_test.rf_meta[1]++;
        s4_host_test.rf_meta[4]=data?B(data):0xffffffff;
        if(data) {
            u8 *m=*(u8 **)((u8 *)data+4);
            if(m){s4_host_test.rf_meta[5]=*(u16 *)(m+2);s4_host_test.rf_meta[6]=*(u16 *)(m+4);}
        }
    }
    int matched=0;
    if(s4_host_test.h[1]==4 && conn==0 && event==0x6003 && data && B(data)==0 &&
       B(DM+0x4c)==5 && s4_host_test.h[24]) {
        u8 *m=*(u8 **)((u8 *)data+4);
        if(m) {
            u16 len=*(u16 *)(m+2),offset=*(u16 *)(m+4);
            if((len==12 || len==13) && offset<=1024) {
                const u8 *p=m+8+offset;matched=1;
                for(u32 i=0;i<12;++i)if(p[i]!=select_apdu[i])matched=0;
                if(len==13 && p[12])matched=0;
            }
            if(matched) {
                s4_host_test.h[9]++;
                free_buffer(m); // Original RF BT_HDR has one owner.
                if(s4_host_test.rf_meta[15])s4_host_test.rf_meta[15]=0;
                else if(!HOST_SPP_BRIDGE)send_fixed();
            }
        }
    }
    if(!matched) {
        if(event==0x6003)s4_host_test.h[11]++;
        ((void (*)(u8,u16,void *))ORIGINAL_RF)(conn,event,data);
    }
    retain_discovery();s4_host_test.h[6]--;
}
static void discovered(u8 event,void *data) {
    if(event==1 && data && B((u8 *)data+1)==4 && B((u8 *)data+2)==0x80 && B((u8 *)data+0x27)==2 && s4_host_test.h[1]==4) {
        s4_host_test.h[7]++;s4_host_test.h[24]=1;s4_host_test.rf_meta[15]=0;
        if(W(RF)==ORIGINAL_RF || W(RF)==0)set_rf((u32)rf_receive);
        else s4_host_test.h[28]++;
    } else if(event==2) {
        s4_host_test.h[8]++;s4_host_test.h[24]=0;s4_host_test.rf_meta[15]=0;
        if(W(RF)==(u32)rf_receive){set_rf(ORIGINAL_RF);s4_host_test.h[26]++;}
    }
}
static void discovery(u16 event,void *data) {
    s4_host_test.h[5]++;
    s4_host_test.h[27]++;s4_host_test.h[29]=event;
    // Only a DH ISO-DEP listen activation uses the extra Host registration.
    // Direct eSE activation and every other event see original bookkeeping.
    int host=event==0x4004 && data && B((u8 *)data+1)==4 && B((u8 *)data+2)==0x80 && B((u8 *)data+0x27)==2;
    if(event==0x4004 && data) {
        // Activation metadata only: protocol, mode and interface; no UID/APDU.
        s4_host_test.h[30]=B((u8 *)data+1) | ((u32)B((u8 *)data+2)<<8) | ((u32)B((u8 *)data+0x27)<<16);
        if(!host)s4_host_test.h[31]++;
    }
    u8 saved=RB(DM+0x11e);
    if(host && s4_host_test.h[1]==4)RB(DM+0x11e)=0;
    ((void (*)(u16,void *))ORIGINAL_DISC)(event,data);
    if(event==0x4004) {
        s4_host_test.rf_meta[8]=W(RF);
        s4_host_test.rf_meta[9]=B(NFC+0x131);
        s4_host_test.rf_meta[10]=B(NFC+0x94);
        s4_host_test.rf_meta[11]=W(NFC+0x20);
    }
    // On this firmware the original activation path can reinstall its sink
    // after calling our listener. Reattach after it returns, only for our
    // active Host ISO-DEP session. SetStaticRfCback also drains queued data.
    if(host && s4_host_test.h[1]==4 && s4_host_test.h[24] && B(DM+0x4c)==5) {
        if(W(RF)==ORIGINAL_RF || W(RF)==0)set_rf((u32)rf_receive);
        else if(W(RF)!=(u32)rf_receive)s4_host_test.h[28]++;
        s4_host_test.rf_meta[12]=W(RF);
    }
    if(host && s4_host_test.h[1]==4)RB(DM+0x11e)=saved;
    retain_discovery();
    s4_host_test.h[25]=B(DM+0x4c);
    s4_host_test.h[5]--;
}
static void arm_reply(u8 event,u16 length,const u8 *p) {
    if(!query_ok(event,length,p) || s4_host_test.h[1]!=3 || !original_entry() || !owned_entry() ||
       H(DM+0x4a)!=1 || B(DM+0x4c)!=1 || W(DISC)!=ORIGINAL_DISC || W(RF)!=ORIGINAL_RF || W(HAL_SLOT)!=ORIGINAL_HAL) {
        s4_host_test.h[2]=2;return;
    }
    RW(DISC)=(u32)discovery;__asm__ volatile("dmb" ::: "memory");s4_host_test.h[1]=4;
    s4_host_test.hal_meta[14]=(u32)hal_receive;
    s4_host_test.hal_meta[1]=1;RW(HAL_SLOT)=(u32)hal_receive;
    __asm__ volatile("dmb" ::: "memory");
}
static void remove_reply(u8 event,u16 length,const u8 *p) {
    if(!query_ok(event,length,p) || (s4_host_test.h[1]!=3 && s4_host_test.h[1]!=4) ||
       !original_entry() || !owned_entry() || H(DM+0x4a) || B(DM+0x4c) ||
       s4_host_test.h[5] || s4_host_test.h[6] || s4_host_test.hal_meta[0] || W(HAL_SLOT)!=ORIGINAL_HAL ||
       s4_host_test.ipc[3] || s4_host_test.ipc[4] ||
       (W(DISC)!=ORIGINAL_DISC && W(DISC)!=(u32)discovery) ||
       (W(RF)!=ORIGINAL_RF && W(RF)!=0 && W(RF)!=(u32)rf_receive)) {
        s4_host_test.h[2]=3;return;
    }
    if(W(DISC)==(u32)discovery)RW(DISC)=ORIGINAL_DISC;
    if(W(RF)==(u32)rf_receive)set_rf(ORIGINAL_RF);
    ((void (*)(u16))0x2c341311)(1);
    for(u32 i=20;i<40;++i)RB(ENTRY+i)=s4_host_test.before[i];
    __asm__ volatile("dmb" ::: "memory");s4_host_test.h[1]=5;
}
static void register_reply(u8 event,u16 length,const u8 *p) {
    if(!query_ok(event,length,p) || H(DM+0x4a) || B(DM+0x4c) || !original_entry() || B(ENTRY+20)) {
        s4_host_test.h[1]=11;return;
    }
    s4_host_test.h[4]=((u16 (*)(u32,u8,void *))0x2c344755)(0x40000,0,discovered);
    s4_host_test.h[1]=s4_host_test.h[4]==1?3:12;
}
__attribute__((constructor,used)) int module_initialize(void) {
    s4_host_test.h[1]=1;s4_host_test.h[3]=W(0x3c2d46d4);s4_host_test.h[19]=1;
    s4_host_test.h[13]=W(RF);s4_host_test.h[14]=W(DISC);
    s4_host_test.h[15]=(u32)discovery;s4_host_test.h[16]=(u32)rf_receive;s4_host_test.h[17]=(u32)discovered;
    s4_host_test.h[20]=(u32)arm_reply;s4_host_test.h[21]=(u32)remove_reply;s4_host_test.h[22]=(u32)register_reply;
    s4_host_test.ipc[1]=HOST_SPP_BRIDGE;s4_host_test.ipc[2]=(u32)bridge_pump;
    if(HOST_SPP_BRIDGE && (W(0x2c55d838)!=0xb590b40e || W(0x2c55d5e0)!=0xb085b530 ||
       H(0x2c55d61a)!=0xb530 || H(0x2c55d61c)!=0xb085 || W(0x2c55af94)!=0xaf00b598 ||
       W(0x2c20dc10)!=0xb084b580)){s4_host_test.h[1]=10;return 0;}
    if(W(0x2c344754)!=0x4b0db5f0 || W(0x2c341310)!=0x2801b580 ||
       W(0x2c344698)!=0xf5a0b5f8 || W(0x2c34dff0)!=0x4b04b580 ||
       W(0x2c3420d0)!=0x4b19b5b0 || H(0x2c34319e)!=0xf246 || H(0x2c3431a0)!=0x0303 ||
       W(0x2c8b459c+26*4)!=0x2c343181 || W(0x2c34de2c)!=0xb082b580 ||
       W(0x2c340d34)!=0x4b03b580 || W(0x2c34cd84)!=0x4b12b5f8) {
        s4_host_test.h[1]=10;return 0;
    }
    if(s4_host_test.h[3]!=0x8101 || H(DM+0x4a) || B(DM+0x4c) ||
       B(ENTRY)!=1 || B(ENTRY+20) || W(RF)!=ORIGINAL_RF || W(DISC)!=ORIGINAL_DISC || W(HAL_SLOT)!=ORIGINAL_HAL ||
       W(NFC+0x140) || B(NFC+0xbc) || H(NFC+0x14c) || B(NFC+0x1cf)!=1) {
        s4_host_test.h[1]=11;return 0;
    }
    for(u32 i=0;i<40;++i)s4_host_test.before[i]=B(ENTRY+i);
    u8 *m=((u8 *(*)(u16))0x2c342e85)(24);
    if(!m){s4_host_test.h[1]=12;return 0;}
    for(u32 i=0;i<24;++i)m[i]=0;
    *(u16 *)m=0x11a;*(u32 *)(m+8)=(u32)register_reply;m[13]=3;
    *(u32 *)(m+16)=(u32)(m+20);m[20]=0x21;m[21]=2;
    s4_host_test.h[1]=2;((void (*)(void *))0x2c34de2d)(m);return 0;
}
