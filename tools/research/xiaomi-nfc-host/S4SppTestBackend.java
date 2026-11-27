// SPDX-License-Identifier: AGPL-3.0-or-later
// Bounded K70-only synthetic APDU backend; no APK, real SE or arbitrary payload.
// Watches only this diagnostic RPK's GB log lines and invokes allowlisted JNI
// response modes. Mac/ADB starts it but does not participate in each reply.
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class S4SppTestBackend {
    private static final String GB="nodomain.freeyourgadget.gadgetbridge";
    private static final String MARK="Wear app com.meta.s4nativeprobe message: ";
    public static void main(String[] args) throws Exception {
        if((args.length!=1 && !(args.length==2 && args[1].equals("--rf"))) || !args[0].matches("[1-9][0-9]{0,6}"))
            throw new IllegalArgumentException("current GB pid [--rf] required");
        final boolean rfWindow=args.length==2;
        Process cat=new ProcessBuilder("logcat","-T","1","--pid="+args[0],"-v","raw").start();
        Thread timeout=new Thread(()->{try{Thread.sleep(rfWindow?330000:30000);cat.destroy();}catch(InterruptedException ignored){}});
        timeout.setDaemon(true);timeout.start();
        Set<Integer> replied=new HashSet<>(),acked=new HashSet<>();
        System.out.println(new JSONObject().put("backend","K70_app_process_fixed_only").put("bounded_seconds",rfWindow?330:30).put("real_SE",false));
        try(BufferedReader in=new BufferedReader(new InputStreamReader(cat.getInputStream()))) {
            for(String line;(line=in.readLine())!=null;) {
                int at=line.indexOf(MARK);if(at<0)continue;
                String text=line.substring(at+MARK.length());if(text.length()>600)continue;
                JSONObject b;try{b=new JSONObject(text);}catch(Exception ignored){continue;}
                String nonce=b.optString("nonce");boolean rf=nonce.equals("s4-spp-rf-20261125");
                if(rf!=rfWindow)continue;
                if(!b.optString("probe").equals("s4-spp-test") ||
                   !(rf || nonce.equals("s4-spp-test-20261125")))continue;
                int seq=b.optInt("seq",0);if(seq<1 || seq>3)continue;
                int epoch=b.optInt("epoch",0);if(rf && (epoch<1 || epoch>65535))continue;
                if(b.optString("op").equals("request") &&
                   b.optString("apdu").equals("00a4040007f001020304050600") && replied.add(seq)) {
                    long t=SystemClock.elapsedRealtimeNanos();
                    String mode=rf?"spp-rf-reply-"+seq+"-"+epoch:"spp-test-reply-"+seq;
                    String library=rfWindow?"libgbwatchops-rfbridge-v5.so":"libgbwatchops-spp.so";
                    String agent="/data/user/0/"+GB+"/code_cache/"+library+"="+mode+"@C4:57:81:3B:DE:6F";
                    Process send=new ProcessBuilder("am","attach-agent",GB,agent).redirectErrorStream(true).start();
                    boolean ended=send.waitFor(3,TimeUnit.SECONDS);
                    if(!ended)send.destroyForcibly();
                    System.out.println(new JSONObject().put("backend_request_seq",seq).put("agent_exited",ended)
                        .put("agent_exit",ended?send.exitValue():-1).put("submit_ns",SystemClock.elapsedRealtimeNanos()-t));
                } else if(b.optString("op").equals("ack") && b.optBoolean("response_matches") && acked.add(seq)) {
                    System.out.println(new JSONObject().put("watch_ack_seq",seq).put("watch_rtt_ms",b.optLong("rtt_ms",-1)).put("fixed_response_matches",true));
                    if(acked.size()==(rfWindow?2:3))break;
                } else if(b.optString("op").equals("timeout")) {
                    System.out.println(new JSONObject().put("watch_timeout_seq",seq));break;
                }
            }
        } finally {cat.destroy();timeout.interrupt();}
        System.out.println(new JSONObject().put("backend_complete",true).put("requests",replied.size()).put("watch_acks",acked.size()));
    }
}
