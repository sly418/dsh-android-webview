package com.deepseek.dshwebview;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Voice input with automatic backend selection:
 *  - Devices with >= 8 GB RAM: bundled offline sherpa-onnx (Apache-2.0, 20MB
 *    int8 Chinese model), executed in-process via ProcessBuilder.
 *  - Devices with < 8 GB RAM: DeepSeek cloud ASR (POST /v1/tools/asr), using
 *    the user's configured API key — same one dsh already uses.
 *
 * The offline runtime is bundled in assets (no download at first use) and is
 * extracted to the app-private directory on demand.
 */
public final class SpeechRecognizer {

    private static final String TAG = "DshSpeech";
    private static final long MIN_OFFLINE_RAM_BYTES = 8L * 1024 * 1024 * 1024;

    private static final String ASSET = "runtime/asr-bundle.zip";
    private static final String BUNDLE_DIR = "asr";
    private static final String ASR_API = "https://api.deepseek.com/v1/tools/asr";

    private SpeechRecognizer() {
    }

    public static final class Result {
        public final boolean ok;
        public final String text;
        public final String error;
        public final String backend; // "offline" or "cloud"

        private Result(boolean ok, String text, String error, String backend) {
            this.ok = ok;
            this.text = text;
            this.error = error;
            this.backend = backend;
        }

        static Result fail(String error, String backend) {
            return new Result(false, null, error, backend);
        }

        static Result success(String text, String backend) {
            return new Result(true, text, null, backend);
        }
    }

    /** True when the device has enough RAM for the offline model. */
    public static boolean offlineAvailable(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.totalMem >= MIN_OFFLINE_RAM_BYTES;
    }

    /** Record for a fixed duration, then transcribe with the selected backend. */
    public static Result transcribe(Context context, int seconds) {
        boolean offline = offlineAvailable(context);
        AudioRecorder.Result rec = AudioRecorder.record(context, seconds);
        if (!rec.ok) return Result.fail(rec.error, offline ? "offline" : "cloud");
        File wav = new File(rec.wavPath);
        if (offline) {
            Result local = transcribeOffline(context, wav);
            if (local.ok) return local;
            // Offline failed: fall back to cloud so the feature still works.
            Log.w(TAG, "offline ASR failed (" + local.error + "), falling back to cloud");
        }
        return transcribeCloud(context, wav);
    }

    // ---- Offline backend ---------------------------------------------------

    private static Result transcribeOffline(Context context, File wav) {
        try {
            File base = new File(context.getFilesDir(), BUNDLE_DIR);
            File runtime = new File(base, "runtime");
            File model = new File(base, "model");
            if (!new File(runtime, "bin/sherpa-onnx").isFile()
                    || !new File(model, "model.int8.onnx").isFile()) {
                if (!extractBundle(context, base)) {
                    return Result.fail("failed to extract offline ASR bundle", "offline");
                }
            }
            // sherpa's Termux build expects libc++_shared.so; our NDK library is
            // named libcxxshared.so. Provide the expected name in its lib dir.
            File expected = new File(runtime, "lib/libc++_shared.so");
            if (!expected.exists()) {
                File appLib = new File(context.getApplicationInfo().nativeLibraryDir,
                        "libcxxshared.so");
                if (appLib.isFile()) copyFile(appLib, expected);
            }
            File bin = new File(runtime, "bin/sherpa-onnx");
            // ZipInputStream does not preserve the executable bit, and some
            // Android filesystems ignore File.setExecutable(); use chmod(1).
            try {
                ProcessBuilder cpb = new ProcessBuilder("chmod", "755", bin.getAbsolutePath());
                cpb.redirectErrorStream(true);
                Process chmod = cpb.start();
                String chmodOut = readStream(chmod.getInputStream());
                int chmodCode = chmod.waitFor() ;
                Log.d(TAG, "chmod exit=" + chmodCode + " out=" + chmodOut
                        + " executable=" + bin.canExecute());
            } catch (Exception e) {
                Log.w(TAG, "chmod failed: " + e.getMessage());
            }
            File modelFile = new File(model, "model.int8.onnx");
            File tokens = new File(model, "tokens.txt");

            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(),
                    "--tokens=" + tokens.getAbsolutePath(),
                    "--zipformer2-ctc-model=" + modelFile.getAbsolutePath(),
                    "--num-threads=1",
                    wav.getAbsolutePath());
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", new File(runtime, "lib").getAbsolutePath());
            Process process = pb.start();
            String output = readStream(process.getInputStream());
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Result.fail("offline ASR timeout", "offline");
            }
            String text = cleanAsrOutput(output);
            if (text.isEmpty()) return Result.fail("no speech recognized", "offline");
            return Result.success(text, "offline");
        } catch (Exception e) {
            Log.e(TAG, "offline ASR error: " + e.getMessage());
            return Result.fail(String.valueOf(e.getMessage()), "offline");
        }
    }

    private static boolean extractBundle(Context context, File base) {
        try (ZipInputStream zis = new ZipInputStream(context.getAssets().open(ASSET))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(base, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract bundle failed: " + e.getMessage());
            return false;
        }
    }

    // ---- Cloud backend -----------------------------------------------------

    private static Result transcribeCloud(Context context, File wav) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) apiKey = apiKeyFromDshHome(context);
        if (apiKey == null || apiKey.isEmpty()) {
            return Result.fail("no API key configured for cloud ASR", "cloud");
        }
        String boundary = "----DshAsr" + System.currentTimeMillis();
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(ASR_API).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new java.io.FileInputStream(wav)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String body = readStream(conn.getInputStream());
            if (code < 200 || code >= 300) return Result.fail("cloud ASR HTTP " + code, "cloud");
            String text = extractJsonString(body, "text");
            if (text == null || text.isEmpty()) return Result.fail("cloud ASR empty result", "cloud");
            return Result.success(text, "cloud");
        } catch (Exception e) {
            Log.e(TAG, "cloud ASR error: " + e.getMessage());
            return Result.fail(String.valueOf(e.getMessage()), "cloud");
        }
    }

    /** Reads the API key from the same place dsh uses. */
    private static String apiKeyFromDshHome(Context context) {
        File home = RuntimeInstaller.dshHome(context);
        File[] candidates = {
                new File(home, "config/dsh.yml"),
                new File(home, "config/cordis.yml"),
                new File(RuntimeInstaller.xdgConfig(context), "dsh/config.yml"),
        };
        for (File f : candidates) {
            if (!f.isFile()) continue;
            try {
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                String key = extractJsonString(content, "apiKey");
                if (key == null) key = extractJsonString(content, "api_key");
                if (key != null && !key.isEmpty()) return key;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ---- helpers -----------------------------------------------------------

    private static String readStream(InputStream in) {
        if (in == null) return "";
        try (InputStream stream = in; java.io.ByteArrayOutputStream bos =
                new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanAsrOutput(String raw) {
        if (raw == null) return "";
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("{") || t.startsWith("/")
                    || t.startsWith("OnlineRecognizerConfig") || t.contains("Elapsed seconds")) continue;
            if (t.contains(":")) t = t.substring(t.indexOf(':') + 1).trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        return q2 > q1 ? json.substring(q1 + 1, q2) : null;
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
