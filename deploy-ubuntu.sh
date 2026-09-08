#!/bin/bash
set -e

echo "=== FLShield Deploy Ubuntu ==="

FLSHIELD_DIR="/opt/flshield"
REPO_DIR="/home/narvar/FLshield/FLshield"

# 1. Detener servidor si corre
echo "[1/6] Deteniendo servidor actual..."
pkill -f "server.py" 2>/dev/null || true
sleep 1

# 2. Copiar archivos actualizados
echo "[2/6] Copiando archivos..."
sudo mkdir -p "$FLSHIELD_DIR"
sudo cp -r "$REPO_DIR/backend" "$FLSHIELD_DIR/"
sudo cp "$REPO_DIR/backend/.env.example" "$FLSHIELD_DIR/.env"

# 3. Instalar dependencias
echo "[3/6] Instalando dependencias..."
pip install --break-system-packages websockets 2>/dev/null || pip3 install --break-system-packages websockets 2>/dev/null || echo "⚠️ Instala websockets manualmente: pip install websockets"
pip install --break-system-packages uvicorn[standard] 2>/dev/null || true

# 4. Crear archivo .env
echo "[4/6] Configurando variables de entorno..."
if [ ! -f "$FLSHIELD_DIR/.env" ]; then
    cat > "$FLSHIELD_DIR/.env" << 'ENVEOF'
FLSHIELD_ADMIN_USER=FLAdmin
FLSHIELD_ADMIN_PASS=TNm5VqCferU6hKtW32upxWOae
PORT=3000
ENVEOF
fi

# 5. Crear servicio systemd
echo "[5/6] Creando servicio systemd..."
sudo tee /etc/systemd/system/flshield.service > /dev/null << 'UNIT'
[Unit]
Description=FLShield Control Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/flshield/backend
EnvironmentFile=/opt/flshield/.env
ExecStart=/usr/bin/python3 /opt/flshield/backend/server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable flshield
sudo systemctl restart flshield

# 6. Verificar
echo "[6/6] Verificando..."
sleep 2
if curl -s http://localhost:3000/api/auth/status > /dev/null 2>&1; then
    echo "✅ Servidor corriendo en http://localhost:3000"
    echo "   Panel web: http://<IP_DEL_SERVER>:3000"
else
    echo "⚠️ Revisa logs: sudo journalctl -u flshield -n 20"
fi

echo "=== Listo ==="
