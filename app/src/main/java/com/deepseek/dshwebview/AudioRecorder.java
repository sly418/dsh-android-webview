package com.deepseek.dshwebview;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/** Records PCM audio to a 16-bit mono 16kHz WAV file using framework APIs. */
public final class AudioRecorder {

    private static final String TAG = "DshAudioRecorder";
    private static final int SAMPLE_RATE = 16000;

    private AudioRecorder() {
    }

    public static final class Result {
        public final boolean ok;
        public final String wavPath;
        public final String error;

        private Result(boolean ok, String wavPath, String error) {
            this.ok = ok;
            this.wavPath = wavPath;
            this.error = error;
        }

        static Result fail(String error) {
            return new Result(false, null, error);
        }

        static Result success(String path) {
            return new Result(true, path, null);
        }
    }

    public static Result record(Context context, int seconds) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.fail("RECORD_AUDIO permission not granted");
        }
        seconds = Math.max(1, Math.min(seconds, 20));
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) return Result.fail("audio buffer unavailable");

        AudioRecord record = new AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            return Result.fail("AudioRecord init failed");
        }

        // Both backends (in-process offline CLI and cloud upload) run in the
        // app process, so the app-private directory is correct here.
        File dir = new File(context.getFilesDir(), "voice");
        if (!dir.exists() && !dir.mkdirs()) return Result.fail("cannot create voice dir");
        File wav = new File(dir, "rec_" + System.currentTimeMillis() + ".wav");

        try {
            File pcm = new File(dir, "pcm_" + System.currentTimeMillis() + ".raw");
            record.startRecording();
            try (FileOutputStream out = new FileOutputStream(pcm)) {
                byte[] buf = new byte[bufferSize];
                long deadline = System.currentTimeMillis() + seconds * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    int read = record.read(buf, 0, buf.length);
                    if (read > 0) out.write(buf, 0, read);
                }
            } finally {
                record.stop();
                record.release();
            }
            writeWavHeader(pcm, wav);
            pcm.delete();
            return Result.success(wav.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "record error: " + e.getMessage());
            try {
                record.release();
            } catch (Throwable ignored) {
            }
            return Result.fail(String.valueOf(e.getMessage()));
        }
    }

    private static void writeWavHeader(File pcm, File wav) throws Exception {
        long dataLen = pcm.length();
        try (FileOutputStream out = new FileOutputStream(wav);
             RandomAccessFile raf = new RandomAccessFile(pcm, "r")) {
            byte[] header = new byte[44];
            putAscii(header, 0, "RIFF");
            putInt(header, 4, 36 + dataLen);
            putAscii(header, 8, "WAVE");
            putAscii(header, 12, "fmt ");
            putInt(header, 16, 16);
            putShort(header, 20, 1);      // PCM
            putShort(header, 22, 1);      // mono
            putInt(header, 24, SAMPLE_RATE);
            putInt(header, 28, SAMPLE_RATE * 2);
            putShort(header, 32, 2);      // block align
            putShort(header, 34, 16);     // bits
            putAscii(header, 36, "data");
            putInt(header, 40, dataLen);
            out.write(header);
            byte[] buf = new byte[8192];
            int n;
            while ((n = raf.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
