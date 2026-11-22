package com.deepseek.dshwebview;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Categorized application logger. Keeps four independent log files so startup,
 * permission, runtime and error events can be inspected separately. This class
 * only records events; it does not affect service or process management.
 */
public final class AppLogger {

    public enum Category {
        STARTUP("app-startup.log", "启动"),
        PERMISSION("app-permission.log", "权限"),
        RUNTIME("app-runtime.log", "运行"),
        ERROR("app-error.log", "异常");

        final String file;
        final String label;

        Category(String file, String label) {
            this.file = file;
            this.label = label;
        }
    }

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private AppLogger() {
    }

    /** Append one categorized line to its own file. Timestamp and label are added. */
    public static synchronized void log(Context c, Category cat, String message) {
        File dir = RuntimeInstaller.logs(c);
        File file = new File(dir, cat.file);
        String line = "[" + TIME.format(new Date()) + "] [" + cat.label + "] " + message;
        try {
            if (dir != null && !dir.exists()) dir.mkdirs();
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"));
            try {
                out.println(line);
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
            // Logging must never break the caller.
        }
    }

    public static void startup(Context c, String message) {
        log(c, Category.STARTUP, message);
    }

    public static void permission(Context c, String message) {
        log(c, Category.PERMISSION, message);
    }

    public static void runtime(Context c, String message) {
        log(c, Category.RUNTIME, message);
    }

    public static void error(Context c, String message) {
        log(c, Category.ERROR, message);
    }
}
