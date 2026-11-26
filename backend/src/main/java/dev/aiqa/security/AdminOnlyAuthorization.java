package dev.aiqa.security;

import dev.aiqa.auth.AuthenticatedUser;
import dev.aiqa.auth.UserRepository;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/** Restricts operational endpoints to accounts that currently hold SYSTEM_ADMIN in the database. */
@Component
public class AdminOnlyAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

  private final UserRepository users;

  public AdminOnlyAuthorization(UserRepository users) {
    this.users = users;
  }

  @Override
  public org.springframework.security.authorization.AuthorizationResult authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    Authentication current = authentication.get();
    if (current == null || !current.isAuthenticated()) {
      return new AuthorizationDecision(false);
    }
    Object principal = current.getPrincipal();
    String issuer;
    String subject;
    if (principal instanceof AuthenticatedUser dev) {
      issuer = dev.issuer();
      subject = dev.subject();
    } else if (principal instanceof OidcUser oidc) {
      issuer = oidc.getIssuer() == null ? "" : oidc.getIssuer().toString();
      subject = oidc.getSubject();
    } else {
      return new AuthorizationDecision(false);
    }
    boolean admin =
        users.findBySubject(issuer, subject).map(user -> user.enabled() && user.isSystemAdmin()).orElse(false);
    return new AuthorizationDecision(admin);
  }
}
