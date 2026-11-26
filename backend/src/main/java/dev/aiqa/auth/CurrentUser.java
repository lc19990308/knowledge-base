package dev.aiqa.auth;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class CurrentUser {

  private final UserRepository users;

  public CurrentUser(UserRepository users) {
    this.users = users;
  }

  public AppUser require() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ApiException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED);
    }
    Object principal = authentication.getPrincipal();
    AuthenticatedUser identity;
    if (principal instanceof AuthenticatedUser dev) {
      identity = dev;
    } else if (principal instanceof OidcUser oidc) {
      identity =
          new AuthenticatedUser(
              oidc.getIssuer() == null ? "unknown" : oidc.getIssuer().toString(),
              oidc.getSubject(),
              oidc.getFullName() == null ? oidc.getSubject() : oidc.getFullName());
    } else {
      throw new ApiException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED);
    }
    AppUser user = users.provision(identity);
    users.requireEnabled(user);
    return user;
  }

  public AppUser requireAdmin() {
    AppUser user = require();
    if (!user.isSystemAdmin()) {
      throw ApiException.forbidden();
    }
    return user;
  }
}
