package io.groundedrag.observability;

class InMemoryTraceStoreTest extends TraceStoreContract {

    private final InMemoryTraceStore store = new InMemoryTraceStore();

    @Override
    protected TraceStore store() {
        return store;
    }
}
