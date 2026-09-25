package dev.superdisc.client;

/** Linear output gain, with optional headroom protection and defined PCM16 saturation. */
public final class PcmGain {
    private PcmGain() {}
    public static double boost(int peak) {
        return peak <= 0 ? 4 : Math.max(1, Math.min(4, 32760.0 / peak));
    }
    public static double outputGain(float requested,double safeBoost,boolean protectedOutput) {
        double gain=Float.isFinite(requested)?Math.max(0,Math.min(4,requested)):0;
        return protectedOutput?Math.min(gain,safeBoost):gain;
    }
    public static short sample(short input, double boost) {
        // PCM16 cannot represent values outside this range. Saturation is hard clipping,
        // not protection: unsafe amplification may clip, but must never wrap polarity.
        return (short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(input * boost)));
    }
}
