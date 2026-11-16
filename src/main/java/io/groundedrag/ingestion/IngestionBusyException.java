package io.groundedrag.ingestion;

public class IngestionBusyException extends RuntimeException {

    public IngestionBusyException() {
        super("Ingestion queue is full, retry later");
    }
}
