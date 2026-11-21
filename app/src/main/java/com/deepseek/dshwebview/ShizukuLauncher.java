package com.deepseek.dshwebview;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Launches the whole DeepSeek Harness under the shell identity provided by
 * Shizuku (uid 2000). The app process itself cannot write the shell-writable
 * shared directory, so a shell script does all the work: extract the runtime
 * straight out of this app's APK (which is world-readable), then start Node.
 *
 * Shizuku's public newProcess() was deprecated in 13.1.x and is hidden, so we
 * call it reflectively. This is the same approach used by other Shizuku apps.
 */
public final class ShizukuLauncher {

    public static final String SHARED_DIR = "/data/local/tmp/dshwebview-shared";
    private static final int SHIZUKU_PKG_CODE = 0x5f17; // arbitrary request code

    private ShizukuLauncher() {
    }

    /**
     * True only when Shizuku is installed, the binder is alive and permission
     * granted. The binder is delivered asynchronously shortly after process
     * start, so wait briefly before concluding it is unavailable.
     */
    public static boolean isAvailable() {
        for (int i = 0; i < 20; i++) {
            try {
                if (Shizuku.isPreV11()) return false;
                if (Shizuku.pingBinder()) {
                    return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Whether a Shizuku/Sui binder exists at all (used to decide whether to prompt). */
    public static boolean isInstalled() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (!Shizuku.isPreV11() && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PKG_CODE);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Start dsh under shell privileges. Returns the remote process, whose
     * stdout/stderr streams are consumed exactly like a local Process.
     */
    public static Process start(Context c, File logFile, int port) throws Exception {
        if (!isAvailable()) throw new IllegalStateException("Shizuku 不可用或未授权");

        String apk = c.getApplicationInfo().sourceDir;
        String nativeLib = c.getApplicationInfo().nativeLibraryDir;
        String script = buildScript(apk, nativeLib, port);

        Method newProcess = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        newProcess.setAccessible(true);
        String[] cmd = new String[]{"/system/bin/sh", "-c", script};
        Object result;
        try {
            // dir must already exist: Shizuku chdir's here before exec. The
            // script creates SHARED_DIR itself, so start from /data/local/tmp.
            result = newProcess.invoke(null, cmd, null, "/data/local/tmp");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOExceptionExtended("Shizuku newProcess 失败: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        if (!(result instanceof ShizukuRemoteProcess)) {
            throw new IOExceptionExtended("Shizuku newProcess 返回了意外的类型: "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        return (Process) result;
    }

    /** Small checked-exception wrapper so callers can keep a single catch(Exception). */
    private static final class IOExceptionExtended extends Exception {
        IOExceptionExtended(String msg) {
            super(msg);
        }
    }

    private static String buildScript(String apk, String nativeLib, int port) {
        String sh = SHARED_DIR;
        // Keep everything on one shell script so a single newProcess call does it all.
        // 'set -e' is intentionally omitted: some probes may fail harmlessly.
        return ""
                + "D=" + sh + "\n"
                + "APK='" + apk + "'\n"
                + "LIB='" + nativeLib + "'\n"
                + "mkdir -p $D/tmp $D/home $D/dsh-home $D/xdg-config $D/xdg-data $D/workspace $D/logs\n"
                + "cd $D\n"
                // Extract dsh.zip when missing OR when the safe-area stylesheet
                // patch is absent (upgrade from an older build). Check the CSS
                // content itself so the runtime self-heals without a version file.
                + "CSS=$D/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-CSAdOBx6.css\n"
                + "if [ ! -f $D/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js ]"
                + " || ! grep -q 'safe-area adaptation === v10' \"$CSS\" 2>/dev/null; then\n"
                + "  rm -rf $D/dsh $D/.dsh.tmp\n"
                + "  mkdir -p $D/.dsh.tmp\n"
                + "  unzip -p \"$APK\" assets/runtime/dsh.zip > $D/.dsh.zip\n"
                + "  unzip -q $D/.dsh.zip -d $D/.dsh.tmp\n"
                + "  mv $D/.dsh.tmp/dsh $D/dsh\n"
                + "  rm -rf $D/.dsh.tmp $D/.dsh.zip\n"
                + "fi\n"
                + "if [ ! -x $D/shell/bin/bash ]; then\n"
                + "  rm -rf $D/shell $D/.shell.tmp\n"
                + "  mkdir -p $D/.shell.tmp $D/shell\n"
                + "  unzip -p \"$APK\" assets/shellruntime.zip > $D/.shell.zip\n"
                + "  unzip -q $D/.shell.zip -d $D/shell\n"
                + "  chmod 755 $D/shell/bin/bash\n"
                + "  rm -rf $D/.shell.tmp $D/.shell.zip\n"
                + "fi\n"
                // Build the CA bundle from the system trust store (shell can read it).
                + "cat /system/etc/security/cacerts/* > $D/ca-bundle.pem 2>/dev/null\n"
                + "SHIM=$D/dsh/node_modules/@deepseek-ai/android-compat.cjs\n"
                + "ENTRY=$D/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js\n"
                + "export LD_LIBRARY_PATH=$LIB:$D/shell/lib\n"
                + "export PATH=$D/shell/bin:$LIB:/system/bin:/system/xbin\n"
                + "export HOME=$D/home TMPDIR=$D/tmp TEMP=$D/tmp\n"
                + "export DSH_HOME=$D/dsh-home XDG_CONFIG_HOME=$D/xdg-config XDG_DATA_HOME=$D/xdg-data\n"
                + "export NODE_EXTRA_CA_CERTS=$D/ca-bundle.pem\n"
                + "export NODE_OPTIONS=--require=$SHIM\n"
                + "export NODE_NO_WARNINGS=1 BROWSER=/system/bin/false TERM=xterm-256color\n"
                + "cd $D/workspace\n"
                + "exec \"$LIB/libnodeexec.so\" --expose-internals --require \"$SHIM\" \"$ENTRY\""
                + " --profile web --no-open --host 127.0.0.1 --port " + port + "\n";
    }
}
