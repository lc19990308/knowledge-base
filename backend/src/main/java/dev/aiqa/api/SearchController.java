package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.RateLimiter;
import dev.aiqa.search.SearchService;
import dev.aiqa.space.SpaceRepository;
import dev.aiqa.space.SpaceService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

  private final CurrentUser current;
  private final SpaceService spaces;
  private final SearchService search;

  private final RateLimiter limiter;

  public SearchController(CurrentUser current, SpaceService spaces, SearchService search, RateLimiter limiter) {
    this.current = current;
    this.spaces = spaces;
    this.search = search;
    this.limiter = limiter;
  }

  public record HitView(
      String documentVersionId, String name, String status, String snippet, String match,
      String validity, int versionNumber) {}

  /**
   * The contract fixes three distinct outcomes, so status is part of the payload.
   * contentSource 标明本次命中来自当前发布、已撤回的最近发布还是未发布草稿。
   */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record SearchView(
      String status, List<HitView> items, String nextCursor, String contentSource) {}

  @GetMapping("/spaces/{spaceId}/search")
  public SearchView search(
      @PathVariable String spaceId,
      @RequestParam(name = "q", required = false, defaultValue = "") String query,
      @RequestParam(name = "field", required = false, defaultValue = "ALL") String field,
      @RequestParam(name = "source", required = false, defaultValue = "auto") String source) {
    AppUser user = current.require();
    UUID space = Http.pathUuid(spaceId, "spaceId");
    String requested = requestedSource(source, user);
    if (!List.of("NAME", "TEXT", "ALL").contains(field)) {
      throw ApiException.invalid("field 仅支持 NAME、TEXT、ALL");
    }
    SpaceRepository.SpaceRow row = spaces.requireSearchable(user, space);
    SearchService.Target target =
        search.resolveTarget(space, row.currentPublicationId(), requested);
    SearchService.Result result = limiter.read(RateLimiter.Read.SEARCH, user.id(),
        () -> search.search(space, query, field, target));
    List<HitView> items =
        result.items().stream()
            .map(hit -> new HitView(hit.documentVersionId().toString(), hit.name(), hit.status(),
                hit.snippet(), hit.match(), hit.validity(), hit.versionNumber()))
            .toList();
    return new SearchView(result.status(), items, null, target.source().name());
  }

  /**
   * 普通用户只能检索当前已发布快照；published/latest/draft 是系统管理员核对未发布内容的入口。
   */
  private static String requestedSource(String source, AppUser user) {
    String requested = source == null ? "AUTO" : source.strip().toUpperCase(Locale.ROOT);
    if (!List.of("AUTO", "PUBLISHED", "LATEST", "DRAFT").contains(requested)) {
      throw ApiException.invalid("source 仅支持 auto、published、latest、draft");
    }
    if (!user.isSystemAdmin() && !"AUTO".equals(requested) && !"PUBLISHED".equals(requested)) {
      throw ApiException.invalid("source 仅系统管理员可用");
    }
    return requested;
  }
}
