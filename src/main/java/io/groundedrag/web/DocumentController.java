package io.groundedrag.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.security.ApiKeyPrincipal;
import io.groundedrag.security.SpaceAuthorizer;
import io.groundedrag.store.KnowledgeStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/spaces/{space}/documents")
class DocumentController {

    private final IngestionService ingestion;
    private final KnowledgeStore store;

    DocumentController(IngestionService ingestion, KnowledgeStore store) {
        this.ingestion = ingestion;
        this.store = store;
    }

    /** 202 + the QUEUED version to poll; 200 + the existing version when the bytes are already indexed. */
    @PostMapping(consumes = "multipart/form-data")
    ResponseEntity<DocumentRecord> upload(@AuthenticationPrincipal ApiKeyPrincipal principal,
            @PathVariable String space, @RequestParam("file") MultipartFile file) throws IOException {
        SpaceId spaceId = SpaceAuthorizer.require(principal, space);
        if (file.isEmpty() || file.getOriginalFilename() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A non-empty file is required");
        }
        String name = file.getOriginalFilename().replaceAll("[\\\\/]", "_");
        IngestionService.Submission s = ingestion.submit(spaceId, name, file.getContentType(), file.getBytes());
        return ResponseEntity.status(s.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(s.document());
    }

    @GetMapping
    List<DocumentRecord> list(@AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable String space) {
        return store.listDocuments(SpaceAuthorizer.require(principal, space));
    }

    @GetMapping("/{id}")
    DocumentRecord get(@AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable String space,
            @PathVariable UUID id) {
        return store.findDocument(SpaceAuthorizer.require(principal, space), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal ApiKeyPrincipal principal, @PathVariable String space,
            @PathVariable UUID id) {
        boolean deleted = store.deleteDocument(SpaceAuthorizer.require(principal, space), id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
