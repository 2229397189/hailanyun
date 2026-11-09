/* 控制台外壳：官网顶栏 + 侧栏 + 浏览器式标签栏（对齐参考控制台） */
(function () {
  var U = JSON.parse(localStorage.getItem('gu_user') || 'null') || { username: '未登录' };
  var active = document.body.dataset.active || 'main';
  // 自有设计语言：不再启用参考站遗留的洋红主题变体（视觉统一由 brand.css 定义）

  var LV = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  var SVG = {
    box: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>',
    spark: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9Z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8Z"/></svg>',
    chev: '<svg viewBox="0 0 24 24" ' + LV + '><path d="m6 9 6 6 6-6"/></svg>',
    burger: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M4 6h16M4 12h16M4 18h16"/></svg>',
    fold: '<svg viewBox="0 0 24 24" ' + LV + '><path d="m11 17-5-5 5-5"/><path d="m18 17-5-5 5-5"/></svg>',
    pin: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"/></svg>',
    palette: '<svg viewBox="0 0 24 24" ' + LV + '><circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.685-.734 1.685-1.64 0-.433-.176-.826-.451-1.126-.285-.3-.451-.69-.451-1.121 0-.904.76-1.637 1.663-1.637H16c3.316 0 6-2.684 6-6 0-4.966-4.486-9-10-9z"/></svg>',
    moon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"/></svg>',
    expand: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>',
    bell: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/></svg>',
    x: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M18 6 6 18M6 6l12 12"/></svg>',
    down: '<svg viewBox="0 0 24 24" ' + LV + '><path d="m6 9 6 6 6-6"/></svg>',
    robot: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/></svg>',
    home: '<svg viewBox="0 0 24 24" ' + LV + '><path d="m3 10 9-7 9 7v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/></svg>',
    display: '<svg viewBox="0 0 24 24" ' + LV + '><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
    folder: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>',
    card: '<svg viewBox="0 0 24 24" ' + LV + '><rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/></svg>',
    ticket: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M2 9a3 3 0 0 1 0 6v3a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-3a3 3 0 0 1 0-6V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2z"/><path d="M13 5v2M13 17v2M13 11v2"/></svg>',
    doc: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="M9 13h6M9 17h4"/></svg>',
    chart: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M3 3v18h18"/><path d="M7 15v3M12 10v8M17 6v12"/></svg>',
    coins: '<svg viewBox="0 0 24 24" ' + LV + '><circle cx="8" cy="8" r="5"/><path d="M18.09 10.37A6 6 0 1 1 10.34 18"/><path d="M7 6h1v4"/><path d="m16.71 13.88.7.71-2.82 2.82"/></svg>',
    receipt: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z"/><path d="M8 7h8M8 11h8M8 15h5"/></svg>',
    wallet: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M19 7V5a2 2 0 0 0-2-2H5a2 2 0 0 0 0 4h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5"/><path d="M18 12h.01"/></svg>',
    user: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="7" r="4"/></svg>',
    users: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
    edit: '<svg viewBox="0 0 24 24" ' + LV + '><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4Z"/></svg>'
  };

  var PAGES = {
    main: { t: '主页看板', h: '/console', ic: '<span class="ci">' + SVG.home + '</span>' },
    inst: { t: '实例列表', h: '/instances', ic: '<span class="ci">' + SVG.display + '</span>' },
    store: { t: '文件存储', h: '/storage', ic: '<span class="ci">' + SVG.folder + '</span>' },
    img: { t: '我的镜像', h: '/images', ic: '<span class="ci">' + SVG.box + '</span>' },
    llm: { t: '大模型 API', h: '/llm-api', ic: '<span class="ci">' + SVG.spark + '</span>' },
    recharge: { t: '在线充值', h: '/recharge', ic: '<span class="ci">' + SVG.card + '</span>' },
    coupons: { t: '我的券包', h: '/coupons', ic: '<span class="ci">' + SVG.ticket + '</span>' },
    orders: { t: '我的订单', h: '/orders', ic: '<span class="ci">' + SVG.doc + '</span>' },
    billing: { t: '消费账单', h: '/billing', ic: '<span class="ci">' + SVG.chart + '</span>' },
    flow: { t: '收支明细', h: '/flow', ic: '<span class="ci">' + SVG.coins + '</span>' },
    invoice: { t: '发票管理', h: '/invoice', ic: '<span class="ci">' + SVG.receipt + '</span>' },
    /* 钱包仍在 /wallet 可达，只是不再进侧栏（费用管理已拆成上面 6 项） */
    wallet: { t: '我的钱包', h: '/wallet', ic: '<span class="ci">' + SVG.wallet + '</span>' },
    msg: { t: '消息通知', h: '/notifications', ic: '<span class="ci">' + SVG.bell + '</span>' },
    subscribe: { t: '订阅推送', h: '/subscribe', ic: '<span class="ci">' + SVG.bell + '</span>' },
    acc: { t: '个人资料', h: '/account', ic: '<span class="ci">' + SVG.user + '</span>' },
    sub: { t: '子账号', h: '/subaccounts', ic: '<span class="ci">' + SVG.users + '</span>' },
    apply: { t: '资源申请', h: '/apply', ic: '<span class="ci">' + SVG.edit + '</span>' }
  };
  var cur = PAGES[active] || PAGES.main;

  /* ---------- 顶部官网导航 ---------- */
  var nav = document.createElement('header');
  nav.className = 'topnav c-topnav';
  nav.innerHTML =
    '<div class="inner">' +
      '<a class="brand" href="/index">' +
        '<span class="brand-mark"><svg width="19" height="19" viewBox="0 0 40 40" fill="none">' +
          '<path d="M9.5 32 A16 16 0 0 1 16.5 4.5" stroke="#fff" stroke-width="4.8" stroke-linecap="round"/>' +
          '<path d="M22.5 4.5 A16 16 0 0 1 31.5 32" stroke="#fce7f3" stroke-width="4.8" stroke-linecap="round"/></svg></span>' +
        '<span><span class="brand-name">Token<span>Ark</span></span>' +
        '<span class="brand-sub">创元方舟 · 算力云</span></span>' +
      '</a>' +
      '<nav class="navlinks">' +
        '<a href="/index">首页</a>' +
        '<a href="/portal">算力市场</a>' +
        '<a href="/llm-api">大模型 API</a>' +
        '<a href="/solutions">解决方案</a>' +
        '<a href="/about">关于我们</a>' +
        '<a href="/index#docs">帮助文档</a>' +
      '</nav>' +
      '<div class="nav-right">' +
        '<a class="btn-console" href="/console">控制台</a>' +
        '<button class="avatar nav-ava" id="navAva" title="账号菜单">' + (U.username || 'U').slice(0, 1).toUpperCase() + '</button>' +
      '</div>' +
    '</div>';
  document.body.insertBefore(nav, document.body.firstChild);

  /* 头像下拉（身份 / 余额 / 外观 / 退出）统一由 user-menu.js 接管，
     本文件只建顶栏骨架，避免两套面板互相打架。 */

  /* ---------- 侧栏 ---------- */
  function item(k) {
    var p = PAGES[k];
    return '<a class="' + (k === active ? 'on' : '') + '" href="' + p.h + '">' +
      '<span class="ic">' + p.ic + '</span><span class="tx">' + p.t + '</span></a>';
  }
  function subItem(k) {
    var p = PAGES[k];
    return '<a class="' + (k === active ? 'on' : '') + '" href="' + p.h + '">' +
      '<span class="ic"></span><span class="tx">' + p.t + '</span></a>';
  }
  function group(key, icon, title, items) {
    var open = items.indexOf(active) >= 0;
    return '<div class="grp' + (open ? ' open' : '') + '" data-grp="' + key + '">' +
      '<a href="javascript:void(0)" class="gh">' +
        '<span class="ic">' + icon + '</span><span class="tx">' + title + '</span>' +
        '<span class="chev">' + SVG.chev + '</span></a>' +
      '<div class="gsub"' + (open ? '' : ' style="display:none"') + '>' +
        items.map(subItem).join('') + '</div></div>';
  }
  var side = document.getElementById('cside');
  if (side) {
    side.innerHTML =
      '<nav>' +
        ['main', 'inst', 'store', 'img', 'llm'].map(item).join('') +
        group('bill', SVG.card, '费用管理',
          ['recharge', 'coupons', 'orders', 'billing', 'flow', 'invoice']) +
        group('acct', SVG.users, '账号管理',
          ['acc', 'sub', 'apply', 'msg', 'subscribe']) +
      '</nav>' +
      '<div class="foot">' +
        '<button class="fold" id="foldBtn" title="收起侧栏">' + SVG.fold + '</button>' +
        '<button class="fold" id="pinBtn" title="固定侧栏">' + SVG.pin + '</button>' +
      '</div>';
    side.querySelectorAll('.grp .gh').forEach(function (gh) {
      gh.addEventListener('click', function (e) {
        e.preventDefault();
        var g = gh.parentNode, sub = g.querySelector('.gsub');
        var open = g.classList.toggle('open');
        sub.style.display = open ? '' : 'none';
      });
    });
    var fb = side.querySelector('#foldBtn');
    if (fb) fb.addEventListener('click', function () {
      var c = document.querySelector('.console');
      var on = c.classList.toggle('collapsed');
      fb.innerHTML = on
        ? '<svg viewBox="0 0 24 24" ' + LV + '><path d="m13 17 5-5-5-5"/><path d="m6 17 5-5-5-5"/></svg>'
        : SVG.fold;
      fb.title = on ? '展开侧栏' : '收起侧栏';
    });
  }

  /* ---------- 顶栏：折叠 + 面包屑 + 工具 ---------- */
  var bar = document.querySelector('.ctopbar');
  if (bar) {
    bar.innerHTML =
      '<button class="burger" title="展开/收起菜单">' + SVG.burger + '</button>' +
      '<div class="crumb">' + (cur.ic || '<span class="ci">📄</span>') + cur.t + '</div>' +
      '<div class="ctools">' +
        '<button class="ctool pink" title="主题配色">' + SVG.palette + '</button>' +
        '<button class="ctool dark" title="深色模式">' + SVG.moon + '</button>' +
        '<button class="ctool" title="全屏">' + SVG.expand + '</button>' +
        '<button class="ctool" title="消息提醒">' + SVG.bell + '</button>' +
      '</div>';
    bar.querySelector('.burger').addEventListener('click', function () {
      var c = document.querySelector('.console');
      if (c) c.classList.toggle('collapsed');
    });
    var moonBtn = bar.querySelector('.ctool.dark');
    if (moonBtn) moonBtn.addEventListener('click', function () {
      if (window.TKTheme) {
        window.TKTheme.set(window.TKTheme.resolved() === 'dark' ? 'light' : 'dark');
      }
    });
  }

  /* ---------- 标签栏 ---------- */
  var tabs = document.querySelector('.ctabrow');
  if (!tabs && bar) {
    tabs = document.createElement('div');
    tabs.className = 'ctabrow';
    bar.parentNode.insertBefore(tabs, bar.nextSibling);
  }
  if (tabs) {
    var tabIc = { main: '🏠', inst: '🖥️', store: '📁', img: 'box', llm: 'spark',
      recharge: '💳', wallet: '👛', coupons: '🎟️', orders: '📋', billing: '📊', flow: '💰',
      invoice: '🧾', msg: '📣', subscribe: '🔔', acc: '👤', sub: '👥' };
    var ti = tabIc[active] || '📄';
    var tiHtml = ti === 'box' ? SVG.box : ti === 'spark' ? SVG.spark : ti;
    tabs.innerHTML =
      '<div class="ctabstrip">' +
        '<a class="ctabb on" href="' + cur.h + '">' +
          '<span class="ti">' + tiHtml + '</span>' +
          '<span>' + cur.t + '</span>' +
          '<span class="x">' + SVG.x + '</span></a>' +
      '</div>' +
      '<button class="ctabmore" title="全部标签">' + SVG.down + '</button>';
  }

  /* 后端时间戳一律带 +00:00，直接截字符串会把 UTC 当本地时间显示，全站会统一早 8 小时。
     所有控制台页面的时间格式化都走这里，不再各页自己拼。 */
  window.Tf = {
    pad: function (n) { return (n < 10 ? '0' : '') + n; },
    parts: function (v) {
      if (!v) return null;
      var d = new Date(v);
      if (isNaN(d.getTime())) return null;
      return [d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()];
    },
    at: function (v) { var p = this.parts(v); return p ? p[0] + '-' + this.pad(p[1]) + '-' + this.pad(p[2]) + ' ' + this.pad(p[3]) + ':' + this.pad(p[4]) : this.raw(v); },
    sec: function (v) { var p = this.parts(v); return p ? this.at(v) + ':' + this.pad(p[5]) : this.raw(v); },
    day: function (v) { var p = this.parts(v); return p ? p[0] + '-' + this.pad(p[1]) + '-' + this.pad(p[2]) : this.raw(v); },
    md: function (v) { var s = this.at(v); return s.length > 16 ? s.slice(5, 16) : s; },
    raw: function (v) { return String(v).replace('T', ' ').slice(0, 16); }
  };

  /* 顶栏骨架就绪后再挂账号菜单（user-menu.js 先于本文件加载） */
  if (window.TKUserMenu) window.TKUserMenu.init();
})();
