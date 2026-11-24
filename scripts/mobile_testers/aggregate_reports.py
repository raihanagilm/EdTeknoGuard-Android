#!/usr/bin/env python3
"""
Aggregate Reports for Mobile Agent Testers
Consolidates individual JSON test reports from parallel device runners into a single Markdown summary.
"""

import argparse
import json
from pathlib import Path
from typing import List, Dict, Any


def aggregate_reports(input_dir: Path, output_file: Path) -> None:
    reports: List[Dict[str, Any]] = []

    for path in input_dir.rglob("report.json"):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
                reports.append(data)
        except Exception as e:
            print(f"Warning: Failed to parse {path}: {e}")

    reports.sort(key=lambda r: r.get("persona", ""))

    total_devices = len(reports)
    passed_count = sum(1 for r in reports if r.get("status") == "PASS")
    failed_count = total_devices - passed_count

    all_bugs = []
    for r in reports:
        for bug in r.get("all_bugs", []):
            if bug not in all_bugs:
                all_bugs.append(bug)

    md = []
    md.append("# 📱 Mobile Agent QA Testing Summary (Gemini 20-Device Matrix)")
    md.append("")
    md.append(f"- **Total Perangkat Diuji:** {total_devices}")
    md.append(f"- **Status:** ✅ {passed_count} PASS | ⚠️ {failed_count} ISSUES FOUND")
    md.append(f"- **Total Isu Unik Ditemukan:** {len(all_bugs)}")
    md.append("")
    md.append("## 1. Matriks Hasil Pengujian Perangkat")
    md.append("")
    md.append("| No | Profil Perangkat / Persona | Status | Jumlah Isu | Catatan Observasi |")
    md.append("|:---|:---|:---:|:---:|:---|")

    for i, r in enumerate(reports, 1):
        persona = r.get("persona", "Unknown")
        status = r.get("status", "UNKNOWN")
        badge = "✅ PASS" if status == "PASS" else "⚠️ ISSUES"
        bugs_count = len(r.get("all_bugs", []))
        last_obs = r.get("steps", [{}])[-1].get("observation", "-") if r.get("steps") else "-"
        md.append(f"| {i} | {persona} | {badge} | {bugs_count} | {last_obs} |")

    md.append("")
    md.append("## 2. Daftar Isu Visual & Aksesibilitas Terdeteksi")
    md.append("")
    if all_bugs:
        for idx, bug in enumerate(all_bugs, 1):
            md.append(f"{idx}. {bug}")
    else:
        md.append("Tidak ditemukan anomali visual atau bug tata letak yang signifikan.")

    md.append("")
    md.append("---")
    md.append("*Laporan ini digenerate otomatis oleh Gemini Mobile Agent Testers via GitHub Actions.*")

    with open(output_file, "w", encoding="utf-8") as f:
        f.write("\n".join(md))

    print(f"Summary written to {output_file} with {total_devices} device reports.")


def main():
    parser = argparse.ArgumentParser(description="Aggregate individual mobile QA test reports")
    parser.add_argument("--input-dir", required=True, help="Directory containing report.json files")
    parser.add_argument("--output", default="SUMMARY_REPORT.md", help="Output markdown file path")
    args = parser.parse_args()

    aggregate_reports(Path(args.input_dir), Path(args.output))


if __name__ == "__main__":
    main()
