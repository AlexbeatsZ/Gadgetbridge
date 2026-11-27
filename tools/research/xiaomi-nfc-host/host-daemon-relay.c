// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 always-armed native direct-SPP Bluetooth APDU relay daemon.
// Preserves the 360-byte s4_host_test ABI at .data.result offset 0 while
// bypassing the foreground QuickApp and filesystem polling:
//   RF C-APDU (hal_receive) -> sem_post -> resident worker pthread ->
//   uv_async_send (same task group as g_data_proxy_loop) ->
//   quickapp_send_wearmsg (type=20 subtype=9) -> Classic SPP -> Gadgetbridge ->
//   K70 eSE1 -> Classic SPP (type=20 subtype=8) -> pb_recv_hook -> NFA 0x10c.
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

#define DATA_PROXY_TOPICS 0x3c2fe464
#define DATA_PROXY_LOOP_PTR 0x3c2fe57c
#define ORIGINAL_PB_RECV 0x2c480b21

#define SEM_INIT_ADDR 0x2c1ff7fd
#define SEM_WAIT_ADDR 0x2c1f15b9
#define SEM_POST_ADDR 0x2c1f1613
#define SEM_DESTROY_ADDR 0x2c1f1591
#define PTHREAD_CREATE_ADDR 0x2c20732d
#define UV_ASYNC_INIT_ADDR 0x2c254fa1
#define UV_ASYNC_SEND_ADDR 0x2c254429
#define UV_CLOSE_ADDR 0x2c255701
#define QUICKAPP_SEND_WEARMSG_ADDR 0x2c47e919

#define MAX_APDU_BYTES 261
#define MAX_RAPDU_BYTES 260
#define SELFTEST_SEQ 999901u
#define SELFTEST_EPOCH 9999u

__attribute__((section(".data.result"),used)) volatile struct {
    u32 h[32];u8 before[40];u32 rf_meta[16];u32 hal_meta[16];u32 ipc[16];
} s4_host_test={{0x53483454},{0},{0},{0},{0x53505049}};

static const char PKG_NAME[]="com.meta.s4nativeprobe";
static u8 capdu_buf[MAX_APDU_BYTES];
static volatile u16 capdu_len;
static volatile u16 capdu_seg_len;
static u8 rapdu_buf[MAX_RAPDU_BYTES];
static char ipc_text[640];
static volatile u32 hal_host_active;
static volatile u32 last_act_packed;
__attribute__((used)) static volatile u32 route_power_state=0xdeadc0df;

static u32 req_sem[8];
static u32 relay_async[32];
static volatile u32 orig_sub_node;
static volatile u32 uv_inited;
static volatile u32 uv_closing;
static volatile u32 uv_closed;
static volatile u32 daemon_stopping;
static volatile u32 worker_running;
static volatile u32 worker_exited;
static volatile u32 selftest_pending;

static void rf_receive(u8 conn,u16 event,void *data);
static void discovered(u8 event,void *data);
static void arm_reply(u8 event,u16 length,const u8 *packet);
static void remove_reply(u8 event,u16 length,const u8 *packet);
static void pb_recv_hook(void *ev,void *user);
static void relay_async_cb(void *handle);
static int original_entry(void);
static int owned_entry(void);

static int sem_init_fn(void *s,int p,u32 v){return ((int (*)(void *,int,u32))SEM_INIT_ADDR)(s,p,v);}
static int sem_wait_fn(void *s){return ((int (*)(void *))SEM_WAIT_ADDR)(s);}
static int sem_post_fn(void *s){return ((int (*)(void *))SEM_POST_ADDR)(s);}
static int pthread_create_fn(u32 *tid,void *attr,void *(*fn)(void *),void *arg){
    return ((int (*)(u32 *,void *,void *(*)(void *),void *))PTHREAD_CREATE_ADDR)(tid,attr,fn,arg);
}
static int uv_async_init_fn(void *loop,void *h,void (*cb)(void *)){
    return ((int (*)(void *,void *,void (*)(void *)))UV_ASYNC_INIT_ADDR)(loop,h,cb);
}
static int uv_async_send_fn(void *h){return ((int (*)(void *))UV_ASYNC_SEND_ADDR)(h);}
static void uv_close_fn(void *h,void (*cb)(void *)){((void (*)(void *,void (*)(void *)))UV_CLOSE_ADDR)(h,cb);}
static void quickapp_send_wearmsg_fn(const char *pkg,const void *buf,u16 len){
    ((void (*)(const char *,const void *,u16))QUICKAPP_SEND_WEARMSG_ADDR)(pkg,buf,len);
}

