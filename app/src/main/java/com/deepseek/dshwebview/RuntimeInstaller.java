package com.deepseek.dshwebview;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the JS payload into app-private storage. The Node ELF and its
 * shared libraries are shipped as native libraries and extracted by Android
 * into nativeLibraryDir, which is the only directory where modern Android
 * permits execve for a normal app. DT_NEEDED/DT_SONAME strings in those ELFs
 * are patched at build time so the linker resolves them directly there.
 */
public final class RuntimeInstaller {

    private static final String VERSION = "dsh-0.2.2-rc.5-android-link-shim-v10-safearea";

    private RuntimeInstaller() {
    }

    public interface Progress {
        void onProgress(String message);
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "runtime");
    }

    public static File dsh(Context c) {
        return new File(root(c), "dsh");
    }

    public static File home(Context c) {
        return new File(c.getFilesDir(), "home");
    }

    public static File dshHome(Context c) {
        return new File(c.getFilesDir(), "dsh-home");
    }

    public static File tmp(Context c) {
        return new File(c.getFilesDir(), "tmp");
    }

    public static File xdgConfig(Context c) {
        return new File(c.getFilesDir(), "xdg-config");
    }

    public static File xdgData(Context c) {
        return new File(c.getFilesDir(), "xdg-data");
    }

    public static File workspace(Context c) {
        return new File(c.getFilesDir(), "workspace");
    }

    public static File logs(Context c) {
        return new File(c.getFilesDir(), "logs");
    }

    public static String abi() throws IOException {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) return abi;
            }
        }
        throw new IOException("不支持的 CPU 架构：" + (abis == null ? "unknown" : String.join(", ", abis)));
    }

    /** Executable in /data/app/.../lib/<abi>/libnodeexec.so, never in filesDir. */
    public static File nodeExecutable(Context c) throws IOException {
        File node = new File(c.getApplicationInfo().nativeLibraryDir, "libnodeexec.so");
        if (!node.isFile()) {
            throw new IOException("原生库缺失：" + node.getAbsolutePath()
                    + "（请确认 APK 包含 jniLibs/" + abi() + "/libnodeexec.so）");
        }
        return node;
    }

    public static String libraryPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * Node ships its own CA store and cannot see Android's system trust store,
     * so HTTPS calls from dsh fail on networks that terminate TLS with a cert
     * signed by a CA installed at the system level (common on Chinese ROMs /
     * carrier networks). Concatenate all system trust anchors into one PEM that
     * NODE_EXTRA_CA_CERTS can consume. Rebuild only when the source changes.
     */
    public static File caBundle(Context c) throws IOException {
        File dir = new File("/system/etc/security/cacerts");
        File out = new File(root(c), "system-ca-bundle.pem");
        long stamp = dir.lastModified();
        File stampFile = new File(root(c), "system-ca-bundle.stamp");
        if (out.isFile() && out.length() > 0 && stampFile.isFile()) {
            try {
                if (Long.parseLong(readWholeFile(stampFile).trim()) == stamp) return out;
            } catch (Exception ignored) {
                // fall through and rebuild
            }
        }
        File[] certs = dir.listFiles();
        if (certs == null || certs.length == 0) {
            throw new IOException("无法读取系统 CA 证书目录：" + dir);
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        Writer w = new FileWriter(tmp, false);
        try {
            int added = 0;
            for (File cert : certs) {
                if (!cert.isFile()) continue;
                String text = readWholeFile(cert);
                if (!text.contains("BEGIN CERTIFICATE")) continue;
                w.write(text);
                if (!text.endsWith("\n")) w.write("\n");
                added++;
            }
            if (added == 0) throw new IOException("系统 CA 目录中没有可用证书");
        } finally {
            w.close();
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("无法写入 CA bundle：" + out);
        }
        writeAll(stampFile, Long.toString(stamp));
        return out;
    }

    private static String readWholeFile(File f) throws IOException {
        Reader r = new FileReader(f);
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            r.close();
        }
    }

    public static void ensureInstalled(Context c, Progress progress) throws IOException {
        String abi = abi();
        File marker = new File(root(c), ".installed." + abi);
        boolean fresh = !(marker.isFile() && VERSION.equals(readAll(marker)));

        if (fresh) {
            File root = root(c);
            if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建运行时目录");
            File staging = new File(root, ".staging");
            File old = new File(root, ".old");
            deleteRecursive(staging);
            deleteRecursive(old);
            if (!staging.mkdirs()) throw new IOException("无法创建暂存目录");

            try {
                if (progress != null) progress.onProgress("正在安装 DeepSeek Harness…");
                unzipAsset(c, "runtime/dsh.zip", staging);
                File stagedDsh = new File(staging, "dsh");
                if (!new File(stagedDsh, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile()) {
                    throw new IOException("资产缺少 dsh 启动入口");
                }
                if (dsh(c).exists() && !dsh(c).renameTo(old)) throw new IOException("无法替换旧版 dsh");
                if (!stagedDsh.renameTo(dsh(c))) throw new IOException("无法安装 DeepSeek Harness");
                writeAll(marker, VERSION);
            } finally {
                deleteRecursive(staging);
                deleteRecursive(old);
            }
        }

        for (File dir : new File[]{home(c), dshHome(c), tmp(c), xdgConfig(c), xdgData(c), workspace(c), logs(c)}) {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录：" + dir.getName());
        }
    }

    private static void unzipAsset(Context c, String assetPath, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        try (InputStream raw = c.getAssets().open(assetPath);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(dest, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new IOException("资产路径非法：" + entry.getName());
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IOException("无法创建目录：" + out);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录：" + parent);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    private static String readAll(File f) {
        try (Reader r = new FileReader(f); BufferedReader br = new BufferedReader(r)) {
            return br.readLine();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeAll(File f, String value) throws IOException {
        try (Writer w = new FileWriter(f, false)) {
            w.write(value);
        }
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File kid : kids) deleteRecursive(kid);
        if (!f.delete()) f.deleteOnExit();
    }
}
