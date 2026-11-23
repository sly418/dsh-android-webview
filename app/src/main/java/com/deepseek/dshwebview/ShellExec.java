package com.deepseek.dshwebview;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Runs a short shell command via Shizuku's hidden newProcess() API, matching
 * the approach already used by ShizukuLauncher. Used for device capabilities
 * that need shell privileges (e.g. screencap). Returns captured stdout/stderr.
 *
 * Unavailable in normal (non-Shizuku) mode — callers must handle null.
 */
public final class ShellExec {

    private static final String TAG = "DshShellExec";

    private ShellExec() {
    }

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** Returns null when Shizuku is unavailable or the command could not be started. */
    public static Result run(String[] command, long timeoutMs) {
        if (!ShizukuLauncher.isAvailable()) return null;
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Object result = newProcess.invoke(null, command, null, "/data/local/tmp");
            if (!(result instanceof ShizukuRemoteProcess)) return null;
            ShizukuRemoteProcess process = (ShizukuRemoteProcess) result;
            try {
                String out = readAll(process.getInputStream());
                String err = readAll(process.getErrorStream());
                int code;
                try {
                    code = process.waitFor();
                } catch (Throwable t) {
                    code = -1;
                }
                String combined = out + (err.isEmpty() ? "" : "\n" + err);
                return new Result(code, combined);
            } finally {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "shell exec failed: " + e.getMessage());
            return null;
        }
    }

    /** Runs a command and returns raw stdout bytes (for binary output like screencap PNG). */
    public static byte[] runBytes(String[] command) {
        if (!ShizukuLauncher.isAvailable()) return null;
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Object result = newProcess.invoke(null, command, null, "/data/local/tmp");
            if (!(result instanceof ShizukuRemoteProcess)) return null;
            ShizukuRemoteProcess process = (ShizukuRemoteProcess) result;
            try {
                return readBytes(process.getInputStream());
            } finally {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "shell exec bytes failed: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readBytes(InputStream in) {
        if (in == null) return null;
        try (InputStream stream = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
