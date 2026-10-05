import json, os, time, threading, uuid, re
DEMO = os.environ.get('MOCK_DEMO') == '1'
DEMO_ANSWER = os.environ.get('MOCK_DEMO_ANSWER', '')
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
LAST = {}
# --- Open WebUI chats and background tasks (behaviour of Open WebUI 0.11) ---
CHATS = {}      # chat_id -> {"id","title","chat"}
STREAMS = {}    # task_id -> {"chat_id","message_id","content","stop"}
LOCK = threading.Lock()
BG_LOG = []     # background completion requests

def merge_chat(stored, incoming):
    out = dict(stored); out.update(incoming)
    if "history" in incoming:
        old = (stored.get("history") or {}).get("messages") or {}
        new = (incoming.get("history") or {}).get("messages") or {}
        merged = {k: dict(v, childrenIds=[]) for k, v in {**old, **new}.items()}
        for k, v in merged.items():
            p = v.get("parentId")
            if p in merged: merged[p]["childrenIds"].append(k)
        cur = (incoming.get("history") or {}).get("currentId")
        if cur not in merged: cur = (stored.get("history") or {}).get("currentId")
        out["history"] = {**(stored.get("history") or {}), **(incoming.get("history") or {}), "messages": merged, "currentId": cur}
    return out

def upsert_message(chat_id, mid, msg):
    c = CHATS[chat_id]["chat"]
    h = c.setdefault("history", {"messages": {}, "currentId": None})
    h["messages"][mid] = {**h["messages"].get(mid, {}), **msg}

def bg_worker(task_id, chat_id, mid, req):
    model = req["model"]; msgs = req.get("messages") or []
    last_user = next((m["content"] for m in reversed(msgs) if m.get("role") == "user"), "")
    if model == "bg-error":
        time.sleep(0.5)
        with LOCK:
            upsert_message(chat_id, mid, {"error": {"content": "Model crashed"}, "done": True}); STREAMS.pop(task_id, None)
        return
    answer = "<think>planning</think>\n\nAnswer %d for: %s" % (len(msgs), last_user)
    if model == "bg-slow" or "slowly" in last_user:
        parts = [answer[i:i+4] for i in range(0, len(answer), 4)]; delay = 0.5
    else:
        parts = [answer[i:i+8] for i in range(0, len(answer), 8)]; delay = 0.1
    content = ""
    for p in parts:
        time.sleep(delay)
        with LOCK:
            st = STREAMS.get(task_id)
            if st is None or st.get("stop"):
                upsert_message(chat_id, mid, {"content": content, "done": True}); STREAMS.pop(task_id, None)
                return
            content += p
            st["content"] = content
    with LOCK:
        if model == "bg-old":   # old servers: no "done" flag, content only at the end
            upsert_message(chat_id, mid, {"content": content})
        else:
            upsert_message(chat_id, mid, {"output": [{"type": "message", "content": [{"type": "output_text", "text": content}]}], "content": content, "done": True})
        STREAMS.pop(task_id, None)
