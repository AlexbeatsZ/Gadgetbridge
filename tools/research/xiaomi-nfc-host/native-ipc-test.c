// SPDX-License-Identifier: AGPL-3.0-or-later
// S4 3.201.79 synchronous private-file ABI test, run by NSH, never NFC callbacks.
// Fixed own-probe paths and fixed synthetic text only. No NFC/SE operations.
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
#define W(a) (*(const volatile u32 *)(a))
#define H(a) (*(const volatile u16 *)(a))
__attribute__((section(".data.result"),used)) volatile u32 s4_ipc_test[16]={0x49504354};
static const char quick_path[]="/data/quickapp/files/com.meta.s4nativeprobe/quickapp-ipc-test.txt";
static const char native_path[]="/data/quickapp/files/com.meta.s4nativeprobe/native-ipc-test.txt";
static const u8 quick_text[]="S4IPC-QUICKAPP-1125";
static const u8 native_text[]="S4IPC-NATIVE-1125";
static int open_file(const char *p,int flags){return ((int (*)(const char *,int,int))0x2c55d839)(p,flags,0600);}
static int close_file(int fd){return ((int (*)(int))0x2c55af95)(fd);}
static int read_file(int fd,void *p,int n){return ((int (*)(int,void *,int))0x2c55d5e1)(fd,p,n);}
static int equal(const u8 *a,const u8 *b,u32 n){for(u32 i=0;i<n;++i)if(a[i]!=b[i])return 0;return 1;}
__attribute__((constructor,used)) int module_initialize(void) {
    s4_ipc_test[1]=1;
    if(W(0x2c55d838)!=0xb590b40e || W(0x2c55d5e0)!=0xb085b530 ||
       H(0x2c55d61a)!=0xb530 || H(0x2c55d61c)!=0xb085 ||
       W(0x2c55af94)!=0xaf00b598 || W(0x2c20dc10)!=0xb084b580) {
        s4_ipc_test[1]=10;return 0;
    }
    s4_ipc_test[14]=1;
    u8 buffer[32];int fd=open_file(quick_path,1);
    s4_ipc_test[2]=fd;if(fd<0){s4_ipc_test[15]=1;return 0;}
    int n=read_file(fd,buffer,sizeof(buffer));s4_ipc_test[3]=n;
    s4_ipc_test[12]=close_file(fd);
    s4_ipc_test[4]=n==sizeof(quick_text)-1 && equal(buffer,quick_text,sizeof(quick_text)-1);
    if(!s4_ipc_test[4]){s4_ipc_test[15]=2;return 0;}
    s4_ipc_test[1]=3;
    s4_ipc_test[5]=((int (*)(u32))0x2c20dc11)(10000);
    // 0x26 is the exact create/truncate/write flag used by this firmware's
    // original NSH cp path, NOT values from modern NuttX or another OS.
    fd=open_file(native_path,0x26);s4_ipc_test[6]=fd;
    if(fd<0){s4_ipc_test[15]=3;return 0;}
    n=((int (*)(int,const void *,int))0x2c55d61b)(fd,native_text,sizeof(native_text)-1);
    s4_ipc_test[7]=n;s4_ipc_test[8]=close_file(fd);
    if(n!=sizeof(native_text)-1){s4_ipc_test[15]=4;return 0;}
    s4_ipc_test[1]=4;fd=open_file(native_path,1);s4_ipc_test[9]=fd;
    if(fd<0){s4_ipc_test[15]=5;return 0;}
    n=read_file(fd,buffer,sizeof(buffer));s4_ipc_test[10]=n;close_file(fd);
    s4_ipc_test[11]=n==sizeof(native_text)-1 && equal(buffer,native_text,sizeof(native_text)-1);
    s4_ipc_test[1]=s4_ipc_test[11]?5:4;return 0;
}
