# Provisioning del kiosco sin cuenta de Google + WhatsApp

Documento operativo para instalar el kiosco FLShield en modo propietario,
sin iniciar sesion con Gmail, y resolver la instalacion/arranque de WhatsApp.

## 1. Instalar WhatsApp sin Play Store (sideload)

Dado que el kiosco no tiene cuenta de Google, no se usa Play Store.
Pasos (via ADB, depuracion USB habilitada):

```bash
# Descargar el APK oficial desde el sitio de WhatsApp:
#   https://www.whatsapp.com/android -> "Descargar ahora" (whatsapp.apk)
# Verificar checksum/md5 recomendado. Copiar a una PC con ADB.

adb wait-for-device
adb install -r whatsapp.apk
```

Alternativa: usar un gestor de archivos o `pm install` con el APK en SD.

> Limitacion: sin Play Store no hay actualizaciones automaticas de WhatsApp.
> Para actualizar, repetir el sideload del APK nuevo por ADB.

## 2. Arreglar "la fecha del device es erronea" al abrir WhatsApp

WhatsApp valida la hora del dispositivo contra su servidor y aborta la
configuracion si el reloj del kiosco no esta sincronizado (tipico en kioscos
offline o recien formateados).

### Automatico (recomendado, requiere red)

```bash
adb shell settings put global auto_time 1
adb shell settings put global auto_time_zone 1
adb shell settings put global ntp_server time.android.com
adb shell date
# Reiniciar si no tomo efecto y volver a verificar la hora.
```

### Manual (sin red)

```bash
adb shell settings put global auto_time 0
adb shell settings put global auto_time_zone 0
adb shell setprop persist.sys.timezone "America/Bogota"   # tu zona
adb root
adb shell date -s "2026-09-08 10:30:00"                    # hora real
adb shell date
```

### Script automatizado

En el repo: `provisioning/set-device-time.sh`

```bash
./provisioning/set-device-time.sh --serial R58M12345 --dry-run   # previsualizar
./provisioning/set-device-time.sh --serial R58M12345              # aplicar (auto)
./provisioning/set-device-time.sh --set-manual \
  --date "2026-09-08 10:30:00" --tz "America/Bogota"             # sin red
```

Regla: la hora del kiosco debe coincidir con la real (+/- 5 min) ANTES de
abrir WhatsApp por primera vez.

## 3. Nota sobre verificar el numero

El sideload resuelve la instalacion, pero WhatsApp sigue exigiendo una SIM
con numero valido para la verificacion por SMS/llamada. Prepara una SIM
activa en el kiosco antes de la configuracion inicial.

## 4. Orden recomendado de provisioning

1. Conectar SIM y encender el kiosco.
2. Sincronizar fecha/hora (`set-device-time.sh`).
3. Sideload del APK de WhatsApp (`adb install`).
4. Abrir WhatsApp y completar la configuracion (verificacion de numero).
5. Instalar/configurar FLShield (modo propietario) y bloquear el launcher.
6. Ajuste manual/ADB de fecha/hora automatica segun la politica ofrecida.
