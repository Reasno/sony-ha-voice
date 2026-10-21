package dev.guxi.sonyhavoice;

final class Pcm {
    static byte[] frame(int handler, byte[] pcm) {
        if (handler < 0 || handler > 255 || pcm.length % 2 != 0)
            throw new IllegalArgumentException("Invalid PCM frame");
        byte[] frame = new byte[pcm.length + 1];
        frame[0] = (byte)handler;
        System.arraycopy(pcm, 0, frame, 1, pcm.length);
        return frame;
    }
    static double rms(byte[] pcm) {
        double sum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int value = (short)((pcm[i] & 255) | (pcm[i + 1] << 8));
            sum += (double)value * value;
        }
        return pcm.length == 0 ? 0 : Math.sqrt(sum / (pcm.length / 2));
    }
    static final class EndDetector {
        private double noise = 8;
        private long elapsed, silence, voiced;
        boolean add(double rms, int milliseconds) {
            elapsed += milliseconds;
            if (elapsed < 600) noise = noise * .85 + rms * .15;
            boolean speech = elapsed >= 600 && rms > Math.max(24, noise * 2.8);
            if (speech) { voiced += milliseconds; silence = 0; }
            else silence += milliseconds;
            return elapsed >= 20000 || (voiced >= 200 && silence >= 1300)
                || (voiced == 0 && elapsed >= 8000);
        }
    }
}
