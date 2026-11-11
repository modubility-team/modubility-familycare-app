package com.familycare.tablet;

/**
 * Which tablet location fix to forward. GPS needs open sky, so indoors the tablet also listens to
 * Wi-Fi/cell (network) and fused locations, the way map apps do. The laptop bridge applies the same limits.
 */
final class GpsChoice {
    static final float MAX_ACCURACY_M=50f;
    static final long MAX_AGE_MS=3000;
    private GpsChoice(){}
    static boolean usable(boolean hasAccuracy,float accuracyM,long ageMs){return hasAccuracy&&accuracyM>0&&accuracyM<=MAX_ACCURACY_M&&ageMs>=0&&ageMs<=MAX_AGE_MS;}
    /** Keep following one source while it is fresh; switch to another only if it is at least as accurate or the last fix is 2 s old. */
    static boolean prefer(String provider,long elapsedNanos,float accuracyM,String lastProvider,long lastElapsedNanos,float lastAccuracyM){
        long gapMs=(elapsedNanos-lastElapsedNanos)/1_000_000;
        if(gapMs<=0)return false;
        return gapMs>=2000||java.util.Objects.equals(provider,lastProvider)||accuracyM<=lastAccuracyM;
    }
    static String label(String provider){
        if("gps".equals(provider))return "GPS";
        if("network".equals(provider))return "Wi-Fi·기지국";
        if("fused".equals(provider))return "GPS·Wi-Fi";
        return "위치";
    }
}
