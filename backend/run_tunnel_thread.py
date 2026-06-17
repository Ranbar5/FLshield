import subprocess
import time
import threading
import sys

# Clean up existing ssh processes
subprocess.run("taskkill /f /im ssh.exe", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

cmd = ["ssh", "-tt", "-o", "StrictHostKeyChecking=no", "-p", "443", "-R0:127.0.0.1:3000", "free@a.pinggy.io"]
print(f"Starting thread tunnel: {' '.join(cmd)}")

proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)

output_lines = []

def reader():
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        output_lines.append(line)
        sys.stdout.write(line)
        sys.stdout.flush()

t = threading.Thread(target=reader)
t.daemon = True
t.start()

# Wait 10 seconds to let it establish and capture the output
time.sleep(10)

# Write to log file
with open("tunnel_output_thread.txt", "w", encoding="utf-8") as f:
    f.write("".join(output_lines))

print("\n--- THREAD CAPTURE FINISHED ---")
if proc.poll() is None:
    print("Tunnel is running in background.")
else:
    print(f"Tunnel terminated with code {proc.poll()}")
