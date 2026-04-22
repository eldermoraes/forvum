package ai.forvum.app.cli;

import ai.forvum.app.ChatModelFactory;
import ai.forvum.core.AgentSpec;
import ai.forvum.engine.agent.SimpleAgent;
import ai.forvum.engine.config.AgentSpecLoader;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@TopCommand
@CommandLine.Command(
    name = "forvum",
    description = "Run a Forvum agent in an interactive REPL.",
    mixinStandardHelpOptions = true
)
public class ForvumCli implements Runnable {

    @CommandLine.Option(
        names = "--agent",
        defaultValue = "demo",
        description = "Agent id to load from agents/<id>.json (default: demo)"
    )
    String agentId;

    @Inject
    AgentSpecLoader specLoader;

    @Inject
    ChatModelFactory modelFactory;

    @Override
    public void run() {
        AgentSpec spec = specLoader.load(agentId);
        ChatLanguageModel chatModel = modelFactory.resolve(spec.primaryModel());
        SimpleAgent agent = new SimpleAgent(spec, chatModel);

        System.out.println("Forvum agent '" + spec.id() + "' ready (model: " + spec.primaryModel() + ")");
        System.out.println("Type your message and press Enter. Use /exit or Ctrl+D to quit.");
        System.out.println();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("forvum> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    System.out.println();
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                if ("/exit".equals(line) || "/quit".equals(line)) break;

                try {
                    String response = agent.chat(line);
                    System.out.println(response);
                    System.out.println();
                } catch (RuntimeException e) {
                    System.err.println("[error] " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[fatal] " + e.getMessage());
        }
    }
}
