package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.auth.LocalAccountService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final CurrentUser current;
  private final ObjectProvider<ClientRegistrationRepository> registrations;
  private final dev.aiqa.auth.LocalAccountService localAccounts;
  private final dev.aiqa.auth.CaptchaService captchas;

  public AuthController(
      CurrentUser current,
      ObjectProvider<ClientRegistrationRepository> registrations,
      dev.aiqa.auth.LocalAccountService localAccounts,
      dev.aiqa.auth.CaptchaService captchas) {
    this.current = current;
    this.registrations = registrations;
    this.localAccounts = localAccounts;
    this.captchas = captchas;
  }

  public record LoginInput(
      String username, String password, String captchaId, String captchaCode) {}

  /** 公开接口：发放一枚登录验证码，前端把图片与 captchaId 一起展示。 */
  @GetMapping("/captcha")
  public ResponseEntity<dev.aiqa.auth.CaptchaService.Challenge> captcha() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(captchas.issue());
  }

  /**
   * 本地账号密码登录：成功建立服务器会话并返回与 /me 相同的会话视图。
   * 验证码缺失、过期或错误统一 400 INVALID_CAPTCHA，且先于密码校验；
   * 密码失败统一 401（不区分用户名不存在与密码错误），停用账号 403，失败次数超限 429。
   */
  @PostMapping("/login")
  public ResponseEntity<MeView> login(
      @RequestBody LoginInput input,
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    // 先判验证码：错误的验证码直接拒绝，不进入密码校验，也不占用密码失败次数窗口。
    captchas.verify(input.captchaId(), input.captchaCode());
    AppUser user = localAccounts.login(input.username(), input.password(), request, response);
    MeView view =
        new MeView(
            user.id().toString(),
            user.displayName(),
            user.roles(),
            user.isSystemAdmin() || user.canCreateSpace());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(view);
  }

  public record ProviderView(String id, String type) {}

  /**
   * 公开接口：当前可用的登录方式。未接入企业 IdP 时 items 为空，登录页据此隐藏 SSO 按钮，
   * 不再把用户送进后端的 401 JSON。
   */
  @GetMapping("/providers")
  public Map<String, Object> providers() {
    List<ProviderView> items = new ArrayList<>();
    ClientRegistrationRepository repository = registrations.getIfAvailable();
    // InMemoryClientRegistrationRepository 可迭代；接口本身未声明 Iterable，用 instanceof 判断。
    if (repository instanceof Iterable<?> iterable) {
      for (Object entry : iterable) {
        if (entry instanceof ClientRegistration registration) {
          items.add(new ProviderView(registration.getRegistrationId(), "SSO"));
        }
      }
    }
    return Map.of("items", items);
  }

  public record MeView(String userId, String displayName, List<String> roles, boolean canCreateSpace) {}

  @GetMapping("/me")
  public ResponseEntity<MeView> me() {
    AppUser user = current.require();
    MeView view = new MeView(user.id().toString(), user.displayName(), user.roles(), user.isSystemAdmin() || user.canCreateSpace());
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(view);
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken());
  }
}
