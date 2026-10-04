#!/usr/bin/env bash
# Copia de seguridad cifrada de Mis Finanzas en el USB (la segunda copia, fuera de este disco).
#
# Guarda la base de datos (data/finanzas.db), la clave de Enable Banking (data/enablebanking/) y tus
# apuntes locales (notas/, fuera de git) en UN archivo cifrado con contraseña (gpg, AES-256) dentro de la carpeta MisFinanzas-copias/ del USB.
# Solo crea archivos nuevos dentro de esa carpeta: no borra ni sobrescribe nada, ni sus propias copias
# viejas (cada una ocupa unos pocos KB).
#
# Uso:
#   ./copia-usb.sh                 # USB en /media/$USER/PENDRIVE
#   ./copia-usb.sh /otra/carpeta   # otro destino (se creará MisFinanzas-copias/ dentro)
#
# La contraseña la pide gpg al ejecutarlo. Si se olvida, la copia no se puede abrir: nadie puede
# recuperarla. Usa siempre la misma y apúntala fuera de este ordenador y fuera del USB.
#
# Restaurar (con la app parada):
#   mkdir /tmp/restaurar && gpg -d MisFinanzas_AAAAMMDD_HHMMSS.tar.gpg | tar -x -C /tmp/restaurar
#   cp /tmp/restaurar/finanzas.db data/finanzas.db
#   cp -r /tmp/restaurar/enablebanking data/          # solo si se perdió la clave
#   cp -r /tmp/restaurar/notas .                      # solo si se perdieron
#   rm -r /tmp/restaurar                               # tiene tus datos sin cifrar
# Al arrancar, la app hace además su copia automática en data/backups/ de lo que hubiera antes.
set -euo pipefail

# data/ es una ruta relativa: hay que ejecutar desde la carpeta del proyecto
cd "$(dirname "$(readlink -f "$0")")"

USB_DEFAULT="/media/$USER/PENDRIVE"
ROOT="${1:-$USB_DEFAULT}"

if [ ! -d "$ROOT" ]; then
    echo "No encuentro $ROOT: ¿está el USB conectado?" >&2
    exit 1
fi
# Sin el USB montado, /media/... podría ser una carpeta vacía del disco interno: la copia acabaría
# en el mismo disco que los datos sin que nadie se diera cuenta.
if [ "$ROOT" = "$USB_DEFAULT" ] && ! mountpoint -q "$ROOT"; then
    echo "$ROOT existe pero no es un USB montado: no copio nada." >&2
    exit 1
fi
if [ ! -f data/finanzas.db ]; then
    echo "No encuentro data/finanzas.db." >&2
    exit 1
fi

DEST="$ROOT/MisFinanzas-copias"
FILE="$DEST/MisFinanzas_$(date +%Y%m%d_%H%M%S).tar.gpg"
if [ -e "$FILE" ]; then
    echo "$FILE ya existe; espera un segundo y vuelve a intentarlo." >&2
    exit 1
fi

# La copia sin cifrar solo existe un momento, en una carpeta privada de este disco
umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir "$WORK/copia"
# .backup da una copia coherente aunque la app esté abierta y escribiendo
sqlite3 -readonly data/finanzas.db ".backup '$WORK/copia/finanzas.db'"
if [ -d data/enablebanking ]; then
    cp -r data/enablebanking "$WORK/copia/"
else
    echo "Aviso: no hay data/enablebanking/, la copia va sin la clave del banco."
fi
# Los apuntes no están en git: esta es su copia fuera de este disco
if [ -d notas ]; then
    cp -rL notas "$WORK/copia/"
fi

echo "Cifrando: gpg te pedirá la contraseña de las copias (dos veces)."
tar -C "$WORK/copia" -c . | gpg --symmetric --cipher-algo AES256 --output "$WORK/copia.tar.gpg"

mkdir -p "$DEST"
(set -o noclobber; cat "$WORK/copia.tar.gpg" > "$FILE")   # noclobber: falla si ya existiera
sync

# Comprobar el archivo que ha quedado en el USB, no el temporal: que se descifra y que la base de
# datos de dentro está entera.
mkdir "$WORK/check"
if ! gpg --quiet --decrypt "$FILE" | tar -x -C "$WORK/check" \
    || [ "$(sqlite3 -readonly "$WORK/check/finanzas.db" 'pragma integrity_check')" != "ok" ]; then
    echo "ATENCIÓN: la copia $FILE no se ha podido comprobar. No te fíes de ella." >&2
    exit 1
fi
MOVS="$(sqlite3 -readonly "$WORK/check/finanzas.db" 'select count(*) from transactions')"

echo "Copia guardada y comprobada: $FILE ($MOVS movimientos)."
echo "Antes de sacar el USB, expúlsalo desde el explorador de archivos."
