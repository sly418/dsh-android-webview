package com.deepseek.dshwebview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the bundled Node.js process that serves the local DeepSeek Harness Web UI. */
public class DshService extends Service {

    private static final String CHANNEL_ID = "dsh-webview";
    private static final String BASE_URL = "http://127.0.0.1:3080/";
    private static final int READY_TIMEOUT_MS = 180_000;

    public enum State { IDLE, INSTALLING, STARTING, READY, FAILED }

    public interface Listener {
        void onState(State state, String message);

        void onUrl(String url);
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dsh-runtime");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Pattern urlPattern = Pattern.compile("https?://[^\\s\"'<>]+");
    private final ArrayDeque<String> recentLines = new ArrayDeque<>();

    private Process process;
    private volatile State state = State.IDLE;
    private volatile String message = "";
    private volatile String lastError = "";
    private volatile String advertisedUrl;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1, buildNotification("DeepSeek Harness 服务正在启动"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startRuntime();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        listeners.clear();
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        executor.shutdownNow();
        stopForeground(true);
        super.onDestroy();
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public String getUrl() {
        return advertisedUrl;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized String getLogTail() {
        return String.join("\n", recentLines);
    }

    private synchronized void remember(String line) {
        if (recentLines.size() >= 80) recentLines.removeFirst();
        recentLines.addLast(line);
    }

    private static void appendLine(File file, String line) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true)));
            try {
                out.println(line);
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    public void setListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onState(state, message);
        if (advertisedUrl != null) listener.onUrl(advertisedUrl);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void restart() {
        if (process != null) {
            // ShizukuRemoteProcess can throw "process hasn't exited" when the
            // process is already gone; tearing down must never abort a restart.
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
            process = null;
        }
        advertisedUrl = null;
        synchronized (this) {
            recentLines.clear();
        }
        startRuntime();
    }

    private void startRuntime() {
        if (state == State.INSTALLING || state == State.STARTING) return;
        setState(State.INSTALLING, getString(R.string.status_installing));
        executor.execute(() -> {
            try {
                File logs = RuntimeInstaller.logs(this);
                Process p;
                boolean shizuku = ShizukuLauncher.isAvailable();
                if (shizuku) {
                    // Full shell-privileged mode: Shizuku extracts the runtime
                    // from our APK into a shared dir and starts Node as uid 2000.
                    setState(State.STARTING, "正在以 Shizuku (shell) 权限启动…");
                    try {
                        p = ShizukuLauncher.start(this, new File(logs, "dsh-shell.log"), 3080);
                        appendLine(new File(logs, "dsh-shell.log"), "[info] Shizuku shell 模式已启动");
                    } catch (Exception se) {
                        // Shizuku failed; record why and fall back to normal mode
                        // so the app still works.
                        String why = se.getMessage() == null ? se.getClass().getSimpleName() : se.getMessage();
                        appendLine(new File(logs, "dsh-shell.log"), "[shizuku-failed] " + why);
                        shizuku = false;
                        p = null;
                    }
                } else {
                    p = null;
                }
                if (!shizuku) {
                    // Fallback: normal app-privileged mode from private storage.
                    RuntimeInstaller.ensureInstalled(this, msg -> setState(State.INSTALLING, msg));
                    File node = RuntimeInstaller.nodeExecutable(this);
                    File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
                    File dshEntry = new File(RuntimeInstaller.dsh(this),
                            "node_modules/@deepseek-ai/dsh/lib/bin.js");
                    if (!dshEntry.isFile()) throw new IOException("找不到 DeepSeek Harness 启动文件");

                    File home = RuntimeInstaller.home(this);
                    File dshHome = RuntimeInstaller.dshHome(this);
                    File tmp = RuntimeInstaller.tmp(this);
                    File config = RuntimeInstaller.xdgConfig(this);
                    File data = RuntimeInstaller.xdgData(this);
                    File workspace = RuntimeInstaller.workspace(this);
                    for (File d : new File[]{home, dshHome, tmp, config, data, workspace, logs}) {
                        if (!d.exists() && !d.mkdirs()) throw new IOException("无法创建目录：" + d.getName());
                    }

                    File compatShim = new File(RuntimeInstaller.dsh(this),
                            "node_modules/@deepseek-ai/android-compat.cjs");
                    ProcessBuilder pb = new ProcessBuilder(
                            node.getAbsolutePath(),
                            "--expose-internals",
                            "--require", compatShim.getAbsolutePath(),
                            dshEntry.getAbsolutePath(),
                            "--profile", "web",
                            "--no-open",
                            "--host", "127.0.0.1",
                            "--port", "3080");
                    pb.directory(workspace);
                    pb.redirectErrorStream(true);
                    Map<String, String> env = pb.environment();
                    env.put("HOME", home.getAbsolutePath());
                    env.put("TMPDIR", tmp.getAbsolutePath());
                    env.put("TEMP", tmp.getAbsolutePath());
                    env.put("PATH", nativeDir.getAbsolutePath() + File.pathSeparator + "/system/bin:/system/xbin");
                    env.put("LD_LIBRARY_PATH", RuntimeInstaller.libraryPath(this));
                    env.put("DSH_HOME", dshHome.getAbsolutePath());
                    env.put("XDG_CONFIG_HOME", config.getAbsolutePath());
                    env.put("XDG_DATA_HOME", data.getAbsolutePath());
                    env.put("BROWSER", "/system/bin/false");
                    env.put("TERM", "xterm-256color");
                    env.put("NODE_NO_WARNINGS", "1");
                    File caBundle = RuntimeInstaller.caBundle(this);
                    env.put("NODE_EXTRA_CA_CERTS", caBundle.getAbsolutePath());
                    String existingNodeOptions = env.get("NODE_OPTIONS");
                    String shimOption = "--require=" + compatShim.getAbsolutePath();
                    env.put("NODE_OPTIONS", existingNodeOptions == null || existingNodeOptions.isEmpty()
                            ? shimOption : existingNodeOptions + " " + shimOption);
                    p = pb.start();
                }
                setState(State.STARTING, "正在启动 DeepSeek Harness Web UI…");
                process = p;
                readOutput(p, new File(logs, "dsh.log"));

                long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (!p.isAlive()) {
                        int code;
                        try {
                            code = p.exitValue();
                        } catch (IllegalThreadStateException ignored) {
                            code = -1;
                        }
                        throw new IOException("Node 进程已退出，退出码=" + code
                                + "\n--- 最近日志 ---\n" + getLogTail());
                    }
                    // The root URL without a launch token is rejected by dsh's
                    // browser-session auth. Wait for the tokenized URL printed
                    // by dsh before telling WebView to load anything.
                    if (isServerUp() && advertisedUrl != null) break;
                    Thread.sleep(400);
                }
                if (!isServerUp()) throw new IOException("服务在 3 分钟内未就绪，请查看 logs/dsh.log");
                if (advertisedUrl == null) {
                    throw new IOException("服务已启动但未捕获到 tokenized URL，请查看 logs/dsh.log");
                }

                setState(State.READY, getString(R.string.status_ready));
                final String readyUrl = advertisedUrl;
                main.post(() -> {
                    for (Listener l : listeners) l.onUrl(readyUrl);
                });
                updateNotification("DeepSeek Harness 运行中");
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (detail.toLowerCase().contains("permission denied")
                        || detail.contains("CANNOT LINK")) {
                    detail = detail + "\n提示：运行时已从 nativeLibraryDir 启动；请确认系统未拦截原生库执行。";
                }
                if (!detail.contains("最近日志")) {
                    String tail = getLogTail();
                    if (!tail.isEmpty()) detail += "\n--- 最近日志 ---\n" + tail;
                }
                lastError = detail;
                setState(State.FAILED, detail);
                updateNotification("DeepSeek Harness 启动失败");
            }
        });
    }

