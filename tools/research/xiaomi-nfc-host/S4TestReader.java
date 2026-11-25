// SPDX-License-Identifier: AGPL-3.0-or-later
// Temporary app_process reader, no APK. Only the synthetic S4 test SELECT.
// Never logs tag UID or arbitrary response contents; ReaderMode is temporary.
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class S4TestReader {
    private static final String CALLBACK = "android.nfc.IAppCallback";
    private static final byte[] SELECT = {(byte)0x00,(byte)0xa4,4,0,7,
        (byte)0xf0,1,2,3,4,5,6,0};
    private static final byte[] EXPECTED = {'S','4','T','E','S','T',(byte)0x90,0};
    private static final AtomicInteger successes = new AtomicInteger();
    private static final CountDownLatch done = new CountDownLatch(1);
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static void probe(Tag tag) {
        if (tag == null) return;
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {System.out.println("{\"result\":\"not_iso_dep\",\"tag_identity_logged\":false}");return;}
        String stage="connect";
        try {
            iso.connect();iso.setTimeout(1000);
            stage="test_select";
            long before=SystemClock.elapsedRealtimeNanos();
            byte[] response=iso.transceive(SELECT);
            long elapsed=SystemClock.elapsedRealtimeNanos()-before;
            if (Arrays.equals(response,EXPECTED)) {
                int count=successes.incrementAndGet();
                System.out.println("{\"result\":\"fixed_test_response_matches\",\"response_hex\":\"5334544553549000\",\"roundtrip_ns\":"+elapsed+",\"successful_presentations\":"+count+"}");
                if(count>=2)done.countDown();
            } else {
                // Do not log arbitrary data even if another tag is presented.
                System.out.println("{\"result\":\"unexpected_test_select_response\",\"response_length\":"+response.length+"}");
            }
        } catch(Exception e) {
            System.out.println("{\"result\":\"test_select_error\",\"stage\":\""+stage+"\",\"exception_type\":\""+e.getClass().getSimpleName()+"\"}");
        } finally {try{iso.close();}catch(Exception ignored){}}
    }

    public static void main(String[] args) throws Exception {
        if(args.length==0 || !(args[0].equals("--inspect") || args[0].equals("--run")))
            throw new IllegalArgumentException("--inspect or --run <1..300 seconds>");
        Class<?> serviceManager=Class.forName("android.os.ServiceManager");
        IBinder binder=(IBinder)serviceManager.getMethod("getService",String.class).invoke(null,"nfc");
        if(binder==null)throw new IllegalStateException("NFC service absent");
        Class<?> adapterType=Class.forName("android.nfc.INfcAdapter");
        Object adapter=Class.forName("android.nfc.INfcAdapter$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Class<?> callbackType=Class.forName(CALLBACK);
        Method setReader=adapterType.getMethod("setReaderMode",IBinder.class,callbackType,int.class,Bundle.class,String.class);
        Class<?> stub=Class.forName(CALLBACK+"$Stub");
        java.lang.reflect.Field tagCode=stub.getDeclaredField("TRANSACTION_onTagDiscovered");tagCode.setAccessible(true);
        int transaction=tagCode.getInt(null);
        System.out.println("{\"mode\":\"inspection\",\"uid\":"+Process.myUid()+",\"nfc_binder_alive\":"+binder.isBinderAlive()+",\"reader_signature\":\"token,callback,flags,extras,packageName\",\"tag_callback_transaction\":"+transaction+"}");
        if(args[0].equals("--inspect")){worker.shutdown();return;}
        int seconds=Integer.parseInt(args[1]);
        if(seconds<1 || seconds>300)throw new IllegalArgumentException("bounded window required");
        Binder callbackBinder=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
                if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(CALLBACK);return true;}
                if(code==transaction){
                    data.enforceInterface(CALLBACK);Tag tag=data.readTypedObject(Tag.CREATOR);
                    worker.execute(()->probe(tag));
                    if(reply!=null)reply.writeNoException();return true;
                }
                return super.onTransact(code,data,reply,flags);
            }
        };
        Object callback=stub.getMethod("asInterface",IBinder.class).invoke(null,callbackBinder);
        Binder token=new Binder();boolean attempted=false;
        try {
            attempted=true;
            setReader.invoke(adapter,token,callback,NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK|NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,new Bundle(),"com.android.shell");
            System.out.println("{\"mode\":\"bounded_test_reader\",\"request_returned\":true,\"accepted_by_service\":\"requires_live_service_check\",\"window_seconds\":"+seconds+"}");
            done.await(seconds,TimeUnit.SECONDS);
        } finally {
            if(attempted){
                setReader.invoke(adapter,token,null,0,null,"com.android.shell");
                System.out.println("{\"reader_mode_disable_request_returned\":true,\"successful_presentations\":"+successes.get()+"}");
            }
            worker.shutdownNow();
        }
    }
}
