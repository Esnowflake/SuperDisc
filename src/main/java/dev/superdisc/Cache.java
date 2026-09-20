package dev.superdisc;

import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class Cache {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    public static final double MAX_SECONDS = 1200;
    private record Verified(long size,java.nio.file.attribute.FileTime modified,String hash) {}
    private static final java.util.Map<Path,Verified> verified=new java.util.LinkedHashMap<>(16,.75f,true);
    public static Path root() { return FMLPaths.GAMEDIR.get().resolve("super_disc/audio_cache"); }
    public static String safeName(String s) {
        String result = s.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        return result.substring(0,Math.min(90,result.length()));
    }
    public static Path file(String hash,String name,String extension) {
        if(!hash.matches("[0-9a-f]{64}") || !(extension.equals("mp3") || extension.equals("ogg"))) throw new IllegalArgumentException("Invalid audio metadata");
        return root().resolve(hash+"_"+safeName(name)+"."+extension);
    }
    public static String sha256(Path path) throws Exception {
        return sha256(path,new WorkToken());
    }
    public static String sha256(Path path,WorkToken token) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)) { byte[] buf=new byte[65536]; int n; while((n=input.read(buf))!=-1) {token.check();digest.update(buf,0,n);} }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static boolean valid(Track track) {
        try {
            Path p=file(track.hash,track.name,track.extension);if(!Files.isRegularFile(p)||Files.size(p)!=track.size)return false;
            var time=Files.getLastModifiedTime(p);
            synchronized(verified){var v=verified.get(p);if(v!=null&&v.size==track.size&&v.modified.equals(time)&&v.hash.equals(track.hash))return true;}
            if(!sha256(p).equals(track.hash))return false;
            synchronized(verified){verified.put(p,new Verified(track.size,time,track.hash));while(verified.size()>128)verified.remove(verified.keySet().iterator().next());}
            return true;
        }
        catch(Exception e) { return false; }
    }
    public static final class Incoming implements AutoCloseable {
        public final Path part, target;
        public final long size;
        private final String hash;
        private OutputStream out;
        private final WorkToken token=new WorkToken();
        public volatile long received;
        public Incoming(Track track) throws IOException {
            if(track.size<=0 || track.size>MAX_BYTES) throw new IOException("File size limit");
            Files.createDirectories(root()); target=file(track.hash,track.name,track.extension);
            part=root().resolve(UUID.randomUUID()+".part"); size=track.size; hash=track.hash; out=new BufferedOutputStream(Files.newOutputStream(part),65536);
        }
        public synchronized void append(long offset,byte[] bytes) throws IOException {
            token.check();
            if(offset!=received || bytes.length==0 || bytes.length>Net.CHUNK || received+bytes.length>size) throw new IOException("Invalid chunk offset/size");
            out.write(bytes); received+=bytes.length;
        }
        public void finish() throws Exception {
            synchronized(this){token.check();out.close();out=null;}
            if(received!=size || !sha256(part,token).equals(hash)) throw new IOException("Audio SHA-256 mismatch");
            synchronized(token){token.check();Files.move(part,target,StandardCopyOption.REPLACE_EXISTING);}
        }
        @Override public void close() { token.cancel();synchronized(this){try { if(out!=null){out.close();out=null;} Files.deleteIfExists(part); } catch(IOException e) { SuperDisc.LOG.warn("Audio partial cleanup",e); }} }
    }
}
