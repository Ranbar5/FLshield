import subprocess
import re
import os

AAPT_PATH = r"C:\Users\Soporte\AppData\Local\Android\Sdk\build-tools\34.0.0\aapt.exe"

def get_apk_package_name_aapt(apk_path: str) -> str:
    try:
        # 1. Try badging first
        result = subprocess.run(
            [AAPT_PATH, "dump", "badging", apk_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
        if result.returncode == 0:
            match = re.search(r"package:\s+name='([^']+)'", result.stdout)
            if match:
                return match.group(1)
        
        # 2. If badging fails (e.g. icon resource errors), fallback to xmltree
        result = subprocess.run(
            [AAPT_PATH, "dump", "xmltree", apk_path, "AndroidManifest.xml"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
        for line in result.stdout.splitlines()[:50]:
            match = re.search(r'package="([^"]+)"', line)
            if match:
                return match.group(1)
    except Exception as e:
        print(f"Error executing aapt for {apk_path}: {e}")
    return ""

apks_dir = r"public/apks"
for f in os.listdir(apks_dir):
    if f.endswith(".apk"):
        path = os.path.join(apks_dir, f)
        pkg = get_apk_package_name_aapt(path)
        print(f"{f} -> {pkg}")
