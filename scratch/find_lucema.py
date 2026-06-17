import requests

urls = [
    "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQGUohbpxsbPmBiWLoyljoN5i32XZLl6D8nCSiMIhTG17f3qUWb7J77zVtuME2isrv_cnzN33G2TNOsPC7bXaMz1EPZlnxwQ1OoTry9xuLNydviIbp_8waEpaEsbbWFx62sf_hqpAqTdvrCAnE94HFQwiL1A3PSuGkRD3PZrEzzH",
    "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQEjhWQxp_AY-lnCD9UL_M3oczRgzc9xOiMskRllMIC5oojwlgppoov0cF18wpcvx1wPwK2woYblAfEquRqRwPTd-8ck-Xs-RiPL-NkeBKAu7r1v9Hraka6Xpgcb33BnZLcscSNTbdkNcVHq-imJC_EHh_MX6N0-tf5GHJX-n3rmagydIWWtwY88"
]

headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

for i, u in enumerate(urls):
    try:
        r = requests.get(u, headers=headers, allow_redirects=True, timeout=10)
        print(f"URL {i} final url: {r.url}")
    except Exception as e:
        print(f"URL {i} failed: {e}")
