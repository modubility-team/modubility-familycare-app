package com.familycare.tablet;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/** scrcpy 3.3.4 packet framing; frames render immediately without playback buffering. */
public final class VideoDecoder {
    interface SizeListener { void size(int width,int height); }
    volatile boolean stopped;
    volatile Throwable outputFailure;
    public final AtomicInteger frames=new AtomicInteger();
    void stop(){stopped=true;}
    void play(DataInputStream in, Surface surface, SizeListener listener) throws Exception {
        int codec=in.readInt(),width=in.readInt(),height=in.readInt();
        if(codec!=0x68323634||width<1||height<1||width>4096||height>4096)throw new IOException("영상 형식을 확인하세요");
        listener.size(width,height);
        MediaCodec decoder=MediaCodec.createDecoderByType("video/avc");Thread output=null;
        try {
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4_000_000);
            decoder.configure(format,surface,null,0);decoder.start();
            output=new Thread(()->{
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                try { while(!stopped) {
                    int index=decoder.dequeueOutputBuffer(info,10000);
                    if(index>=0){decoder.releaseOutputBuffer(index,true);frames.incrementAndGet();}
                }}catch(Exception e){if(!stopped)outputFailure=e;}
            },"tablet-video-output");output.start();
            while(!stopped && outputFailure==null) {
                long meta=in.readLong();int size=in.readInt();
                if(size<1||size>4_000_000)throw new IOException("잘못된 영상 크기");
                byte[] packet=new byte[size];in.readFully(packet);
                int index=-1;
                while(!stopped && outputFailure==null && (index=decoder.dequeueInputBuffer(10000))<0){}
                if(stopped||outputFailure!=null)break;
                ByteBuffer buf=decoder.getInputBuffer(index);
                if(buf==null||buf.capacity()<size)throw new IOException("기기 영상 버퍼가 부족해요");
                buf.clear();buf.put(packet);
                int flags=(meta&Long.MIN_VALUE)!=0?MediaCodec.BUFFER_FLAG_CODEC_CONFIG:0;
                if((meta&(1L<<62))!=0)flags|=MediaCodec.BUFFER_FLAG_KEY_FRAME;
                decoder.queueInputBuffer(index,0,size,meta&0x3fffffffffffffffL,flags);
            }
            if(outputFailure!=null)throw new IOException("영상을 다시 연결해 주세요",outputFailure);
        }finally {
            stopped=true;if(output!=null)output.join(2000);
            try{decoder.stop();}catch(Exception ignored){}decoder.release();
        }
    }
}
