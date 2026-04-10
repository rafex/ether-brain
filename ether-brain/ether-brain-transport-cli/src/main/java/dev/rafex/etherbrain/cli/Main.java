package dev.rafex.etherbrain.cli;

import dev.rafex.etherbrain.bootstrap.ApplicationBootstrap;
import dev.rafex.etherbrain.core.runtime.AgentRuntime;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String input = args.length == 0 ? "What time is it?" : String.join(" ", args);
        AgentRuntime runtime = new ApplicationBootstrap().bootstrapForDemo();
        String result = runtime.run("cli-session", input);
        System.out.println(result);
    }
}
