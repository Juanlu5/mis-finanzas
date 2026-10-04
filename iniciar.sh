#!/usr/bin/env bash
# Arranca Mis Finanzas sin escribir el comando de Maven.
# Compila el jar solo si no existe o si el código/pom cambió desde la última vez.
set -euo pipefail

# data/ y logs/ son rutas relativas: hay que ejecutar desde la carpeta del proyecto
cd "$(dirname "$(readlink -f "$0")")"

JAR=target/app-finanzas-0.1.0.jar

# Aplicación registrada en Enable Banking para "Descargar del banco": su id va en
# data/enablebanking/app-id y su clave privada en data/enablebanking/private_key.pem (data/ no va a
# git). Si la variable ya viene del entorno, manda esa. Sin el fichero, la app arranca igual y solo
# la descarga del banco dice que no está configurada.
APP_ID_FILE=data/enablebanking/app-id
if [ -z "${ENABLE_BANKING_APP_ID:-}" ] && [ -f "$APP_ID_FILE" ]; then
    export ENABLE_BANKING_APP_ID="$(tr -d '[:space:]' < "$APP_ID_FILE")"
fi

if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -print -quit)" ]; then
    echo "Compilando..."
    ./mvnw -q -DskipTests package
fi

# Se arranca una copia del jar: recompilar después (demo.sh, ./mvnw package) reescribe el original, y cambiarle el
# jar a una app en marcha puede tumbarla. Si ya está en marcha, no se toca su copia.
RUNNING=target/app-en-marcha.jar
if pgrep -f "java -jar $RUNNING" > /dev/null; then
    echo "Mis Finanzas ya está en marcha (https://127.0.0.1:8000)."
    exit 1
fi
cp "$JAR" "$RUNNING"
exec java -jar "$RUNNING" "$@"
