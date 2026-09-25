package dev.superdisc.client;
public final class AudioPath {
    private AudioPath() {}
    public static String normalize(String value) {
        String result=value.strip();
        if(result.length()>=2&&result.startsWith("\"")&&result.endsWith("\""))result=result.substring(1,result.length()-1).strip();
        return result;
    }
}
