#!/usr/bin/env bash
# Arranca la demo de Mis Finanzas: datos inventados, en https://127.0.0.1:8001 (usuario demo, contraseña demo).
# Usa su propia carpeta (target/demo-data), que se borra en cada arranque: nunca toca data/ ni el banco, y puede
# ir a la vez que la app de verdad (puerto 8000). Detalle en docs/demo.md.
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

JAR=target/app-finanzas-0.1.0.jar
DEMO_DIR=target/demo-data

RUNNING=target/demo-en-marcha.jar
if pgrep -f "java -jar $RUNNING" > /dev/null; then
    echo "La demo ya está en marcha (https://127.0.0.1:8001)."
    exit 1
fi

if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -print -quit)" ]; then
    echo "Compilando..."
    ./mvnw -q -DskipTests package
fi

# Cada arranque empieza de cero; el certificado se conserva para que el navegador no vuelva a avisar
if [ -d "$DEMO_DIR" ]; then
    find "$DEMO_DIR" -mindepth 1 -maxdepth 1 ! -name tls -exec rm -rf {} +
fi

# Su propia copia del jar, como iniciar.sh, para que recompilar no afecte a ninguna de las dos
cp "$JAR" "$RUNNING"
# Sin la variable del banco aunque venga del entorno: la demo no se conecta a ninguno
exec env -u ENABLE_BANKING_APP_ID java -jar "$RUNNING" --spring.profiles.active=demo "$@"
