#!/usr/bin/env python3
"""Build a Java Android APK using installed SDK tools, without Gradle."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEPS = {
    "okhttp-4.12.0.jar": ("com/squareup/okhttp3/okhttp/4.12.0",
        "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0"),
    "okio-jvm-3.6.0.jar": ("com/squareup/okio/okio-jvm/3.6.0",
        "67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412"),
    "kotlin-stdlib-1.9.10.jar": ("org/jetbrains/kotlin/kotlin-stdlib/1.9.10",
        "55e989c512b80907799f854309f3bc7782c5b3d13932442d0379d5c472711504"),
    "annotations-13.0.jar": ("org/jetbrains/annotations/13.0",
        "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478"),
}

def run(args):
    subprocess.run([str(arg) for arg in args], check=True)

def tools(args):
    java_candidates = [args.java_home, os.environ.get("JAVA_HOME"),
        "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home",
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"]
    java = next((Path(p) for p in java_candidates if p and (Path(p) / "bin/javac").exists()), None)
    if java is None:
        raise SystemExit("JDK not found; pass --java-home")
    sdk_candidates = [args.sdk, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
        str(Path.home() / "Library/Android/sdk"), "/opt/homebrew/share/android-commandlinetools"]
    sdk = next((Path(p) for p in sdk_candidates if p and (Path(p) / "build-tools").exists()), None)
    if sdk is None:
        raise SystemExit("Android SDK not found; pass --sdk")
    def version(path):
        return tuple(int(s) for s in path.name.replace("android-", "").split(".") if s.isdigit())
    build = max((p for p in (sdk / "build-tools").iterdir() if (p / "aapt").exists()), key=version)
    platform = max((p for p in (sdk / "platforms").iterdir() if (p / "android.jar").exists()), key=version)
    return java, build, platform / "android.jar"

def dependencies(cache):
    output = []
    for filename, (coordinate, checksum) in DEPS.items():
        target = cache / filename
        if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
            for base in ("https://repo.maven.apache.org/maven2", "https://maven.aliyun.com/repository/central"):
                try:
                    with urllib.request.urlopen(f"{base}/{coordinate}/{filename}", timeout=30) as response:
                        data = response.read()
                    if hashlib.sha256(data).hexdigest() != checksum:
                        raise ValueError("Dependency checksum mismatch")
                    target.write_bytes(data)
                    break
                except Exception:
                    continue
            else:
                raise SystemExit(f"Unable to download verified dependency {filename}")
        output.append(target)
    return output

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home")
    parser.add_argument("--sdk")
    args = parser.parse_args()
    java, build, android = tools(args)
    cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "sony-ha-voice"
    cache.mkdir(parents=True, exist_ok=True)
    deps = dependencies(cache)
    work = cache / "build"
    if work.exists():
        shutil.rmtree(work)
    classes = work / "classes"
    dex = work / "dex"
    generated = work / "generated"
    for path in (classes, dex, generated):
        path.mkdir(parents=True)
    ns = "http://schemas.android.com/apk/res/android"
    ET.register_namespace("android", ns)
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
    manifest.getroot().set(f"{{{ns}}}versionCode", "1")
    manifest.getroot().set(f"{{{ns}}}versionName", "1.0.0")
    manifest.getroot().find("application").set(f"{{{ns}}}debuggable", "true")
    manifest_path = work / "AndroidManifest.xml"
    manifest.write(manifest_path, encoding="utf-8", xml_declaration=True)
    unsigned = work / "unsigned.apk"
    run([build / "aapt", "package", "-f", "-M", manifest_path,
        "-S", ROOT / "app/src/main/res", "-I", android, "-J", generated, "-F", unsigned])
    sources = list((ROOT / "app/src/main/java").rglob("*.java")) + list(generated.rglob("*.java"))
    run([java / "bin/javac", "--release", "8", "-encoding", "UTF-8",
        "-cp", os.pathsep.join(str(p) for p in [android, *deps]), "-d", classes, *sources])
    # Enumerate class files only after javac has completed.
    run([java / "bin/java", "-cp", build / "lib/d8.jar", "com.android.tools.r8.D8",
        "--min-api", "26", "--lib", android, "--output", dex, *classes.rglob("*.class"), *deps])
    if not (dex / "classes.dex").exists():
        raise SystemExit("D8 did not produce classes.dex")
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        for path in dex.glob("*.dex"):
            archive.write(path, path.name)
    aligned = work / "aligned.apk"
    run([build / "zipalign", "-f", "-p", "4", unsigned, aligned])
    state = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "sony-ha-voice"
    state.mkdir(parents=True, exist_ok=True)
    state.chmod(0o700)
    key = state / "debug.keystore"
    if not key.exists():
        run([java / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android",
            "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "10000", "-dname", "CN=Sony HA Voice Debug"])
        key.chmod(0o600)
    output = ROOT / "dist/sony-ha-voice-debug.apk"
    output.parent.mkdir(exist_ok=True)
    signer = [java / "bin/java", "-jar", build / "lib/apksigner.jar"]
    run([*signer, "sign", "--ks", key, "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", output, aligned])
    run([*signer, "verify", "--verbose", output])
    run([build / "zipalign", "-c", "4", output])
    print(f"APK: {output}\nSHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}")

if __name__ == "__main__":
    main()
