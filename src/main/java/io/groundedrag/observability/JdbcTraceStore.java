package io.groundedrag.observability;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Traces as JSONB with the columns we filter on (time, confidence, grounded, rating) broken out. */
public class JdbcTraceStore implements TraceStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcTraceStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(AnswerTrace t) {
        jdbc.update("""
                INSERT INTO answer_traces (id, principal, created_at, confidence, grounded, cached, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
                t.id(), t.principal(), Timestamp.from(t.createdAt()), t.confidence(), t.grounded(), t.cached(),
                json.writeValueAsString(t));
    }

    @Override
    public Optional<AnswerTrace> find(UUID id) {
        return jdbc.query("SELECT payload::text, rating, feedback FROM answer_traces WHERE id = ?",
                (rs, i) -> {
                    AnswerTrace t = json.readValue(rs.getString(1), AnswerTrace.class);
                    Object rating = rs.getObject(2);
                    return rating == null ? t : t.withFeedback(((Number) rating).intValue(), rs.getString(3));
                }, id).stream().findFirst();
    }

    @Override
    public boolean recordFeedback(UUID id, String principal, int rating, String comment) {
        return jdbc.update("UPDATE answer_traces SET rating = ?, feedback = ? WHERE id = ? AND principal = ?",
                rating, comment, id, principal) == 1;
    }
}
