package com.familycare.tablet;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Decode the real notebook stream on Android without replacing the vehicle foreground. */
public class DecoderIntegrationTest {
 @Test public void realVehicleStreamDecodesWithAndroidHardwarePipeline() throws Exception {
  android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  Path input=context.getFileStreamPath("tablet-test-link.txt").toPath();
  String link=new String(Files.readAllBytes(input),java.nio.charset.StandardCharsets.UTF_8).trim();Files.delete(input);
  BridgeClient c=new BridgeClient(Pairing.parse(link));VideoDecoder decoder=new VideoDecoder();
  HandlerThread output=new HandlerThread("decode-test");output.start();
  ImageReader images=ImageReader.newInstance(1600,904,ImageFormat.YUV_420_888,4);
  java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
  images.setOnImageAvailableListener(reader->{try(android.media.Image image=reader.acquireLatestImage()){if(image!=null)received.incrementAndGet();}},new Handler(output.getLooper()));
  ExecutorService worker=Executors.newSingleThreadExecutor();
  Future<?> task=worker.submit(()->{try(java.io.DataInputStream stream=c.stream()){
   decoder.play(stream,images.getSurface(),(w,h)->{});
  }catch(Exception e){if(!decoder.stopped)throw new RuntimeException(e);}});
  try {
   long end=SystemClock.elapsedRealtime()+20000;
   while(SystemClock.elapsedRealtime()<end&&received.get()<3){
    if(!c.session.isEmpty())c.post("/heartbeat",new JSONObject());
    if(task.isDone())task.get();Thread.sleep(300);
   }
   assertTrue("No rendered Android video frames",received.get()>=3);
   Files.write(context.getFileStreamPath("tablet-test-result.json").toPath(),new JSONObject().put("decodedFrames",decoder.frames.get()).put("renderedFrames",received.get()).put("ok",true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }finally{
   decoder.stop();if(!c.session.isEmpty())try{c.post("/disconnect",new JSONObject());}catch(Exception ignored){}
   c.closeVideo();try{task.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}
   worker.shutdownNow();images.close();output.quitSafely();
  }
 }
}
