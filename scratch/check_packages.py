import requests

packages = [
    "com.waze",
    "com.microsoft.teams",
    "com.microsoft.powerbim",
    "com.ionicframework.sfaLucema"
]

for pkg in packages:
    url = f"https://ws75.aptoide.com/api/7/app/get/package_name={pkg}"
    try:
        r = requests.get(url, timeout=10)
        data = r.json()
        nodes = data.get("nodes", {})
        meta = nodes.get("meta", {})
        meta_data = meta.get("data", {})
        file_info = meta_data.get("file", {})
        path = file_info.get("path")
        print(f"Package: {pkg} -> Found: {path is not None} -> Path: {path}")
    except Exception as e:
        print(f"Package: {pkg} -> Failed: {e}")
