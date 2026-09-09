import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Runs isolated production-core tests. Does not invoke Gradle, compile the mod, or launch Minecraft. */
public class RegressionChecks {
    public static void main(String[] args)throws Exception {
        Path project=Path.of("").toAbsolutePath().normalize();
        Path temp=Files.createTempDirectory("super-disc-tests-").toAbsolutePath().normalize();
        try {
            Map<String,String> stubs=Map.of(
                "net/minecraftforge/fml/loading/FMLPaths.java", """
                    package net.minecraftforge.fml.loading;
                    public class FMLPaths {
                        public static final Value GAMEDIR=new Value();
                        public static class Value {public java.nio.file.Path get(){return java.nio.file.Path.of(System.getProperty("test.cache"));}}
                    }
                    """,
                "dev/superdisc/SuperDisc.java", """
                    package dev.superdisc;
                    public class SuperDisc {public static final Log LOG=new Log();public static class Log {public void warn(String s,Exception e){throw new AssertionError(s,e);}}}
                    """,
                "dev/superdisc/Net.java", "package dev.superdisc; public class Net {public static final int CHUNK=16384;}",
                "dev/superdisc/Track.java", "package dev.superdisc; public class Track {public long size;public String hash,name,extension;}"
            );
            List<String> files=new ArrayList<>();
            for(var entry:stubs.entrySet()){Path p=temp.resolve(entry.getKey());Files.createDirectories(p.getParent());Files.writeString(p,entry.getValue());files.add(p.toString());}
            for(String file:List.of("Cache.java","WorkToken.java","client/PcmGain.java","client/PlaybackCursor.java"))files.add(project.resolve("src/main/java/dev/superdisc/"+file).toString());
            Path test=temp.resolve("CoreTests.java");Files.writeString(test,TESTS);files.add(test.toString());
            List<String> options=new ArrayList<>(List.of("-encoding","UTF-8","-d",temp.toString()));options.addAll(files);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,options.toArray(String[]::new))!=0)throw new AssertionError("Core test compilation failed");
            try(var loader=new java.net.URLClassLoader(new java.net.URL[]{temp.toUri().toURL()})){
                System.setProperty("test.cache",temp.resolve("game").toString());
                loader.loadClass("CoreTests").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            }
        } finally {
            // Only remove files beneath this test's freshly-created temporary directory.
            try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){
                if(!p.toAbsolutePath().normalize().startsWith(temp))throw new SecurityException("Unexpected test cleanup path");
                Files.deleteIfExists(p);
            }}
        }
    }
    private static final String TESTS="""
        import dev.superdisc.*;
        import dev.superdisc.client.*;
        import java.nio.file.*;
        import java.util.*;
        public class CoreTests {
            static int checks;
            static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
            public static void main(String[] args)throws Exception {
                // A 3.25-second stream, with one partial final buffer. The cursor may never
                // reach the end while the last 0.25 seconds are still queued.
                PlaybackCursor clock=new PlaybackCursor();
                clock.queued(1000);clock.queued(1000);clock.queued(1000);clock.queued(250);
                check(clock.position(4,0)==0,"initial buffer read-ahead is not playback");
                check(clock.position(4,1500)==1500,"offset includes processed queued buffers");
                check(clock.position(3,500)==1500,"unqueue preserves position");
                check(clock.position(1,0)==3000,"partial tail starts at 3 seconds");
                check(clock.position(1,249)==3249,"tail is not over early");
                check(clock.position(0,0)==3250,"drained cursor matches exact sample count");
                boolean empty=false;try{clock.queued(0);}catch(IllegalArgumentException e){empty=true;}check(empty,"EOF cannot add an empty buffer");
                PlaybackCursor shortClip=new PlaybackCursor();shortClip.queued(50);
                check(shortClip.position(1,25)==25,"subsecond clip");
                // All signed PCM16 inputs: full-scale material must never wrap or clip.
                for(int peak:new int[]{0,1000,12000,16380,20000,32767,32768}){
                    double boost=PcmGain.boost(peak);
                    check(boost>=1&&boost<=2,"bounded boost");
                    for(int value=-Math.min(32768,peak);value<=Math.min(32767,peak);value++){
                        short sample=PcmGain.sample((short)value,boost);
                        check(value==0||Integer.signum(sample)==Integer.signum(value),"no signed overflow");
                        double unity=sample*PcmGain.sourceGain(1,boost);
                        check(Math.abs(unity-value)<1.01,"100 percent preserves samples within quantization");
                        check(Math.abs(sample*PcmGain.sourceGain(2,boost))<=32768,"200 percent stays within PCM range");
                    }
                    check(PcmGain.sourceGain(0,boost)==0,"mute");
                }
                check(PcmGain.boost(12000)==2,"quiet material can double amplitude");
                WorkToken token=new WorkToken();token.cancel();boolean cancelled=false;
                try{token.check();}catch(java.io.InterruptedIOException e){cancelled=true;}check(cancelled,"cancelled decode cannot continue");
                Files.createDirectories(Cache.root());Path original=Cache.root().resolve("original.bin");byte[] data=new byte[20000];new Random(7).nextBytes(data);Files.write(original,data);
                Track t=new Track();t.hash=Cache.sha256(original);t.name="test";t.extension="mp3";t.size=data.length;
                Cache.Incoming partial=new Cache.Incoming(t);partial.append(0,Arrays.copyOf(data,100));Path part=partial.part;partial.close();
                check(!Files.exists(part),"cancel removes partial file");
                boolean rejected=false;try{partial.finish();}catch(Exception e){rejected=true;}check(rejected,"cancelled transfer cannot publish later");
                try(Cache.Incoming full=new Cache.Incoming(t)){
                    full.append(0,Arrays.copyOfRange(data,0,16384));full.append(16384,Arrays.copyOfRange(data,16384,data.length));full.finish();
                }
                check(Cache.valid(t),"complete verified cache is reusable");
                try(Cache.Incoming replacement=new Cache.Incoming(t)){replacement.append(0,new byte[100]);}
                check(Cache.valid(t),"cancel never deletes another complete cache");
                try(var list=Files.list(Cache.root())){check(list.noneMatch(p->p.toString().endsWith(".part")),"no orphaned transfer partials");}
                System.out.println("PASS: "+checks+" assertions: queue clock/EOF, 0-200% PCM gain, cancellation, partial cleanup and cache preservation.");
            }
        }
        """;
}
