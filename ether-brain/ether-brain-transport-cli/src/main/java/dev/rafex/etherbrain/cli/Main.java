package dev.rafex.etherbrain.cli;

import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;
import java.util.Arrays;
import java.util.Scanner;

/**
 * CLI entry-point for EtherBrain.
 *
 * <h2>Usage</h2>
 * <pre>
 * # Single turn
 * ./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="What time is it?"
 *
 * # Named session (single turn)
 * ./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="--session s1 What time is it?"
 *
 * # Interactive REPL (no args)
 * ./mvnw -pl ether-brain-transport-cli exec:java
 * </pre>
 *
 * <p>Set {@code MODEL_PROVIDER}, {@code MODEL_NAME} and the matching API key
 * environment variable before running. See {@link ApplicationBootstrap} for details.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String sessionId = "cli-session";
        String[] remaining = args;

        if (args.length >= 2 && "--session".equals(args[0])) {
            sessionId = args[1];
            remaining = Arrays.copyOfRange(args, 2, args.length);
        }

        AgentRuntime runtime = new ApplicationBootstrap().bootstrap();

        if (remaining.length > 0) {
            runSingleTurn(runtime, sessionId, String.join(" ", remaining));
        } else {
            runRepl(runtime, sessionId);
        }
    }

    private static void runSingleTurn(AgentRuntime runtime, String sessionId, String input)
            throws Exception {
        String result = runtime.run(sessionId, input);
        System.out.println(result);
    }

    private static void runRepl(AgentRuntime runtime, String sessionId) {
        System.out.println("EtherBrain — type 'exit' to quit | session: " + sessionId);
        System.out.println("─".repeat(60));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break;

                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) break;

                try {
                    String result = runtime.run(sessionId, line);
                    System.out.println(result);
                } catch (Exception e) {
                    System.err.println("[Error] " + e.getMessage());
                }
            }
        }

        System.out.println("Goodbye.");
    }
}
