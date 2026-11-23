/*
 * DshWebView Tech Skin
 * --------------------
 * Non-destructive visual skin layered on top of dsh's Web UI.
 * It overrides dsh's design tokens (the --dsw-alias-* custom properties) via
 * !important CSS rules and inline style, WITHOUT touching dsh's DOM/logic.
 * A floating toggle switches between "Tech" and "Original" and remembers the
 * choice in localStorage.
 *
 * This file is injected by MainActivity.injectWebSkin() after page load.
 */
(function () {
  'use strict';
  if (window.__dshTechSkin && window.__dshTechSkin.__installed) return;

  var STYLE_ID = 'dsh-tech-skin-style';
  var TOGGLE_ID = 'dsh-tech-skin-toggle';
  var STORAGE_KEY = 'dsh_skin_mode_v2'; // 'tech' | 'original' (v2: fresh default = tech)

  // ---- Tech palette: deep-space navy + cyan/blue neon accents -------------
  var TOKENS = {
    // backgrounds
    '--dsw-alias-bg-base': '#0a0f1a',
    '--dsw-alias-bg-layer-1': '#0e1524',
    '--dsw-alias-bg-layer-2': '#131d30',
    '--dsw-alias-bg-layer-3': '#1a273e',
    '--dsw-alias-bg-layer-4': '#22314c',
    '--dsw-alias-bg-mask-1': 'rgba(3,7,15,0.74)',
    '--dsw-alias-bg-skeleton': '#1a273e',
    '--dsw-alias-bg-module-platform': '#0c1320',
    // borders
    '--dsw-alias-border-l1': 'rgba(110,190,255,0.10)',
    '--dsw-alias-border-l2': 'rgba(110,190,255,0.16)',
    '--dsw-alias-border-l2-darkmode-thin': 'rgba(110,190,255,0.14)',
    '--dsw-alias-border-l3': 'rgba(110,190,255,0.24)',
    '--dsw-alias-border-l4': 'rgba(110,190,255,0.34)',
    // brand / buttons
    '--dsw-alias-brand-primary': '#38bdf8',
    '--dsw-alias-brand-primary-new-colorprimary-new-color': '#38bdf8',
    '--dsw-alias-button-primary-fill': '#0891b2',
    '--dsw-alias-button-primary-hover': '#06b6d4',
    '--dsw-alias-button-tool-bar-fill': 'rgba(56,189,248,0.10)',
    '--dsw-alias-button-tool-bar-hover': 'rgba(56,189,248,0.18)',
    '--dsw-alias-button-ghost-active-border': 'rgba(56,189,248,0.5)',
    '--dsw-alias-button-ghost-active-fill': 'rgba(56,189,248,0.12)',
    // text
    '--dsw-alias-label-primary': '#e6eefb',
    '--dsw-alias-label-primary-foreground': '#04121e',
    '--dsw-alias-label-secondary': '#a9bad2',
    '--dsw-alias-label-tertiary': '#7186a3',
    '--dsw-alias-label-caption': '#5c708c',
    '--dsw-alias-label-dimmed': '#43546d',
    '--dsw-alias-label-shimmer': 'rgba(180,215,255,0.10)',
    '--dsw-alias-link': '#38bdf8',
    '--dsw-alias-menu-icon': '#a9bad2',
    // interactive states
    '--dsw-alias-interactive-bg-hover': 'rgba(56,189,248,0.10)',
    '--dsw-alias-interactive-bg-active': 'rgba(56,189,248,0.18)',
    '--dsw-alias-interactive-bg-hover-danger': 'rgba(248,113,113,0.12)',
    '--dsw-alias-switch-thumb': '#e6eefb',
    // menu / hovercard / toast / tooltip
    '--dsw-alias-menu-group-header-fill': 'rgba(56,189,248,0.08)',
    '--dsw-alias-hovercard-bg': '#162236',
    '--dsw-alias-hovercard-label-primary': '#e6eefb',
    '--dsw-alias-toast-bg': '#162236',
    '--dsw-alias-toast-label': '#e6eefb',
    '--dsw-alias-toast-action': '#38bdf8',
    '--dsw-alias-tooltip-bg': '#162236',
    '--dsw-alias-tooltip-label': '#e6eefb',
    '--dsw-alias-tooltip-key-bg': 'rgba(110,190,255,0.16)',
    // scrollbar
    '--dsw-alias-scrollbar-bg-l2': 'rgba(110,190,255,0.22)',
    '--dsw-alias-scrollbar-hover-l2': 'rgba(110,190,255,0.38)',
    // state colors
    '--dsw-alias-state-business-primary': '#818cf8',
    '--dsw-alias-state-error-primary': '#f87171',
    '--dsw-alias-state-idle-primary': '#64748b',
    '--dsw-alias-state-success-primary': '#34d399',
    '--dsw-alias-state-success-tertiary': 'rgba(52,211,153,0.16)',
    '--dsw-alias-state-warn-label': '#fbbf24',
    '--dsw-alias-state-warn-primary': '#fbbf24',
    '--dsw-alias-state-warn-tertiary': 'rgba(251,191,36,0.16)',
    // code / diff / markdown
    '--dsw-alias-code-diff-added': 'rgba(52,211,153,0.16)',
    '--dsw-alias-code-diff-deleted': 'rgba(248,113,113,0.16)',
    '--dsw-alias-file-diff-added-bg': 'rgba(52,211,153,0.12)',
    '--dsw-alias-file-diff-added-marker': '#34d399',
    '--dsw-alias-file-diff-deleted-bg': 'rgba(248,113,113,0.12)',
    '--dsw-alias-file-diff-deleted-marker': '#f87171',
    '--dsw-alias-markdown-code-block': '#0c1320',
    '--dsw-alias-markdown-code-block-banner': '#131d30',
    '--dsw-alias-markdown-inline-code': '#7dd3fc',
    '--dsw-alias-markdown-tag': '#38bdf8',
    '--dsw-alias-json-tree-hover': 'rgba(56,189,248,0.08)',
    '--dsw-alias-json-tree-icon': '#7186a3',
    '--dsw-alias-json-tree-keyword': '#c084fc',
    '--dsw-alias-json-tree-number': '#fbbf24',
    '--dsw-alias-json-tree-property': '#7dd3fc',
    '--dsw-alias-json-tree-punctuation': '#7186a3',
    '--dsw-alias-json-tree-string': '#34d399'
  };

  // Extra structural tweaks that complement the palette (still non-destructive).
  var EXTRA_CSS = [
    'html.dsh-tech { color-scheme: dark !important; }',
    /* subtle cyan glow on the primary send button */
    'html.dsh-tech button[class*="_primary_"] { box-shadow: 0 0 0 1px rgba(56,189,248,0.22), 0 4px 18px rgba(8,145,178,0.20) !important; }',
    /* crisp thin borders on cards/panels */
    'html.dsh-tech [class*="_bubble_"], html.dsh-tech [class*="_card_"] { border: 0.5px solid rgba(110,190,255,0.14) !important; }',
    /* scrollbar theming */
    'html.dsh-tech ::-webkit-scrollbar { width: 8px; height: 8px; }',
    'html.dsh-tech ::-webkit-scrollbar-thumb { background: rgba(110,190,255,0.24) !important; border-radius: 8px; }',
    'html.dsh-tech ::-webkit-scrollbar-track { background: transparent !important; }',
    /* smoother font rendering */
    'html.dsh-tech body { -webkit-font-smoothing: antialiased; }'
  ].join('\n');

  function buildCssText() {
    // body.dsh-tech (specificity 0,1,1) beats dsh's own body{...} (0,0,1),
    // so our token values win even though dsh redeclares them on body.
    var parts = ['body.dsh-tech{'];
    for (var k in TOKENS) {
      if (Object.prototype.hasOwnProperty.call(TOKENS, k)) {
        parts.push(k + ':' + TOKENS[k] + ' !important;');
      }
    }
    parts.push('}');
    parts.push(EXTRA_CSS);
    return parts.join('\n');
  }

  function currentMode() {
    // Tech skin is the default look for a fresh install; users can switch back.
    try { return localStorage.getItem(STORAGE_KEY) || 'tech'; }
    catch (e) { return 'tech'; }
  }

  function saveMode(mode) {
    try { localStorage.setItem(STORAGE_KEY, mode); } catch (e) {}
  }

  // dsh ships an OFFICIAL dark theme driven by the body[data-ds-dark-theme]
  // attribute. Enabling it is far more reliable than redefining 71 tokens
  // (which dsh's own <style> re-declares on body). Tech mode = official dark
  // theme + a few cyan/neon accents layered on top.
  function setDarkAttr(on) {
    var body = document.body;
    if (!body) return;
    try {
      if (on) body.setAttribute('data-ds-dark-theme', '');
      else body.removeAttribute('data-ds-dark-theme');
    } catch (e) {}
  }

  function setAll(el) {
    if (!el || !el.style) return;
    for (var k in TOKENS) {
      if (Object.prototype.hasOwnProperty.call(TOKENS, k)) {
        try { el.style.setProperty(k, TOKENS[k], 'important'); } catch (e) {}
      }
    }
  }

  function applyTokens() {
    var html = document.documentElement;
    var body = document.body;
    if (html) html.classList.add('dsh-tech');
    if (body) body.classList.add('dsh-tech');
    setDarkAttr(true);
    var style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      style.type = 'text/css';
    }
    style.textContent = buildCssText();
    var head = document.head || html;
    if (style.parentNode !== head || style.nextSibling) head.appendChild(style);
    diagnose();
  }

  function buildAccentCss() {
    var accent = {
      '--dsw-alias-brand-primary': '#38bdf8',
      '--dsw-alias-brand-primary-new-colorprimary-new-color': '#38bdf8',
      '--dsw-alias-button-primary-fill': '#0891b2',
      '--dsw-alias-button-primary-hover': '#06b6d4',
      '--dsw-alias-link': '#38bdf8'
    };
    var parts = ['body[data-ds-dark-theme]{'];
    for (var k in accent) {
      if (Object.prototype.hasOwnProperty.call(accent, k)) parts.push(k + ':' + accent[k] + ' !important;');
    }
    parts.push('}');
    parts.push(EXTRA_CSS);
    return parts.join('\n');
  }

  // Diagnostic overlay: only shown if the effective token is NOT our tech value.
  // Makes field debugging possible without rebuilding.
  function diagnose() {
    try {
      var body = document.body;
      var hasClass = body && body.classList && body.classList.contains('dsh-tech');
      var bg = body ? getComputedStyle(body).backgroundColor : '?';
      // Parse rgb; consider "dark" if all channels < 70.
      var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(bg);
      var dark = m && (+m[1] < 70 && +m[2] < 70 && +m[3] < 70);
      var d = document.getElementById('dsh-tech-diag');
      if (!hasClass || !dark) {
        if (!d) {
          d = document.createElement('div');
          d.id = 'dsh-tech-diag';
          d.style.cssText = 'position:fixed;left:8px;bottom:120px;z-index:2147483000;background:#b91c1c;color:#fff;font:11px monospace;padding:6px 8px;border-radius:6px;max-width:92vw;opacity:.92';
          (body || document.documentElement).appendChild(d);
        }
        d.textContent = 'class=' + hasClass + ' dark=' + !!dark + ' body bg=' + bg;
      } else {
        if (d) d.parentNode.removeChild(d);
      }
    } catch (e) {}
  }

  function clearAll(el) {
    if (!el || !el.style) return;
    for (var k in TOKENS) {
      if (Object.prototype.hasOwnProperty.call(TOKENS, k)) {
        try { el.style.removeProperty(k); } catch (e) {}
      }
    }
  }

  function clearTokens() {
    setDarkAttr(false);
    if (document.documentElement) document.documentElement.classList.remove('dsh-tech');
    if (document.body) document.body.classList.remove('dsh-tech');
    var style = document.getElementById(STYLE_ID);
    if (style) style.textContent = '';
    clearAll(document.documentElement);
    clearAll(document.body);
    try {
      var nodes = document.querySelectorAll('[style*="--dsw-"]');
      for (var i = 0; i < nodes.length; i++) clearAll(nodes[i]);
    } catch (e) {}
    var d = document.getElementById('dsh-tech-diag');
    if (d) d.parentNode.removeChild(d);
  }

  function updateToggle(mode) {
    var btn = document.getElementById(TOGGLE_ID);
    if (!btn) return;
    var tech = mode === 'tech';
    btn.setAttribute('data-mode', tech ? 'tech' : 'original');
    btn.setAttribute('aria-label', tech ? 'Switch to original UI' : 'Switch to tech UI');
    btn.title = tech ? '当前：科技风（点击恢复原版）' : '当前：原版（点击切换科技风）';
    var dot = btn.querySelector('.dot');
    if (dot) dot.textContent = tech ? '✦' : '◐';
  }

  function applyMode(mode) {
    var tech = mode === 'tech';
    var html = document.documentElement;
    if (tech) {
      html.classList.add('dsh-tech');
      applyTokens();
    } else {
      html.classList.remove('dsh-tech');
      clearTokens();
    }
    saveMode(mode);
    updateToggle(mode);
    // Keep the native status-bar icons legible on both themes.
    try {
      if (window.DshUiTheme && window.DshUiTheme.setStatusBarLight) {
        window.DshUiTheme.setStatusBarLight(!tech); // original=light UI -> dark icons
      }
    } catch (e) {}
  }

  function makeToggle() {
    if (document.getElementById(TOGGLE_ID)) return;
    var btn = document.createElement('button');
    btn.id = TOGGLE_ID;
    btn.type = 'button';
    btn.innerHTML = '<span class="dot" style="font-size:17px;line-height:1">◐</span>';
    var css = document.createElement('style');
    css.id = TOGGLE_ID + '-css';
    css.textContent = [
      '#' + TOGGLE_ID + '{position:fixed;z-index:2147483000;right:12px;top:calc(var(--dsh-safe-top, 0px) + 62px);',
      'width:38px;height:38px;border-radius:12px;border:0.5px solid rgba(110,190,255,0.35);',
      'background:rgba(10,21,38,0.86);backdrop-filter:blur(10px);color:#38bdf8;',
      'box-shadow:0 4px 18px rgba(0,0,0,0.35);display:flex;align-items:center;justify-content:center;',
      'cursor:pointer;-webkit-tap-highlight-color:transparent;transition:transform .15s ease,opacity .15s ease;opacity:0.82;}',
      '#' + TOGGLE_ID + ':active{transform:scale(0.92);}',
      '#' + TOGGLE_ID + '[data-mode="tech"]{color:#7dd3fc;border-color:rgba(125,211,252,0.55);',
      'box-shadow:0 0 0 1px rgba(56,189,248,0.18),0 4px 18px rgba(8,145,178,0.28);opacity:0.95;}',
      '#' + TOGGLE_ID + ' .dot{pointer-events:none;}'
    ].join('');
    (document.head || document.documentElement).appendChild(css);
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var next = currentMode() === 'tech' ? 'original' : 'tech';
      applyMode(next);
    });
    (document.body || document.documentElement).appendChild(btn);
  }

  function install() {
    makeToggle();
    applyMode(currentMode());
    // dsh is a SPA and re-injects tokens on route changes / late paints;
    // re-assert the FULL mode (class + tokens + toggle) so a rebuilt <body>
    // cannot silently drop the skin.
    var reassert = function () { applyMode(currentMode()); };
    var attempts = [150, 500, 1200, 2500, 4500];
    for (var i = 0; i < attempts.length; i++) {
      setTimeout(reassert, attempts[i]);
    }
    try {
      var observer = new MutationObserver(function () {
        clearTimeout(window.__dshTechSkin._t);
        window.__dshTechSkin._t = setTimeout(function () { applyMode(currentMode()); }, 250);
      });
      observer.observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class'] });
      if (document.body) observer.observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] });
    } catch (e) {}
  }

  var api = { __installed: true, apply: applyMode, get: currentMode };
  window.__dshTechSkin = api;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install);
  } else {
    install();
  }
})();
