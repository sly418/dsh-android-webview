package com.deepseek.dshwebview;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal HTTPS downloader for optional runtime assets (sherpa-onnx, models). */
public final class RuntimeDownloader {

    private static final String TAG = "DshDownloader";

    private RuntimeDownloader() {
    }

    /** Downloads to a temp file then atomically renames. Returns the final file, or null. */
    public static File download(String urlString, File dest) {
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return null;
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(20000);
            // Large model downloads over mobile networks can stall for a while;
            // a short read timeout aborts them needlessly.
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DshWebView/2.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " for " + urlString);
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
                Log.d(TAG, "downloaded " + total + " bytes to " + tmp.getName());
            }
            if (dest.exists() && !dest.delete()) return null;
            if (!tmp.renameTo(dest)) return null;
            return dest;
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e.getMessage());
            if (tmp.exists()) tmp.delete();
            return null;
        }
    }

    public static boolean exists(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }
}
