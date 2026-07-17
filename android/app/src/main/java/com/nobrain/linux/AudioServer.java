package com.nobrain.linux;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;

/**
 * Connect ke PulseAudio module-simple-protocol-tcp di port 4714.
 * PA config: null-sink + simple-protocol-tcp (source=android_out.monitor, record=true).
 * Format: s16le, 44100Hz, stereo.
 */
public class AudioServer {

    private static final String TAG  = "AudioServer";
    private static final int    PORT = 4714;
    private static final int    RATE = 44100;

    private volatile boolean running = false;
    private Thread thread;
    private final File logFile;

    public AudioServer(Context ctx) {
        File extDir = ctx.getExternalFilesDir(null);
        logFile = (extDir != null) ? new File(extDir, "audio.log") : null;
        dbgLog("AudioServer init, will connect to PA port " + PORT);
    }

    private void dbgLog(String msg) {
        Log.d(TAG, msg);
        if (logFile == null) return;
        try {
            FileWriter fw = new FileWriter(logFile, true);
            fw.write("AUDIO: " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "audio-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void run() {
        int minBuf = AudioTrack.getMinBufferSize(RATE,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, 16384);

        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        track.play();

        byte[] buf = new byte[4096];
        int retries = 0;

        while (running) {
            try {
                Socket socket = new Socket("127.0.0.1", PORT);
                socket.setTcpNoDelay(true);
                retries = 0;
                dbgLog("connected to PA port " + PORT + ", streaming");
                try (InputStream in = socket.getInputStream()) {
                    long bytes = 0;
                    int n;
                    while (running && (n = in.read(buf)) > 0) {
                        track.write(buf, 0, n);
                        bytes += n;
                        if (bytes % (44100 * 4 * 10L) < 4096) {
                            dbgLog("streaming: " + (bytes / 1024) + "KB");
                        }
                    }
                }
                dbgLog("stream ended, reconnecting");
            } catch (ConnectException e) {
                if (retries++ % 10 == 0) dbgLog("waiting for PA port " + PORT + "...");
            } catch (Exception e) {
                if (running) dbgLog("error: " + e.getMessage());
            }
            if (running) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }

        track.stop();
        track.release();
    }
}
