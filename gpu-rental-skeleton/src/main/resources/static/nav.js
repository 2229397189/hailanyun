/* 顶部导航「算力市场」下拉：点击展开 / 点击外部收起 / 当前项高亮 */
(function () {
  var link = null;
  var links = document.querySelectorAll('.navlinks a');
  for (var i = 0; i < links.length; i++) {
    if ((links[i].textContent || '').indexOf('算力市场') >= 0) { link = links[i]; break; }
  }
  if (!link || link.parentNode.classList.contains('nav-drop')) return;

  var wrap = document.createElement('div');
  wrap.className = 'nav-drop';
  link.parentNode.insertBefore(wrap, link);
  wrap.appendChild(link);
  link.insertAdjacentHTML('beforeend', '<span class="caret">⌄</span>');

  var path = location.pathname;
  var I = {
    cube: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>',
    users: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
    server: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="7" rx="2"/><rect x="2" y="14" width="20" height="7" rx="2"/><path d="M6 6.5h.01M6 17.5h.01"/></svg>',
    arrow: '<svg class="dp-arrow" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>'
  };
  var items = [
    { h: '/portal', cls: 'blue', svg: I.cube, t: '容器云', tag: '按量计费', tagCls: 'pink',
      d: '弹性 GPU 容器集群，按需扩缩容', p: '¥0.50', u: '起 / 卡时' },
    { h: '/group', cls: 'orange', svg: I.users, t: '拼租卡', tag: '拼租', tagCls: 'green',
      d: '多人共享整机，平摊成本更省钱', p: '¥670', u: '起 / 月·卡' },
    { h: '/baremetal', cls: 'purple', svg: I.server, t: '裸金属', tag: '企业级', tagCls: 'blue',
      d: '独享物理服务器，适合大规模训练', p: '¥5,500', u: '起 / 月' }
  ];

  wrap.insertAdjacentHTML('beforeend',
    '<div class="drop-panel">' +
      '<div class="dp-head"><b>算力市场</b><span>GPU 按需租用 · 秒级交付</span></div>' +
      '<div class="dp-list">' +
      items.map(function (x) {
        var on = path === x.h;
        return '<a class="drop-item' + (on ? ' on' : '') + '" href="' + x.h + '">' +
          (on ? '<span class="dp-cur">当前</span>' : '') +
          '<span class="di ' + x.cls + '">' + x.svg + '</span>' +
          '<span class="dp-body"><b>' + x.t + '<em class="dp-tag ' + x.tagCls + '">' + x.tag + '</em></b>' +
            '<i>' + x.d + '</i></span>' +
          '<span class="dp-price">' + x.p + '<small>' + x.u + '</small></span>' +
          I.arrow +
        '</a>';
      }).join('') +
      '</div>' +
      '<a class="drop-all" href="/portal" style="display:flex;align-items:center;justify-content:center;height:42px;margin:4px 14px 14px;border-radius:7px;background:#3760f4!important;color:#fff!important;font-size:13px;font-weight:600;text-decoration:none">查看全部机型 <span style="font-size:14px;margin-left:4px">&rarr;</span></a>' +
    '</div>');

  /* 点击展开 / 收起，不再依赖悬停 */
  link.addEventListener('click', function (e) {
    e.preventDefault();
    e.stopPropagation();
    wrap.classList.toggle('open');
  });
  document.addEventListener('click', function (e) {
    if (!wrap.contains(e.target)) wrap.classList.remove('open');
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') wrap.classList.remove('open');
  });

  /* 当前页高亮：市场落地 / 容器云 / 拼团 / 裸金属 都算「算力市场」 */
  var inMarket = ['/market', '/portal', '/group', '/baremetal'].indexOf(path) >= 0;
  link.classList.toggle('on', inMarket);

})();
