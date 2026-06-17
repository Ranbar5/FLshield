import subprocess
import time
import sys

# Clean up existing ssh processes
subprocess.run("taskkill /f /im ssh.exe", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

cmd = ["ssh", "-tt", "-o", "StrictHostKeyChecking=no", "-p", "443", "-R0:127.0.0.1:3000", "free@a.pinggy.io"]
print(f"Starting tunnel loop: {' '.join(cmd)}")

proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

# Read output for 12 seconds
start_time = time.time()
output = []
while time.time() - start_time < 12:
    try:
        # Read one byte (blocking read, but we have a timeout check)
        byte = proc.stdout.read(1)
        if not byte:
            break
        char = byte.decode('utf-8', errors='ignore')
        sys.stdout.write(char)
        sys.stdout.flush()
        output.append(char)
    except Exception as e:
        print(f"\nRead error: {e}")
        break

full_output = "".join(output)
print("\n--- LOOP FINISHED ---")

with open("tunnel_output_loop.txt", "w", encoding="utf-8") as f:
    f.write(full_output)

if proc.poll() is None:
    print("Tunnel process is still running in the background.")
else:
    print(f"Tunnel process terminated with code: {proc.poll()}")