TOKEN = "sk-test"
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def _json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def _auth(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self._json(401, {"detail": "Not authenticated"}); return False
        return True
    def _alias(self):
        # Open WebUI talks to its upstream as to OpenAI: /v1/models, /v1/chat/completions
        if self.path.startswith("/v1/"): self.path = "/api/" + self.path[4:]
    def do_GET(self):
        self._alias()
        if self.path == "/api/models":
            if not self._auth(): return
            if DEMO:
                self._json(200, {"data": [
                    {"id": "qwen2.5-coder:14b", "name": "Qwen2.5 Coder 14B", "object": "model", "owned_by": "ollama", "info": {"params": {"num_ctx": 32768}}},
                    {"id": "llama3.1:8b", "name": "Llama 3.1 8B", "object": "model", "owned_by": "ollama", "info": {"params": {"num_ctx": 8192}}},
                    {"id": "gpt-4o", "name": "GPT-4o", "object": "model", "owned_by": "openai", "context_length": 128000}]})
                return
            self._json(200, {"data": [
                {"id": "llama3.1:8b", "name": "Llama 3.1", "object": "model", "owned_by": "ollama", "info": {"params": {"num_ctx": 8192}}},
                {"id": "gpt-4o", "name": "GPT-4o", "object": "model", "owned_by": "openai"}]})
        elif self.path == "/_last":
            self._json(200, LAST.get("req", {}))
        elif self.path == "/_bg":
            self._json(200, {"requests": BG_LOG, "chats": CHATS})
        elif self.path.startswith("/api/v1/chats/"):
            if not self._auth(): return
            cid = self.path[len("/api/v1/chats/"):]
            with LOCK:
                c = CHATS.get(cid)
                if c is None:
                    self._json(401, {"detail": "We could not find what you're looking for :/"}); return
                data = json.loads(json.dumps(c))
                for st in STREAMS.values():   # overlay of in-progress answers (Open WebUI 0.9+)
                    if st["chat_id"] == cid and st.get("model") != "bg-old":
                        m = data["chat"]["history"]["messages"].get(st["message_id"])
                        if m is not None: m["content"] = st["content"]; m["done"] = False
            self._json(200, data)
        elif self.path.startswith("/api/tasks/chat/"):
            if not self._auth(): return
            cid = self.path[len("/api/tasks/chat/"):]
            with LOCK:
                ids = [t for t, st in STREAMS.items() if st["chat_id"] == cid]
            self._json(200, {"task_ids": ids})
        else:
            b = b"<!doctype html><html><body>Open WebUI</body></html>"
            self.send_response(200); self.send_header("Content-Type","text/html"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        self._alias()
        n = int(self.headers.get("Content-Length", 0)); req = json.loads(self.rfile.read(n))
        LAST["req"] = req
        if self.path == "/api/v1/chats/new":
            if not self._auth(): return
            cid = str(uuid.uuid4())
            with LOCK:
                CHATS[cid] = {"id": cid, "title": req["chat"].get("title", "New Chat"), "chat": merge_chat({}, req["chat"])}
                self._json(200, CHATS[cid])
            return
        if self.path.startswith("/api/v1/chats/"):
            if not self._auth(): return
            cid = self.path[len("/api/v1/chats/"):]
            with LOCK:
                if cid not in CHATS:
                    self._json(401, {"detail": "You do not have permission to access this resource."}); return
                CHATS[cid]["chat"] = merge_chat(CHATS[cid]["chat"], req["chat"])
                CHATS[cid]["title"] = CHATS[cid]["chat"].get("title", "New Chat")
                self._json(200, CHATS[cid])
            return
        if self.path.startswith("/api/tasks/chat/") and self.path.endswith("/stop"):
            if not self._auth(): return
            cid = self.path[len("/api/tasks/chat/"):-len("/stop")]
            with LOCK:
                for st in STREAMS.values():
                    if st["chat_id"] == cid: st["stop"] = True
            self._json(200, {"status": True}); return
        if self.path == "/api/chat/completions" and req.get("session_id") and req.get("chat_id"):
            if not self._auth(): return
            cid = req["chat_id"]; mid = req.get("id")
            with LOCK:
                if cid not in CHATS:
                    self._json(400, {"detail": "Chat not found"}); return
                BG_LOG.append(req)
                um = req.get("user_message") or req.get("parent_message")
                if um: upsert_message(cid, um["id"], um)
                upsert_message(cid, mid, {"id": mid, "parentId": um["id"] if um else None, "role": "assistant", "content": "", "done": False, "model": req["model"], "childrenIds": []})
                tid = str(uuid.uuid4())
                STREAMS[tid] = {"chat_id": cid, "message_id": mid, "content": "", "model": req["model"]}
            threading.Thread(target=bg_worker, args=(tid, cid, mid, req), daemon=True).start()
            self._json(200, {"status": True, "task_ids": [tid], "chat_id": cid}); return
        if self.path != "/api/chat/completions":
            self._json(405, {"detail": "Method Not Allowed"}); return
        if not self._auth(): return
        model = req["model"]
        if model == "err-model":
            self._json(400, {"detail": "Model not found"}); return
        if model == "notemp-model" and "temperature" in req:
            self._json(400, {"error": {"message": "Unsupported value: 'temperature' does not support 0.0 with this model. Only the default (1) value is supported."}}); return
        if not req.get("stream"):
            if model == "tool-model":
                self._json(200, {"choices": [{"index": 0, "finish_reason": "tool_calls", "message": {"role": "assistant", "content": None,
                  "tool_calls": [{"id": "call_abc", "type": "function", "function": {"name": "db_listTableNames", "arguments": "{\"schemaNames\":\"public\"}"}}]}}],
                  "usage": {"prompt_tokens": 50, "completion_tokens": 7}})
            else:
                self._json(200, {"choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "<think>hmm</think>\n\nSELECT 1;"}}],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 3}})
            return
        self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
        def send(line):
            data = (line + "\n\n").encode()
            self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data)); self.wfile.flush(); time.sleep(0.01)
        def chunk(delta, fr=None, usage=None):
            o = {"choices": [{"index": 0, "delta": delta, "finish_reason": fr}]}
            if usage: o["usage"] = usage
            send("data: " + json.dumps(o))
        send(": keep-alive")
        if model in ("tool-model",):
            chunk({"role": "assistant", "tool_calls": [{"index": 0, "id": "call_1", "type": "function", "function": {"name": "db_listTableNames", "arguments": ""}}]})
            chunk({"tool_calls": [{"index": 0, "function": {"arguments": "{\"schema"}}]})
            chunk({"tool_calls": [{"index": 0, "function": {"arguments": "Names\":\"public\"}"}}]})
            chunk({"tool_calls": [{"index": 1, "id": "call_2", "type": "function", "function": {"name": "db_getTableDetails", "arguments": "{\"tableNames\":\"public.users\"}"}}]})
            chunk({}, "stop")  # Ollama quirk: stop instead of tool_calls
        elif model == "ollama-obj-model":
            # arguments as JSON object, no index
            chunk({"tool_calls": [{"id": "c9", "function": {"name": "db_listSchemaNames", "arguments": {"catalogName": ""}}}]}, "tool_calls")
        elif DEMO:
            text = open(DEMO_ANSWER, encoding="utf-8").read() if DEMO_ANSWER else "SELECT 1;"
            for i in range(0, len(text), 12):
                chunk({"content": text[i:i+12]})
            chunk({}, "stop")
            send("data: " + json.dumps({"choices": [], "usage": {"prompt_tokens": 1830, "completion_tokens": 142}}))
        else:
            for part in ["<thi", "nk>Let me ", "think</th", "ink>\n\nSELECT ", "* FROM ", "users;"]:
                chunk({"content": part})
            chunk({}, "stop")
            send("data: " + json.dumps({"choices": [], "usage": {"prompt_tokens": 120, "completion_tokens": 9, "prompt_tokens_details": {"cached_tokens": 100}}}))
        send("data: [DONE]")
        self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("MOCK_PORT", "18080"))), H).serve_forever()
