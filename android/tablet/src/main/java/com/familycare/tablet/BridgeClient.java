package com.familycare.tablet;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class BridgeClient {
    final Pairing pairing;
    volatile String session="";
    volatile HttpURLConnection video;
    BridgeClient(Pairing p) { pairing=p; }
    HttpURLConnection open(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(pairing.base+path).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setRequestProperty("Authorization","Bearer "+pairing.token);
        return c;
    }
    JSONObject post(String path,JSONObject b) throws Exception {
        if(session.isEmpty())throw new IOException("차량 화면 연결을 기다려 주세요");
        b.put("session",session);
        HttpURLConnection c=open(path);
        try {
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            byte[] data=b.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=c.getOutputStream()){out.write(data);}
            int code=c.getResponseCode();InputStream in=code==200?c.getInputStream():c.getErrorStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(in!=null){byte[] block=new byte[1024];int count;while((count=in.read(block))!=-1){bytes.write(block,0,count);if(bytes.size()>8192)throw new IOException("응답 크기를 확인하세요");}}
            byte[] result=bytes.toByteArray();
            JSONObject reply=result.length==0?new JSONObject():new JSONObject(new String(result,StandardCharsets.UTF_8));
            if(code!=200)throw new IOException(reply.optString("error","차량 연결을 확인하세요"));
            return reply;
        } finally { c.disconnect(); }
    }
    DataInputStream stream() throws Exception {
        HttpURLConnection c=open("/video");video=c;c.setReadTimeout(0);
        int code=c.getResponseCode();
        if(code!=200) { c.disconnect();throw new IOException(code==409?"다른 탭의 연결을 먼저 종료하세요":"시연 보조 화면에서 새 QR을 스캔해 주세요"); }
        session=c.getHeaderField("X-Session");
        if(session==null||session.isEmpty())throw new IOException("차량 연결 응답을 확인하세요");
        return new DataInputStream(new BufferedInputStream(c.getInputStream(),128*1024));
    }
    void closeVideo(){HttpURLConnection c=video;if(c!=null)c.disconnect();}
}
