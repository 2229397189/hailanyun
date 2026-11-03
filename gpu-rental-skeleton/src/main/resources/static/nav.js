/* 顶部导航「算力市场」下拉：默认第一项，点击各自跳转 */
(function () {
  var link = document.querySelector('.navlinks a[href="/market.html"]');
  if (!link || link.parentNode.classList.contains('nav-drop')) return;

  var wrap = document.createElement('div');
  wrap.className = 'nav-drop';
  link.parentNode.insertBefore(wrap, link);
  wrap.appendChild(link);
  link.insertAdjacentHTML('beforeend', '<span class="caret">⌄</span>');

  wrap.insertAdjacentHTML('beforeend', [
    '<div class="drop-panel">',
    '  <a class="drop-item" href="/portal.html">',
    '    <span class="di blue">▣</span>',
    '    <span class="dt"><b>容器云 <em class="tag pink">按量计费</em></b>',
    '      <i>弹性 GPU 容器集群，按需弹性扩缩容</i></span>',
    '  </a>',
    '  <a class="drop-item" href="/group.html">',
    '    <span class="di orange">◉</span>',
    '    <span class="dt"><b>拼租卡 <em class="tag green">拼租卡</em></b>',
    '      <i>多人共享显卡，平摊成本更省钱</i></span>',
    '  </a>',
    '  <a class="drop-item" href="/baremetal.html">',
    '    <span class="di purple">✦</span>',
    '    <span class="dt"><b>裸金属 <em class="tag blue">企业级</em></b>',
    '      <i>独享物理服务器，适合大规模训练</i></span>',
    '  </a>',
    '</div>'
  ].join(''));

  /* 当前页高亮：市场落地 / 容器云 / 拼团 / 裸金属 都算「算力市场」 */
  var path = location.pathname;
  var inMarket = ['/market.html', '/portal.html', '/group.html', '/baremetal.html'].indexOf(path) >= 0;
  link.classList.toggle('on', inMarket);
})();
