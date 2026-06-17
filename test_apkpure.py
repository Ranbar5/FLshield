from apkpure.apkpure import ApkPure
import sys

try:
    print("Initializing ApkPure...")
    api = ApkPure()
    pkg = "com.waze"
    print(f"Getting info for {pkg}...")
    info = api.get_info(pkg)
    print(f"Info: {info}")
    
    print(f"Downloading {pkg}...")
    path = api.download(pkg)
    print(f"Downloaded to {path}")
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
