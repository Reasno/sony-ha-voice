#!/usr/bin/env python3
"""Check authenticated HA Assist protocol using a non-controlling test query."""
import argparse
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
import websocket

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path,
        default=Path.home() / ".local/state/sony-ha-voice/ha-config.json")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if config.get("refresh_token"):
        data = urllib.parse.urlencode({"grant_type": "refresh_token",
            "refresh_token": config["refresh_token"], "client_id": config["client_id"]}).encode()
        with urllib.request.urlopen(config["url"] + "/auth/token", data, timeout=15) as response:
            token = json.load(response)
        config["token"] = token["access_token"]
    ws = websocket.create_connection(config["url"].replace("http", "ws", 1) + "/api/websocket", timeout=20)
    assert json.loads(ws.recv())["type"] == "auth_required"
    ws.send(json.dumps({"type": "auth", "access_token": config["token"]}))
    assert json.loads(ws.recv())["type"] == "auth_ok"
    ws.send(json.dumps({"id": 1, "type": "assist_pipeline/pipeline/list"}))
    pipelines = json.loads(ws.recv())
    assert pipelines["success"]
    preferred = pipelines["result"]["preferred_pipeline"]
    ws.send(json.dumps({"id": 2, "type": "assist_pipeline/run", "start_stage": "intent",
        "end_stage": "tts", "input": {"text": "请告诉我现在几点，不要操作任何设备。"},
        "timeout": 60}))
    events = []
    tts_path = None
    deadline = time.monotonic() + 65
    while time.monotonic() < deadline:
        result = json.loads(ws.recv())
        if result.get("id") != 2:
            continue
        if result["type"] == "result":
            assert result["success"], "Assist rejected test"
            continue
        event = result["event"]
        events.append(event["type"])
        if event["type"] == "error":
            raise RuntimeError("HA error: " + event["data"]["code"])
        if event["type"] == "tts-end":
            tts_path = event["data"]["tts_output"]["url"]
        if event["type"] == "run-end":
            break
    ws.close()
    assert all(stage in events for stage in ("run-start", "intent-end", "tts-end", "run-end"))
    url = urllib.parse.urljoin(config["url"] + "/", tts_path)
    base, target = urllib.parse.urlsplit(config["url"]), urllib.parse.urlsplit(url)
    assert (base.scheme, base.netloc) == (target.scheme, target.netloc)
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + config["token"]})
    with urllib.request.urlopen(request, timeout=20) as audio:
        body = audio.read()
        mime = audio.headers.get("Content-Type")
    assert len(body) > 100
    result = {"auth": "auth_ok", "preferred_pipeline": preferred,
        "events": events, "tts_bytes": len(body), "tts_content_type": mime}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
