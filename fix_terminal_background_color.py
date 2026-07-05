#!/usr/bin/env python3
"""
fix_terminal_background_color.py

Hotfix: ganti ?attr/colorBackground → ?attr/colorSurface di layout terminal.
colorBackground tidak tersedia di theme ini; colorSurface otomatis adapt
dark/light theme (setara MaterialTheme.colorScheme.surface di Compose).

Cara pakai:
    python3 fix_terminal_background_color.py
    python3 fix_terminal_background_color.py --repo /path/ke/tiny_computer-test
"""

import argparse, sys
from pathlib import Path

FILE = "app/src/main/res/layout/tc4_fragment_terminal.xml"
OLD  = 'android:background="?attr/colorBackground">'
NEW  = 'android:background="?attr/colorSurface">'


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=".", help="Root repo tiny_computer-test")
    args = ap.parse_args()
    path = Path(args.repo).resolve() / FILE

    if not path.exists():
        sys.exit(f"[ERROR] {path} tidak ditemukan.")

    text = path.read_text(encoding="utf-8")

    if NEW in text:
        print("[skip] colorSurface sudah terpasang.")
        return

    if OLD not in text:
        sys.exit(
            "[ERROR] Anchor tidak ditemukan.\n"
            "Pastikan patch remove_terminal_blur.py sudah dijalankan dulu."
        )

    path.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    print("[ok] ?attr/colorBackground → ?attr/colorSurface")
    print("Build ulang sekarang.")


if __name__ == "__main__":
    main()
