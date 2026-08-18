#!/usr/bin/env python3
"""Render the VitalWork RAP-126 grant diagrams (HTML) to PNG via headless Edge/Chrome.

Usage: python render_diagrams.py

Renders each diagram at a generous window height, then autocrops the
screenshot down to its actual content bounding box (the page background is
pure white, so any solid-white margin is trimmed). Output goes to ./png/.
"""
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageChops

DIAGRAMS_DIR = Path(__file__).parent
PNG_DIR = DIAGRAMS_DIR / "png"
PROFILE_DIR = Path(r"C:\Users\andre\AppData\Local\Temp\edge-headless-profile")

# Chromium-based browser executable (adjust if Chrome is installed instead).
BROWSER_CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
]

# (filename, viewport width, tall-enough probe height)
DIAGRAMS = [
    ("01_system_architecture_en.html", 938, 700),
    ("02_operator_session_workflow_en.html", 800, 1500),
    ("03_data_model_en.html", 780, 1500),
    ("04_data_flow_pipeline_en.html", 560, 1400),
    ("05_watch_store_forward_en.html", 740, 1700),
    ("06_export_schema_en.html", 950, 1620),
]

WHITE = (255, 255, 255)
PAD = 16  # px of white margin to keep around the autocropped content


def find_browser() -> str:
    for candidate in BROWSER_CANDIDATES:
        if Path(candidate).is_file():
            return candidate
    print("ERROR: no Chromium-based browser found.", file=sys.stderr)
    sys.exit(1)


def screenshot(browser: str, html_path: Path, out_path: Path, width: int, height: int) -> None:
    cmd = [
        browser,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--hide-scrollbars",
        "--force-device-scale-factor=2",
        f"--user-data-dir={PROFILE_DIR}",
        f"--window-size={width},{height}",
        f"--screenshot={out_path}",
        html_path.as_uri(),
    ]
    subprocess.run(cmd, check=True, capture_output=True, timeout=60)


def autocrop_to_content(png_path: Path) -> None:
    img = Image.open(png_path).convert("RGB")
    bg = Image.new("RGB", img.size, WHITE)
    diff = ImageChops.difference(img, bg)
    bbox = diff.getbbox()
    if bbox is None:
        return  # blank page — leave as-is
    left, top, right, bottom = bbox
    left = max(0, left - PAD)
    top = max(0, top - PAD)
    right = min(img.width, right + PAD)
    bottom = min(img.height, bottom + PAD)
    img.crop((left, top, right, bottom)).save(png_path)


def main() -> None:
    browser = find_browser()
    PNG_DIR.mkdir(exist_ok=True)
    print(f"Using browser: {browser}")

    for filename, width, height in DIAGRAMS:
        html_path = DIAGRAMS_DIR / filename
        out_path = PNG_DIR / (html_path.stem + ".png")
        print(f"Rendering {filename} -> {out_path.name} ({width}x{height} probe)")
        screenshot(browser, html_path, out_path, width, height)
        autocrop_to_content(out_path)
        with Image.open(out_path) as im:
            print(f"  final size: {im.size[0]}x{im.size[1]}")

    print("Done.")


if __name__ == "__main__":
    main()
