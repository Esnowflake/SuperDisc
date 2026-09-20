package dev.superdisc;
import java.util.UUID;

/** Server authority: a protected listener can always change their own volume. */
public final class VolumePolicy {
    private VolumePolicy() {}
    public static boolean allowed(UUID controller, UUID actor, UUID target, boolean protectedVolume) {
        return actor.equals(target) || (actor.equals(controller) && !protectedVolume);
    }
    public static float clamp(float value) {return Math.max(0,Math.min(4,value));}
}
