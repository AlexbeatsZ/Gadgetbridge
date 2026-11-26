// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 Bluetooth APDU relay host module.
// Preserves the 360-byte s4_host_test ABI at .data.result offset 0 while
// forwarding arbitrary ISO-DEP C-APDUs / R-APDUs through the companion mailbox.
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
#define MAX_APDU_BYTES 261
#define MAX_RAPDU_BYTES 260

__attribute__((section(".data.result"),used)) volatile struct {
    u32 h[32];u8 before[40];u32 rf_meta[16];u32 hal_meta[16];u32 ipc[16];
} s4_host_test={{0x53483454},{0},{0},{0},{0x53505049}};

static u8 capdu_buf[MAX_APDU_BYTES];
static volatile u16 capdu_len;
static volatile u16 capdu_seg_len;
static u8 rapdu_buf[MAX_RAPDU_BYTES];
static char ipc_text[640];
static volatile u32 hal_host_active;

static void rf_receive(u8 conn,u16 event,void *data);
static void discovered(u8 event,void *data);
static void arm_reply(u8 event,u16 length,const u8 *packet);
static void remove_reply(u8 event,u16 length,const u8 *packet);
static int original_entry(void);
static int owned_entry(void);
static void bridge_pump(void);

static void bridge_invalidate(void) {
    s4_host_test.ipc[5]++;
    s4_host_test.ipc[4]=0;
    capdu_seg_len=0;
    __asm__ volatile("dmb" ::: "memory");
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
    if(!host_guard())return;
    if(s4_host_test.ipc[4]){s4_host_test.ipc[13]++;return;}
    s4_host_test.ipc[6]++;
    s4_host_test.ipc[7]=s4_host_test.ipc[5];
    s4_host_test.ipc[11]=0;
    s4_host_test.rf_meta[15]++;
    __asm__ volatile("dmb" ::: "memory");
    s4_host_test.ipc[4]=1;
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
            if(conn==0 && packet[1]==0 && plen==length-3 && host_guard()) {
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

static void set_rf(u32 target) {((void (*)(void *))0x2c34dff1)((void *)target);}
static void free_buffer(void *p) {((void (*)(void *))0x2c34311d)(p);}

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

static int ipc_open(const char *path,int flags){return ((int (*)(const char *,int,int))0x2c55d839)(path,flags,0600);}
static void ipc_close(int fd){((int (*)(int))0x2c55af95)(fd);}

static void bridge_pump(void) {
    s4_host_test.ipc[3]++;
    const u32 seq=s4_host_test.ipc[6],epoch=s4_host_test.ipc[7],stage=s4_host_test.ipc[4];
    if(!stage)goto done;
    if(epoch!=s4_host_test.ipc[5] || !host_guard()){s4_host_test.ipc[4]=0;goto done;}
    if(stage==1) {
        const u16 len=capdu_len;
        if(len<4 || len>MAX_APDU_BYTES){s4_host_test.ipc[4]=0;goto done;}
        char *p=ipc_text;
        p=put_text(p,"{\"relay\":\"s4\",\"op\":\"apdu\",\"seq\":");
        p=put_number(p,seq);p=put_text(p,",\"epoch\":");p=put_number(p,epoch);
        p=put_text(p,",\"apdu\":\"");p=put_hex(p,capdu_buf,len);p=put_text(p,"\"}");
        int fd=ipc_open("/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-request.txt",0x26);
        if(fd<0){s4_host_test.ipc[12]++;goto done;}
        int n=((int (*)(int,const void *,int))0x2c55d61b)(fd,ipc_text,(int)(p-ipc_text));ipc_close(fd);
        if(n!=p-ipc_text){s4_host_test.ipc[12]++;goto done;}
        s4_host_test.ipc[8]++;
        if(epoch==s4_host_test.ipc[5] && s4_host_test.ipc[6]==seq)s4_host_test.ipc[4]=2;
    } else if(stage==2) {
        if(++s4_host_test.ipc[11]>150){s4_host_test.ipc[14]++;s4_host_test.ipc[4]=0;goto done;}
        int fd=ipc_open("/data/quickapp/files/com.meta.s4nativeprobe/rf-spp-response.txt",1);
        if(fd<0)goto done;
        int n=((int (*)(int,void *,int))0x2c55d5e1)(fd,ipc_text,(int)sizeof(ipc_text));ipc_close(fd);
        if(n<10 || n>=(int)sizeof(ipc_text))goto done;
        const char *p=ipc_text,*end=ipc_text+n;u32 got_seq=0,got_epoch=0;
        const char *prefix="S4R1:";while(*prefix && p<end && *p==*prefix){p++;prefix++;}
        if(*prefix || !read_number(&p,end,&got_seq) || !read_number(&p,end,&got_epoch) ||
           got_seq!=seq || got_epoch!=epoch)goto done;
        const int hex_len=(int)(end-p);
        if(hex_len<4 || hex_len>MAX_RAPDU_BYTES*2 || (hex_len&1))goto done;
        const u16 out_len=(u16)(hex_len/2);
        for(u16 i=0;i<out_len;++i) {
            int hi=hex_nibble(p[i*2]),lo=hex_nibble(p[i*2+1]);
            if(hi<0 || lo<0)goto done;
            rapdu_buf[i]=(u8)((hi<<4)|lo);
        }
        if(epoch!=s4_host_test.ipc[5] || s4_host_test.ipc[6]!=seq || !host_guard())goto done;
        u8 *m=relay_message(rapdu_buf,out_len);if(!m)goto done;
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
    s4_host_test.ipc[1]=1;s4_host_test.ipc[2]=(u32)bridge_pump;
    if(W(0x2c55d838)!=0xb590b40e || W(0x2c55d5e0)!=0xb085b530 ||
       H(0x2c55d61a)!=0xb530 || H(0x2c55d61c)!=0xb085 || W(0x2c55af94)!=0xaf00b598 ||
       W(0x2c20dc10)!=0xb084b580){s4_host_test.h[1]=10;return 0;}
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
