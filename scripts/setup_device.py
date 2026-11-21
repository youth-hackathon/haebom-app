"""Prepare a phone or an emulator for the guide in one go.

  python -I scripts/setup_device.py --serial <adb serial>
  python -I scripts/setup_device.py --serial <adb serial> --guide https://artemis.example.com --tts https://tts.example.com

Steps: install app-debug.apk (build it first: gradlew assembleDebug), turn the accessibility service on, point the
app at the two servers, wake the screen, and ask the app what it is set to (DEBUG_CONFIG).

Without --guide / --tts the servers are the ones on this computer (ports 8765 and 8766), reached through
`adb reverse`. --mode and --engine choose the servers' way of working; the default is `baseline` on both.
Some phones (One UI) do not let adb turn an accessibility service on; the script then says so and the service
is switched on by hand: Settings > Accessibility > Installed apps > 해봄 (화면 안내).
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get("ADB") or shutil.which("adb") or str(
    Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ".") / "platform-tools" / "adb")
PKG = "ai.toolus.mobileguide"
SERVICE = f"{PKG}/{PKG}.GuideAccessibilityService"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
LOCAL_GUIDE = "http://127.0.0.1:8765"
LOCAL_TTS = "http://127.0.0.1:8766"


def adb(serial: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def healthy(base: str) -> bool:
    try:
        with urllib.request.urlopen(base.rstrip("/") + "/health", timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--guide", default=LOCAL_GUIDE, help="guide server (haebom-artemis)")
    ap.add_argument("--tts", default=LOCAL_TTS, help="TTS server (haebom-tts); '' for the phone's own voice only")
    ap.add_argument("--mode", default="baseline", help="guide server mode")
    ap.add_argument("--engine", default="baseline", help="TTS engine")
    ap.add_argument("--no-install", action="store_true", help="the app is already installed")
    a = ap.parse_args()
    s = a.serial

    if not a.no_install:
        if not APK.exists():
            sys.exit(f"no APK at {APK}; run gradlew assembleDebug first")
        r = adb(s, "install", "-r", str(APK))
        print("install:", (r.stdout.strip().splitlines() or [r.stderr.strip()])[-1])

    enabled = adb(s, "shell", "settings", "get", "secure", "enabled_accessibility_services").stdout.strip()
    if SERVICE not in enabled:
        value = SERVICE if enabled in ("", "null") else f"{enabled}:{SERVICE}"
        adb(s, "shell", "settings", "put", "secure", "enabled_accessibility_services", value)
        adb(s, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
        time.sleep(2)
    enabled = adb(s, "shell", "settings", "get", "secure", "enabled_accessibility_services").stdout.strip()
    print("accessibility service:", "on" if SERVICE in enabled else "OFF - turn it on by hand (Settings > Accessibility)")

    # A server on this computer is reached from the device through adb reverse.
    for name, base in (("guide", a.guide), ("tts", a.tts)):
        if not base:
            print(f"{name} server: not used")
            continue
        if base in (LOCAL_GUIDE, LOCAL_TTS):
            port = base.rsplit(":", 1)[1]
            adb(s, "reverse", f"tcp:{port}", f"tcp:{port}")
        print(f"{name} server: {base}", "(answers /health)" if healthy(base) else "(NO ANSWER on /health from this computer)")

    adb(s, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    # Right after an install the service is still reconnecting and the broadcast reaches no one (am still says it
    # completed), so send again until the app answers.
    line = ""
    for _ in range(8):
        adb(s, "logcat", "-c")
        adb(s, "shell", "am", "broadcast", "-a", f"{PKG}.DEBUG_CONFIG", "-p", PKG,
            "--es", "guide_base", a.guide, "--es", "mode", a.mode, "--es", "tts_base", f"'{a.tts}'", "--es", "engine", a.engine)
        for _ in range(6):
            time.sleep(0.5)
            hits = [l for l in adb(s, "logcat", "-d", "-s", "ToolusGuide:I").stdout.splitlines() if "CONFIG " in l]
            if hits:
                line = hits[-1]
                break
        if line:
            break
    print("app:", line.split("ToolusGuide:", 1)[-1].strip() if line else "no answer (is the accessibility service on?)")


if __name__ == "__main__":
    main()
