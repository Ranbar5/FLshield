import subprocess
import time
import os

# Clean up existing ssh processes
subprocess.run("taskkill /f /im ssh.exe", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

cmd = ["ssh", "-tt", "-o", "StrictHostKeyChecking=no", "-p", "443", "-R0:127.0.0.1:3000", "free@a.pinggy.io"]
print(f"Starting tunnel: {' '.join(cmd)}")

proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=False, bufsize=0)

# Wait for tunnel initialization
time.sleep(8)

# Read output non-blocking
stdout_data = ""
stderr_data = ""

try:
    os.set_blocking(proc.stdout.fileno(), False)
    raw_out = proc.stdout.read()
    if raw_out is not None:
        stdout_data = raw_out.decode('utf-8', errors='ignore')
except Exception as e:
    stdout_data = f"Error reading stdout: {e}"
    
try:
    os.set_blocking(proc.stderr.fileno(), False)
    raw_err = proc.stderr.read()
    if raw_err is not None:
        stderr_data = raw_err.decode('utf-8', errors='ignore')
except Exception as e:
    stderr_data = f"Error reading stderr: {e}"

print("--- STDOUT ---")
print(stdout_data)
print("--- STDERR ---")
print(stderr_data)

with open("tunnel_output.txt", "w", encoding="utf-8") as f:
    f.write("STDOUT:\n" + stdout_data + "\n\nSTDERR:\n" + stderr_data)

# Keep the tunnel running in background if it succeeded
if proc.poll() is None:
    print("Tunnel process is still running in the background.")
else:
    print(f"Tunnel process terminated with code: {proc.poll()}")
