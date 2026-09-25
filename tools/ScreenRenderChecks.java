import java.io.InputStream;
import java.util.*;
import org.objectweb.asm.*;

/** Checks compiled GUI call order without creating an OpenGL context or a game world. */
public class ScreenRenderChecks {
    private static final String SCREEN="net/minecraft/client/gui/screens/Screen";
    private static int checks;
    private record Call(String owner,String name) {}
    private static void check(boolean condition,String message) {
        checks++;
        if(!condition)throw new AssertionError(message);
    }
    private static Map<String,List<Call>> methods(String name)throws Exception {
        Map<String,List<Call>> result=new HashMap<>();
        try(InputStream in=ClassLoader.getSystemResourceAsStream(name+".class")){
            if(in==null)throw new AssertionError("Missing class: "+name);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                    List<Call> calls=new ArrayList<>();
                    result.put(method+descriptor,calls);
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitInsn(int opcode){
                            if(method.equals("isInGameUi"))calls.add(new Call("opcode",Integer.toString(opcode)));
                        }
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean isInterface){
                            calls.add(new Call(owner,name));
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private static List<Call> method(Map<String,List<Call>> methods,String name){
        var matches=methods.entrySet().stream().filter(e->e.getKey().startsWith(name+"(")).toList();
        check(matches.size()==1,"Expected one "+name+" method");
        return matches.get(0).getValue();
    }
    private static long count(List<Call> calls,String name){
        return calls.stream().filter(c->c.name.equals(name)).count();
    }
    private static int index(List<Call> calls,String name){
        for(int i=0;i<calls.size();i++)if(calls.get(i).name.equals(name))return i;
        return -1;
    }
    private static void absent(Map<String,List<Call>> methods,String name){
        check(methods.keySet().stream().noneMatch(k->k.startsWith(name+"(")),"Must inherit "+name);
    }
    public static void main(String[] args)throws Exception {
        String version=args[0];
        var base=methods(SCREEN);
        for(String name:List.of("DiscScreen","VolumeScreen")){
            var own=methods("dev/superdisc/client/"+name);
            if(version.equals("1.21.1")){
                absent(own,"render");
                var background=method(own,"renderBackground");
                check(background.get(0).name.equals("renderTransparentBackground"),"Dim before drawing content");
                check(count(background,"renderTransparentBackground")==1,"One dim pass");
                check(count(background,"renderBackground")==0&&count(background,"render")==0,"No recursive background or widgets");
                check(index(background,"fill")>0,"Panel follows dim");
                var render=method(base,"render");
                check(count(render,"renderBackground")==1,"Parent owns one background pass");
                check(index(render,"renderBackground")<index(render,"render"),"Background before widgets");
            }else if(version.equals("26.2")){
                absent(own,"extractBackground");
                check(method(own,"isInGameUi").equals(List.of(
                    new Call("opcode",Integer.toString(Opcodes.ICONST_1)),
                    new Call("opcode",Integer.toString(Opcodes.IRETURN)))),"Use the unblurred in-game background");
                var background=method(base,"extractBackground");
                check(index(background,"isInGameUi")<index(background,"extractTransparentBackground"),"Parent selects in-game dim");
                check(count(background,"extractDeferredSubtitles")==1,"Preserve background subtitle extraction");
                var content=method(own,"extractRenderState");
                check(count(content,"extractBackground")==0,"Do not repeat wrapper background");
                check(content.get(content.size()-1).equals(new Call(SCREEN,"extractRenderState")),"Widgets after content");
                var wrapper=method(base,"extractRenderStateWithTooltipAndSubtitles");
                check(count(wrapper,"extractBackground")==1,"Wrapper owns one background pass");
                check(index(wrapper,"extractBackground")<index(wrapper,"extractRenderState"),"Wrapper background before content");
                check(count(method(base,"extractRenderState"),"extractBackground")==0,"Widget pass has no background");
            }else if(version.equals("1.20.1")){
                var render=method(own,"render");
                check(count(render,"renderBackground")==1,"Legacy explicit background once");
                check(render.get(0).name.equals("renderBackground"),"Legacy background before content");
                check(render.get(render.size()-1).equals(new Call(SCREEN,"render")),"Legacy widgets after content");
                check(count(method(base,"render"),"renderBackground")==0,"Legacy parent must not redraw background");
            }else throw new AssertionError("Unknown version: "+version);
            for(var entry:own.entrySet()){
                check(entry.getValue().stream().noneMatch(c->c.name.toLowerCase(Locale.ROOT).contains("blur")),
                    name+" must not request blur: "+entry.getKey());
            }
        }
        System.out.println("PASS: "+checks+" compiled screen-render contracts ("+version+")");
    }
}
