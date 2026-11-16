package io.groundedrag.generation;

import java.util.List;

import io.groundedrag.retrieval.ContextSource;
import reactor.core.publisher.Flux;

public interface AnswerGenerator {

    String generate(String question, List<Turn> history, List<ContextSource> sources);

    Flux<String> stream(String question, List<Turn> history, List<ContextSource> sources);

    String name();
}
