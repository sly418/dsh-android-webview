package com.deepseek.dshwebview;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Preflight checks for the bundled runtime. Run before spawning Node so that
 * a missing/corrupt asset produces a precise message instead of a generic
 * "process exited" failure. Both modes are checked conservatively: Shizuku
 * mode extracts into a shared dir, normal mode uses app-private files.
 */
public final class EnvironmentCheck {

    private EnvironmentCheck() {
    }

    /** Result of a preflight check. */
    public static final class Result {
        public final boolean ok;
        public final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * Check prerequisites for NORMAL (app-privileged) mode.
     * Shizuku mode has its own shell-side checks inside its launch script.
     */
    public static Result checkNormalMode(Context c) {
        List<String> problems = new ArrayList<>();

        // ABI support
        try {
            RuntimeInstaller.abi();
        } catch (Exception e) {
            problems.add(e.getMessage());
        }

        // Native Node executable must exist and be executable.
        try {
            File node = RuntimeInstaller.nodeExecutable(c);
            if (!node.canExecute()) problems.add("Node 可执行文件无执行权限：" + node.getName());
        } catch (Exception e) {
            problems.add(e.getMessage());
        }

        // Extracted dsh entry point.
        File dshEntry = new File(RuntimeInstaller.dsh(c),
                "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!dshEntry.isFile()) problems.add("dsh 启动文件缺失");

        // Android compat shim.
        File shim = new File(RuntimeInstaller.dsh(c),
                "node_modules/@deepseek-ai/android-compat.cjs");
        if (!shim.isFile()) problems.add("Android 兼容层缺失");

        // Required shared libraries.
        File libDir = new File(c.getApplicationInfo().nativeLibraryDir);
        // Names are the DT_SONAME-patched filenames actually shipped in jniLibs.
        String[] needed = {"libnodeexec.so", "libcxxshared.so", "libicuuc.so"};
        List<String> missingLibs = new ArrayList<>();
        for (String lib : needed) {
            if (!new File(libDir, lib).isFile()) missingLibs.add(lib);
        }
        if (!missingLibs.isEmpty()) problems.add("缺少动态库：" + String.join(", ", missingLibs));

        // Writable working directories (ensured by installer, verified here).
        File[] dirs = {
                RuntimeInstaller.home(c), RuntimeInstaller.dshHome(c), RuntimeInstaller.tmp(c),
                RuntimeInstaller.xdgConfig(c), RuntimeInstaller.xdgData(c),
                RuntimeInstaller.workspace(c), RuntimeInstaller.logs(c)
        };
        for (File d : dirs) {
            if (!d.isDirectory() && !d.mkdirs()) problems.add("无法创建目录：" + d.getName());
        }

        if (problems.isEmpty()) return new Result(true, "运行环境检查通过");
        return new Result(false, String.join("\n", problems));
    }

    /** Check whether the Shizuku shell-mode shared runtime looks usable. */
    public static Result checkShizukuMode() {
        File shared = new File(ShizukuLauncher.SHARED_DIR);
        List<String> problems = new ArrayList<>();
        if (!shared.isDirectory()) {
            problems.add("Shizuku 共享目录尚未创建（首次启动会自动解压）");
        }
        // Shared dir is created by the shell script; absence is not fatal on
        // first launch, so this check is informational only.
        return new Result(true, String.join("\n", problems));
    }
}
