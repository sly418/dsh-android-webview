package com.deepseek.dshwebview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.Window;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ScrollView;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/** System WebView front-end for the locally running DeepSeek Harness Web UI. */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_NOTIFICATION = 1002;

    private WebView webView;
    private LinearLayout statusBar;
    private TextView statusText;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private TextView errorText;
    private Button retryButton;
    private Button logButton;

    private DshService service;
    private String loadedUrl;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean shizukuGrantHandled;
    private boolean recoveringSession;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((DshService.LocalBinder) binder).getService();
            service.setListener(serviceListener);
            applyState(service.getState(), service.getMessage());
            if (service.getUrl() != null) loadUrl(service.getUrl());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final DshService.Listener serviceListener = new DshService.Listener() {
        @Override
        public void onState(DshService.State state, String message) {
            applyState(state, message);
        }

        @Override
        public void onUrl(String url) {
            loadUrl(url);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // Always restart on (re)grant: Shizuku may have restarted and
                    // re-authorized while we were in shell mode. The service's
                    // generation gate makes repeated restarts harmless.
                    if (shizukuGrantHandled && "shizuku".equals(AppState.getMode(this))) {
                        return; // already running in shell mode, nothing changed
                    }
                    shizukuGrantHandled = true;
                    AppLogger.permission(this, "用户授予 Shizuku 权限");
                    Toast.makeText(this, "Shizuku 已授权，正在以 shell 权限重启…", Toast.LENGTH_SHORT).show();
                    if (service != null) {
                        loadedUrl = null;
                        webView.loadUrl("about:blank");
                        service.restart();
                    }
                } else {
                    AppLogger.permission(this, "未获得 Shizuku 授权，使用普通模式");
                    Toast.makeText(this, "未获得 Shizuku 授权，将使用普通模式", Toast.LENGTH_LONG).show();
                }
            };

    /**
     * Shizuku service can die/restart (e.g. app update). When its binder
     * returns we re-check permission; if granted, restart the runtime so the
     * app moves back to shell mode automatically.
     */
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        // Mark that a re-grant should be acted on even if we previously did so.
        shizukuGrantHandled = false;
        AppLogger.permission(this, "Shizuku binder 断开，等待重新授权");
    };

    // The binder arrives once per process. Only ask for permission, never
    // restart here — the permission callback (or normal startup) handles that.
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () ->
            requestShizukuPermission();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        statusBar = findViewById(R.id.statusBar);
        statusText = findViewById(R.id.statusText);
        progress = findViewById(R.id.progress);
        errorPanel = findViewById(R.id.errorPanel);
        errorText = findViewById(R.id.errorText);
        retryButton = findViewById(R.id.retryButton);
        logButton = findViewById(R.id.logButton);

        setupEdgeToEdge();
        setupTextScale();

        logButton.setOnClickListener(v -> showLogDialog());

        retryButton.setOnClickListener(v -> {
            if (service != null) {
                loadedUrl = null;
                webView.loadUrl("about:blank");
                service.restart();
            }
        });

        configureWebView();
        requestNotificationPermission();

        // Detect a previous session that was interrupted (service killed while
        // in background). The service will restart; we just inform the user.
        int attempts = AppState.getLaunchAttempts(this);
        String prevState = AppState.getLastState(this);
        recoveringSession = attempts > 1 && !"READY".equals(prevState);

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
            requestShizukuPermission();
        } catch (Throwable ignored) {
            // Shizuku provider missing (e.g. stale install) — normal mode still works.
        }

        startService(new Intent(this, DshService.class));
        bindService(new Intent(this, DshService.class), connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Edge-to-edge so the page can extend behind the translucent status bar.
     * dsh's own top toolbar is fixed at top:0 and does NOT handle safe areas,
     * so injectMobileCss() shifts it below the inset via env(safe-area-inset-top).
     * On gesture navigation we intentionally reserve no bottom inset.
     */
    private void setupEdgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        View decor = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
        // Note: no top padding on the WebView here. Shifting fixed page elements
        // is done in CSS; padding the WebView would not move fixed elements.
    }

    /** Slightly larger text and touch targets for comfortable phone use. */
    private void setupTextScale() {
        // WebView text zoom is applied on top of the page's own responsive units.
        webView.getSettings().setTextZoom(112);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        // dsh's Web UI is responsive; render at device width and start at 100%
        // instead of shrinking the whole desktop layout to fit the screen.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMinimumFontSize(10);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setInitialScale(100);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocal(uri)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    Toast.makeText(MainActivity.this, "无法打开外部链接", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setProgress(10);
                hideWithAnim(errorPanel, R.anim.fade_out);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setProgress(100);
                injectMobileCss();
                hideWithAnim(statusBar, R.anim.slide_out_top);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("页面加载失败：" + error.getDescription());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                }
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(r);
            } catch (Exception e) {
                Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean isLocal(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    private void loadUrl(String url) {
        if (TextUtils.isEmpty(url) || url.equals(loadedUrl)) return;
        loadedUrl = url;
        hideWithAnim(errorPanel, R.anim.fade_out);
        showWithAnim(statusBar, R.anim.slide_in_top);
        statusText.setText(R.string.status_ready);
        webView.loadUrl(url);
    }

    private void applyState(DshService.State state, String message) {
        if (state == null) return;
        switch (state) {
            case READY:
                if (recoveringSession) {
                    recoveringSession = false;
                    Toast.makeText(this, "已从后台恢复，运行环境正常", Toast.LENGTH_SHORT).show();
                }
                showWithAnim(statusBar, R.anim.slide_in_top);
                statusText.setText(R.string.status_ready);
                hideWithAnim(errorPanel, R.anim.fade_out);
                break;
            case FAILED:
                showWithAnim(statusBar, R.anim.slide_in_top);
                statusText.setText(R.string.status_failed);
                showError(message);
                break;
            case INSTALLING:
            case STARTING:
                showWithAnim(statusBar, R.anim.slide_in_top);
                statusText.setText(TextUtils.isEmpty(message) ? getString(R.string.status_starting) : message);
                progress.setProgress(30);
                hideWithAnim(errorPanel, R.anim.fade_out);
                break;
            default:
                showWithAnim(statusBar, R.anim.slide_in_top);
                statusText.setText(R.string.status_starting);
                break;
        }
    }

    /** Show a view with an entrance animation if it is currently hidden. */
    private void showWithAnim(View v, int animRes) {
        v.clearAnimation();
        if (v.getVisibility() == View.VISIBLE) return;
        v.setVisibility(View.VISIBLE);
        v.startAnimation(AnimationUtils.loadAnimation(this, animRes));
    }

    /** Hide a view with an exit animation, applying visibility at the end. */
    private void hideWithAnim(View v, int animRes) {
        if (v.getVisibility() != View.VISIBLE) return;
        v.clearAnimation();
        Animation anim = AnimationUtils.loadAnimation(this, animRes);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) { }
            @Override public void onAnimationRepeat(Animation a) { }
            @Override public void onAnimationEnd(Animation a) {
                // Only hide if no newer animation changed the state meanwhile.
                if (v.getAnimation() == a) v.setVisibility(View.GONE);
            }
        });
        v.startAnimation(anim);
    }

    /**
     * Inject the real status-bar height (in CSS px) as --dsh-safe-top. Android
     * WebView's env(safe-area-inset-top) is unreliable here, so we measure the
     * bar natively and set an inline var on <html> that overrides the stylesheet
     * fallback. dsh's bundled CSS then shifts its top-pinned layers accordingly.
     */
    private void injectMobileCss() {
        int statusBarPx = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarPx = getResources().getDimensionPixelSize(resId);
        }
        // Convert device px to CSS px (density-independent).
        float density = getResources().getDisplayMetrics().density;
        int safeCssPx = density > 0 ? Math.round(statusBarPx / density) : 0;
        final int safe = safeCssPx;
        String js = "(function(){try{"
                + "var mv=document.querySelector('meta[name=viewport]');"
                + "if(mv){var c=mv.getAttribute('content')||'';"
                + "if(c.indexOf('viewport-fit')<0){mv.setAttribute('content',c+',viewport-fit=cover');}}"
                + "document.documentElement.style.setProperty('--dsh-safe-top','" + safe + "px');"
                + "document.documentElement.style.setProperty('--dsh-safe-bottom','0px');"
                // Push the whole document below the status bar so dsh's top
                // toolbar buttons (hamburger / split-view) are not covered.
                + "document.documentElement.style.paddingTop='" + safe + "px';"
                + "document.documentElement.style.boxSizing='border-box';"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private void showError(String message) {
        errorText.setText(getString(R.string.status_failed) + "\n" + (message == null ? "" : message));
        showWithAnim(errorPanel, R.anim.pop_in);
    }

    private void showLogDialog() {
        String text = service == null ? "服务尚未连接" : service.getLogTail();
        if (TextUtils.isEmpty(text)) text = "暂无日志";
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextSize(12);
        tv.setPadding(24, 16, 24, 16);
        tv.setText(text);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.view_log)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.isPreV11() && Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1003);
            }
        } catch (Throwable ignored) {
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Toast.makeText(this, R.string.notification_permission_rationale, Toast.LENGTH_LONG).show();
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            String dataString = data.getDataString();
            if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (dataString != null) {
                results = new Uri[]{Uri.parse(dataString)};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.removeListener(serviceListener);
            unbindService(connection);
            service = null;
        }
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
