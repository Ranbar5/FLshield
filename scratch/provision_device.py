import subprocess
import time

adb_path = r"C:\Users\Soporte\AppData\Local\Android\Sdk\platform-tools\adb.exe"
device = "ZY22JNLPPQ"

print("Starting MainActivity...")
subprocess.run([adb_path, "-s", device, "shell", "am", "start", "-n", "com.example.applocker/.MainActivity"])
time.sleep(3)

print("Typing server URL...")
subprocess.run([adb_path, "-s", device, "shell", "input", "text", "http://172.16.33.234:3000"])
time.sleep(1)

print("Pressing Enter to provision...")
subprocess.run([adb_path, "-s", device, "shell", "input", "keyevent", "66"])
print("Done!")
