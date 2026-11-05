/* 业务动作共用组件：通用弹窗 / 商务需求登记 / 智能助手 / 工单。
 *
 * 这些入口以前点了只弹一句「演示环境，仅展示交互」，现在每个按钮都打到真实接口：
 * 需求登记落库进管理端商务跟进列表，助手回答带平台实时数据，工单进管理端工单队列。
 * 官网页面（首页 / 拼团 / 裸金属）与控制台页面共用这一份，避免每页各写一套弹窗。
 */
(function () {
  const B = {};

  B.token = () => localStorage.getItem('gu_token') || '';
  B.user = () => {
    try { return JSON.parse(localStorage.getItem('gu_user') || 'null'); } catch (e) { return null; }
  };
  B.login = next => { location.href = '/login?next=' + encodeURIComponent(next || location.pathname + location.search); };

  B.api = async function (path, opt) {
    opt = opt || {};
    opt.headers = Object.assign({}, opt.headers || {}, { 'Content-Type': 'application/json' });
    const t = B.token();
    if (t) opt.headers.Authorization = 'Bearer ' + t;
    const u = B.user();
    if (u && u.username) opt.headers['X-User'] = u.username;
    if (opt.body && typeof opt.body !== 'string') opt.body = JSON.stringify(opt.body);
    const r = await fetch(path, opt);
    let j = {};
    try { j = await r.json(); } catch (e) { j = {}; }
    if (!r.ok) throw new Error(j.message || ('请求失败（HTTP ' + r.status + '）'));
    return j;
  };

  B.esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  B.toast = function (msg, kind) {
    let el = document.querySelector('.biz-toast');
    if (!el) {
      el = document.createElement('div');
      el.className = 'biz-toast';
      document.body.appendChild(el);
    }
    el.className = 'biz-toast ' + (kind || '');
    el.textContent = msg;
    requestAnimationFrame(() => el.classList.add('on'));
    clearTimeout(el._t);
    el._t = setTimeout(() => el.classList.remove('on'), 3200);
  };

  /**
   * 通用弹窗。onOk 收到 (box, close)，自己负责校验与提交，
   * 出错就把错误写在弹窗里（不 alert、不静默），成功再 close。
   */
  B.modal = function (o) {
    o = o || {};
    // footFull：取消/提交两个按钮等宽铺满（对齐参考站弹窗底部）；okClass：给主按钮加样式（如 pink-btn 粉色渐变）
    const fs = o.footFull ? ' style="flex:1;padding:11px 0;font-size:13px"' : '';
    const okCls = 'sm' + (o.okClass ? ' ' + o.okClass : '');
    const el = document.createElement('div');
    el.className = 'modal on';
    el.innerHTML =
      '<div class="box' + (o.wide ? ' wide' : '') + '">' +
        '<h3>' + B.esc(o.title || '') + '</h3>' +
        '<div class="alert err" style="display:none"></div>' +
        '<div class="biz-body">' + (o.html || '') + '</div>' +
        (o.tip ? '<div class="tip">' + o.tip + '</div>' : '') +
        '<div class="foot">' +
          (o.hideCancel ? '' : '<button class="ghost sm" data-x="cancel"' + fs + '>' + B.esc(o.cancelText || '取消') + '</button>') +
          (o.hideOk ? '' : '<button class="' + okCls + '" data-x="ok"' + fs + '>' + B.esc(o.okText || '确定') + '</button>') +
        '</div>' +
      '</div>';
    document.body.appendChild(el);

    const box = el.querySelector('.box');
    const err = el.querySelector('.alert.err');
    const okBtn = el.querySelector('[data-x=ok]');
    const close = () => { el.remove(); document.removeEventListener('keydown', onKey); };
    const onKey = e => { if (e.key === 'Escape') close(); };

    el.addEventListener('click', e => { if (e.target === el) close(); });
    const cancel = el.querySelector('[data-x=cancel]');
    if (cancel) cancel.onclick = close;
    document.addEventListener('keydown', onKey);

    box.showError = m => { err.textContent = m; err.style.display = m ? 'block' : 'none'; };
    box.busy = on => {
      if (!okBtn) return;
      okBtn.disabled = !!on;
      okBtn.textContent = on ? '处理中…' : (o.okText || '确定');
    };
    if (okBtn && o.onOk) okBtn.onclick = () => o.onOk(box, close);
    if (o.ready) o.ready(box, close);
    return { el, box, close };
  };

  // ============================================================ 商务需求登记

  /**
   * 需求登记：裸金属申请、演示机型开通、大客户需求都走这里。
   * 登录用户写 /api/leads（带租户，管理端跟进后会推消息），未登录写 /api/public/leads。
   *
   * 新增可选参数（不传则维持旧版外观，index/solutions/portal 的调用不受影响）：
   *   ref        参考站版式：副标题+提示行、必填红星、粉色等宽按钮
   *   subtitle   ref 版式下标题下的灰色副标题（机型 · 显存 · 地区）
   *   hint       ref 版式下的引导语
   *   noteMax    备注/需求描述字数上限（裸金属 300、拼团 200，默认 500），带实时计数
   *   nameLabel/phoneLabel/orgLabel/noteLabel/namePh/phonePh/orgPh/notePh  各字段文案
   *   org        单位/学校预填
   * org 字段随所有调用一并落库（biz_leads.org 列已就绪）。
   */
  B.lead = function (o) {
    o = o || {};
    const u = B.user();
    const source = o.source || '官网';
    const ref = !!o.ref;
    const noteMax = o.noteMax || 500;
    const nameLabel = o.nameLabel || '联系人';
    const phoneLabel = o.phoneLabel || '手机号';
    const orgLabel = o.orgLabel || '单位 / 学校';
    const noteLabel = o.noteLabel || '需求说明';
    const namePh = o.namePh || '怎么称呼您';
    const phonePh = o.phonePh || '11 位手机号，商务回访用';
    const orgPh = o.orgPh || '请输入您的单位/学校名称';
    const notePh = o.notePh || '台数 / 显存 / 时长 / 预算等，写清楚商务能直接给方案';
    const star = ref ? '<span style="color:#ef4444">*</span> ' : '';

    const head = ref
      ? (o.subtitle ? '<div style="color:#6b7280;font-size:13px;margin:-10px 0 3px">' + B.esc(o.subtitle) + '</div>' : '') +
        (o.hint ? '<div style="color:#9aa1b2;font-size:12px;margin:0 0 14px">' + B.esc(o.hint) + '</div>' : '')
      : '<div class="biz-sum"><span class="hint">需求类型</span><b style="font-size:13.5px">' +
          B.esc(source) + (o.model ? ' · ' + B.esc(o.model) : '') + '</b></div>' +
        (o.desc ? '<div class="tip" style="margin:0 0 12px">' + o.desc + '</div>' : '');

    const m = B.modal({
      title: o.title || '提交需求',
      okText: o.okText || (ref ? '立即提交' : '提交需求'),
      cancelText: '取消',
      wide: true,
      okClass: ref ? 'pink-btn' : '',
      footFull: ref,
      html:
        head +
        '<div class="field"><label>' + star + B.esc(nameLabel) + '</label>' +
          '<input id="ldName" maxlength="32" placeholder="' + B.esc(namePh) + '" value="' +
          B.esc(o.contactName || (u && (u.displayName || u.username)) || '') + '"></div>' +
        '<div class="field"><label>' + star + B.esc(phoneLabel) + '</label>' +
          '<input id="ldPhone" maxlength="20" placeholder="' + B.esc(phonePh) + '" value="' +
          B.esc(o.contactPhone || '') + '"></div>' +
        '<div class="field"><label>' + B.esc(orgLabel) + '</label>' +
          '<input id="ldOrg" maxlength="64" placeholder="' + B.esc(orgPh) + '" value="' + B.esc(o.org || '') + '"></div>' +
        '<div class="field"><label>' + B.esc(noteLabel) + (ref ? '（选填）' : '') + '</label>' +
          '<textarea id="ldNote" rows="3" maxlength="' + noteMax + '" style="width:100%;border:1px solid #e6dcfa;' +
          'border-radius:10px;padding:9px 11px;font-size:12.5px;font-family:inherit;resize:vertical" ' +
          'placeholder="' + B.esc(notePh) + '">' + B.esc(o.note || '') + '</textarea>' +
          '<div style="text-align:right;font-size:11px;color:#9aa1b2;margin-top:4px"><span id="ldNoteCount">0</span> / ' + noteMax + '</div></div>',
      tip: o.tip || (u ? '提交后进入平台商务跟进队列，跟进结果会推送到您的消息中心，也能在「账户中心 → 我的需求」查看。'
                       : '无需登录即可提交；提交后商务会电话回访，注册账号后可在「账户中心 → 我的需求」查看进度。'),
      ready: box => {
        const ta = box.querySelector('#ldNote');
        const cnt = box.querySelector('#ldNoteCount');
        const sync = () => { cnt.textContent = ta.value.length; };
        ta.addEventListener('input', sync);
        sync();
      },
      onOk: async (box, close) => {
        const name = box.querySelector('#ldName').value.trim();
        const phone = box.querySelector('#ldPhone').value.trim();
        const org = box.querySelector('#ldOrg').value.trim();
        const note = box.querySelector('#ldNote').value.trim();
        if (!name) return box.showError('请填写' + nameLabel);
        if (!/^[0-9+\-() ]{6,20}$/.test(phone)) return box.showError('请填写正确的手机号（6~20 位数字）');
        if (note.length > noteMax) return box.showError('内容请精简到 ' + noteMax + ' 字以内');
        box.showError('');
        box.busy(true);
        try {
          const path = B.token() ? '/api/leads' : '/api/public/leads';
          const r = await B.api(path, { method: 'POST', body: { source, model: o.model || '', contactName: name, contactPhone: phone, org, note } });
          close();
          B.modal({
            title: '需求已提交',
            hideCancel: true,
            okText: '知道了',
            html: '<div class="biz-rows">' +
              '<div class="biz-row"><div class="t"><b>需求编号</b><span class="biz-tag">' + B.esc(r.id) + '</span></div>' +
              '<div class="d">' + B.esc(source) + (o.model ? ' · ' + B.esc(o.model) : '') + '<br>' + B.esc(r.message) + '</div></div>' +
              '</div>' +
              (B.token() ? '<div class="tip" style="margin:0">进度可在 <a href="/account" style="color:#7c3aed">账户中心 → 我的需求</a> 查看。</div>' : ''),
            onOk: (b, c) => c()
          });
          if (o.done) o.done(r);
        } catch (e) {
          box.busy(false);
          box.showError(e.message);
        }
      }
    });
    return m;
  };

  // ============================================================ 智能助手

  const QUICK = ['现在多少钱一小时', '跑 70B 模型要多少显存', '拼团怎么退钱', '售罄的卡怎么等到货', 'SSH 连不上怎么办'];

  /** 智能助手：已配置大模型走大模型，没配置走内置规则回答（两边都带平台实时数据） */
  B.assistant = function (question) {
    const path = B.token() ? '/api/assistant/chat' : '/api/public/assistant';
    const m = B.modal({
      title: '智能助手',
      wide: true,
      hideOk: true,
      cancelText: '关闭',
      html:
        '<div class="biz-chat" id="aiChat">' +
          '<div class="biz-msg ai">你好，我是创元方舟智能助手。可以问我价格与套餐、显存选型、拼团与退款规则、到货提醒、SSH 接入和配额问题。<span class="src">回答基于平台实时数据</span></div>' +
        '</div>' +
        '<div class="biz-quick" id="aiQuick">' +
          QUICK.map(q => '<button type="button" data-q="' + B.esc(q) + '">' + B.esc(q) + '</button>').join('') +
        '</div>' +
        '<div style="display:flex;gap:8px">' +
          '<input id="aiInput" placeholder="输入你的问题，回车发送" style="flex:1">' +
          '<button class="sm" id="aiSend" style="white-space:nowrap">发送</button>' +
        '</div>',
      ready: (box, close) => {
        const chat = box.querySelector('#aiChat');
        const input = box.querySelector('#aiInput');
        const send = box.querySelector('#aiSend');

        const push = (cls, text, src) => {
          const d = document.createElement('div');
          d.className = 'biz-msg ' + cls;
          d.textContent = text;
          if (src) {
            const s = document.createElement('span');
            s.className = 'src';
            s.textContent = src;
            d.appendChild(s);
          }
          chat.appendChild(d);
          chat.scrollTop = chat.scrollHeight;
          return d;
        };

        const ask = async q => {
          q = (q || '').trim();
          if (!q) return;
          push('me', q);
          input.value = '';
          send.disabled = true;
          const wait = push('ai', '正在思考…');
          try {
            const r = await B.api(path, { method: 'POST', body: { message: q } });
            wait.textContent = r.answer;
            const s = document.createElement('span');
            s.className = 'src';
            s.textContent = r.source === 'llm' ? '来源：大模型' : '来源：内置规则（未配置大模型参数）';
            wait.appendChild(s);
          } catch (e) {
            wait.textContent = '没能回答：' + e.message;
          }
          send.disabled = false;
          chat.scrollTop = chat.scrollHeight;
          input.focus();
        };

        send.onclick = () => ask(input.value);
        input.addEventListener('keydown', e => { if (e.key === 'Enter') ask(input.value); });
        box.querySelectorAll('#aiQuick button').forEach(b => {
          b.onclick = () => ask(b.dataset.q);
        });
        if (question) ask(question);
        else input.focus();
      }
    });
    return m;
  };

  // ============================================================ 工单 / 客服

  /** 联系客服 = 真实工单：落库后进管理端工单队列，回复会推到消息中心 */
  B.ticket = function (o) {
    o = o || {};
    if (!B.token()) {
      B.modal({
        title: '需要登录',
        hideCancel: true,
        okText: '去登录',
        html: '<div class="tip" style="margin:0">提交工单需要账号，登录后客服回复会推送到你的消息中心。</div>',
        onOk: (b, c) => { c(); B.login(); }
      });
      return;
    }
    B.modal({
      title: o.title || '联系客服',
      okText: '提交工单',
      wide: true,
      html:
        '<div class="field"><label>主题</label>' +
          '<input id="tkSubject" maxlength="64" value="' + B.esc(o.subject || '') + '" placeholder="一句话说清问题"></div>' +
        '<div class="field"><label>问题描述</label>' +
          '<textarea id="tkContent" rows="4" style="width:100%;border:1px solid #e6dcfa;border-radius:10px;' +
          'padding:9px 11px;font-size:12.5px;font-family:inherit;resize:vertical" ' +
          'placeholder="实例 ID、现象、期望结果">' + B.esc(o.content || '') + '</textarea></div>' +
        (o.extra ? '<div class="tip" style="margin:0 0 10px">' + o.extra + '</div>' : ''),
      tip: '提交后进入平台工单队列，管理员回复会推送到「消息中心」，也可以在「工单」页看处理进度。',
      onOk: async (box, close) => {
        const subject = box.querySelector('#tkSubject').value.trim();
        const content = box.querySelector('#tkContent').value.trim();
        if (!subject) return box.showError('请填写工单主题');
        box.showError('');
        box.busy(true);
        try {
          const r = await B.api('/api/tickets', { method: 'POST', body: { subject, content } });
          close();
          B.toast('工单已提交（' + r.id + '），管理员会尽快处理', 'ok');
          if (o.done) o.done(r);
        } catch (e) {
          box.busy(false);
          box.showError(e.message);
        }
      }
    });
  };

  window.Biz = B;
})();
