import requests
import sys

pkg = "com.waze"
url = f"https://ws75.aptoide.com/api/7/app/get/package_name={pkg}"
try:
    res = requests.get(url)
    data = res.json()
    if "nodes" in data and "meta" in data["nodes"] and "data" in data["nodes"]["meta"]:
        meta_data = data["nodes"]["meta"]["data"]
        file_info = meta_data.get("file", {})
        download_path = file_info.get("path")
        print(f"Direct download path: {download_path}")
        
        # Test download
        print("Testing download of direct path...")
        head_res = requests.head(download_path)
        print(f"Head status: {head_res.status_code}")
        print(f"Content-Type: {head_res.headers.get('Content-Type')}")
        print(f"Content-Length: {head_res.headers.get('Content-Length')}")
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
