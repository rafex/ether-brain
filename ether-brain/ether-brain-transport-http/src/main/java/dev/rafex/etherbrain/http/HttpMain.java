package dev.rafex.etherbrain.http;

/**
 * Punto de entrada del servidor HTTP de EtherBrain.
 *
 * <h2>Uso</h2>
 * <pre>
 * java -jar ether-brain-http.jar
 * </pre>
 *
 * <h2>Variables de entorno</h2>
 * <pre>
 * HTTP_PORT     — puerto (default 8080)
 * HTTP_THREADS  — hilos del servidor (default 4)
 * # + todas las variables estándar: LLM_TYPE, LLM_URL, LLM_TOKEN, LLM_MODEL…
 * </pre>
 *
 * <h2>Ejemplo de uso</h2>
 * <pre>
 * curl -X POST http://localhost:8080/sessions/s1/run \
 *      -H "Content-Type: application/json" \
 *      -d '{"message":"¿Quién eres?"}'
 * </pre>
 */
public final class HttpMain {

    private HttpMain() {}

    public static void main(String[] args) throws Exception {
        HttpAgentServer server = HttpAgentServer.fromEnv();
        server.start();

        // Graceful shutdown con Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[EtherBrain HTTP] Apagando...");
            server.stop();
        }));

        // Mantener vivo el proceso (el servidor corre en hilos del executor)
        Thread.currentThread().join();
    }
}
