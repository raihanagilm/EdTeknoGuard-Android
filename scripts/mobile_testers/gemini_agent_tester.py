#!/usr/bin/env python3
"""
Gemini Mobile Agent Tester
Autonomous visual mobile QA runner using Google Gemini Multimodal API and ADB.
"""

import argparse
import json
import os
import subprocess
import time
from pathlib import Path
from typing import Dict, Any

try:
    from google import genai
    from google.genai import types
except ImportError:
    genai = None


SYSTEM_PROMPT = """You are an expert autonomous Mobile QA Tester.
You will inspect a mobile application screenshot.
Your goal is to test the app, discover UI/UX bugs, and decide the next action.

Inspect the visual interface for:
1. Truncated text or ugly label clipping (...)
2. Elements overlapping or unclickable buttons
3. Color contrast issues (especially in dark mode)
4. Desktop artifacts (e.g. keyboard shortcuts Ctrl+S on mobile)
5. Layout clipping or broken RTL alignment

Return your answer strictly in valid JSON format:
{
  "bugs_found": ["description of bug 1", "description of bug 2"],
  "observation": "Brief summary of current screen state",
  "action": "tap" | "swipe" | "text" | "key" | "finish",
  "params": {
    "x": 500, "y": 1200,
    "x1": 500, "y1": 1500, "x2": 500, "y2": 500,
    "text": "sample text",
    "keycode": 4
  },
  "reason": "Why this action is chosen"
}
"""


class GeminiAgentTester:
    def __init__(self, api_key: str, output_dir: Path, persona: str):
        self.api_key = api_key
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.persona = persona
        self.client = genai.Client(api_key=api_key) if (genai and api_key) else None

    def run_adb(self, cmd_list: list) -> str:
        res = subprocess.run(["adb"] + cmd_list, capture_output=True, text=True, check=False)
        return res.stdout.strip()

    def capture_screenshot(self, step: int) -> Path:
        dest = self.output_dir / f"step_{step:02d}.png"
        try:
            with open(dest, "wb") as f:
                subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, check=True)
        except Exception as e:
            print(f"Warning: Screenshot failed: {e}")
        return dest

    def execute_action(self, action: str, params: Dict[str, Any]) -> None:
        if action == "tap":
            self.run_adb(["shell", "input", "tap", str(params.get("x", 500)), str(params.get("y", 1000))])
        elif action == "swipe":
            self.run_adb(["shell", "input", "swipe",
                          str(params.get("x1", 500)), str(params.get("y1", 1200)),
                          str(params.get("x2", 500)), str(params.get("y2", 400)), "300"])
        elif action == "text":
            safe_text = str(params.get("text", "")).replace(" ", "%s")
            self.run_adb(["shell", "input", "text", f"'{safe_text}'"])
        elif action == "key":
            self.run_adb(["shell", "input", "keyevent", str(params.get("keycode", 4))])

    def query_gemini(self, image_path: Path, step: int) -> Dict[str, Any]:
        if not self.client:
            return {
                "bugs_found": ["Gemini client not initialized (check GEMINI_API_KEY)"],
                "observation": "No Gemini client available",
                "action": "finish",
                "params": {}
            }

        if not image_path.exists() or image_path.stat().st_size == 0:
            return {"bugs_found": ["Screenshot missing"], "observation": "Blank", "action": "finish", "params": {}}

        with open(image_path, "rb") as f:
            img_bytes = f.read()

        candidate_models = ["gemini-2.0-flash", "gemini-1.5-flash", "gemini-2.5-flash"]
        last_error = ""

        for model_name in candidate_models:
            try:
                response = self.client.models.generate_content(
                    model=model_name,
                    contents=[
                        types.Part.from_bytes(data=img_bytes, mime_type="image/png"),
                        f"Persona: {self.persona}. Step: {step}. Evaluate visual UI and decide next action."
                    ],
                    config=types.GenerateContentConfig(
                        system_instruction=SYSTEM_PROMPT,
                        response_mime_type="application/json",
                        temperature=0.2,
                    )
                )
                return json.loads(response.text)
            except Exception as e:
                last_error = str(e)
                print(f"Model {model_name} attempt failed: {last_error}")
                continue

        return {
            "bugs_found": [f"Gemini API request failed: {last_error}"],
            "observation": f"API Error: {last_error}",
            "action": "finish",
            "params": {}
        }

    def run_test(self, package: str, max_steps: int = 5) -> Dict[str, Any]:
        report = {
            "persona": self.persona,
            "package": package,
            "steps": [],
            "all_bugs": [],
            "status": "PASS"
        }
        report_file = self.output_dir / "report.json"
        with open(report_file, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)

        self.run_adb(["shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"])
        time.sleep(3)

        for step in range(1, max_steps + 1):
            shot = self.capture_screenshot(step)
            decision = self.query_gemini(shot, step)

            bugs = decision.get("bugs_found", [])
            if bugs:
                report["all_bugs"].extend(bugs)
                report["status"] = "FAIL (Issues Found)"

            report["steps"].append({
                "step": step,
                "screenshot": shot.name,
                "observation": decision.get("observation", ""),
                "bugs": bugs,
                "action": decision.get("action", "finish"),
                "reason": decision.get("reason", "")
            })

            action = decision.get("action", "finish")
            if action == "finish" or step == max_steps:
                break

            self.execute_action(action, decision.get("params", {}))
            time.sleep(2)

        report_file = self.output_dir / "report.json"
        with open(report_file, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)

        return report


def main():
    parser = argparse.ArgumentParser(description="Gemini Mobile Agent Tester")
    parser.add_argument("--package", default="com.edtekno.guard", help="Target Android package name")
    parser.add_argument("--persona", default="standard", help="Persona name")
    parser.add_argument("--output-dir", default="test_results", help="Directory for reports and screenshots")
    parser.add_argument("--steps", type=int, default=5, help="Maximum exploration steps")
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not api_key:
        print("WARNING: GEMINI_API_KEY environment variable is missing or empty!")
        report = {
            "persona": args.persona,
            "package": args.package,
            "steps": [],
            "all_bugs": ["GEMINI_API_KEY is not set in GitHub Secrets."],
            "status": "SKIPPED (Missing GEMINI_API_KEY in GitHub Secrets)"
        }
        with open(out_dir / "report.json", "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print("Diagnostic report saved. Exiting gracefully.")
        return

    tester = GeminiAgentTester(api_key=api_key, output_dir=out_dir, persona=args.persona)
    results = tester.run_test(package=args.package, max_steps=args.steps)
    print(f"Test completed for persona {args.persona}: {results['status']}")


if __name__ == "__main__":
    main()