static int is_ram_ptr(u32 addr){return addr>=0x3c200000 && addr<0x3e000000 && !(addr&3);}

static u32 find_pb_sub_node(void) {
    const u32 head=DATA_PROXY_TOPICS;
    u32 node=W(head+4);
    for(u32 i=0;i<256 && node!=head;++i) {
        if(!is_ram_ptr(node))return 0;
        if(B(node+8)==0 && B(node+0xc)==0 && B(node+0xd)==0x14 && B(node+0xe)==8) {
            const u32 shead=node+0x28;
            u32 sub=W(node+0x2c);
            for(u32 j=0;j<16 && sub!=shead;++j) {
                if(!is_ram_ptr(sub))break;
                if(W(sub+8)==ORIGINAL_PB_RECV)return sub;
                sub=W(sub+4);
            }
        }
        node=W(node+4);
    }
    return 0;
}

static void bridge_invalidate(void) {
    s4_host_test.ipc[5]++;
    s4_host_test.ipc[4]=0;
    capdu_seg_len=0;
    __asm__ volatile("dmb" ::: "memory");
}

static int hal_capture_guard(void) {
    const u16 handle=H(DM+0x52);
    const int host=(s4_host_test.h[24] && handle==1) || hal_host_active;
    return s4_host_test.h[1]==4 && host && original_entry() && owned_entry();
}

static int host_guard(void) {
    const u16 handle=H(DM+0x52);
    const int host=(s4_host_test.h[24] && handle==1) || (hal_host_active && handle==0xffff);
    return s4_host_test.h[1]==4 && host && B(DM+0x4c)==5 && original_entry() && owned_entry();
}

static void queue_hal_relay_request(void) {
    const u16 handle=H(DM+0x52);
    s4_host_test.rf_meta[13]=(s4_host_test.h[1]&15) | ((u32)B(DM+0x4c)<<4) |
        (s4_host_test.h[24]<<8) | (hal_host_active<<9) | ((u32)handle<<16);
    if(!hal_capture_guard())return;
    if(s4_host_test.ipc[4]){s4_host_test.ipc[13]++;return;}
    s4_host_test.ipc[6]++;
    s4_host_test.ipc[7]=s4_host_test.ipc[5];
    s4_host_test.ipc[11]=0;
    s4_host_test.rf_meta[15]++;
    __asm__ volatile("dmb" ::: "memory");
    s4_host_test.ipc[4]=1;
    __asm__ volatile("dmb" ::: "memory");
    if(uv_inited && !daemon_stopping) {
        sem_post_fn(req_sem);
    }
}

