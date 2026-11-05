/**
 * TokenArk Node.js SDK（零依赖，Node 18+ 自带 fetch）
 *
 *   import { TokenArk } from './tokenark-sdk.mjs';
 *   const tk = new TokenArk({ baseUrl: 'https://your-platform.example.com', apiKey: 'sk-xxx' });
 *   console.log(await tk.chat('deepseek-chat', '你好'));
 */
export class TokenArkError extends Error {
  constructor(status, message) {
    super(`HTTP ${status}: ${message}`);
    this.status = status;
  }
}

export class TokenArk {
  constructor({ baseUrl, apiKey, username, password, timeout = 60000 }) {
    this.baseUrl = String(baseUrl || '').replace(/\/+$/, '');
    this.apiKey = apiKey || null;
    this.timeout = timeout;
    this.token = null;
    if (username && password) this.login(username, password);
  }

  async _req(method, path, body, auth = 'session') {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth === 'api_key' && this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`;
    if (auth === 'session' && this.token) headers.Authorization = `Bearer ${this.token}`;

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), this.timeout);
    try {
      const r = await fetch(this.baseUrl + path, {
        method, headers, signal: ctrl.signal,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await r.text();
      const data = text ? JSON.parse(text) : {};
      if (!r.ok) throw new TokenArkError(r.status, data.message || data.error?.message || text);
      return data;
    } finally {
      clearTimeout(timer);
    }
  }

  async login(username, password) {
    const r = await this._req('POST', '/api/auth/login', { username, password });
    this.token = r.token;
    return r;
  }

  models() {
    return this._req('GET', '/v1/models', undefined, 'api_key').then(r => r.data || []);
  }

  async chat(model, prompt, { maxTokens = 512, system } = {}) {
    const messages = [];
    if (system) messages.push({ role: 'system', content: system });
    messages.push({ role: 'user', content: prompt });
    const r = await this._req('POST', '/v1/chat/completions',
      { model, messages, max_tokens: maxTokens }, 'api_key');
    return r.choices[0].message.content;
  }

  createInstance({ sku, hours, dataDiskGb = 0, image = '', orderNo }) {
    return this._req('POST', '/api/orders', {
      sku, hours, dataDiskGb, image,
      orderNo: orderNo || `sdk-${Date.now()}`,
    });
  }

  instances() {
    return this._req('GET', '/api/instances');
  }

  release(instanceId) {
    return this._req('POST', `/api/instances/${encodeURIComponent(instanceId)}/release`);
  }
}

export default TokenArk;
