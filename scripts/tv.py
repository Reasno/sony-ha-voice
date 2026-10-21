#!/usr/bin/env python3
"""Install and manage Sony HA Voice through ADB."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess

PACKAGE = "dev.guxi.sonyhavoice"
COMPONENT = PACKAGE + "/.AssistantService"
ROOT = Path(__file__).resolve().parents[1]

def adb(serial, *args, data=None):
    result = subprocess.run(["adb", "-s", serial, *args], input=data,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return result.stdout.decode("utf-8", errors="replace").strip()

def state(serial):
    base = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "sony-ha-voice"
    device_id = adb(serial, "shell", "getprop", "ro.serialno")
    if not device_id or device_id == "unknown":
        raise RuntimeError("无法读取电视序列号，未修改助手备份")
    path = base / hashlib.sha256(device_id.encode()).hexdigest()[:16]
    path.mkdir(parents=True, exist_ok=True)
    path.chmod(0o700)
    previous = base / hashlib.sha256(serial.encode()).hexdigest()[:16] / "assistant-before.json"
    saved = path / "assistant-before.json"
    if previous.exists() and not saved.exists():
        saved.write_bytes(previous.read_bytes())
        saved.chmod(0o600)
    return path

def configure(serial, config):
    # Keep secret values out of shell arguments and output.
    data = json.dumps(config, ensure_ascii=False).encode()
    adb(serial, "shell", "run-as", PACKAGE, "sh", "-c",
        "'umask 077; mkdir -p files; cat > files/incoming-config.json'", data=data)
    result = adb(serial, "shell", "am", "broadcast", "--user", "0",
        "-a", PACKAGE + ".CONFIGURE", "-n", PACKAGE + "/.ConfigureReceiver")
    if "result=1" not in result:
        raise RuntimeError("配置未成功导入：" + result)
    print("电视配置已导入并加密保存")

def backup(serial):
    path = state(serial) / "assistant-before.json"
    if not path.exists():
        settings = {key: adb(serial, "shell", "settings", "get", "secure", key)
            for key in ("assistant", "voice_interaction_service", "voice_recognition_service")}
        settings["roles"] = adb(serial, "shell", "cmd", "role", "get-role-holders",
            "--user", "0", "android.app.role.ASSISTANT").splitlines()
        settings["saved_at"] = datetime.datetime.now().isoformat()
        path.write_text(json.dumps(settings, ensure_ascii=False, indent=2))
        path.chmod(0o600)
    return path

def assistant(serial):
    path = backup(serial)
    adb(serial, "shell", "pm", "grant", "--user", "0", PACKAGE,
        "android.permission.WRITE_SECURE_SETTINGS")
    adb(serial, "shell", "cmd", "role", "add-role-holder", "--user", "0",
        "android.app.role.ASSISTANT", PACKAGE, "0")
    for key in ("assistant", "voice_interaction_service"):
        adb(serial, "shell", "settings", "put", "secure", key, COMPONENT)
    adb(serial, "shell", "settings", "put", "secure", "voice_recognition_service",
        PACKAGE + "/.RecognitionService")
    print("已设为系统助手；恢复记录：" + str(path))

def restore(serial):
    path = state(serial) / "assistant-before.json"
    config = json.loads(path.read_text())
    adb(serial, "shell", "pm", "revoke", "--user", "0", PACKAGE,
        "android.permission.WRITE_SECURE_SETTINGS")
    adb(serial, "shell", "cmd", "role", "remove-role-holder", "--user", "0",
        "android.app.role.ASSISTANT", PACKAGE, "0")
    for role in config["roles"]:
        if role:
            adb(serial, "shell", "cmd", "role", "add-role-holder", "--user", "0",
                "android.app.role.ASSISTANT", role, "0")
    for key in ("assistant", "voice_interaction_service", "voice_recognition_service"):
        value = config[key]
        if value in ("", "null"):
            adb(serial, "shell", "settings", "delete", "secure", key)
        else:
            adb(serial, "shell", "settings", "put", "secure", key, value)
    print("助手绑定已恢复")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    install = sub.add_parser("install")
    install.add_argument("--apk", type=Path, default=ROOT / "dist/sony-ha-voice-debug.apk")
    configure_parser = sub.add_parser("configure")
    configure_parser.add_argument("--file", type=Path, required=True)
    for command in ("status", "set-assistant", "restore-assistant"):
        sub.add_parser(command)
    args = parser.parse_args()
    if args.command == "install":
        print(adb(args.serial, "install", "--no-incremental", "-r", str(args.apk)))
        for permission in ("RECORD_AUDIO", "POST_NOTIFICATIONS"):
            try: adb(args.serial, "shell", "pm", "grant", PACKAGE, "android.permission." + permission)
            except subprocess.CalledProcessError: pass
        print(adb(args.serial, "shell", "am", "start", "-n", PACKAGE + "/.MainActivity"))
    elif args.command == "configure":
        configure(args.serial, json.loads(args.file.read_text()))
    elif args.command == "set-assistant":
        assistant(args.serial)
    elif args.command == "restore-assistant":
        restore(args.serial)
    else:
        for key in ("assistant", "voice_interaction_service", "voice_recognition_service"):
            print(key + ": " + adb(args.serial, "shell", "settings", "get", "secure", key))
        print("role: " + adb(args.serial, "shell", "cmd", "role", "get-role-holders",
            "--user", "0", "android.app.role.ASSISTANT"))
        print(adb(args.serial, "shell", "dumpsys", "activity", "services", PACKAGE))

if __name__ == "__main__":
    main()