static void hal_receive(u16 length,u8 *packet) {
    int relay_ready=0;
    s4_host_test.hal_meta[0]++;s4_host_test.hal_meta[2]++;
    if(packet && length>=3) {
        const u32 mt=packet[0]&0xe0;
        s4_host_test.hal_meta[6]=mt;s4_host_test.hal_meta[7]=packet[1];
        if(mt==0) {
            s4_host_test.hal_meta[3]++;s4_host_test.hal_meta[5]=length;
            s4_host_test.hal_meta[12]=W(RF);s4_host_test.hal_meta[13]=packet[0]&15;
            const u8 conn=packet[0]&0x0f;
            const u8 pbf=(packet[0]>>4)&1;
            const u8 plen=packet[2];
            if(conn==0 && packet[1]==0 && plen==length-3 && hal_capture_guard()) {
                if(s4_host_test.ipc[4]) {
                    s4_host_test.ipc[13]++;
                } else if((u32)capdu_seg_len+plen<=MAX_APDU_BYTES) {
                    for(u32 i=0;i<plen;++i)capdu_buf[capdu_seg_len+i]=packet[3+i];
                    capdu_seg_len=(u16)(capdu_seg_len+plen);
                    if(!pbf) {
                        if(capdu_seg_len>=4) {
                            capdu_len=capdu_seg_len;
                            capdu_seg_len=0;
                            s4_host_test.hal_meta[4]++;
                            relay_ready=1;
                        } else {
                            capdu_seg_len=0;
                        }
                    }
                } else {
                    capdu_seg_len=0;
                    s4_host_test.ipc[13]++;
                }
            }
        } else if(packet[0]==0x61 && packet[1]==5) {
            s4_host_test.hal_meta[8]++;
            bridge_invalidate();
            if(length>=7) {
                last_act_packed=((u32)packet[4]<<16)|((u32)packet[5]<<8)|packet[6];
            }
            hal_host_active=length>=7 && packet[2]==length-3 &&
                packet[4]==2 && packet[5]==4 && packet[6]==0x80;
            s4_host_test.rf_meta[15]=0;
        } else if(packet[0]==0x61 && packet[1]==6) {
            s4_host_test.hal_meta[9]++;hal_host_active=0;bridge_invalidate();
        } else if(packet[0]==0x61 && packet[1]==7) {
            s4_host_test.hal_meta[10]++;
            if(length>=4)s4_host_test.hal_meta[11]=packet[3];
        } else {
            s4_host_test.hal_meta[15]++;
        }
        if(packet[0]==0x41 && (packet[1]==2 || packet[1]==6) && W(HAL_SLOT)==(u32)hal_receive) {
            hal_host_active=0;
            bridge_invalidate();
            RW(HAL_SLOT)=ORIGINAL_HAL;__asm__ volatile("dmb" ::: "memory");
            s4_host_test.hal_meta[1]=packet[1]==2?2:3;
        }
    }
    if(relay_ready)queue_hal_relay_request();
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
    if(s4_host_test.h[1]==4 && W(DISC)==ORIGINAL_DISC && original_entry() && owned_entry()) {
        RW(DISC)=s4_host_test.h[15];__asm__ volatile("dmb" ::: "memory");
    }
}

static void set_rf(u32 target){((void (*)(void *))0x2c34dff1)((void *)target);}
static void free_buffer(void *p){((void (*)(void *))0x2c34311d)(p);}

static u8 *relay_message(const u8 *data,u16 len) {
    u8 *m=((u8 *(*)(u16))0x2c342e85)((u16)(12+len));
    if(!m){s4_host_test.h[12]++;return (u8 *)0;}
    for(u32 i=0;i<12;++i)m[i]=0;
    *(u16 *)m=0x10c;*(u16 *)(m+2)=len;*(u16 *)(m+4)=4;
    for(u32 i=0;i<len;++i)m[12+i]=data[i];
    return m;
}

static char *put_text(char *p,const char *s){while(*s)*p++=*s++;return p;}
static char *put_number(char *p,u32 value) {
    char rev[10];u32 n=0;do{rev[n++]=(char)('0'+value%10);value/=10;}while(value);
    while(n)*p++=rev[--n];return p;
}
static char *put_hex(char *p,const u8 *data,u16 len) {
    static const char hex[]="0123456789abcdef";
    for(u16 i=0;i<len;++i){*p++=hex[(data[i]>>4)&15];*p++=hex[data[i]&15];}
    return p;
}
static int hex_nibble(char c) {
    if(c>='0' && c<='9')return c-'0';
    if(c>='a' && c<='f')return c-'a'+10;
    if(c>='A' && c<='F')return c-'A'+10;
    return -1;
}
static int read_number(const char **p,const char *end,u32 *v) {
    u32 n=0,count=0;while(*p<end && **p>='0' && **p<='9') {
        if(++count>8)return 0;n=n*10+(u32)(*(*p)++-'0');
    }
    if(!count || *p>=end || *(*p)++!=':')return 0;*v=n;return 1;
}
static int str_eq(const char *a,const char *b) {
    if(!a || !b)return 0;
    while(*a && *b){if(*a!=*b)return 0;a++;b++;}
    return *a==0 && *b==0;
}
static int has_prefix(const char *data,u16 len,const char *prefix) {
    u16 i=0;while(prefix[i]){if(i>=len || data[i]!=prefix[i])return 0;i++;}
    return 1;
}

