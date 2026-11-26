package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.RateLimiter;
import dev.aiqa.usage.UsageService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class UsageController {

  private final CurrentUser current;
  private final UsageService usage;

  private final RateLimiter limiter;

  public UsageController(CurrentUser current, UsageService usage, RateLimiter limiter) {
    this.current = current;
    this.usage = usage;
    this.limiter = limiter;
  }

  @GetMapping("/usage/ranking")
  public dev.aiqa.common.PageView<UsageService.UserUsageRow> ranking(
      @RequestParam String period, @RequestParam String date) {
    AppUser actor = current.require();
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    return limiter.read(RateLimiter.Read.USAGE, actor.id(),
        () -> dev.aiqa.common.PageView.of(usage.ranking(period, LocalDate.parse(date))));
  }

  @GetMapping("/usage/questions")
  public dev.aiqa.common.PageView<UsageService.QuestionRowView> questions(
      @RequestParam String period,
      @RequestParam String date,
      @RequestParam(required = false) String userId,
      @RequestParam(defaultValue = "false") boolean allUsers) {
    AppUser actor = current.require();
    java.util.UUID subject = resolveSubject(actor, userId, allUsers);
    return limiter.read(RateLimiter.Read.USAGE, actor.id(),
        () -> dev.aiqa.common.PageView.of(usage.questions(subject, period, LocalDate.parse(date))));
  }

  @GetMapping("/usage/spaces")
  public dev.aiqa.common.PageView<UsageService.SpaceUsageRow> spaceUsage(
      @RequestParam String period, @RequestParam String date) {
    AppUser actor = current.require();
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    return limiter.read(RateLimiter.Read.USAGE, actor.id(),
        () -> dev.aiqa.common.PageView.of(usage.spaceUsage(period, LocalDate.parse(date))));
  }

  @GetMapping("/usage/series")
  public Map<String, Object> series(
      @RequestParam String period,
      @RequestParam String date,
      @RequestParam(required = false) String userId,
      @RequestParam(defaultValue = "false") boolean allUsers) {
    AppUser actor = current.require();
    java.util.UUID subject = resolveSubject(actor, userId, allUsers);
    LocalDate day = LocalDate.parse(date);
    return Map.of(
        "period", period,
        "date", day.toString(),
        "timeZone", "Asia/Shanghai",
        "buckets", limiter.read(RateLimiter.Read.USAGE, actor.id(),
             () -> usage.series(subject, period, day)));
  }

  @GetMapping("/usage")
  public UsageService.UsageView usage(
      @RequestParam String period,
      @RequestParam String date,
      @RequestParam(required = false) String userId,
      @RequestParam(defaultValue = "false") boolean allUsers) {
    AppUser actor = current.require();
    java.util.UUID subject = resolveSubject(actor, userId, allUsers);
    return limiter.read(RateLimiter.Read.USAGE, actor.id(),
        () -> usage.summary(subject, period, LocalDate.parse(date)));
  }

  /** A null subject is an explicit administrator-only aggregate. */
  private static java.util.UUID resolveSubject(AppUser actor, String userId, boolean allUsers) {
    boolean specificUser = userId != null && !userId.isBlank();
    if (!allUsers && !specificUser) {
      return actor.id();
    }
    if (!actor.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    if (allUsers && specificUser) {
      throw ApiException.invalid("allUsers 与 userId 不能同时使用");
    }
    return allUsers ? null : Http.pathUuid(userId, "userId");
  }
}
