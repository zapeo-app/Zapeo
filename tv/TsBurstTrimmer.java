package com.zapeo.player;

import java.io.IOException;

/**
 * Recorta la ráfaga inicial de un directo MPEG-TS.
 *
 * Muchos servidores, al conectar, mandan de golpe 15-25 s de vídeo ya emitido y luego siguen a ritmo normal.
 * ExoPlayer empezaría a reproducir desde el principio de esa ráfaga (20 s por detrás del directo) y un .ts en bruto
 * no admite saltos. Esta clase recoge la ráfaga, espera a que termine y entrega solo el final (el colchón pedido),
 * precedido de las tablas PAT/PMT para que el reproductor entienda el flujo. Lo que viene después pasa sin tocar.
 *
 * Si el flujo no es MPEG-TS, o no se encuentra reloj (PCR), lo entrega todo tal cual.
 */
final class TsBurstTrimmer {

    interface Source {
        int read(byte[] b, int off, int len) throws IOException;
    }

    private static final int PKT = 188;
    private static final int MAX_STORE = 48 * 1024 * 1024;

    private final Source src;
    private final double targetSec;
    private final long maxWaitMs;

    private byte[] store = new byte[1 << 20];
    private int n = 0;

    private boolean collecting = true;
    private boolean passthrough = false;
    private byte[] out = null;
    private int outPos = 0;
    private int outLen = 0;

    // análisis del flujo
    private int alignedStart = -1;
    private int parsed = 0; // primer byte aún sin analizar
    private int pcrPid = -1;
    private int[] pcrOff = new int[1024];
    private long[] pcrVal = new long[1024];
    private int pcrCount = 0;
    private int[] kfOff = new int[256];
    private long[] kfPcr = new long[256];
    private int kfCount = 0;
    private byte[] pat = null;
    private byte[] pmt = null;
    private int pmtPid = -1;

    // Seguimiento del adelanto del servidor: L = vídeo recibido - tiempo transcurrido. Durante la ráfaga L sube;
    // cuando el servidor ya manda a ritmo real, L se queda quieto (aunque llegue a trozos).
    private long tFirst = 0;
    private double refL = 0;
    private long tRef = 0;
    private double maxL = 0;

    TsBurstTrimmer(Source src, long targetMs, long maxWaitMs) {
        this.src = src;
        this.targetSec = Math.max(1.0, targetMs / 1000.0);
        this.maxWaitMs = Math.max(2000L, maxWaitMs);
    }

    int read(byte[] b, int off, int len) throws IOException {
        if (!passthrough && collecting) collect();
        if (out != null) {
            int k = Math.min(len, outLen - outPos);
            System.arraycopy(out, outPos, b, off, k);
            outPos += k;
            if (outPos >= outLen) {
                out = null;
                store = null;
                passthrough = true;
            }
            return k;
        }
        return src.read(b, off, len);
    }

    /* ---------- recogida ---------- */

    private void collect() throws IOException {
        final long t0 = System.currentTimeMillis();
        while (true) {
            if (n + 65536 > store.length) {
                if (store.length >= MAX_STORE) {
                    finish();
                    return;
                }
                byte[] bigger = new byte[Math.min(MAX_STORE, store.length * 2)];
                System.arraycopy(store, 0, bigger, 0, n);
                store = bigger;
            }
            int r = src.read(store, n, Math.min(65536, store.length - n));
            if (r < 0) {
                finish();
                return;
            }
            if (r == 0) continue;
            n += r;
            analyze();

            long now = System.currentTimeMillis();
            if (alignedStart < 0 && n >= PKT * 8) {
                // no es MPEG-TS: se entrega tal cual
                finish();
                return;
            }
            if (alignedStart >= 0 && pcrCount == 0 && n > 600000) {
                finish();
                return;
            }
            if (pcrCount > 0) {
                if (tFirst == 0) {
                    tFirst = now;
                    tRef = now;
                }
                double elapsed = (now - tFirst) / 1000.0;
                double media = (pcrVal[pcrCount - 1] - pcrVal[0]) / 90000.0;
                double l = media - elapsed;
                if (l > maxL) maxL = l;
                if (l > refL + 1.0) {
                    refL = l;
                    tRef = now; // el servidor sigue adelantando: la ráfaga no ha terminado
                }
                long waited = now - t0;
                boolean steady = now - tRef >= 3000 && elapsed >= 2.0;
                if (media >= targetSec + 1.0 && steady) {
                    finish();
                    return;
                }
                if ((waited >= maxWaitMs && media > 0.3) || waited >= maxWaitMs + 6000) {
                    finish();
                    return;
                }
            }
        }
    }

    /* ---------- análisis TS ---------- */

