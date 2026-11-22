package com.deepseek.dshwebview;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Low-overhead periodic sampler for resource usage (memory, CPU, temperature).
 * Runs on its own single-thread scheduler and writes to app-resources.log so
 * usage can be correlated with idle / continuous-chat / file-handling scenarios.
 * It never touches service or process lifecycle beyond start()/stop().
 */
public final class ResourceSampler {

    private static final long INTERVAL_SEC = 30;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private final Context context;
    private final File logFile;
    private ScheduledExecutorService scheduler;
    private long prevCpuTime = -1;
    private long prevWallTime = -1;

    public ResourceSampler(Context c) {
        this.context = c.getApplicationContext();
        this.logFile = new File(RuntimeInstaller.logs(this.context), "app-resources.log");
    }

    /** Begin periodic sampling. Safe to call more than once. */
    public synchronized void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsh-resource-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sample, 0, INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** Stop sampling. Safe to call more than once. */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Write one labeled scenario marker into the resource log. */
    public void mark(String scenario) {
        write("=== 场景标记: " + scenario + " ===");
    }

    private void sample() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(TIME.format(new Date())).append(" | ");

            // Memory: dalvik/heap + total PSS of this process.
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            int pssKb = mi.getTotalPss();
            sb.append("PSS=").append(pssKb).append("KB ");
            long rssKb = readFirstLong("/proc/self/status", "VmRSS:");
            if (rssKb >= 0) sb.append("RSS=").append(rssKb).append("KB ");

            // CPU: delta of process utime+stime over wall time (jiffies, 100 Hz).
            long cpu = readProcessCpuJiffies();
            long wall = System.currentTimeMillis();
            if (prevCpuTime >= 0 && cpu >= prevCpuTime) {
                double wallSec = (wall - prevWallTime) / 1000.0;
                double cpuSec = (cpu - prevCpuTime) / 100.0;
                if (wallSec > 0) {
                    sb.append(String.format(Locale.getDefault(), "CPU=%.1f%% ",
                            (cpuSec / wallSec) * 100.0));
                }
            }
            prevCpuTime = cpu;
            prevWallTime = wall;

            // Battery/SoC temperature from sysfs (millidegrees C, best effort).
            double temp = readTemperatureC();
            if (temp > 0) {
                sb.append(String.format(Locale.getDefault(), "Temp=%.1fC ", temp));
            }

            // Foreground / activity-manager hint.
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mem);
                sb.append("AvailMem=").append(mem.availMem / (1024 * 1024)).append("MB");
            }

            write(sb.toString().trim());
        } catch (Throwable ignored) {
            // Sampling must never affect the app.
        }
    }

    private static long readProcessCpuJiffies() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/self/stat"));
            String line = reader.readLine();
            if (line == null) return -1;
            // Fields after comm (which may contain spaces/parens). Use last ')' index.
            int end = line.lastIndexOf(')');
            String[] parts = line.substring(end + 2).trim().split("\\s+");
            // parts[11]=utime, parts[12]=stime (0-based after state at parts[0]).
            long utime = Long.parseLong(parts[11]);
            long stime = Long.parseLong(parts[12]);
            return utime + stime;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (reader != null) try { reader.close(); } catch (Throwable ignored) { }
        }
    }

    /** Read the first available thermal zone temperature, in degrees Celsius. */
    private static double readTemperatureC() {
        try {
            File thermal = new File("/sys/class/thermal");
            File[] zones = thermal.listFiles((dir, name) -> name.startsWith("thermal_zone"));
            if (zones == null) return -1;
            for (File zone : zones) {
                File tempFile = new File(zone, "temp");
                if (!tempFile.isFile()) continue;
                BufferedReader reader = new BufferedReader(new FileReader(tempFile));
                try {
                    String line = reader.readLine();
                    if (line != null) {
                        double millideg = Double.parseDouble(line.trim());
                        double c = millideg / 1000.0;
                        if (c > 0 && c < 120) return c;
                    }
                } finally {
                    reader.close();
                }
            }
        } catch (Throwable ignored) {
            // Device may not expose thermal zones to apps.
        }
        return -1;
    }

    /** Read a key's numeric value from a /proc-style file (first integer after key). */
    private static long readFirstLong(String path, String key) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] parts = line.substring(key.length()).trim().split("\\s+");
                    if (parts.length > 0) return Long.parseLong(parts[0]);
                }
            }
            return -1;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (reader != null) try { reader.close(); } catch (Throwable ignored) { }
        }
    }

    private void write(String line) {
        try {
            File dir = logFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8"));
            try {
                out.println(line);
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
