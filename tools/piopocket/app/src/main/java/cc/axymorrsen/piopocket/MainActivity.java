package cc.axymorrsen.piopocket;

import android.app.Activity;
import android.app.PendingIntent;
import android.os.Bundle;
import android.graphics.Color;
import android.content.*;
import android.hardware.usb.*;
import android.text.InputType;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import com.hoho.android.usbserial.driver.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private EditText owner, repo, branch, token, environment, path, source, baud, sendText;
    private TextView log;
    private volatile String loadedPath="", loadedSha="";
    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private volatile boolean reading=false;
    private static final String USB_ACTION="cc.axymorrsen.piopocket.USB_PERMISSION";
    private BroadcastReceiver permissionReceiver;
    private LinearLayout root;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f7fb);
        root.setPadding(dp(16),dp(14),dp(16),dp(26));
        scroll.addView(root);setContentView(scroll);
        title("PIO Pocket",25);
        note("ESP32 · PlatformIO 云端编译 + Android USB 串口（构建测试版）");
        title("GitHub 项目",18);
        owner=input("Owner",getPreferences(0).getString("owner",""));
        repo=input("Repository",getPreferences(0).getString("repo",""));
        branch=input("Branch",getPreferences(0).getString("branch","main"));
        environment=input("PlatformIO environment",getPreferences(0).getString("env","esp32dev"));
        token=input("Fine-grained GitHub token（不保存）","");
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("保存仓库设置",()->{
            getPreferences(0).edit()
                .putString("owner",val(owner)).putString("repo",val(repo))
                .putString("branch",val(branch)).putString("env",val(environment)).apply();
            say("已保存仓库设置；访问令牌只保留在当前进程内。");
        });
        title("PlatformIO / GitHub Actions",18);
        button("触发 pio run",()->async(()->{
            JSONObject body=new JSONObject().put("ref",val(branch))
              .put("inputs",new JSONObject().put("environment",val(environment)));
            request("POST","actions/workflows/pio-mobile.yml/dispatches",body);
            return "编译请求已提交。请稍后查询 GitHub Actions 的运行状态。";
        }));
        button("查询最新编译状态",()->async(()->{
            JSONObject j=request("GET","actions/workflows/pio-mobile.yml/runs?per_page=5",null);
            JSONArray runs=j.optJSONArray("workflow_runs");
            if(runs==null||runs.length()==0)return "该仓库没有 pio-mobile.yml 运行记录。";
            StringBuilder s=new StringBuilder();
            for(int i=0;i<runs.length();i++){
                JSONObject r=runs.getJSONObject(i);
                s.append("#").append(r.optLong("id")).append("  ")
                 .append(r.optString("status")).append("  ")
                 .append(r.optString("conclusion")).append("\n")
                 .append(r.optString("html_url")).append("\n");
            }
            return s.toString();
        }));
        title("源文件编辑",18);
        path=input("文件路径", "src/main.cpp");
        button("打开文件",()->async(()->{
            String p=val(path);
            JSONObject f=request("GET","contents/"+escapedPath(p)+"?ref="+enc(val(branch)),null);
            String decoded=new String(Base64.decode(f.getString("content"),Base64.DEFAULT),StandardCharsets.UTF_8);
            loadedPath=p;loadedSha=f.getString("sha");
            runOnUiThread(()->source.setText(decoded));
            return "已打开 "+p;
        }));
        button("打开 platformio.ini",()->{path.setText("platformio.ini"); async(()->{
            JSONObject f=request("GET","contents/platformio.ini?ref="+enc(val(branch)),null);
            String decoded=new String(Base64.decode(f.getString("content"),Base64.DEFAULT),StandardCharsets.UTF_8);
            loadedPath="platformio.ini";loadedSha=f.getString("sha");
            runOnUiThread(()->source.setText(decoded));
            return "已打开 platformio.ini";
        });});
        source=new EditText(this);source.setTextSize(13);source.setTextColor(Color.BLACK);
        source.setGravity(Gravity.TOP);source.setTypeface(android.graphics.Typeface.MONOSPACE);
        source.setMinLines(8);source.setBackgroundColor(Color.WHITE);
        source.setPadding(dp(9),dp(9),dp(9),dp(9));
        root.addView(source,new LinearLayout.LayoutParams(-1,dp(260)));
        button("保存文件到 GitHub",()->{
            final String p=val(path),src=source.getText().toString(),sha=loadedSha;
            if(!p.equals(loadedPath)||sha.isEmpty()){say("保存前必须先打开同一个文件，防止覆盖未知版本。");return;}
            async(()->{
                JSONObject body=new JSONObject().put("message","Edit "+p+" via PIO Pocket")
                  .put("content",Base64.encodeToString(src.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP))
                  .put("sha",sha).put("branch",val(branch));
                request("PUT","contents/"+escapedPath(p),body);
                JSONObject updated=request("GET","contents/"+escapedPath(p)+"?ref="+enc(val(branch)),null);
                loadedSha=updated.getString("sha");
                return "已保存 "+p;
            });
        });
        title("USB OTG 串口",18);
        baud=input("Baud", "115200");
        button("连接 USB 串口",this::connectUsb);
        button("断开 USB",this::disconnectUsb);
        sendText=input("发送文本", "");
        button("串口发送",()->async(()->{
            byte[] bytes=(val(sendText)+"\n").getBytes(StandardCharsets.UTF_8);
            synchronized(this){if(port==null)throw new IOException("尚未连接 USB 串口");port.write(bytes,3000);}
            return "串口发送完成";
        }));
        title("日志",18);
        log=new TextView(this);log.setTextSize(12);log.setTextColor(0xff162a46);log.setBackgroundColor(Color.WHITE);
        log.setTextIsSelectable(true);log.setPadding(dp(8),dp(8),dp(8),dp(8));log.setText("Ready.\n");
        root.addView(log,new LinearLayout.LayoutParams(-1,dp(260)));
        note("提示：PIO 固件工程需安装 pio-mobile.yml；USB 固件烧录功能仍需真机验证，此构建版只开放 USB 串口。");
        permissionReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent) {
                if(USB_ACTION.equals(intent.getAction()))
                    say("USB 权限操作完成。请再次点击连接 USB 串口。");
            }
        };
        ContextCompatReceiver.register(this,permissionReceiver,USB_ACTION);
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private void title(String t,int size){TextView x=new TextView(this);x.setText(t);x.setTextSize(size);x.setTypeface(null,1);x.setTextColor(0xff14335a);x.setPadding(0,dp(16),0,dp(6));root.addView(x);}
    private void note(String t){TextView x=new TextView(this);x.setText(t);x.setTextColor(0xff53657b);x.setTextSize(12);root.addView(x);}
    private EditText input(String hint,String def){EditText x=new EditText(this);x.setSingleLine(true);x.setTextSize(14);x.setHint(hint);x.setText(def);root.addView(x,new LinearLayout.LayoutParams(-1,dp(51)));return x;}
    private void button(String label,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setOnClickListener(v->action.run());root.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));}
    private String val(EditText x){return x.getText().toString().trim();}
    private void say(String t){runOnUiThread(()->{if(log==null)return;String prev=log.getText().toString();if(prev.length()>24000)prev=prev.substring(prev.length()-15000);log.setText(prev+t+"\n");});}
    private interface Work{String run()throws Exception;}
    private void async(Work w){executor.execute(()->{try{say(w.run());}catch(Exception e){say("失败: "+e.getMessage());}});}
    private static String enc(String s)throws Exception{return java.net.URLEncoder.encode(s,"UTF-8");}
    private static String escapedPath(String p)throws Exception{
        if(p.isEmpty()||p.startsWith("/")||p.contains("..")||p.contains("\\"))throw new IOException("非法文件路径");
        StringBuilder s=new StringBuilder();for(String x:p.split("/")){if(x.isEmpty())throw new IOException("非法文件路径");if(s.length()>0)s.append('/');s.append(enc(x));}return s.toString();
    }
    private JSONObject request(String method,String endpoint,JSONObject body)throws Exception{
        String own=val(owner),repository=val(repo);
        if(!own.matches("[A-Za-z0-9-]{1,39}")||!repository.matches("[A-Za-z0-9_.-]{1,100}"))throw new IOException("请先填写有效的 Owner/Repository");
        URL url=new URL("https://api.github.com/repos/"+own+"/"+repository+"/"+endpoint);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(25000);
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        String t=val(token);if(!t.isEmpty())c.setRequestProperty("Authorization","Bearer "+t);
        if(body!=null){
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream o=c.getOutputStream()){o.write(data);}
        }
        int status=c.getResponseCode();InputStream in=status<400?c.getInputStream():c.getErrorStream();
        String payload="";
        if(in!=null){try(InputStream i=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;while((n=i.read(buf))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("API 回复过大");out.write(buf,0,n);}
            payload=out.toString("UTF-8");
        }}
        c.disconnect();
        if(status<200||status>=300)throw new IOException("GitHub API HTTP "+status+": "+(payload.length()>220?payload.substring(0,220):payload));
        return payload.trim().isEmpty()?new JSONObject():new JSONObject(payload);
    }
    private void connectUsb(){async(()->{
        UsbManager um=(UsbManager)getSystemService(USB_SERVICE);
        List<UsbSerialDriver> drivers=UsbSerialProber.getDefaultProber().findAllDrivers(um);
        if(drivers.isEmpty())throw new IOException("未检测到兼容 USB 串口（需 OTG 线）");
        UsbSerialDriver d=drivers.get(0);UsbDevice device=d.getDevice();
        if(!um.hasPermission(device)){
            Intent i=new Intent(USB_ACTION).setPackage(getPackageName());
            PendingIntent p=PendingIntent.getBroadcast(this,0,i,PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            um.requestPermission(device,p);
            return "USB 访问授权已请求，请允许后再点连接";
        }
        disconnectInternal();
        UsbDeviceConnection c=um.openDevice(device);if(c==null)throw new IOException("无法打开 USB 设备");
        UsbSerialPort p=d.getPorts().get(0);
        try{p.open(c);p.setParameters(Integer.parseInt(val(baud)),8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);}
        catch(Exception e){c.close();throw e;}
        synchronized(this){port=p;connection=c;reading=true;}
        Thread thread=new Thread(()->{
            byte[] data=new byte[2048];
            while(reading){
                try{
                    int n;
                    synchronized(MainActivity.this){if(port==null)break;n=port.read(data,150);}
                    if(n>0)say(new String(data,0,n,StandardCharsets.UTF_8));
                }catch(Exception e){if(reading)say("USB: "+e.getMessage());break;}
            }
        },"pio-serial");thread.setDaemon(true);thread.start();
        return "USB 串口已连接："+device.getDeviceName();
    });}
    private void disconnectUsb(){async(()->{disconnectInternal();return "USB 已断开";});}
    private synchronized void disconnectInternal(){reading=false;if(port!=null){try{port.close();}catch(Exception ignored){}port=null;}if(connection!=null){connection.close();connection=null;}}
    @Override protected void onDestroy(){reading=false;disconnectInternal();if(permissionReceiver!=null)unregisterReceiver(permissionReceiver);executor.shutdownNow();super.onDestroy();}
    private static final class ContextCompatReceiver {
        static void register(Context ctx,BroadcastReceiver rec,String action){
            if(android.os.Build.VERSION.SDK_INT>=33)ctx.registerReceiver(rec,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
            else ctx.registerReceiver(rec,new IntentFilter(action));
        }
    }
}
