import com.github.javaparser.*;
import java.nio.file.*;
/** Syntax-only verification: does not compile or build the mod. */
public class ParseSources {
    public static void main(String[] args)throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        int count=0;
        try(var files=Files.walk(Path.of(args[0]))) {
            for(Path p:files.filter(f->f.toString().endsWith(".java")).toList()){
                StaticJavaParser.parse(p);count++;
            }
        }
        System.out.println("Parsed "+count+" Java source files (Java 17 syntax); no compilation performed.");
    }
}
