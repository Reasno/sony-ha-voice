#!/usr/bin/env python3
"""Independent Home Assistant OAuth authorization; never reads another app's credentials."""
import argparse
import http.server
import json
import os
from pathlib import Path
import secrets
import threading
import time
import urllib.parse
import urllib.request
import webbrowser
from tv import configure

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--ha-url", required=True)
    parser.add_argument("--port", type=int, default=8768)
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    ha = args.ha_url.rstrip("/")
    origin = urllib.parse.urlsplit(ha)
    if origin.scheme not in ("http", "https") or not origin.hostname or origin.username:
        raise SystemExit("Invalid HA URL")
    client = f"http://localhost:{args.port}/"
    nonce = secrets.token_urlsafe(24)
    result = {}
    done = threading.Event()
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == "/":
                body = b'<html><head><title>Sony HA Voice</title></head><body>Sony HA Voice OAuth client</body></html>'
            elif parsed.path == "/callback":
                query = urllib.parse.parse_qs(parsed.query)
                if query.get("state", [""])[0] != nonce:
                    self.send_error(403)
                    return
                result["code"] = query.get("code", [""])[0]
                body = "授权已接收，可以关闭此页面。".encode()
                done.set()
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    auth_url = ha + "/auth/authorize?" + urllib.parse.urlencode({
        "client_id": client, "redirect_uri": client + "callback", "state": nonce,
    })
    print("授权页面：" + auth_url, flush=True)
    if not args.no_browser:
        webbrowser.open(auth_url)
    if not done.wait(args.timeout):
        server.shutdown()
        raise SystemExit("授权等待超时；可重新运行此命令")
    if not result.get("code"):
        raise SystemExit("HA did not return an authorization code")
    data = urllib.parse.urlencode({"grant_type": "authorization_code",
        "code": result["code"], "client_id": client}).encode()
    request = urllib.request.Request(ha + "/auth/token", data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    # This endpoint is contacted only on the configured HA origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    with opener.open(request, timeout=20) as response:
        credentials = json.load(response)
    config = {"url": ha, "token": credentials["access_token"],
        "refresh_token": credentials["refresh_token"], "client_id": client,
        "expires_at": int(time.time() * 1000 + credentials["expires_in"] * 1000),
        "pipeline": "", "device_id": "", "input_device": -1, "audio_source": 1,
        "autostart": True}
    directory = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "sony-ha-voice"
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o700)
    target = directory / "ha-config.json"
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as file:
        json.dump(config, file)
    target.chmod(0o600)
    configure(args.serial, config)
    print("客户端已完成独立授权；凭据保存在电脑私有状态目录和电视加密配置中", flush=True)
    server.shutdown()

if __name__ == "__main__":
    main()
