import requests
import sys

url = "https://d.apkpure.com/b/APK/com.waze?version=latest"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

try:
    print(f"Downloading from {url}...")
    response = requests.get(url, headers=headers, stream=True, allow_redirects=True)
    print(f"Response status: {response.status_code}")
    print(f"Response headers: {response.headers}")
    
    if response.status_code == 200:
        filename = "waze_test.apk"
        # check if it is an octet-stream or apk
        content_type = response.headers.get("Content-Type", "")
        print(f"Content-Type: {content_type}")
        
        with open(filename, "wb") as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
        print("Download complete!")
    else:
        print("Download failed.")
        sys.exit(1)
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
