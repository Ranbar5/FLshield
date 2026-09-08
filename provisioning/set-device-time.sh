#!/usr/bin/env bash
#
# set-device-time.sh
# ----------------
# Asegura que la fecha/hora del kiosco sea correcta ANTES de abrir WhatsApp.
# WhatsApp aborta la configuracion ("fecha del device erronea") cuando el
# reloj del dispositivo difiere de la hora real o no esta sincronizado.
#
# Uso:
#   ./set-device-time.sh [--serial <SERIAL>] [--dry-run]
#
# Ejemplos:
#   ./set-device-time.sh --serial R58M12345 --dry-run
#   ./set-device-time.sh
#
# Requiere: adb en el PATH, depuracion USB habilitada en el kiosco
# (propietario, sin cuenta de Google).
#
# NOTA: aunque el flujo principal del kiosco es offline, la hora AUTOMATICA
# de red (auto_time=1) debe quedar activada porque WhatsApp la valida con su
# servidor. Sin red, ajustar manualmente con --set-manual.

set -euo pipefail

ADB="${ADB:-adb}"
SERIAL=""
DRY_RUN=0
SET_MANUAL=0
MANUAL_DATE=""
MANUAL_TZ=""

usage() {
  cat <<'EOF'
  set-device-time.sh  — sincroniza la fecha/hora del kiosco antes de WhatsApp.

  Opciones:
    --serial <SERIAL>   Dispositivo ADB al que dirigirse (si hay varios).
    --dry-run           Muestra los comandos sin ejecutarlos.
    --set-manual        Fija fecha/hora y zona horaria manualmente (sin red).
                        Requiere --date "--/--/---- --:--:--" y --tz <Area/Ciudad>.
    --date <VALOR>      Hora a aplicar con --set-manual. Ej: "2026-09-08 10:30:00".
    --tz <VALOR>        Zona horaria a aplicar con --set-manual. Ej: "America/Bogota".
    -h, --help          Muestra esta ayuda.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)           SERIAL="$2"; shift 2 ;;
    --dry-run)          DRY_RUN=1; shift ;;
    --set-manual)       SET_MANUAL=1; shift ;;
    --date)             MANUAL_DATE="$2"; shift 2 ;;
    --tz)               MANUAL_TZ="$2"; shift 2 ;;
    -h|--help)          usage; exit 0 ;;
    *) echo "Opcion desconocida: $1" >&2; usage >&2; exit 1 ;;
  esac
done

adb_cmd() {
  local cmd_args=("$ADB")
  [[ -n "$SERIAL" ]] && cmd_args+=( -s "$SERIAL" )
  cmd_args+=( "$@" )
  if (( DRY_RUN )); then
    printf '  > %s\n' "${cmd_args[*]}"
    return 0
  fi
  "$ADB" "${cmd_args[@]}"
}

# Solo lectura / validaciones no escritoras tambien pasan por dry-run
run() {
  if (( DRY_RUN )); then
    adb_cmd "${@:1}"
  else
    adb_cmd "${@:1}"
  fi
}

echo "== Verificando conexion ADB =="
target=( )
[[ -n "$SERIAL" ]] && target+=( "-s" "$SERIAL" )
if ! "$ADB" "${target[@]}" get-state >/dev/null 2>&1; then
  echo "ERROR: no hay dispositivo ADB disponible${SERIAL:+ (serial: $SERIAL)}." >&2
  exit 1
fi

echo "== Hora actual en el dispositivo =="
run shell date "+%Y-%m-%d %H:%M:%S %Z"

if (( SET_MANUAL )); then
  if [[ -z "$MANUAL_DATE" || -z "$MANUAL_TZ" ]]; then
    echo "ERROR: --set-manual requiere --date y --tz." >&2
    exit 1
  fi
  echo "== Ajuste MANUAL de fecha/hora y zona =="
  run shell settings put global auto_time 0
  run shell settings put global auto_time_zone 0
  run shell setprop persist.sys.timezone "$MANUAL_TZ"
  # ADB permite fijar la hora con 'date' via root si el device esta debuggable.
  # Si falla sin root, usa:  adb shell am broadcast -a com.android.intent.action.SET_DATE
  run shell date -s "$MANUAL_DATE" || \
    echo "ADVERTENCIA: no se pudo fijar la hora via 'date' sin root." >&2
else
  echo "== Activando hora y zona automaticas de red =="
  run shell settings put global auto_time 1
  run shell settings put global auto_time_zone 1
  run shell settings put global ntp_server time.android.com
  echo "  (el ajuste automatico tomara efecto normalmente < 60s y tras reinicio)"
fi

echo "== Esperando sincronizacion =="
if (( ! DRY_RUN && ! SET_MANUAL )); then
  sleep 5
fi

echo "== Hora final en el dispositivo =="
run shell date "+%Y-%m-%d %H:%M:%S %Z"

echo ""
echo "Comprobacion rapida: la hora del dispositivo debe coincidir"
echo "con tu hora local (+/- 5 min) antes de abrir WhatsApp."
if (( DRY_RUN )); then
  echo "(modo dry-run: no se ejecuto nada)"
fi
