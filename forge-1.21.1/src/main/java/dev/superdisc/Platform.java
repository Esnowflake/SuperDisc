package dev.superdisc;
public final class Platform {
    private Platform() {}
    public static java.nio.file.Path gameDir() { return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get(); }
}
