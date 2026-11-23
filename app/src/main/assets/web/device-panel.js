/*
 * DshWebView Device Tool Panel
 * ----------------------------
 * A floating quick-action panel exposing the Android capabilities that this
 * project added on top of dsh (MCP tool layer). It calls the native side via
 * the DshDevice JavascriptInterface bridge (no CORS, no MCP handshake needed).
 *
 * Non-destructive: injected as an independent overlay; does not touch dsh DOM.
 */
(function () {
  'use strict';
  if (window.__dshDevicePanel && window.__dshDevicePanel.__installed) return;

  var FAB_ID = 'dsh-tool-fab';
  var PANEL_ID = 'dsh-tool-panel';
  var CSS_ID = 'dsh-tool-panel-css';
  var TOAST_ID = 'dsh-tool-toast';
  var VIEWER_ID = 'dsh-tool-viewer';

  // ---- bridge callback plumbing -------------------------------------------
  var seq = 0;
  var pending = {};
  window.__dshToolCb = function (id, json) {
    var r = pending[id];
    if (!r) return;
    delete pending[id];
    try { r.resolve(JSON.parse(json)); } catch (e) { r.reject(new Error(json)); }
  };

  function callTool(tool, args) {
    return new Promise(function (resolve, reject) {
      if (!window.DshDevice || !window.DshDevice.callDeviceTool) {
        reject(new Error('device bridge unavailable'));
        return;
      }
      var id = 'cb' + (++seq);
      pending[id] = { resolve: resolve, reject: reject };
      var argsJson = JSON.stringify(args || {});
      try {
        window.DshDevice.callDeviceTool(tool, argsJson, id);
      } catch (e) {
        delete pending[id];
        reject(e);
      }
      setTimeout(function () {
        if (pending[id]) { delete pending[id]; reject(new Error('timeout')); }
      }, 35000);
    });
  }

  // ---- UI helpers ---------------------------------------------------------
  function toast(msg, isError) {
    var t = document.getElementById(TOAST_ID);
    if (!t) {
      t = document.createElement('div');
      t.id = TOAST_ID;
      (document.body || document.documentElement).appendChild(t);
    }
    t.textContent = msg;
    t.className = isError ? 'err' : '';
    t.style.opacity = '1';
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.style.opacity = '0'; }, 3200);
  }

  function showImage(dataB64, mime, caption) {
    var v = document.getElementById(VIEWER_ID);
    if (!v) {
      v = document.createElement('div');
      v.id = VIEWER_ID;
      v.innerHTML = '<div class="bar"><span class="cap"></span><span class="close">✕</span></div><img alt="preview"/>';
      (document.body || document.documentElement).appendChild(v);
      v.querySelector('.close').addEventListener('click', function (e) {
        e.stopPropagation(); v.style.display = 'none';
      });
    }
    v.querySelector('img').src = 'data:' + (mime || 'image/jpeg') + ';base64,' + dataB64;
    v.querySelector('.cap').textContent = caption || '预览';
    v.style.display = 'flex';
  }

  function findImage(res) {
    if (!res) return null;
    var arr = res.content || (res.result && res.result.content);
    if (Array.isArray(arr)) {
      for (var i = 0; i < arr.length; i++) {
        if (arr[i] && arr[i].type === 'image' && arr[i].data) return arr[i];
      }
    }
    // DeviceBridge direct responses use image_b64.
    if (res.image_b64) return { data: res.image_b64, mimeType: res.mime_type || 'image/jpeg' };
    if (res.result && res.result.image_b64) {
      return { data: res.result.image_b64, mimeType: res.result.mime_type || 'image/jpeg' };
    }
    return null;
  }

  function firstText(res) {
    if (!res) return '';
    if (typeof res.text === 'string') return res.text;
    if (res.error) return '错误: ' + res.error;
    var arr = res.content || (res.result && res.result.content);
    if (Array.isArray(arr)) {
      for (var i = 0; i < arr.length; i++) if (arr[i] && arr[i].type === 'text') return arr[i].text;
    }
    return JSON.stringify(res).slice(0, 160);
  }

  // ---- tool actions --------------------------------------------------------
  var ACTIONS = {
    battery: function () {
      return callTool('get_battery', {}).then(function (r) {
        var pct = r.level_percent != null ? r.level_percent : '?';
        toast('电量 ' + pct + '% · ' + (r.charging ? '充电中' : '未充电'));
      });
    },
    vibrate: function () {
      return callTool('vibrate', { duration_ms: 250 }).then(function (r) {
        toast(r.ok === false ? ('震动失败: ' + (r.error || '')) : '已震动 250ms');
      });
    },
    notify: function () {
      return callTool('notify', { title: 'DSH WebView', message: '来自设备工具面板的测试通知' }).then(function (r) {
        toast(r.ok === false ? ('通知失败: ' + (r.error || '')) : '已发送测试通知');
      });
    },
    clipboard: function () {
      return callTool('get_clipboard', {}).then(function (r) {
        var txt = r.text != null ? r.text : firstText(r);
        toast('剪贴板: ' + (txt ? String(txt).slice(0, 80) : '(空)'));
      });
    },
    photo: function () {
      toast('正在拍照…');
      return callTool('capture_photo', { front: false, quality: 80 }).then(function (r) {
        var img = findImage(r);
        if (img) { showImage(img.data, img.mimeType, '摄像头照片'); toast('拍照成功'); }
        else toast(firstText(r) || '拍照完成', !!r.error);
      });
    },
    screen: function () {
      toast('正在截图…');
      return callTool('capture_screen', {}).then(function (r) {
        var img = findImage(r);
        if (img) { showImage(img.data, img.mimeType, '屏幕截图'); toast('截图成功'); }
        else toast(firstText(r) || '截图完成', !!r.error);
      });
    }
  };

  // ---- build panel ---------------------------------------------------------
  var TOOLS = [
    { id: 'battery', icon: '▣', label: '电量' },
    { id: 'vibrate', icon: '≋', label: '震动' },
    { id: 'notify', icon: '◔', label: '通知' },
    { id: 'clipboard', icon: '⎘', label: '剪贴板' },
    { id: 'photo', icon: '◉', label: '拍照' },
    { id: 'screen', icon: '▤', label: '截图' }
  ];

  var STYLE = [
    '#' + FAB_ID + '{position:fixed;z-index:2147482900;right:14px;bottom:calc(var(--dsh-safe-bottom, 0px) + 118px);',
    'width:46px;height:46px;border-radius:14px;border:0.5px solid rgba(110,190,255,0.4);',
    'background:linear-gradient(145deg,rgba(12,28,48,0.94),rgba(16,42,66,0.94));backdrop-filter:blur(10px);',
    'color:#7dd3fc;box-shadow:0 6px 22px rgba(0,0,0,0.4);display:flex;align-items:center;justify-content:center;',
    'font-size:20px;cursor:pointer;-webkit-tap-highlight-color:transparent;opacity:0.9;transition:transform .15s;}',
    '#' + FAB_ID + ':active{transform:scale(0.9);}',
    '#' + PANEL_ID + '{position:fixed;z-index:2147482900;right:14px;bottom:calc(var(--dsh-safe-bottom, 0px) + 172px);',
    'width:186px;padding:10px;border-radius:16px;border:0.5px solid rgba(110,190,255,0.35);',
    'background:linear-gradient(160deg,rgba(10,20,36,0.97),rgba(14,28,46,0.97));backdrop-filter:blur(16px);',
    'box-shadow:0 12px 40px rgba(0,0,0,0.5);display:none;grid-template-columns:1fr 1fr;gap:8px;}',
    '#' + PANEL_ID + '.open{display:grid;}',
    '#' + PANEL_ID + ' .tool{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;',
    'height:60px;border-radius:12px;border:0.5px solid rgba(110,190,255,0.18);background:rgba(56,189,248,0.06);',
    'color:#cfe4f7;font-size:18px;cursor:pointer;-webkit-tap-highlight-color:transparent;}',
    '#' + PANEL_ID + ' .tool:active{background:rgba(56,189,248,0.2);transform:scale(0.96);}',
    '#' + PANEL_ID + ' .tool .lbl{font-size:11px;opacity:0.85;}',
    '#' + PANEL_ID + ' .hd{grid-column:1/-1;text-align:center;font-size:11px;letter-spacing:.08em;color:#7dd3fc;opacity:.8;padding:2px 0 4px;}',
    '#' + TOAST_ID + '{position:fixed;z-index:2147482950;left:50%;bottom:calc(var(--dsh-safe-bottom, 0px) + 150px);',
    'transform:translateX(-50%);background:rgba(10,24,40,0.95);color:#e6eefb;border:0.5px solid rgba(110,190,255,0.3);',
    'padding:9px 14px;border-radius:12px;font:13px/1.4 system-ui,sans-serif;max-width:84vw;text-align:center;',
    'box-shadow:0 8px 26px rgba(0,0,0,0.4);opacity:0;transition:opacity .2s;pointer-events:none;}',
    '#' + TOAST_ID + '.err{background:rgba(90,20,20,0.95);border-color:rgba(248,113,113,0.5);}',
    '#' + VIEWER_ID + '{position:fixed;z-index:2147482990;inset:0;background:rgba(2,6,14,0.94);display:none;',
    'flex-direction:column;align-items:center;justify-content:center;padding:20px;}',
    '#' + VIEWER_ID + ' .bar{position:absolute;top:calc(var(--dsh-safe-top, 0px) + 12px);left:14px;right:14px;',
    'display:flex;justify-content:space-between;color:#cfe4f7;font:14px system-ui;}',
    '#' + VIEWER_ID + ' .bar .close{cursor:pointer;padding:4px 10px;border-radius:8px;background:rgba(56,189,248,0.14);}',
    '#' + VIEWER_ID + ' img{max-width:100%;max-height:78vh;border-radius:12px;border:0.5px solid rgba(110,190,255,0.3);}'
  ].join('');

  function install() {
    if (document.getElementById(CSS_ID)) return;
    var css = document.createElement('style');
    css.id = CSS_ID; css.textContent = STYLE;
    (document.head || document.documentElement).appendChild(css);

    var fab = document.createElement('div');
    fab.id = FAB_ID; fab.textContent = '⚙'; fab.title = '设备工具';
    fab.addEventListener('click', function (e) {
      e.stopPropagation();
      var p = document.getElementById(PANEL_ID);
      p.classList.toggle('open');
    });
    (document.body || document.documentElement).appendChild(fab);

    var panel = document.createElement('div');
    panel.id = PANEL_ID;
    var hd = document.createElement('div'); hd.className = 'hd'; hd.textContent = '设备工具';
    panel.appendChild(hd);
    TOOLS.forEach(function (t) {
      var b = document.createElement('div');
      b.className = 'tool';
      b.innerHTML = '<span>' + t.icon + '</span><span class="lbl">' + t.label + '</span>';
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        ACTIONS[t.id]().catch(function (err) { toast(t.label + '失败: ' + err.message, true); });
      });
      panel.appendChild(b);
    });
    (document.body || document.documentElement).appendChild(panel);

    // Close panel when tapping elsewhere.
    document.addEventListener('click', function (e) {
      if (!panel.contains(e.target) && e.target !== fab) panel.classList.remove('open');
    });
  }

  var api = { __installed: true };
  window.__dshDevicePanel = api;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install);
  else install();
})();
