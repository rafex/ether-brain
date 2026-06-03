#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# .env.sh — carga el .env real y exporta las variables al entorno del SO.
#
# Uso:
#   source .env.sh                        — carga en el shell actual
#   . .env.sh                             — idem (POSIX)
#   .env.sh java -jar ether-brain-cli.jar — ejecuta un comando con las vars
#
# El archivo .env se busca en este orden:
#   1. Variable ENV_FILE=/ruta/a/mi.env  (override explícito)
#   2. .env junto a este script          (mismo directorio)
# ─────────────────────────────────────────────────────────────────────────────

# Directorio donde vive este script
_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

# Resolver el archivo .env a cargar
if [[ -n "${ENV_FILE}" ]]; then
    _ENV_FILE="${ENV_FILE}"
elif [[ -f "${_SCRIPT_DIR}/.env" ]]; then
    _ENV_FILE="${_SCRIPT_DIR}/.env"
else
    echo "[.env.sh] ERROR: no se encontró .env en ${_SCRIPT_DIR}" >&2
    echo "           Crea uno copiando: cp .env.example .env" >&2
    return 1 2>/dev/null || exit 1
fi

# Cargar y exportar — ignora comentarios y líneas vacías
_loaded=0
while IFS= read -r _line || [[ -n "${_line}" ]]; do
    # Ignorar comentarios y líneas vacías
    [[ "${_line}" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${_line// }" ]] && continue

    # Extraer clave=valor
    _key="${_line%%=*}"
    _val="${_line#*=}"

    # Quitar comillas opcionales: "valor" o 'valor'
    if [[ "${_val}" =~ ^\"(.*)\"$ ]] || [[ "${_val}" =~ ^\'(.*)\'$ ]]; then
        _val="${BASH_REMATCH[1]}"
    fi

    # Solo exportar si la variable NO existe ya en el entorno del SO
    # (las vars reales del SO siempre tienen prioridad)
    if [[ -z "${!_key+x}" ]]; then
        export "${_key}=${_val}"
        (( _loaded++ ))
    fi
done < "${_ENV_FILE}"

echo "[.env.sh] ${_ENV_FILE} cargado (${_loaded} variables nuevas)" >&2

# Limpiar variables temporales
unset _SCRIPT_DIR _ENV_FILE _line _key _val _loaded

# Si se pasaron argumentos, ejecutarlos con el entorno ya cargado
if [[ $# -gt 0 ]]; then
    exec "$@"
fi
