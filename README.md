# FLShield (AppLocker)

Kiosco Android con control central: app en primer plano, launcher propio, panel web y servidor FastAPI.

## Estructura

- `app/` — Proyecto Android (Kotlin + Compose)
- `backend/` — Servidor Python (`server.py`) y panel en `backend/public/`

## Servidor (entorno de pruebas)

```powershell
cd backend
python -m venv ..\.venv
..\.venv\Scripts\activate
pip install -r ..\requirements.txt
python server.py
```

Panel: http://localhost:3000  
En el móvil usa la IP de tu PC, por ejemplo `http://192.168.1.x:3000`.

## Android — compilación e instalación

```powershell
cd app
.\gradlew :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Device Owner (kiosco completo, Moto G54 u otros)

Tras instalar la app (sin cuentas de usuario en el dispositivo, o tras factory reset):

```powershell
adb shell dpm set-device-owner com.example.applocker/.AppLockerDeviceAdminReceiver
```

Si falla, quita cuentas Google o usa un dispositivo recién reseteado.

## Permisos en el dispositivo

1. Superposición (overlay)
2. Estadísticas de uso
3. Accesibilidad (recomendado)
4. Administrador de dispositivo
5. FLShield como launcher predeterminado

## Apps en el kiosco

Las apps del grid salen de **Apps Permitidas** en el panel. Si la lista está vacía, solo aparecen esenciales (teléfono, mensajes, etc.).

1. Conecta el móvil (aprovisionamiento con URL del servidor).
2. En el panel → **Apps Permitidas** → agrega paquetes (atajos Motorola o manual).
3. **Guardar cambios** (envía la lista por WebSocket al dispositivo).

Listar paquetes instalados (usuario) en el Moto:

```powershell
adb shell pm list packages -3
```

Lanzables con launcher:

```powershell
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

## Reinicio automático

`BootCompletedReceiver` arranca el servicio de protección si el dispositivo ya estaba aprovisionado.

## Notas

- Backend Node (`server.js`) eliminado; usar solo `server.py`.
- Seguridad (HTTPS, auth) pendiente para producción.
