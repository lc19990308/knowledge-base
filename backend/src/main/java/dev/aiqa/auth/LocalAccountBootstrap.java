package dev.aiqa.auth;

import dev.aiqa.common.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 首次启动引导：本地没有任何账号时创建一个系统管理员和一个普通用户，使登录页开箱可用。
 * 密码优先取环境变量；使用内置默认值时打 WARN，生产必须通过环境变量覆盖。
 * （2026-11-22 决策修订：首期加入本地账号体系；生产仍推荐 IdP 主体初始化。）
 */
@Component
@ConditionalOnProperty(name = "aiqa.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class LocalAccountBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LocalAccountBootstrap.class);

  private final JdbcClient jdbc;
  private final LocalAccountService accounts;
  private final String adminUsername;
  private final String adminPassword;
  private final String userUsername;
  private final String userPassword;

  public LocalAccountBootstrap(
      JdbcClient jdbc,
      LocalAccountService accounts,
      @Value("${aiqa.bootstrap.admin-username:admin}") String adminUsername,
      @Value("${aiqa.bootstrap.admin-password:admin123}") String adminPassword,
      @Value("${aiqa.bootstrap.user-username:user}") String userUsername,
      @Value("${aiqa.bootstrap.user-password:user1234}") String userPassword) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
    this.userUsername = userUsername;
    this.userPassword = userPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    Long existing =
        jdbc.sql("SELECT count(*) FROM app_user WHERE issuer = :issuer")
            .param("issuer", LocalAccountService.ISSUER)
            .query(Long.class)
            .single();
    if (existing != 0) {
      return;
    }
    if ("admin123".equals(adminPassword) || "user1234".equals(userPassword)) {
      log.warn(
          "本地账号使用了内置默认密码（admin/admin123、user/user1234）；生产环境必须用"
              + " AIQA_BOOTSTRAP_ADMIN_PASSWORD / AIQA_BOOTSTRAP_USER_PASSWORD 覆盖");
    }
    accounts.createLocalUser(adminUsername, "系统管理员", adminPassword, List.of("SYSTEM_ADMIN", "USER"));
    accounts.createLocalUser(userUsername, "普通用户", userPassword, List.of("USER"));
    log.info("已创建本地引导账号：{}（系统管理员）、{}（普通用户）", adminUsername, userUsername);
  }
}
