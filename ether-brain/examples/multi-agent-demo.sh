#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# EtherBrain — Demo multi-agente distribuido (HTTP)
#
# Levanta dos agentes especializados y un orquestador.
# Requiere:  java 21+, ether-brain-http.jar compilado, .env con LLM_* válidas
#
# Uso:
#   cd ether-brain/
#   ./examples/multi-agent-demo.sh          # usa .env del directorio actual
#   ./examples/multi-agent-demo.sh test     # solo prueba los endpoints
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

JAR="$(dirname "$0")/../ether-brain-transport-http/target/ether-brain-http.jar"
SESSIONS_DIR="/tmp/etherbrain-demo-sessions"
mkdir -p "$SESSIONS_DIR"

# ── 1. Arrancar sub-agente: investigador (puerto 8081) ───────────────────────

start_agent() {
    local name="$1" description="$2" port="$3" system_prompt="$4"
    AGENT_NAME="$name" \
    AGENT_DESCRIPTION="$description" \
    AGENT_SYSTEM_PROMPT="$system_prompt" \
    HTTP_PORT="$port" \
    SESSION_DIR="$SESSIONS_DIR/$name" \
    java -jar "$JAR" &
    echo "$!" # return PID
}

echo "═══════════════════════════════════════════════════════════════════"
echo " EtherBrain — Demo multi-agente (3 agentes en 3 puertos)"
echo "═══════════════════════════════════════════════════════════════════"
echo ""

if [[ "${1:-}" == "test" ]]; then
    echo "── Modo test: probando endpoints existentes ─────────────────────"
    curl -s http://localhost:8080/health | python3 -m json.tool || echo "orchestrator no está corriendo"
    curl -s http://localhost:8081/health | python3 -m json.tool || echo "investigador no está corriendo"
    curl -s http://localhost:8082/health | python3 -m json.tool || echo "redactor no está corriendo"
    exit 0
fi

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: JAR no encontrado en $JAR"
    echo "Ejecuta primero:  ./mvnw clean package -DskipTests"
    exit 1
fi

mkdir -p "$SESSIONS_DIR/investigador" "$SESSIONS_DIR/redactor" "$SESSIONS_DIR/orchestrator"

# ── Arranca investigador ──────────────────────────────────────────────────────
PID_INV=$(start_agent \
    "investigador" \
    "Agente especializado en investigación. Busca, analiza y sintetiza información. Úsame cuando necesites datos, hechos o análisis detallado." \
    "8081" \
    "Eres un agente investigador experto. Tu trabajo es buscar, analizar y sintetizar información con precisión. Sé conciso y estructura tus respuestas.")

# ── Arranca redactor ──────────────────────────────────────────────────────────
PID_RED=$(start_agent \
    "redactor" \
    "Agente especializado en redacción y comunicación. Transforma información técnica en texto claro y estructurado. Úsame cuando necesites generar documentos, emails o reportes." \
    "8082" \
    "Eres un agente redactor experto. Tu trabajo es transformar información en texto claro, estructurado y profesional. Adapta el tono según el contexto.")

# ── Arranca orquestador con los sub-agentes como tools HTTP ─────────────────
# En producción los sub-agentes estarían en tools.json.
# Para el demo los definimos inline via variables de entorno especiales.
cat > /tmp/etherbrain-demo-tools.json << 'EOF'
[
  {
    "type":        "http",
    "name":        "investigador",
    "description": "Agente especializado en investigación. Busca, analiza y sintetiza información. Úsame cuando necesites datos, hechos o análisis detallado de un tema.",
    "endpoint":    "http://localhost:8081/sessions/sub-inv/run",
    "method":      "POST",
    "timeout_seconds": 120
  },
  {
    "type":        "http",
    "name":        "redactor",
    "description": "Agente especializado en redacción y comunicación. Transforma información en texto claro y estructurado. Úsame para generar reportes, emails o documentos.",
    "endpoint":    "http://localhost:8082/sessions/sub-red/run",
    "method":      "POST",
    "timeout_seconds": 120
  }
]
EOF

PID_ORCH=$(
    AGENT_NAME="orchestrator" \
    AGENT_DESCRIPTION="Orquestador principal. Delega investigación al agente investigador y redacción al agente redactor." \
    AGENT_SYSTEM_PROMPT="Eres un orquestador inteligente. Tienes acceso a dos agentes especializados: 'investigador' para buscar y analizar información, y 'redactor' para generar textos. Cuando el usuario pida algo complejo, descompón el trabajo y delega a los agentes apropiados. Combina sus respuestas para dar una respuesta final completa." \
    AGENT_TOOLS_FILE="/tmp/etherbrain-demo-tools.json" \
    HTTP_PORT="8080" \
    SESSION_DIR="$SESSIONS_DIR/orchestrator" \
    java -jar "$JAR" &
    echo "$!"
)

# ── Cleanup al salir ──────────────────────────────────────────────────────────
cleanup() {
    echo ""
    echo "Deteniendo agentes..."
    kill "$PID_INV" "$PID_RED" "$PID_ORCH" 2>/dev/null || true
    rm -f /tmp/etherbrain-demo-tools.json
    echo "Demo finalizado."
}
trap cleanup EXIT INT TERM

echo "Esperando que los agentes arranquen..."
sleep 5

# ── Health checks ─────────────────────────────────────────────────────────────
echo ""
echo "── Health checks ────────────────────────────────────────────────"
curl -sf http://localhost:8081/health > /dev/null && echo "✓ investigador (8081)" || echo "✗ investigador (8081) — no responde"
curl -sf http://localhost:8082/health > /dev/null && echo "✓ redactor     (8082)" || echo "✗ redactor     (8082) — no responde"
curl -sf http://localhost:8080/health > /dev/null && echo "✓ orchestrator (8080)" || echo "✗ orchestrator (8080) — no responde"

echo ""
echo "═══════════════════════════════════════════════════════════════════"
echo " Demo listo. Ejemplos de uso:"
echo "═══════════════════════════════════════════════════════════════════"
echo ""
echo "# Pregunta directa al orquestador (delegará a sub-agentes):"
echo "curl -X POST http://localhost:8080/sessions/demo/run \\"
echo "  -H 'Content-Type: application/json' \\"
echo "  -d '{\"message\":\"Investiga qué es EtherBrain y escribe un email ejecutivo resumiendo el proyecto.\"}'"
echo ""
echo "# Con SSE streaming (ver progreso en tiempo real):"
echo "curl -N -X POST http://localhost:8080/sessions/demo/run/stream \\"
echo "  -H 'Content-Type: application/json' \\"
echo "  -d '{\"message\":\"Analiza las ventajas de Java 21 virtual threads y escribe un reporte técnico.\"}'"
echo ""
echo "# Evento asíncrono con callback:"
echo "curl -X POST http://localhost:8080/events \\"
echo "  -H 'Content-Type: application/json' \\"
echo "  -d '{\"session_id\":\"async-1\",\"message\":\"Resumen ejecutivo de IA en 2025\",\"callback_url\":\"http://localhost:9999/result\"}'"
echo ""
echo "# Cancelar un loop activo:"
echo "curl -X DELETE http://localhost:8080/sessions/demo/cancel"
echo ""
echo "Presiona Ctrl+C para detener todos los agentes."
echo ""

# ── Mantener vivo el demo ──────────────────────────────────────────────────────
wait
