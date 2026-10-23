package com.zapeo.player;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Reproductor nativo de Zapeo (ExoPlayer / Media3).
 *
 * El vídeo se dibuja en una SurfaceView colocada DETRÁS del WebView, que se vuelve transparente.
 * La interfaz (barra, título, avisos) sigue siendo la web y se pinta por encima.
 * La web envía el rectángulo donde quiere el vídeo (setRect) y recibe el estado cada 250 ms (evento "tick").
 */
@OptIn(markerClass = UnstableApi.class)
@CapacitorPlugin(name = "ZapeoPlayer")
public class ZapeoPlayerPlugin extends Plugin {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout host;
    private SurfaceView surface;
    private ExoPlayer player;

    private int hostW = 1;
    private int hostH = 1;
    private float videoRatio = 0f;

    private boolean live = false;
    private boolean everPlayed = false;
    private boolean ticking = false;
    private long sid = 0;

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            if (player == null) {
                ticking = false;
                return;
            }
            emitTick();
            ui.postDelayed(this, 250);
        }
    };

    /* ---------- Vistas ---------- */

    private void ensureHost() {
        if (host != null) return;
        Activity a = getActivity();
        if (a == null || getBridge() == null || getBridge().getWebView() == null) return;
        View web = getBridge().getWebView();
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent == null) return;

        host = new FrameLayout(a);
        host.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(a);
        host.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // índice 0 = por debajo del WebView
        parent.addView(host, 0, new ViewGroup.LayoutParams(1, 1));
        host.setX(-20000f);
        web.setBackgroundColor(Color.TRANSPARENT);
    }

    private void fit() {
        if (surface == null) return;
        int w = hostW, h = hostH, fw = w, fh = h;
        if (videoRatio > 0f && w > 0 && h > 0) {
            if ((float) w / (float) h > videoRatio) {
                fw = Math.round(h * videoRatio);
            } else {
                fh = Math.round(w / videoRatio);
            }
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, fw), Math.max(1, fh), Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    @Override
    protected void handleOnResume() {
        if (host != null && getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
        }
    }

    @Override
    protected void handleOnDestroy() {
        releasePlayer();
    }

    /* ---------- Métodos para la web ---------- */

    @PluginMethod
    public void isAvailable(final PluginCall call) {
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                ensureHost();
                JSObject r = new JSObject();
                r.put("ok", host != null);
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void load(final PluginCall call) {
        final String url = call.getString("url", "");
        final String ua = call.getString("ua", "");
        final boolean isLive = call.getData().optBoolean("live", false);
        final boolean hls = call.getData().optBoolean("hls", false);
        final long startMs = call.getData().optLong("startMs", 0L);
        final long id = call.getData().optLong("id", 0L);
        final long preMs = call.getData().optLong("preMs", 0L);
        final boolean muted = call.getData().optBoolean("muted", false);
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                ensureHost();
                if (host == null) {
                    call.reject("sin vista de vídeo");
                    return;
                }
                releasePlayer();
                live = isLive;
                sid = id;
                everPlayed = false;
                videoRatio = 0f;
                fit();

                DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(20000)
                        .setAllowCrossProtocolRedirects(true);
                if (ua != null && ua.length() > 0) http.setUserAgent(ua);

                DefaultLoadControl lc = new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(isLive ? (int) Math.max(20000L, preMs + 10000L) : 30000, isLive ? (int) Math.max(40000L, preMs + 25000L) : 60000, 2500, 5000)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();

                DefaultRenderersFactory rf = new DefaultRenderersFactory(getContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                        .setEnableDecoderFallback(true);

                // En directos .ts se recorta la ráfaga inicial del servidor: así se entra con el colchón elegido y no con 20 s de retraso
                DataSource.Factory dsf = http;
                if (isLive && !hls && preMs > 0) {
                    final DataSource.Factory inner = http;
                    final long target = preMs;
                    final long maxWait = Math.max(15000L, preMs + 10000L);
                    dsf = new DataSource.Factory() {
                        @Override
                        public DataSource createDataSource() {
                            return new TrimmingDataSource(inner.createDataSource(), target, maxWait);
                        }
                    };
                }

                player = new ExoPlayer.Builder(getContext(), rf)
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(dsf))
                        .setLoadControl(lc)
                        .build();
                player.setVideoSurfaceView(surface);
                player.setVolume(muted ? 0f : 1f);
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        awake(state == Player.STATE_BUFFERING || (state == Player.STATE_READY && player != null && player.getPlayWhenReady()));
                        emitState(null);
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying) everPlayed = true;
                        if (isPlaying) awake(true);
                        emitState(null);
                    }

                    @Override
                    public void onPlayerError(PlaybackException e) {
                        emitState(e);
                    }

                    @Override
                    public void onVideoSizeChanged(VideoSize vs) {
                        if (vs.width > 0 && vs.height > 0) {
                            videoRatio = vs.width * vs.pixelWidthHeightRatio / vs.height;
                            fit();
                        }
                    }
                });

                MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
                if (hls) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
                if (startMs > 0) player.setMediaItem(mb.build(), startMs);
                else player.setMediaItem(mb.build());
                player.setPlayWhenReady(false);
                player.prepare();

                if (!ticking) {
                    ticking = true;
                    ui.postDelayed(tickTask, 250);
                }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void play(final PluginCall call) {
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                if (player != null) player.setPlayWhenReady(true);
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void pause(final PluginCall call) {
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                if (player != null) player.setPlayWhenReady(false);
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void seekTo(final PluginCall call) {
        final long ms = call.getData().optLong("ms", 0L);
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                if (player != null) player.seekTo(Math.max(0L, ms));
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void setSpeed(final PluginCall call) {
        final double sp = call.getData().optDouble("speed", 1.0);
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                if (player != null) player.setPlaybackParameters(new PlaybackParameters((float) sp));
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void setMuted(final PluginCall call) {
        final boolean m = call.getData().optBoolean("muted", false);
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                if (player != null) player.setVolume(m ? 0f : 1f);
                call.resolve();
            }
        });
    }

    /** Pantalla inmersiva en el móvil: oculta la barra de estado (hora, batería…) y la de navegación mientras se reproduce. */
    @PluginMethod
    public void immersive(final PluginCall call) {
        final boolean on = call.getData().optBoolean("on", false);
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                android.view.Window w = getActivity().getWindow();
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsetsController c = w.getInsetsController();
                    if (c != null) {
                        if (on) {
                            c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                            c.hide(android.view.WindowInsets.Type.systemBars());
                        } else {
                            c.show(android.view.WindowInsets.Type.systemBars());
                        }
                    }
                } else {
                    View d = w.getDecorView();
                    d.setSystemUiVisibility(on
                            ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                            : View.SYSTEM_UI_FLAG_VISIBLE);
                }
                call.resolve();
            }
        });
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                releasePlayer();
                call.resolve();
            }
        });
    }

    /** x, y, w, h en píxeles del dispositivo; show=false lo aparta de la pantalla. */
    @PluginMethod
    public void setRect(final PluginCall call) {
        final int x = call.getData().optInt("x", 0);
        final int y = call.getData().optInt("y", 0);
        final int w = call.getData().optInt("w", 1);
        final int h = call.getData().optInt("h", 1);
        final boolean show = call.getData().optBoolean("show", false);
        runOnUi(call, new Runnable() {
            @Override
            public void run() {
                ensureHost();
                if (host == null) {
                    call.resolve();
                    return;
                }
                if (!show) {
                    host.setX(-20000f);
                } else {
                    hostW = Math.max(1, w);
                    hostH = Math.max(1, h);
                    ViewGroup.LayoutParams lp = host.getLayoutParams();
                    lp.width = hostW;
                    lp.height = hostH;
                    host.setLayoutParams(lp);
                    host.setX(x);
                    host.setY(y);
                    fit();
                }
                call.resolve();
            }
        });
    }

    /* ---------- Interno ---------- */

    private void runOnUi(final PluginCall call, final Runnable r) {
        Activity a = getActivity();
        if (a == null) {
            call.reject("sin actividad");
            return;
        }
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    call.reject(String.valueOf(t));
                }
            }
        });
    }

    // Evita que la tele active el salvapantallas mientras se reproduce
    private void awake(final boolean on) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (surface != null) surface.setKeepScreenOn(on);
                    android.view.Window w = getActivity().getWindow();
                    if (on) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void releasePlayer() {
        awake(false);
        ui.removeCallbacks(tickTask);
        ticking = false;
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private String stateName(int st) {
        switch (st) {
            case Player.STATE_BUFFERING:
                return "buffering";
            case Player.STATE_READY:
                return "ready";
            case Player.STATE_ENDED:
                return "ended";
            default:
                return "idle";
        }
    }

    private void emitState(PlaybackException err) {
        if (player == null) return;
        JSObject o = new JSObject();
        o.put("id", sid);
        o.put("state", stateName(player.getPlaybackState()));
        o.put("playing", player.isPlaying());
        o.put("playWhenReady", player.getPlayWhenReady());
        if (err != null) {
            int http = 0;
            Throwable c = err.getCause();
            if (c instanceof HttpDataSource.InvalidResponseCodeException) {
                http = ((HttpDataSource.InvalidResponseCodeException) c).responseCode;
            }
            o.put("error", err.getErrorCodeName());
            o.put("errorCode", err.errorCode);
            o.put("errorMsg", String.valueOf(err.getMessage()));
            o.put("http", http);
        }
        notifyListeners("state", o);
        emitTick();
    }

    private void emitTick() {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long buf = player.getBufferedPosition();
        long dur = player.getDuration();
        JSObject o = new JSObject();
        o.put("id", sid);
        o.put("pos", pos / 1000.0);
        o.put("buf", Math.max(pos, buf) / 1000.0);
        o.put("dur", dur > 0 ? dur / 1000.0 : -1.0);
        o.put("state", stateName(player.getPlaybackState()));
        o.put("playing", player.isPlaying());
        o.put("playWhenReady", player.getPlayWhenReady());
        o.put("speed", player.getPlaybackParameters().speed);
        DecoderCounters dc = player.getVideoDecoderCounters();
        if (dc != null) {
            dc.ensureUpdated();
            o.put("dropped", dc.droppedBufferCount);
            o.put("rendered", dc.renderedOutputBufferCount);
        }
        notifyListeners("tick", o);
    }
}
