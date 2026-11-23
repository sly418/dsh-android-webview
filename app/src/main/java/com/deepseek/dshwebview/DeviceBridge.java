package com.deepseek.dshwebview;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP bridge that exposes a small set of Android device capabilities to
 * the MCP server running inside the bundled Node process. Listens only on the
 * loopback interface; every endpoint returns plain JSON.
 *
 * This is intentionally dependency-free: Android does not ship
 * com.sun.net.httpserver, so a minimal HTTP/1.1 server is implemented on top of
 * ServerSocket. Requests are tiny local JSON-RPC tool calls.
 */
public final class DeviceBridge {

    private static final String TAG = "DshDeviceBridge";
    private static final int PORT = 3001;

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public DeviceBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
                running = true;
                Log.d(TAG, "Device bridge listening on 127.0.0.1:" + PORT);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.execute(() -> handle(client));
                    } catch (IOException e) {
                        if (running) Log.w(TAG, "accept failed: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "bind failed: " + e.getMessage());
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            StringBuilder body = new StringBuilder();
            for (int i = 0; i < contentLength; i++) {
                int c = reader.read();
                if (c < 0) break;
                body.append((char) c);
            }

            String response;
            int status = 200;
            if ("GET".equals(method) && "/health".equals(path)) {
                response = "{\"ok\":true}";
            } else if ("POST".equals(method) && "/tool".equals(path)) {
                response = dispatch(body.toString());
            } else {
                status = 404;
                response = "{\"error\":\"not found\"}";
            }

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Not Found") + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "handle error: " + e.getMessage());
        }
    }

    /** Routes a tool invocation to the matching Android capability. */
    private String dispatch(String jsonBody) {
        String tool = extractString(jsonBody, "tool");
        if (tool == null || tool.isEmpty()) {
            return "{\"error\":\"missing tool\"}";
        }
        try {
            switch (tool) {
                case "get_battery":
                    return getBattery();
                case "vibrate":
                    long ms = extractLong(jsonBody, "duration_ms", 200);
                    return vibrate(ms);
                case "notify":
                    String title = extractString(jsonBody, "title");
                    String message = extractString(jsonBody, "message");
                    return notify(title, message);
                case "get_clipboard":
                    return getClipboard();
                case "capture_photo":
                    boolean front = extractBool(jsonBody, "front", false);
                    int quality = (int) extractLong(jsonBody, "quality", 80);
                    return capturePhoto(front, quality);
                case "capture_screen":
                    return captureScreen();
                case "transcribe_audio":
                    int seconds = (int) extractLong(jsonBody, "seconds", 5);
                    return transcribeAudio(seconds);
                default:
                    return "{\"error\":\"unknown tool: " + escape(tool) + "\"}";
            }
        } catch (Exception e) {
            Log.e(TAG, "tool " + tool + " failed: " + e.getMessage());
            return "{\"error\":" + quote("tool failed: " + e.getMessage()) + "}";
        }
    }

    private String getBattery() {
        Intent battery = context.registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return "{\"error\":\"battery status unavailable\"}";
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        float percent = scale > 0 ? level * 100f / scale : -1f;
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return String.format(Locale.US,
                "{\"level_percent\":%d,\"charging\":%b,\"plugged\":%d}",
                Math.round(percent), charging, plugged);
    }

    private String vibrate(long millis) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return "{\"ok\":false,\"error\":\"no vibrator\"}";
        }
        long duration = Math.max(50, Math.min(millis, 5000));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //noinspection deprecation
            vibrator.vibrate(duration);
        }
        return "{\"ok\":true,\"duration_ms\":" + duration + "}";
    }

    private String notify(String title, String message) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return "{\"ok\":false,\"error\":\"no notification manager\"}";
        String channelId = "dsh-device-tools";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Device tools", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
        Intent launch = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pi = launch == null ? null : PendingIntent.getActivity(
                context, 0, launch,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, channelId);
        } else {
            //noinspection deprecation
            builder = new android.app.Notification.Builder(context);
        }
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title == null ? "DSH" : title)
                .setContentText(message == null ? "" : message)
                .setAutoCancel(true);
        if (pi != null) builder.setContentIntent(pi);
        manager.notify((int) (System.currentTimeMillis() % 100000), builder.build());
        return "{\"ok\":true}";
    }

    private String getClipboard() {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return "{\"ok\":false,\"error\":\"clipboard empty\"}";
        }
        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
        CharSequence text = item == null ? null : item.getText();
        return "{\"ok\":true,\"text\":" + quote(text == null ? "" : text.toString()) + "}";
    }

    private String transcribeAudio(int seconds) {
        SpeechRecognizer.Result result = SpeechRecognizer.transcribe(context, seconds);
        if (!result.ok) return "{\"ok\":false,\"error\":" + quote(result.error) + "}";
        return "{\"ok\":true,\"text\":" + quote(result.text) + "}";
    }

    private String captureScreen() {
        byte[] png = ShellExec.runBytes(new String[]{"screencap", "-p"});
        if (png == null || png.length == 0) {
            return "{\"ok\":false,\"error\":\"screenshot unavailable (Shizuku required)\"}";
        }
        String base64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
        return "{\"ok\":true,\"base64\":" + quote(base64) + ",\"bytes\":" + png.length + "}";
    }

    private String capturePhoto(boolean front, int quality) {
        CameraCapture.Result result = new CameraCapture(context).capture(front, quality);
        if (!result.ok) return "{\"ok\":false,\"error\":" + quote(result.error) + "}";
        return "{\"ok\":true,\"path\":" + quote(result.path)
                + ",\"base64\":" + quote(result.base64) + "}";
    }

    private static boolean extractBool(String json, String key, boolean fallback) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return fallback;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return fallback;
        for (int i = colon + 1; i < Math.min(json.length(), colon + 10); i++) {
            char c = json.charAt(i);
            if (c == 't' || c == 'f') return c == 't';
        }
        return fallback;
    }

    private static String extractString(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(unescape(c));
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long extractLong(String json, String key, long fallback) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return fallback;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-') sb.append(c);
            else if (sb.length() > 0) break;
        }
        try {
            return sb.length() > 0 ? Long.parseLong(sb.toString()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static char unescape(char c) {
        switch (c) {
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            default: return c;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }
}
