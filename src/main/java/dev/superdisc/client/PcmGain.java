package dev.superdisc.client;

/** Constant headroom-limited amplification: preserves the waveform instead of clipping peaks. */
public final class PcmGain {
    private PcmGain() {}
    public static double boost(int peak) {
        return peak <= 0 ? 2 : Math.max(1, Math.min(2, 32760.0 / peak));
    }
    public static float sourceGain(float requested, double boost) {
        return (float)Math.max(0, Math.min(1, requested / boost));
    }
    public static short sample(short input, double boost) {
        return (short)Math.round(input * boost);
    }
}
