package io.groundedrag.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Same contract against real Postgres + pgvector, booting the application with the postgres
 * profile so the Flyway migration (tables, HNSW and GIN indexes) is exercised too.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("postgres")
class PostgresKnowledgeStoreTest extends KnowledgeStoreContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    KnowledgeStore store;

    @Autowired
    io.groundedrag.observability.TraceStore traces;

    @Override
    protected KnowledgeStore store() {
        return store;
    }

    /** The trace contract against JSONB storage, sharing this container and context. */
    @org.junit.jupiter.api.Nested
    class Traces extends io.groundedrag.observability.TraceStoreContract {
        @Override
        protected io.groundedrag.observability.TraceStore store() {
            return traces;
        }
    }
}
