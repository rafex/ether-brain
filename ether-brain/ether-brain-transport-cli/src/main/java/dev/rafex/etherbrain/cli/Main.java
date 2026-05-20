package dev.rafex.etherbrain.cli;

import dev.rafex.etherbrain.bootstrap.SpiModelBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import dev.rafex.etherbrain.spi.model.ProviderMetadata;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point for EtherBrain agent runtime.
 * <p>
 * Usage:
 * <pre>
 *   mvn exec:java -pl ether-brain-transport-cli -Dexec.args="--provider demo What time is it?"
 *   mvn exec:java -pl ether-brain-transport-cli -Dexec.args="--list-providers"
 * </pre>
 * <p>
 * Provider selection via SPI ({@link java.util.ServiceLoader}).
 * Configuration via environment variables:
 * <ul>
 *   <li>{@code OPENAI_API_KEY} — required for openai provider</li>
 *   <li>{@code OPENAI_BASE_URL} — optional, default: {@code https://api.openai.com}</li>
 *   <li>{@code OPENAI_MODEL} — optional, default: {@code gpt-4o-mini}</li>
 *   <li>{@code OLLAMA_URL} — optional, default: {@code http://localhost:11434}</li>
 *   <li>{@code OLLAMA_MODEL} — optional, default: {@code llama3.2}</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--list-providers".equals(args[0])) {
            listProviders();
            return;
        }

        String provider = "demo";
        String input = "What time is it?";
        Map<String, String> extraConfig = new HashMap<>();

        int argIdx = 0;
        while (argIdx < args.length) {
            String arg = args[argIdx];
            if ("--provider".equals(arg) && argIdx + 1 < args.length) {
                provider = args[++argIdx];
            } else if (arg.startsWith("--config.") && arg.contains("=")) {
                String key = arg.substring("--config.".length(), arg.indexOf('='));
                String value = arg.substring(arg.indexOf('=') + 1);
                extraConfig.put(key, value);
            } else {
                input = collectRemainingArgs(args, argIdx);
                break;
            }
            argIdx++;
        }

        Map<String, String> config = buildConfig(provider, extraConfig);
        AgentRuntime runtime = SpiModelBootstrap.bootstrap(provider, config);
        String result = runtime.run("cli-session", input);
        System.out.println(result);
    }

    private static void listProviders() {
        List<ProviderMetadata> providers = SpiModelBootstrap.listProviders();
        if (providers.isEmpty()) {
            System.out.println("No model providers found on classpath.");
            return;
        }
        System.out.println("Available model providers:");
        for (ProviderMetadata meta : providers) {
            System.out.printf("  %-10s — %s%n", meta.name(), meta.description());
        }
    }

    private static Map<String, String> buildConfig(String provider, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>();
        config.putAll(extra);

        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            String key = env.getKey();
            if (key.startsWith("OPENAI_") || key.startsWith("OLLAMA_")) {
                config.putIfAbsent(key, env.getValue());
            }
        }

        return config;
    }

    private static String collectRemainingArgs(String[] args, int fromIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.length() == 0 ? "What time is it?" : sb.toString();
    }
}