    private void analyze() {
        if (alignedStart < 0) {
            if (n < PKT * 6) return;
            for (int o = 0; o < PKT; o++) {
                boolean ok = true;
                for (int k = 0; k < 5 && ok; k++) ok = (store[o + PKT * k] == 0x47);
                if (ok) {
                    alignedStart = o;
                    parsed = o;
                    break;
                }
            }
            if (alignedStart < 0) return;
        }
        while (parsed + PKT <= n) {
            int p = parsed;
            parsed += PKT;
            if (store[p] != 0x47) continue;
            int b1 = store[p + 1] & 0xFF, b2 = store[p + 2] & 0xFF, b3 = store[p + 3] & 0xFF;
            boolean pusi = (b1 & 0x40) != 0;
            int pid = ((b1 & 0x1F) << 8) | b2;
            int afc = (b3 >> 4) & 3;
            int payload = p + 4;
            if ((afc & 2) != 0) {
                int afLen = store[p + 4] & 0xFF;
                if (afLen >= 7 && (store[p + 5] & 0x10) != 0 && (pcrPid < 0 || pcrPid == pid)) {
                    long base = ((long) (store[p + 6] & 0xFF) << 25) | ((long) (store[p + 7] & 0xFF) << 17)
                            | ((long) (store[p + 8] & 0xFF) << 9) | ((long) (store[p + 9] & 0xFF) << 1)
                            | ((store[p + 10] & 0xFF) >> 7);
                    pcrPid = pid;
                    addPcr(p, base);
                }
                payload = p + 5 + afLen;
            }
            if ((afc & 1) == 0 || payload >= p + PKT) continue;
            if (pusi && pid != 0 && pid != pmtPid && pcrCount > 0 && startsGop(payload, p + PKT)) addKf(p, pcrVal[pcrCount - 1]);
            if (pid == 0 && pusi) {
                pat = copy(p);
                int t = payload + 1 + (store[payload] & 0xFF); // salta el pointer_field
                if (t + 12 < p + PKT && store[t] == 0) {
                    int secLen = ((store[t + 1] & 0x0F) << 8) | (store[t + 2] & 0xFF);
                    int end = Math.min(t + 3 + secLen - 4, p + PKT - 3);
                    for (int e = t + 8; e + 3 < end; e += 4) {
                        int prog = ((store[e] & 0xFF) << 8) | (store[e + 1] & 0xFF);
                        if (prog != 0) {
                            pmtPid = ((store[e + 2] & 0x1F) << 8) | (store[e + 3] & 0xFF);
                            break;
                        }
                    }
                }
            } else if (pmtPid >= 0 && pid == pmtPid && pusi) {
                pmt = copy(p);
            }
        }
    }

    /** ¿Hay en este trozo el inicio de un grupo de imágenes? SPS de H.264, VPS de HEVC o cabecera de secuencia MPEG-2. */
    private boolean startsGop(int from, int to) {
        for (int i = from; i + 3 < to; i++) {
            if (store[i] == 0 && store[i + 1] == 0 && store[i + 2] == 1) {
                int nal = store[i + 3] & 0xFF;
                if ((nal & 0x1F) == 7 || nal == 0x40 || nal == 0xB3) return true;
            }
        }
        return false;
    }

    private void addKf(int off, long pcr) {
        if (kfCount == kfOff.length) {
            kfOff = java.util.Arrays.copyOf(kfOff, kfCount * 2);
            kfPcr = java.util.Arrays.copyOf(kfPcr, kfCount * 2);
        }
        kfOff[kfCount] = off;
        kfPcr[kfCount] = pcr;
        kfCount++;
    }

    private byte[] copy(int p) {
        byte[] c = new byte[PKT];
        System.arraycopy(store, p, c, 0, PKT);
        return c;
    }

    private void addPcr(int off, long val) {
        if (pcrCount > 0 && val < pcrVal[pcrCount - 1] - 90000L * 5) return; // salto atrás raro: se ignora
        if (pcrCount == pcrOff.length) {
            pcrOff = java.util.Arrays.copyOf(pcrOff, pcrCount * 2);
            pcrVal = java.util.Arrays.copyOf(pcrVal, pcrCount * 2);
        }
        pcrOff[pcrCount] = off;
        pcrVal[pcrCount] = val;
        pcrCount++;
    }

    /* ---------- decisión ---------- */

    private void finish() {
        collecting = false;
        int cut = 0;
        if (alignedStart >= 0 && pcrCount >= 2 && kfCount > 0) {
            // Borde del directo: lo recibido más lo que el servidor ya tenía de adelanto. El colchón se mide desde ahí.
            double elapsed = tFirst == 0 ? 0 : (System.currentTimeMillis() - tFirst) / 1000.0;
            long edge = pcrVal[0] + (long) ((elapsed + maxL) * 90000.0);
            long want = edge - (long) (targetSec * 90000.0);
            // el último inicio de imágenes que aún deja, como mínimo, el colchón pedido
            for (int i = kfCount - 1; i >= 0; i--) {
                if (kfPcr[i] <= want) {
                    cut = kfOff[i];
                    break;
                }
            }
        }
        boolean withTables = cut > 0 && pat != null && pmt != null;
        int headLen = withTables ? PKT * 2 : 0;
        int bodyStart = cut > 0 ? cut : 0;
        out = new byte[headLen + (n - bodyStart)];
        if (withTables) {
            System.arraycopy(pat, 0, out, 0, PKT);
            System.arraycopy(pmt, 0, out, PKT, PKT);
        } else if (cut > 0) {
            // sin tablas no se puede recortar con seguridad: se entrega todo
            bodyStart = 0;
            out = new byte[n];
        }
        System.arraycopy(store, bodyStart, out, headLen, n - bodyStart);
        outLen = out.length;
        outPos = 0;
        if (outLen == 0) {
            out = null;
            passthrough = true;
        }
    }
}
