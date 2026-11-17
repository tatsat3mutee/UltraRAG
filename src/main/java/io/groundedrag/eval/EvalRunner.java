package io.groundedrag.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.groundedrag.chat.RagService;
import io.groundedrag.config.RagProperties;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.Reranker;
import io.groundedrag.retrieval.RetrievalMode;
import io.groundedrag.store.KnowledgeStore;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code java -jar app.jar --spring.profiles.active=openai --rag.eval.run=true}
 * ingests eval/corpus into the configured store, scores the golden set with the configured
 * models and writes target/eval-report.md, then exits. Use a throwaway store, not production.
 */
@Component
@ConditionalOnProperty(name = "rag.eval.run", havingValue = "true")
class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final KnowledgeStore store;
    private final IngestionService ingestion;
    private final Embedder embedder;
    private final HybridRetriever retriever;
    private final Reranker reranker;
    private final RagService rag;
    private final RagProperties props;
    private final ConfigurableApplicationContext context;
    private final ObjectProvider<ChatModel> chatModel;

    EvalRunner(KnowledgeStore store, IngestionService ingestion, Embedder embedder, HybridRetriever retriever,
            Reranker reranker, RagService rag, RagProperties props, ConfigurableApplicationContext context,
            ObjectProvider<ChatModel> chatModel) {
        this.chatModel = chatModel;
        this.store = store;
        this.ingestion = ingestion;
        this.embedder = embedder;
        this.retriever = retriever;
        this.reranker = reranker;
        this.rag = rag;
        this.props = props;
        this.context = context;
    }

    /** Uses the configured chat model as judge; ideally point it at a stronger model than the generator. */
    private FaithfulnessJudge judge() {
        ChatModel model = chatModel.getIfAvailable();
        return model == null ? null : new FaithfulnessJudge(ChatClient.create(model));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Evaluator ev = new Evaluator(store, ingestion, embedder, retriever);
        int docs = ev.ingestCorpus(Path.of("eval/corpus"));
        List<EvalCase> cases = Evaluator.loadGoldenSet(Path.of("eval/golden-set.json"));
        int k = props.retrieval().topK();
        String report = Evaluator.markdown(
                List.of(ev.retrieval(cases, RetrievalMode.HYBRID, k), ev.retrieval(cases, RetrievalMode.VECTOR, k),
                        ev.retrieval(cases, RetrievalMode.KEYWORD, k)),
                ev.answers(cases, rag, judge()),
                "Setup: %d documents, %d questions, embedding=%s, reranker=%s, generator=%s, k=%d"
                        .formatted(docs, cases.size(), embedder.modelName(), reranker.name(),
                                props.generation().provider(), k));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/eval-report.md"), report);
        log.info("\n{}", report);
        context.close();
    }
}