static void async_closed_cb(void *handle) {
    (void)handle;
    uv_closed=1;
    __asm__ volatile("dmb" ::: "memory");
}

static void *relay_worker(void *arg) {
    (void)arg;
    worker_running=1;
    __asm__ volatile("dmb" ::: "memory");
    while(!daemon_stopping) {
        if(sem_wait_fn(req_sem)!=0)continue;
        if(daemon_stopping)break;
        if(uv_inited && !uv_closing) {
            uv_async_send_fn(relay_async);
        }
    }
    worker_running=0;
    worker_exited=1;
    __asm__ volatile("dmb" ::: "memory");
    return (void *)0;
}

static void daemon_init_on_loop(void) {
    if(uv_inited || daemon_stopping)return;
    const u32 loop=W(DATA_PROXY_LOOP_PTR);
    if(!is_ram_ptr(loop))return;
    for(u32 i=0;i<8;++i)req_sem[i]=0;
    for(u32 i=0;i<32;++i)relay_async[i]=0;
    if(sem_init_fn(req_sem,0,0)!=0)return;
    if(uv_async_init_fn((void *)loop,relay_async,relay_async_cb)!=0)return;
    u32 tid=0;
    if(pthread_create_fn(&tid,(void *)0,relay_worker,(void *)0)!=0) {
        uv_closing=1;
        uv_close_fn(relay_async,async_closed_cb);
        return;
    }
    uv_inited=1;
    __asm__ volatile("dmb" ::: "memory");
}

static void send_ctrl_reply(const char *tag,u32 a,u32 b,u32 c,u32 d) {
    char *p=ipc_text;
    p=put_text(p,tag);
    p=put_number(p,a);*p++=':';
    p=put_number(p,b);*p++=':';
    p=put_number(p,c);*p++=':';
    p=put_number(p,d);
    quickapp_send_wearmsg_fn(PKG_NAME,ipc_text,(u16)(p-ipc_text));
}

static void relay_async_cb(void *handle) {
    (void)handle;
    if(daemon_stopping)return;
    s4_host_test.ipc[3]++;
    if(s4_host_test.ipc[4]==1) {
        const u32 seq=s4_host_test.ipc[6],epoch=s4_host_test.ipc[7];
        const u16 len=capdu_len;
        if(epoch!=s4_host_test.ipc[5] || !hal_capture_guard() || len<4 || len>MAX_APDU_BYTES) {
            s4_host_test.ipc[4]=0;
        } else {
            char *p=ipc_text;
            p=put_text(p,"S4Q1:");
            p=put_number(p,seq);*p++=':';
            p=put_number(p,epoch);*p++=':';
            p=put_hex(p,capdu_buf,len);
            if(epoch==s4_host_test.ipc[5] && s4_host_test.ipc[6]==seq) {
                s4_host_test.ipc[4]=2;
                s4_host_test.ipc[8]++;
                __asm__ volatile("dmb" ::: "memory");
                quickapp_send_wearmsg_fn(PKG_NAME,ipc_text,(u16)(p-ipc_text));
            }
        }
    } else if(selftest_pending) {
        selftest_pending=0;
        static const char q[]="S4Q1:999901:9999:00a4040008a00000063201010500";
        quickapp_send_wearmsg_fn(PKG_NAME,q,(u16)(sizeof(q)-1));
    }
    s4_host_test.ipc[3]--;
}