    private void readOutput(Process p, File logFile) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile, true)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.println(line);
                    out.flush();
                    remember(line);
                    String clean = line.replaceAll("\u001B\\[[;\\d]*m", "");
                    if (clean.contains("dsh web:")) {
                        Matcher m = urlPattern.matcher(clean);
                        if (m.find()) {
                            String found = cleanUrl(m.group());
                            if (found != null && !found.equals(advertisedUrl)) {
                                advertisedUrl = found;
                                final String readyUrl = found;
                                main.post(() -> {
                                    for (Listener l : listeners) l.onUrl(readyUrl);
                                });
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "dsh-log-reader").start();
        // ShizukuRemoteProcess cannot merge stderr into stdout; drain it too so
        // script errors (unzip/linker) still land in the log for diagnosis.
        new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile, true)))) {
                String line;
                while ((line = err.readLine()) != null) {
                    out.println("[stderr] " + line);
                    out.flush();
                }
            } catch (Exception ignored) {
            }
        }, "dsh-err-reader").start();
    }

    private static String cleanUrl(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[),;\\]]+$", "");
        return s.startsWith("http") ? s : null;
    }

    private static boolean isServerUp() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL).openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void setState(State s, String msg) {
        state = s;
        message = msg == null ? "" : msg;
        final State fs = s;
        final String fm = message;
        main.post(() -> {
            for (Listener l : listeners) l.onState(fs, fm);
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, buildNotification(text));
    }

    public final class LocalBinder extends Binder {
        public DshService getService() {
            return DshService.this;
        }
    }
}
