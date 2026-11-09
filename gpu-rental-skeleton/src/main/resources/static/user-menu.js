/* 全站账号菜单（官网 + 控制台共用）
   ------------------------------------------------------------
   头像：白底 + 1px 描边，hover 加深一档，按下/展开再深一档
   面板：身份 / 余额 / 外观 / 退出登录
   依赖：localStorage.gu_user / gu_token / gu_theme
   用法：在本站任意带 .nav-right 的页面引入即可，自动接管头像。
   ------------------------------------------------------------ */
(function () {
  'use strict';

  var LS_USER = 'gu_user';
  var LS_TOKEN = 'gu_token';
  var LS_THEME = 'gu_theme';
  var inited = false;

  var LV = 'fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"';
  var ICON = {
    sun: '<svg viewBox="0 0 24 24" ' + LV + '><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>',
    moon: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"/></svg>',
    auto: '<svg viewBox="0 0 24 24" ' + LV + '><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
    out: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/></svg>',
    user: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="7" r="4"/></svg>',
    key: '<svg viewBox="0 0 24 24" ' + LV + '><circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 8.8-8.8"/><path d="m16 4 3 3"/><path d="m13 7 3 3"/></svg>'
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* ---------------- 主题：全局唯一实现 ---------------- */
  /* 只有控制台外壳有完整深色皮肤；官网保持浅色，避免半深不深 */
  function consoleShell() {
    return !!(document.querySelector && document.querySelector('.console'));
  }
  function themeMode() {
    try { return localStorage.getItem(LS_THEME) || 'auto'; } catch (e) { return 'auto'; }
  }
  function themeResolved() {
    var m = themeMode();
    if (m === 'auto') {
      return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
    }
    return m;
  }
  function applyTheme() {
    var m = themeMode();
    if (consoleShell()) document.documentElement.setAttribute('data-theme', themeResolved());
    else document.documentElement.removeAttribute('data-theme');
    var lbl = document.getElementById('udThemeLbl');
    if (lbl) lbl.textContent = m === 'light' ? '浅色' : m === 'dark' ? '深色' : '跟随系统';
    var box = document.getElementById('udrop');
    if (!box) return;
    Array.prototype.forEach.call(box.querySelectorAll('.ud-themes button'), function (b) {
      var on = b.getAttribute('data-t') === m;
      b.classList.toggle('on', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }
  applyTheme();
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    if (mq.addEventListener) mq.addEventListener('change', applyTheme);
    else if (mq.addListener) mq.addListener(applyTheme);
  }

  window.TKTheme = {
    mode: themeMode,
    resolved: themeResolved,
    apply: applyTheme,
    set: function (m) {
      try { localStorage.setItem(LS_THEME, m); } catch (e) {}
      applyTheme();
    }
  };

  /* ---------------- 面板 ---------------- */
  function readUser() {
    try { return JSON.parse(localStorage.getItem(LS_USER) || 'null'); } catch (e) { return null; }
  }

  function buildPanel(U) {
    var name = U.displayName || U.username || '未登录';
    var initial = String(name).slice(0, 1).toUpperCase();
    var roleTxt = U.role === 'admin' ? '管理员' : U.role === 'ops' ? '运维' : '租户';

    var box = document.createElement('div');
    box.className = 'udrop';
    box.id = 'udrop';
    box.innerHTML =
      '<div class="ud-card" role="menu" aria-label="账号菜单">' +
        '<div class="ud-id">' +
          '<span class="ud-ava">' + esc(initial) + '<i class="ud-dot" title="在线"></i></span>' +
          '<div class="ud-nm">' +
            '<div class="ud-name"><b>' + esc(name) + '</b><span class="ud-badge">' + roleTxt + '</span></div>' +
            '<div class="ud-sub" id="udSub">' + esc(U.username || '') + '</div>' +
          '</div>' +
        '</div>' +
        '<div class="ud-bal" id="udBal" style="display:none">' +
          '<div class="ud-bal-top"><span>账户总余额 <i class="ud-q" title="余额来自平台账本，充值后实时更新">?</i></span>' +
          '<button class="ud-btn" type="button" id="udRecharge">充值</button></div>' +
          '<b class="ud-bal-num" id="udBalNum">—</b>' +
        '</div>' +
        '<div class="ud-sec"' + (consoleShell() ? '' : ' style="display:none"') + '>' +
          '<div class="ud-sec-t"><span>外观</span><span class="ud-sec-r" id="udThemeLbl">跟随系统</span></div>' +
          '<div class="ud-themes" role="group" aria-label="外观">' +
            '<button type="button" data-t="light" title="浅色">' + ICON.sun + '<span>浅色</span></button>' +
            '<button type="button" data-t="dark" title="深色">' + ICON.moon + '<span>深色</span></button>' +
            '<button type="button" data-t="auto" title="跟随系统">' + ICON.auto + '<span>自动</span></button>' +
          '</div>' +
        '</div>' +
        (typeof window.openPwd === 'function'
          ? '<button class="ud-item" type="button" id="udPwd">' + ICON.key + '<span>修改密码</span></button>'
          : '') +
        '<button class="ud-out" type="button" id="udOut" role="menuitem">' + ICON.out + '<span>退出登录</span></button>' +
      '</div>';
    document.body.appendChild(box);
    return box;
  }

  function init() {
    if (inited) return;
    var navRight = document.querySelector('.nav-right') ||
                   document.querySelector('.topbar .right') ||
                   document.querySelector('.usermenu');
    if (!navRight) return;
    inited = true;

    var U = readUser();
    var token = '';
    try { token = localStorage.getItem(LS_TOKEN) || ''; } catch (e) {}
    var authed = !!(U && token);

    /* 兼容四种历史结构：
       console 的 #navAva、portal/create 的 .nav-right .usermenu-btn、
       官网静态 .avatar、管理端 .topbar .right 里的头像胶囊 */
    var trigger = navRight.querySelector('#navAva') || navRight.querySelector('.usermenu-btn');
    if (!trigger) {
      var av = navRight.querySelector('.avatar');
      if (!av) { inited = false; return; }
      var b = document.createElement('button');
      b.type = 'button';
      b.id = 'navAva';
      b.className = 'nav-ava';
      av.parentNode.replaceChild(b, av);
      trigger = b;
    }
    if (!trigger.id) trigger.id = 'navAva';
    trigger.classList.add('nav-ava');
    trigger.setAttribute('role', 'button');
    trigger.setAttribute('aria-haspopup', 'menu');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.setAttribute('title', '账号菜单');

    var inner = trigger.querySelector('.avatar');
    if (authed) {
      var initial = esc(String(U.username || U.displayName || 'U').slice(0, 1).toUpperCase());
      if (inner) inner.textContent = initial;
      else trigger.textContent = initial;
    } else {
      if (inner) inner.innerHTML = ICON.user;
      else trigger.innerHTML = ICON.user;
      trigger.setAttribute('title', '登录 / 注册');
    }

    /* 旧版 .usermenu-pop 一律停用（portal/create 的内联 toggleMenu 仍在，但面板已被 CSS 隐藏） */
    var oldPop = document.getElementById('umPop');
    if (oldPop) oldPop.classList.remove('on');

    if (!authed) {
      trigger.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        location.href = '/login';
      });
      return;
    }

    var drop = buildPanel(U);
    var open = false;

    function place() {
      var r = trigger.getBoundingClientRect();
      var w = drop.offsetWidth || 300;
      var left = Math.min(Math.max(12, r.right - w), window.innerWidth - w - 12);
      drop.style.top = Math.round(r.bottom + 8) + 'px';
      drop.style.left = Math.round(left) + 'px';
      drop.style.right = 'auto';
    }
    function show() {
      place();
      drop.classList.add('open');
      open = true;
      trigger.setAttribute('aria-expanded', 'true');
      fill();
      applyTheme();
    }
    function hide() {
      if (!open) return;
      drop.classList.remove('open');
      open = false;
      trigger.setAttribute('aria-expanded', 'false');
    }
    function toggle(e) {
      if (e) { e.preventDefault(); e.stopPropagation(); }
      if (open) hide(); else show();
    }

    trigger.addEventListener('click', toggle);
    document.addEventListener('click', function (e) {
      if (open && !drop.contains(e.target) && !trigger.contains(e.target)) hide();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && open) { hide(); trigger.focus(); }
    });
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', function () { if (open) place(); });

    /* 余额 */
    var balBox = document.getElementById('udBal');
    var balNum = document.getElementById('udBalNum');
    var subEl = document.getElementById('udSub');
    function fill() {
      var uid = U.tenantId;
      if (!uid) {
        balBox.style.display = 'none';
        if (subEl) subEl.textContent = '平台账号 · ' + (U.username || '');
        return;
      }
      var tk = '';
      try { tk = localStorage.getItem(LS_TOKEN) || ''; } catch (e) {}
      fetch('/api/tenants', { headers: { Authorization: 'Bearer ' + tk } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (list) {
          if (!list) return;
          var arr = Array.isArray(list) ? list : (list.items || []);
          var own = arr.filter(function (t) { return t.id === uid || t.tenantId === uid; })[0] || {};
          if (own.name && subEl) subEl.textContent = own.name + ' · ' + (U.username || '');
          if (own.balance_yuan == null) { balBox.style.display = 'none'; return; }
          balBox.style.display = '';
          balNum.textContent = '¥ ' + Number(own.balance_yuan).toLocaleString('zh-CN', {
            minimumFractionDigits: 2, maximumFractionDigits: 2
          });
        })
        .catch(function () { balBox.style.display = 'none'; });
    }

    document.getElementById('udRecharge').addEventListener('click', function () {
      location.href = '/recharge';
    });

    /* 管理端沿用页面自带的改密弹窗 */
    var pwdBtn = document.getElementById('udPwd');
    if (pwdBtn) pwdBtn.addEventListener('click', function () {
      hide();
      if (typeof window.openPwd === 'function') window.openPwd();
    });

    /* 外观 */
    Array.prototype.forEach.call(drop.querySelectorAll('.ud-themes button'), function (btn) {
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        window.TKTheme.set(btn.getAttribute('data-t'));
      });
    });

    /* 退出登录 */
    document.getElementById('udOut').addEventListener('click', function () {
      var tk = '';
      try { tk = localStorage.getItem(LS_TOKEN) || ''; } catch (e) {}
      fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + tk } })
        .catch(function () {})
        .finally(function () {
          try {
            localStorage.removeItem(LS_TOKEN);
            localStorage.removeItem(LS_USER);
          } catch (e) {}
          location.href = '/login';
        });
    });
  }

  window.TKUserMenu = { init: init };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
