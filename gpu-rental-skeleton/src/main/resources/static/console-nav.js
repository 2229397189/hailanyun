/* 控制台外壳：侧栏 + 顶部标签，所有子页共用 */
(function () {
  var path = location.pathname;
  var U = JSON.parse(localStorage.getItem('gu_user') || 'null') || { username: '未登录' };

  var side = [
    { k: 'main', t: '主控制台', i: '▦', h: '/console.html' },
    { k: 'inst', t: '实例列表', i: '▤', h: '/portal.html#instances' },
    { k: 'store', t: '文件存储', i: '▧', h: '/storage.html' },
    { k: 'img', t: '我的镜像', i: '◫', h: '/images.html' },
    { k: 'llm', t: '大模型 API', i: '✦', h: '/llm-api.html' },
    { k: 'fin', t: '财务管理', i: '¥', h: '/wallet.html', arrow: true },
    { k: 'acc', t: '账号管理', i: '⚙', h: '/account.html', arrow: true }
  ];
  var sideSub = [
    { k: 'recharge', t: '在线充值', h: '/recharge.html' },
    { k: 'wallet', t: '我的钱包', h: '/wallet.html' },
    { k: 'orders', t: '我的订单', h: '/orders.html' },
    { k: 'billing', t: '消费账单', h: '/portal.html#billing' }
  ];
  var tabs = [
    { k: 'main', t: '主控制台', h: '/console.html' },
    { k: 'inst', t: '实例列表', h: '/portal.html#instances' },
    { k: 'img', t: '我的镜像', h: '/images.html' },
    { k: 'store', t: '文件存储', h: '/storage.html' },
    { k: 'llm', t: '大模型 API', h: '/llm-api.html' },
    { k: 'recharge', t: '在线充值', h: '/recharge.html' },
    { k: 'acc', t: '个人资料', h: '/account.html' },
    { k: 'orders', t: '我的订单', h: '/orders.html' },
    { k: 'wallet', t: '我的钱包', h: '/wallet.html' },
    { k: 'msg', t: '消息通知', h: '/notifications.html' }
  ];
  var active = (document.body.dataset.active || 'main');

  var el = document.getElementById('cside');
  if (el) {
    el.innerHTML =
      '<a class="logo brand" href="/index.html">' +
        '<span class="brand-mark" style="width:28px;height:28px;border-radius:9px">' +
          '<svg width="15" height="15" viewBox="0 0 40 40" fill="none"><path d="M9.5 32 A16 16 0 0 1 16.5 4.5" stroke="#fff" stroke-width="4.8" stroke-linecap="round"/><path d="M22.5 4.5 A16 16 0 0 1 31.5 32" stroke="#e9d5ff" stroke-width="4.8" stroke-linecap="round"/></svg>' +
        '</span>' +
        '<span><span class="brand-name" style="font-size:13.5px">Token<span>Ark</span></span>' +
        '<span class="brand-sub" style="font-size:9.5px">创元方舟</span></span>' +
      '</a>' +
      '<nav>' +
        side.map(function (s) {
          return '<a class="' + (s.k === active ? 'on' : '') + '" href="' + s.h + '">' +
            '<span class="ic">' + s.i + '</span>' + s.t + (s.arrow ? '<span class="arw">▸</span>' : '') + '</a>' +
            (s.k === 'fin'
              ? sideSub.map(function (x) {
                  return '<a class="sub ' + (x.k === active ? 'on' : '') + '" style="padding-left:33px;font-size:11.5px" href="' + x.h + '">' + x.t + '</a>';
                }).join('')
              : '') +
            (s.k === 'acc'
              ? '<a class="sub ' + (active === 'msg' ? 'on' : '') + '" style="padding-left:33px;font-size:11.5px" href="/notifications.html">消息通知</a>'
              : '');
        }).join('') +
      '</nav>' +
      '<div class="foot">v1.0.0 · ' + U.username + '</div>';
  }

  var t = document.getElementById('ctabs');
  if (t) {
    t.innerHTML = tabs.map(function (x) {
      return '<a class="ctab2 ' + (x.k === active ? 'on' : '') + '" href="' + x.h + '">' + x.t +
        '<span class="x">×</span></a>';
    }).join('');
  }
})();
