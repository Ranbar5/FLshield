import os
import requests
import zipfile

package_name = "com.ionicframework.sfaLucema"
xapk_url = f"https://d.apkpure.com/b/XAPK/{package_name}?version=latest"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
print(f"Checking XAPK url: {xapk_url}")
try:
    res = requests.get(xapk_url, headers=headers, stream=True, allow_redirects=True, timeout=30)
    print(f"Status Code: {res.status_code}")
    print(f"Content-Disposition: {res.headers.get('Content-Disposition')}")
    if res.status_code == 200:
        temp_path = "xapk_test.zip"
        with open(temp_path, "wb") as f:
            for chunk in res.iter_content(chunk_size=8192):
                if chunk:
                    f.write(chunk)
        print(f"File size: {os.path.getsize(temp_path)}")
        if zipfile.is_zipfile(temp_path):
            with zipfile.ZipFile(temp_path) as z:
                print(f"ZIP files list: {z.namelist()}")
        os.remove(temp_path)
except Exception as e:
    print(f"Exception: {e}")
