import subprocess
import re

AAPT_PATH = r"C:\Users\Soporte\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe"

def check_split_required(apk_path: str):
    try:
        result = subprocess.run(
            [AAPT_PATH, "dump", "xmltree", apk_path, "AndroidManifest.xml"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
        # Check first 100 lines of xmltree
        lines = result.stdout.splitlines()[:100]
        for line in lines:
            if "isSplitRequired" in line or "split" in line:
                print(f"{apk_path} line: {line.strip()}")
    except Exception as e:
        print(f"Error checking {apk_path}: {e}")

check_split_required("public/apks/Zoom.apk")
check_split_required("public/apks/Waze.apk")
check_split_required("public/apks/Teams.apk")
check_split_required("public/apks/SFA Lucema.apk")
check_split_required("public/apks/Power BI.apk")
