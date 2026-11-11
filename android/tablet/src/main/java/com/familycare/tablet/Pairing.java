package com.familycare.tablet;

import java.net.URI;

/** LAN-only credentials remain in memory, never logs or preferences. */
public final class Pairing {
    public final String base, token;
    private Pairing(String base, String token) { this.base=base; this.token=token; }
    public static Pairing parse(String value) {
        try {
            URI u=new URI(value.trim());
            String host=u.getHost();
            if (!"http".equals(u.getScheme()) || u.getUserInfo()!=null || u.getQuery()!=null ||
                !"/join".equals(u.getPath()) || u.getPort()!=8771 || !privateHost(host) ||
                u.getFragment()==null || !u.getFragment().matches("[A-Za-z0-9_-]{43}")) throw new Exception();
            return new Pairing("http://"+host+":8771",u.getFragment());
        } catch (Exception e) { throw new IllegalArgumentException("시연 보조 화면의 새 탭 연결 링크를 붙여 넣어 주세요"); }
    }
    static boolean privateHost(String host) {
        if(host==null || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] p=host.split("\\."); int[] a=new int[4];
        for(int i=0;i<4;i++) { if(p[i].length()>1&&p[i].startsWith("0"))return false; a[i]=Integer.parseInt(p[i]); if(a[i]>255)return false; }
        return a[0]==10 || a[0]==192&&a[1]==168 || a[0]==172&&a[1]>=16&&a[1]<=31 || a[0]==127;
    }
    public static int[] point(float x,float y,int vw,int vh,int width,int height) {
        if(vw<=0||vh<=0||width<=0||height<=0 || x<0||y<0||x>=vw||y>=vh) return null;
        return new int[]{Math.min(width-1,(int)(x*width/vw)),Math.min(height-1,(int)(y*height/vh))};
    }
}
