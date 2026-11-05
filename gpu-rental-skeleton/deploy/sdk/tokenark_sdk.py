"""TokenArk Python SDK（零第三方依赖，仅标准库）

覆盖两类接口：
  1) 推理（OpenAI 兼容）：client.chat(...) / client.models()
  2) 平台（实例生命周期）：client.create_instance(...) / client.instances() / client.release(...)

用法：
    from tokenark_sdk import TokenArk

    tk = TokenArk(base_url="https://your-platform.example.com", api_key="sk-...")
    print(tk.chat("deepseek-chat", "帮我写一个快速排序"))

    session = TokenArk(base_url="https://your-platform.example.com",
                       username="tenant_a", password="******")
    inst = session.create_instance(sku="1", hours=1, data_disk_gb=50)
    print(inst["instanceId"], "→", session.instances()[0]["status"])
    session.release(inst["instanceId"])
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request


class TokenArkError(RuntimeError):
    """接口返回非 2xx 时抛出，带 HTTP 状态码与服务端 message。"""

    def __init__(self, status: int, message: str):
        super().__init__("HTTP %s: %s" % (status, message))
        self.status = status
        self.message = message


class TokenArk:
    def __init__(self, base_url: str, api_key: str | None = None,
                 username: str | None = None, password: str | None = None, timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self._token = None
        if username and password:
            self.login(username, password)

    # ---------------------------------------------------------------- 基础

    def _request(self, method: str, path: str, body=None, auth: str = "session"):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if auth == "api_key" and self.api_key:
            req.add_header("Authorization", "Bearer " + self.api_key)
        elif auth == "session" and self._token:
            req.add_header("Authorization", "Bearer " + self._token)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                text = resp.read().decode("utf-8")
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "ignore")
            try:
                msg = json.loads(raw).get("message") or json.loads(raw).get("error", {}).get("message") or raw
            except Exception:
                msg = raw
            raise TokenArkError(e.code, str(msg)) from None

    # ---------------------------------------------------------------- 会话

    def login(self, username: str, password: str) -> dict:
        r = self._request("POST", "/api/auth/login", {"username": username, "password": password})
        self._token = r.get("token")
        return r

    # ---------------------------------------------------------------- 推理

    def models(self) -> list:
        return self._request("GET", "/v1/models", auth="api_key").get("data", [])

    def chat(self, model: str, prompt: str, max_tokens: int = 512,
             system: str | None = None) -> str:
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        r = self._request("POST", "/v1/chat/completions",
                          {"model": model, "messages": messages, "max_tokens": max_tokens},
                          auth="api_key")
        return r["choices"][0]["message"]["content"]

    # ---------------------------------------------------------------- 实例

    def create_instance(self, sku: str, hours: int, data_disk_gb: int = 0,
                        image: str = "", order_no: str | None = None) -> dict:
        import time
        body = {
            "sku": sku,
            "hours": hours,
            "dataDiskGb": data_disk_gb,
            "image": image,
            # orderNo 是幂等键：网络重试不会重复扣费
            "orderNo": order_no or ("sdk-%d" % int(time.time() * 1000)),
        }
        return self._request("POST", "/api/orders", body)

    def instances(self) -> list:
        return self._request("GET", "/api/instances")

    def release(self, instance_id: str) -> dict:
        return self._request("POST", "/api/instances/%s/release" % urllib.parse.quote(instance_id))

    def instance_access(self, instance_id: str) -> dict:
        return self._request("GET", "/api/instances/%s/access" % urllib.parse.quote(instance_id))
