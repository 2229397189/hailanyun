/* 控制台外壳：官网顶栏 + 侧栏 + 浏览器式标签栏（对齐智川云控制台） */
(function () {
  var U = JSON.parse(localStorage.getItem('gu_user') || 'null') || { username: '未登录' };
  var active = document.body.dataset.active || 'main';
  document.body.classList.add('theme-pink');

  var tabs = [
    { k: 'main', t: '主页概览', h: '/console.html' },
    { k: 'inst', t: '实例列表', h: '/instances.html' },
    { k: 'img', t: '我的镜像', h: '/images.html' },
    { k: 'store', t: '文件存储', h: '/storage.html' },
    { k: 'llm', t: '大模型 API', h: '/llm-api.html' },
    { k: 'recharge', t: '在线充值', h: '/recharge.html' },
    { k: 'acc', t: '个人资料', h: '/account.html' },
    { k: 'sub', t: '子账号管理', h: '/subaccounts.html' },
    { k: 'msg', t: '消息通知', h: '/notifications.html' }
  ];
  var cur = tabs.filter(function (x) { return x.k === active; })[0] || tabs[0];

  /* ---------- 顶部导航 ---------- */
  var nav = document.createElement('header');
  nav.className = 'topnav c-topnav';
  nav.innerHTML =
    '<div class="inner">' +
      '<a class="brand" href="/index.html">' +
        '<span class="brand-mark"><svg width="17" height="17" viewBox="0 0 40 40" fill="none">' +
          '<path d="M9.5 32 A16 16 0 0 1 16.5 4.5" stroke="#fff" stroke-width="4.8" stroke-linecap="round"/>' +
          '<path d="M22.5 4.5 A16 16 0 0 1 31.5 32" stroke="#fce7f3" stroke-width="4.8" stroke-linecap="round"/></svg></span>' +
        '<span><span class="brand-name">Token<span>Ark</span></span>' +
        '<span class="brand-sub">创元方舟 · 算力云</span></span>' +
      '</a>' +
      '<nav class="navlinks">' +
        '<a href="/index.html">首页</a>' +
        '<a href="/market.html">算力市场</a>' +
        '<a class="soon">大模型 API</a>' +
        '<a href="/solutions.html">解决方案</a>' +
        '<a href="/about.html">关于我们</a>' +
        '<a href="/index.html#pricing">帮助文档</a>' +
      '</nav>' +
      '<div class="nav-right">' +
        '<a class="btn-console" href="/console.html">控制台</a>' +
        '<span class="avatar">' + (U.username || 'U').slice(0, 1).toUpperCase() + '</span>' +
      '</div>' +
    '</div>';
  document.body.insertBefore(nav, document.body.firstChild);

  /* ---------- 侧栏 ---------- */
  var side = [
    { k: 'main', t: '主页概览', i: '▦', h: '/console.html' },
    { k: 'inst', t: '实例市场', i: '▤', h: '/portal.html#market' },
    { k: 'store', t: '文件存储', i: '▧', h: '/storage.html' },
    { k: 'img', t: '我的镜像', i: '◫', h: '/images.html' },
    { k: 'llm', t: '大模型 API', i: '✦', h: '/llm-api.html' }
  ];
  var fin = [
    { k: 'recharge', t: '在线充值', h: '/recharge.html' },
    { k: 'wallet', t: '我的钱包', h: '/wallet.html' },
    { k: 'orders', t: '我的订单', h: '/orders.html' },
    { k: 'msg', t: '消息通知', h: '/notifications.html' }
  ];
  var acc = [
    { k: 'acc', t: '个人资料', h: '/account.html' },
    { k: 'sub', t: '子账号', h: '/subaccounts.html' },
    { k: 'push', t: '订单推送', h: '/notifications.html' }
  ];
  function link(s) {
    return '<a class="' + (s.k === active ? 'on' : '') + '" href="' + s.h + '">' +
      (s.i ? '<span class="ic">' + s.i + '</span>' : '<span class="ic dot"></span>') + s.t + '</a>';
  }
  var el = document.getElementById('cside');
  if (el) {
    el.innerHTML =
      '<div class="cs-logo"><span class="cs-mark">◈</span><b>控制台</b></div>' +
      '<nav>' +
        side.map(link).join('') +
        '<div class="grp2"><span>费用管理</span><i>⌄</i></div>' +
        fin.map(link).join('') +
        '<div class="grp2"><span>账号管理</span><i>⌄</i></div>' +
        acc.map(link).join('') +
      '</nav>' +
      '<div class="foot">v1.0.0 · ' + U.username + '</div>';
  }

  /* ---------- 标签栏 + 面包屑 ---------- */
  var host = document.querySelector('.ctopbar');
  if (host) {
    host.classList.add('c-tabsbar');
    host.innerHTML =
      '<div class="ctabs">' + tabs.map(function (x) {
        return '<a class="ctab2 ' + (x.k === active ? 'on' : '') + '" href="' + x.h + '">' + x.t +
          '<span class="x">×</span></a>';
      }).join('') + '</div>';
    var bc = document.createElement('div');
    bc.className = 'c-breadcrumb';
    bc.innerHTML = cur.k === 'main'
      ? '<span>▦</span><b>主页概览</b>'
      : '<span>▦</span><span>主页概览</span><span>›</span><b>' + cur.t + '</b>';
    host.parentNode.insertBefore(bc, host.nextSibling);
  }
})();
