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
  var items = [
    { h: '/portal.html', ic: '▣', cls: 'blue', t: '容器云', tag: '按量计费', tagCls: 'pink',
      d: '弹性 GPU 容器集群，按需弹性扩缩容' },
    { h: '/group.html', ic: '◉', cls: 'orange', t: '拼租卡', tag: '拼租卡', tagCls: 'green',
      d: '多人共享显卡，平摊成本更省钱' },
    { h: '/baremetal.html', ic: '✦', cls: 'purple', t: '裸金属', tag: '企业级', tagCls: 'blue',
      d: '独享物理服务器，适合大规模训练' }
  ];

  wrap.insertAdjacentHTML('beforeend',
    '<div class="drop-panel">' +
      items.map(function (x) {
        var on = path === x.h ? ' on' : '';
        return '<a class="drop-item' + on + '" href="' + x.h + '">' +
          '<span class="di ' + x.cls + '">' + x.ic + '</span>' +
          '<span class="dt"><b>' + x.t + '<em class="tag ' + x.tagCls + '">' + x.tag + '</em></b>' +
          '<i>' + x.d + '</i></span>' +
          (path === x.h ? '<span class="cur">当前</span>' : '') +
        '</a>';
      }).join('') +
      '<a class="drop-all" href="/market.html">查看全部商品 <span>›</span></a>' +
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
  var inMarket = ['/market.html', '/portal.html', '/group.html', '/baremetal.html'].indexOf(path) >= 0;
  link.classList.toggle('on', inMarket);

  /* autoOpen：进了这三个产品页，菜单默认展开，方便直接切另一个 */
  if (['/portal.html', '/group.html', '/baremetal.html'].indexOf(path) >= 0) {
    wrap.classList.add('open');
  }
})();
