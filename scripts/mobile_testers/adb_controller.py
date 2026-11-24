#!/usr/bin/env python3
"""
ADB Controller for Mobile Agent Testers
Provides high-level automation for persona-driven mobile testing on Android.
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Tuple


def resolve_adb_path() -> str:
    """Finds adb executable in PATH or standard Android SDK directories."""
    found = shutil.which("adb")
    if found:
        return found

    candidates = [
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
        r"C:\platform-tools\adb.exe",
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate

    return "adb"


class AdbController:
    """Manages ADB communication, persona states, and UI interactions."""

    def __init__(self, device_id: Optional[str] = None):
        self.adb_bin = resolve_adb_path()
        self.device_id = device_id or self.get_default_device()

    def run_cmd(self, args: List[str], capture_output: bool = True) -> subprocess.CompletedProcess:
        """Executes an adb command with target device routing."""
        cmd = [self.adb_bin]
        if self.device_id:
            cmd.extend(["-s", self.device_id])
        cmd.extend(args)
        return subprocess.run(
            cmd,
            stdout=subprocess.PIPE if capture_output else None,
            stderr=subprocess.PIPE if capture_output else None,
            text=True,
            check=False,
        )

    def shell(self, cmd_str: str) -> str:
        """Runs a command string inside adb shell."""
        res = self.run_cmd(["shell", cmd_str])
        return res.stdout.strip()

    def get_default_device(self) -> Optional[str]:
        """Detects the first available authorized device."""
        res = subprocess.run([self.adb_bin, "devices"], stdout=subprocess.PIPE, text=True, check=False)
        lines = res.stdout.strip().splitlines()[1:]
        for line in lines:
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device":
                return parts[0]
        return None

    def get_device_info(self) -> dict:
        """Retrieves model, manufacturer, and Android version."""
        return {
            "device_id": self.device_id,
            "model": self.shell("getprop ro.product.model"),
            "manufacturer": self.shell("getprop ro.product.manufacturer"),
            "android_version": self.shell("getprop ro.build.version.release"),
            "sdk_version": self.shell("getprop ro.build.version.sdk"),
        }

    def set_persona(self, persona: str) -> bool:
        """Applies a testing persona profile to the device."""
        if persona == "a11y_large_text":
            self.shell("settings put system font_scale 1.5")
            return True
        elif persona == "a11y_huge_text":
            self.shell("settings put system font_scale 2.0")
            return True
        elif persona == "compact_density":
            self.shell("wm density 480")
            return True
        elif persona == "rtl_layout":
            self.shell("setprop debug.force_rtl 1")
            return True
        elif persona == "dark_mode":
            self.shell("cmd uimode night yes")
            return True
        elif persona == "light_mode":
            self.shell("cmd uimode night no")
            return True
        return False

    def reset_persona(self) -> None:
        """Restores font scale, display density, and layout direction."""
        self.shell("settings put system font_scale 1.0")
        self.shell("wm size reset")
        self.shell("wm density reset")
        self.shell("setprop debug.force_rtl 0")

    def capture_screen(self, output_file: str) -> str:
        """Takes a screenshot and writes it locally."""
        out_path = Path(output_file).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "wb") as f:
            cmd = [self.adb_bin]
            if self.device_id:
                cmd.extend(["-s", self.device_id])
            cmd.extend(["exec-out", "screencap", "-p"])
            subprocess.run(cmd, stdout=f, check=True)
        return str(out_path)

    def dump_hierarchy(self, output_file: str) -> str:
        """Dumps UI hierarchy XML from device and saves locally."""
        out_path = Path(output_file).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        remote_tmp = "/data/local/tmp/window_dump.xml"
        self.shell(f"uiautomator dump {remote_tmp}")
        self.run_cmd(["pull", remote_tmp, str(out_path)])
        return str(out_path)

    def tap(self, x: int, y: int) -> None:
        """Simulates a screen tap at (x, y)."""
        self.shell(f"input tap {x} {y}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        """Simulates a swipe gesture from (x1, y1) to (x2, y2)."""
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")

    def type_text(self, text: str) -> None:
        """Inputs text (spaces replaced by %s for adb compatibility)."""
        escaped = text.replace(" ", "%s").replace("'", "\\'")
        self.shell(f"input text '{escaped}'")

    def press_key(self, keycode: int) -> None:
        """Sends an Android KeyEvent (4: Back, 3: Home, 66: Enter)."""
        self.shell(f"input keyevent {keycode}")

    def launch_app(self, package_name: str) -> str:
        """Launches target app launcher activity."""
        return self.shell(f"monkey -p {package_name} -c android.intent.category.LAUNCHER 1")

    def force_stop(self, package_name: str) -> None:
        """Stops the specified package."""
        self.shell(f"am force-stop {package_name}")

    def get_crashes(self, package_name: str) -> str:
        """Inspects logcat for recent fatal exceptions matching package."""
        logs = self.shell(f"logcat -d -t 1000 *:E")
        relevant = [line for line in logs.splitlines() if package_name in line or "FATAL" in line]
        return "\n".join(relevant[-20:])


def main():
    parser = argparse.ArgumentParser(description="ADB Controller CLI for Mobile Agent Testers")
    parser.add_argument("--device", "-s", help="Target device ID")
    sub = parser.add_subparsers(dest="action", required=True)

    sub.add_parser("info", help="Get connected device details")
    sub.add_parser("devices", help="List all connected devices")

    p_persona = sub.add_parser("set-persona", help="Set test persona")
    p_persona.add_argument("persona", choices=[
        "a11y_large_text", "a11y_huge_text", "compact_density", "rtl_layout", "dark_mode", "light_mode"
    ])

    sub.add_parser("reset-persona", help="Reset device persona settings")

    p_shot = sub.add_parser("screenshot", help="Capture screen image")
    p_shot.add_argument("--output", "-o", default="screenshot.png")

    p_dump = sub.add_parser("dump", help="Dump UI Automator hierarchy")
    p_dump.add_argument("--output", "-o", default="hierarchy.xml")

    p_tap = sub.add_parser("tap", help="Tap screen coordinates")
    p_tap.add_argument("x", type=int)
    p_tap.add_argument("y", type=int)

    p_swipe = sub.add_parser("swipe", help="Swipe gesture")
    p_swipe.add_argument("x1", type=int)
    p_swipe.add_argument("y1", type=int)
    p_swipe.add_argument("x2", type=int)
    p_swipe.add_argument("y2", type=int)

    p_text = sub.add_parser("text", help="Type text into focused element")
    p_text.add_argument("text", type=str)

    p_key = sub.add_parser("key", help="Press keycode (4=Back, 3=Home)")
    p_key.add_argument("keycode", type=int)

    p_launch = sub.add_parser("launch", help="Launch target app package")
    p_launch.add_argument("package", type=str)

    p_stop = sub.add_parser("stop", help="Force stop target app")
    p_stop.add_argument("package", type=str)

    args = parser.parse_args()
    ctrl = AdbController(device_id=args.device)

    if args.action == "devices":
        res = subprocess.run([ctrl.adb_bin, "devices"], check=False)
        sys.exit(res.returncode)
    elif args.action == "info":
        print(ctrl.get_device_info())
    elif args.action == "set-persona":
        ok = ctrl.set_persona(args.persona)
        print(f"Persona '{args.persona}' applied: {ok}")
    elif args.action == "reset-persona":
        ctrl.reset_persona()
        print("Device settings restored to default.")
    elif args.action == "screenshot":
        saved = ctrl.capture_screen(args.output)
        print(f"Screenshot saved to: {saved}")
    elif args.action == "dump":
        saved = ctrl.dump_hierarchy(args.output)
        print(f"Hierarchy dumped to: {saved}")
    elif args.action == "tap":
        ctrl.tap(args.x, args.y)
        print(f"Tapped ({args.x}, {args.y})")
    elif args.action == "swipe":
        ctrl.swipe(args.x1, args.y1, args.x2, args.y2)
        print(f"Swiped ({args.x1}, {args.y1}) -> ({args.x2}, {args.y2})")
    elif args.action == "text":
        ctrl.type_text(args.text)
        print(f"Typed text: {args.text}")
    elif args.action == "key":
        ctrl.press_key(args.keycode)
        print(f"Sent keycode: {args.keycode}")
    elif args.action == "launch":
        print(ctrl.launch_app(args.package))
    elif args.action == "stop":
        ctrl.force_stop(args.package)
        print(f"Stopped {args.package}")


if __name__ == "__main__":
    main()