static void pb_recv_hook(void *ev,void *user) {
    const u8 *e=(const u8 *)ev;
    if(e && e[0]==0) {
        const u8 *msg=*(const u8 *const *)(e+0x18);
        if(msg && msg[0]==0x14 && msg[1]==8) {
            const char *pkg=*(const char *const *)(msg+0x10);
            const u8 *pb_bytes=*(const u8 *const *)(msg+0x18);
            if(pkg && pb_bytes && str_eq(pkg,PKG_NAME)) {
                const u16 len=*(const u16 *)pb_bytes;
                const char *data=(const char *)(pb_bytes+2);
                if(has_prefix(data,len,"S4CTL:")) {
                    if(has_prefix(data,len,"S4CTL:PING")) {
                        daemon_init_on_loop();
                        send_ctrl_reply("S4CTL:PONG:",s4_host_test.h[1],uv_inited,
                            W(HAL_SLOT)==(u32)hal_receive?1u:0u,s4_host_test.ipc[10]);
                        return;
                    }
                    if(has_prefix(data,len,"S4CTL:DIAG")) {
                        const u32 pwr=(route_power_state&0xffffff00u)?0u:route_power_state;
                        const u32 counts=((s4_host_test.hal_meta[4]&0xffffu)<<16)|(s4_host_test.ipc[10]&0xffffu);
                        send_ctrl_reply("S4CTL:DIAG:",pwr,s4_host_test.hal_meta[8],
                            last_act_packed,counts);
                        return;
                    }
                    if(has_prefix(data,len,"S4CTL:SELFTEST")) {
                        daemon_init_on_loop();
                        if(uv_inited && !daemon_stopping) {
                            selftest_pending=1;
                            __asm__ volatile("dmb" ::: "memory");
                            sem_post_fn(req_sem);
                        } else {
                            send_ctrl_reply("S4CTL:SELFTEST_ERR:",s4_host_test.h[1],uv_inited,0,0);
                        }
                        return;
                    }
                    if(has_prefix(data,len,"S4CTL:STOP")) {
                        daemon_stopping=1;
                        s4_host_test.ipc[1]=0;
                        __asm__ volatile("dmb" ::: "memory");
                        if(uv_inited)sem_post_fn(req_sem);
                        if(uv_inited && !uv_closing) {
                            uv_closing=1;
                            uv_close_fn(relay_async,async_closed_cb);
                        }
                        if(orig_sub_node && W(orig_sub_node+8)==(u32)pb_recv_hook) {
                            RW(orig_sub_node+8)=ORIGINAL_PB_RECV;
                            __asm__ volatile("dmb" ::: "memory");
                        }
                        send_ctrl_reply("S4CTL:STOPPED:",s4_host_test.h[1],worker_exited,uv_closed,0);
                        return;
                    }
                }
                if(has_prefix(data,len,"S4R1:")) {
                    const char *p=data+5,*end=data+len;
                    u32 got_seq=0,got_epoch=0;
                    if(read_number(&p,end,&got_seq) && read_number(&p,end,&got_epoch)) {
                        const int hex_len=(int)(end-p);
                        if(hex_len>=4 && hex_len<=MAX_RAPDU_BYTES*2 && !(hex_len&1)) {
                            const u16 out_len=(u16)(hex_len/2);
                            int ok=1;
                            for(u16 i=0;i<out_len;++i) {
                                int hi=hex_nibble(p[i*2]),lo=hex_nibble(p[i*2+1]);
                                if(hi<0 || lo<0){ok=0;break;}
                                rapdu_buf[i]=(u8)((hi<<4)|lo);
                            }
                            if(ok) {
                                if(got_seq==SELFTEST_SEQ && got_epoch==SELFTEST_EPOCH) {
                                    u32 sw=((u32)rapdu_buf[out_len-2]<<8)|rapdu_buf[out_len-1];
                                    send_ctrl_reply("S4CTL:SELFTEST_OK:",out_len,sw,
                                        s4_host_test.h[1],uv_inited);
                                } else if(s4_host_test.ipc[4]==2 &&
                                          got_seq==s4_host_test.ipc[6] &&
                                          got_epoch==s4_host_test.ipc[7] &&
                                          got_epoch==s4_host_test.ipc[5] &&
                                          host_guard()) {
                                    u8 *m=relay_message(rapdu_buf,out_len);
                                    if(m) {
                                        s4_host_test.ipc[9]++;
                                        ((void (*)(void *))0x2c34de2d)(m);
                                        s4_host_test.ipc[10]++;
                                        s4_host_test.ipc[4]=0;
                                    }
                                }
                            }
                        }
                    }
                    return;
                }
            }
        }
    }
    ((void (*)(void *,void *))ORIGINAL_PB_RECV)(ev,user);
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
    int suppressed=0;
    if(s4_host_test.h[1]==4 && conn==0 && event==0x6003 && data && B(data)==0 &&
       B(DM+0x4c)==5 && s4_host_test.h[24] && s4_host_test.rf_meta[15]) {
        u8 *m=*(u8 **)((u8 *)data+4);
        if(m)free_buffer(m);
        s4_host_test.rf_meta[15]--;
        s4_host_test.h[9]++;
        suppressed=1;
    }
    if(!suppressed) {
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
    int host=event==0x4004 && data && B((u8 *)data+1)==4 && B((u8 *)data+2)==0x80 && B((u8 *)data+0x27)==2;
    if(event==0x4004 && data) {
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
       H(DM+0x4a)!=1 || B(DM+0x4c)!=1 || W(DISC)!=ORIGINAL_DISC || W(RF)!=ORIGINAL_RF || W(HAL_SLOT)!=ORIGINAL_HAL ||
       !orig_sub_node || W(orig_sub_node+8)!=ORIGINAL_PB_RECV) {
        s4_host_test.h[2]=2;return;
    }
    RW(DISC)=(u32)discovery;__asm__ volatile("dmb" ::: "memory");s4_host_test.h[1]=4;
    s4_host_test.hal_meta[14]=(u32)hal_receive;
    s4_host_test.hal_meta[1]=1;RW(HAL_SLOT)=(u32)hal_receive;
    RW(orig_sub_node+8)=(u32)pb_recv_hook;
    __asm__ volatile("dmb" ::: "memory");
}

static void remove_reply(u8 event,u16 length,const u8 *p) {
    if(!query_ok(event,length,p) || (s4_host_test.h[1]!=3 && s4_host_test.h[1]!=4) ||
       !original_entry() || !owned_entry() || H(DM+0x4a) || B(DM+0x4c) ||
       s4_host_test.h[5] || s4_host_test.h[6] || s4_host_test.hal_meta[0] || W(HAL_SLOT)!=ORIGINAL_HAL ||
       s4_host_test.ipc[3] || s4_host_test.ipc[4] || worker_running ||
       (uv_inited && !uv_closed) ||
       (orig_sub_node && W(orig_sub_node+8)!=ORIGINAL_PB_RECV) ||
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
    s4_host_test.ipc[1]=3;s4_host_test.ipc[2]=(u32)pb_recv_hook;
    if(W(0x2c1ff7fc)!=0x4611b598 || W(0x2c1f15b8)!=0xaf00b598 ||
       H(0x2c1f1612)!=0xb598 || H(0x2c1f1614)!=0xaf00 || W(0x2c1f1590)!=0xaf00b598 ||
       W(0x2c20732c)!=0x4ff0e92d || W(0x2c254fa0)!=0x41f0e92d ||
       W(0x2c254428)!=0xf100b5f8 || W(0x2c255700)!=0x43f8e92d ||
       W(0x2c47e918)!=0x41f0e92d || W(0x2c480b20)!=0x41f0e92d) {
        s4_host_test.h[1]=10;return 0;
    }
    if(W(0x2c344754)!=0x4b0db5f0 || W(0x2c341310)!=0x2801b580 ||
       W(0x2c344698)!=0xf5a0b5f8 || W(0x2c34dff0)!=0x4b04b580 ||
       W(0x2c3420d0)!=0x4b19b5b0 || H(0x2c34319e)!=0xf246 || H(0x2c3431a0)!=0x0303 ||
       W(0x2c8b459c+26*4)!=0x2c343181 || W(0x2c34de2c)!=0xb082b580 ||
       W(0x2c340d34)!=0x4b03b580 || W(0x2c34cd84)!=0x4b12b5f8) {
        s4_host_test.h[1]=10;return 0;
    }
    if(!is_ram_ptr(W(DATA_PROXY_LOOP_PTR))) {
        s4_host_test.h[1]=13;return 0;
    }
    orig_sub_node=find_pb_sub_node();
    s4_host_test.ipc[15]=orig_sub_node;
    if(!orig_sub_node) {
        s4_host_test.h[1]=14;return 0;
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
