package io.groundedrag.store;

class InMemoryKnowledgeStoreTest extends KnowledgeStoreContract {

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();

    @Override
    protected KnowledgeStore store() {
        return store;
    }
}
