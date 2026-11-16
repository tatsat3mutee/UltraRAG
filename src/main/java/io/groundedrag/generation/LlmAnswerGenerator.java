package io.groundedrag.generation;

import java.util.List;

import io.groundedrag.retrieval.ContextSource;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/** Grounded generation through Spring AI's ChatClient (any provider: OpenAI-compatible, Ollama, ...). */
public class LlmAnswerGenerator implements AnswerGenerator {

    private final ChatClient chat;

    public LlmAnswerGenerator(ChatClient chat) {
        this.chat = chat;
    }

    @Override
    public String generate(String question, List<Turn> history, List<ContextSource> sources) {
        return chat.prompt().system(Prompts.ANSWER_SYSTEM).user(userMessage(question, history, sources))
                .call().content();
    }

    @Override
    public Flux<String> stream(String question, List<Turn> history, List<ContextSource> sources) {
        return chat.prompt().system(Prompts.ANSWER_SYSTEM).user(userMessage(question, history, sources))
                .stream().content();
    }

    @Override
    public String name() {
        return "llm";
    }

    static String userMessage(String question, List<Turn> history, List<ContextSource> sources) {
        StringBuilder sb = new StringBuilder("<sources>\n");
        for (ContextSource s : sources) {
            sb.append("<source id=\"").append(s.number()).append("\" file=\"").append(s.chunk().sourceName()).append('"');
            if (s.chunk().page() != null) {
                sb.append(" page=\"").append(s.chunk().page()).append('"');
            }
            sb.append(">\n").append(s.text().replace("</source>", "")).append("\n</source>\n");
        }
        sb.append("</sources>\n\n");
        if (!history.isEmpty()) {
            sb.append("Earlier in this conversation:\n");
            for (Turn t : history.subList(Math.max(0, history.size() - 2), history.size())) {
                sb.append("Q: ").append(t.question()).append('\n');
            }
            sb.append('\n');
        }
        return sb.append("Question: ").append(question).toString();
    }
}
