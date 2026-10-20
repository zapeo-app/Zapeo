package com.zapeo.player;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Envuelve una fuente de datos y, en cada conexión nueva de un directo, recorta la ráfaga inicial (ver TsBurstTrimmer). */
@OptIn(markerClass = UnstableApi.class)
final class TrimmingDataSource implements DataSource {

    private final DataSource upstream;
    private final long targetMs;
    private final long maxWaitMs;
    private TsBurstTrimmer trimmer;

    TrimmingDataSource(DataSource upstream, long targetMs, long maxWaitMs) {
        this.upstream = upstream;
        this.targetMs = targetMs;
        this.maxWaitMs = maxWaitMs;
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        upstream.addTransferListener(listener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long len = upstream.open(dataSpec);
        trimmer = dataSpec.position == 0
                ? new TsBurstTrimmer(new TsBurstTrimmer.Source() {
                    @Override
                    public int read(byte[] b, int off, int l) throws IOException {
                        return upstream.read(b, off, l);
                    }
                }, targetMs, maxWaitMs)
                : null;
        return len;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        return trimmer != null ? trimmer.read(buffer, offset, length) : upstream.read(buffer, offset, length);
    }

    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        trimmer = null;
        upstream.close();
    }
}
