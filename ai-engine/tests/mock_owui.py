import json, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
LAST = {}
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
    def do_GET(self):
        if self.path == "/api/models":
            if not self._auth(): return
            self._json(200, {"data": [
                {"id": "llama3.1:8b", "name": "Llama 3.1", "object": "model", "owned_by": "ollama", "info": {"params": {"num_ctx": 8192}}},
                {"id": "gpt-4o", "name": "GPT-4o", "object": "model", "owned_by": "openai"}]})
        elif self.path == "/_last":
            self._json(200, LAST.get("req", {}))
        else:
            b = b"<!doctype html><html><body>Open WebUI</body></html>"
            self.send_response(200); self.send_header("Content-Type","text/html"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); req = json.loads(self.rfile.read(n))
        LAST["req"] = req
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
        else:
            for part in ["<thi", "nk>Let me ", "think</th", "ink>\n\nSELECT ", "* FROM ", "users;"]:
                chunk({"content": part})
            chunk({}, "stop")
            send("data: " + json.dumps({"choices": [], "usage": {"prompt_tokens": 120, "completion_tokens": 9, "prompt_tokens_details": {"cached_tokens": 100}}}))
        send("data: [DONE]")
        self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
ThreadingHTTPServer(("127.0.0.1", 18080), H).serve_forever()
