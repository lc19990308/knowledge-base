package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.draft.DraftRepository;
import dev.aiqa.draft.DraftService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/draft")
public class DraftController {

  private final CurrentUser current;
  private final DraftService drafts;
  private final DraftRepository draftRepository;
  private final dev.aiqa.document.DocumentRepository documentRepository;

  public DraftController(
      CurrentUser current,
      DraftService drafts,
      DraftRepository draftRepository,
      dev.aiqa.document.DocumentRepository documentRepository) {
    this.current = current;
    this.drafts = drafts;
    this.draftRepository = draftRepository;
    this.documentRepository = documentRepository;
  }

  public record EntryInput(
      String documentVersionId,
      String effectiveFrom,
      String effectiveUntil,
      String policyState,
      String replacesDocumentVersionId) {}

  public record DraftInput(List<EntryInput> entries) {}

  public record EntryView(
      UUID documentVersionId, String effectiveFrom, String effectiveUntil, String policyState,
      UUID replacesDocumentVersionId, String name, String parseState, Long sizeBytes,
      String mediaType, Integer versionNumber, String createdAt) {}

  public record DraftView(UUID spaceId, long revision, List<EntryView> entries) {}

  private static DraftView view(DraftRepository.Draft draft) {
    return new DraftView(
        draft.spaceId(), draft.revision(),
        draft.entries().stream()
            .map(entry ->
                new EntryView(
                    entry.documentVersionId(),
                    entry.effectiveFrom() == null ? null : entry.effectiveFrom().toString(),
                    entry.effectiveUntil() == null ? null : entry.effectiveUntil().toString(),
                    entry.policyState(),
                    entry.replacesDocumentVersionId(),
                    entry.name(),
                    entry.parseState(),
                    entry.sizeBytes(),
                    entry.mediaType(),
                    entry.versionNumber(),
                    entry.createdAt() == null ? null : entry.createdAt().toString()))
            .toList());
  }

  @GetMapping
  public ResponseEntity<DraftView> get(@PathVariable String spaceId) {
    AppUser user = current.require();
    DraftView view = view(drafts.get(user.id(), Http.pathUuid(spaceId, "spaceId")));
    return ResponseEntity.ok().header(HttpHeaders.ETAG, Http.etag(view.revision())).body(view);
  }

  public record DraftDocument(String documentVersionId, String name, String parseState, String previewText) {}

  @GetMapping("/documents/{documentVersionId}")
  public DraftDocument draftDocument(@PathVariable String spaceId, @PathVariable String documentVersionId) {
    AppUser user = current.require();
    UUID space = Http.pathUuid(spaceId, "spaceId");
    drafts.get(user.id(), space);
    UUID document = Http.pathUuid(documentVersionId, "documentVersionId");
    if (!draftRepository.isInDraft(space, document)) {
      throw dev.aiqa.common.ApiException.notFound();
    }
    var row = documentRepository.find(space, document).orElseThrow(dev.aiqa.common.ApiException::notFound);
    String text =
        documentRepository.chunks(space, document).stream()
            .map(dev.aiqa.document.DocumentRepository.ChunkRow::body)
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse(null);
    return new DraftDocument(row.id().toString(), row.displayFilename(), row.parseState(), text);
  }

  @PutMapping
  public ResponseEntity<DraftView> replace(
      @PathVariable String spaceId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody DraftInput input) {
    AppUser user = current.require();
    long expected = Http.requireIfMatch(ifMatch);
    List<DraftRepository.Entry> entries =
        input.entries().stream()
            .map(entry ->
                new DraftRepository.Entry(
                    Http.pathUuid(entry.documentVersionId(), "documentVersionId"),
                    entry.effectiveFrom() == null ? null : LocalDate.parse(entry.effectiveFrom()),
                    entry.effectiveUntil() == null ? null : LocalDate.parse(entry.effectiveUntil()),
                    entry.policyState(),
                    entry.replacesDocumentVersionId() == null
                        ? null
                        : Http.pathUuid(entry.replacesDocumentVersionId(), "replacesDocumentVersionId"),
                    null, null, null, null, null, null))
            .toList();
    DraftView view = view(drafts.replace(user.id(), Http.pathUuid(spaceId, "spaceId"), expected, entries));
    return ResponseEntity.ok().header(HttpHeaders.ETAG, Http.etag(view.revision())).body(view);
  }
}
