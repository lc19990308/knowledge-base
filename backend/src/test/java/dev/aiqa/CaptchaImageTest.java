package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aiqa.auth.CaptchaService;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证码服务不依赖数据库与 Web 上下文：确认图片可解码、字符大小写不敏感、
 * 每个 captchaId 只能用一次，过期或缺失一律按 INVALID_CAPTCHA 拒绝。
 */
class CaptchaImageTest {

  private static final String PNG_PREFIX = "data:image/png;base64,";

  @Test
  void challengeIsRenderablePngAndVerifiesOnlyOnce() {
    CaptchaService service = new CaptchaService(Duration.ofMinutes(2), "K7QM");

    CaptchaService.Challenge challenge = service.issue();
    assertThat(challenge.captchaId()).isNotBlank();
    assertThat(challenge.image()).startsWith(PNG_PREFIX);
    byte[] png =
        Base64.getDecoder().decode(challenge.image().substring(PNG_PREFIX.length()));
    // PNG 文件头校验：确保返回的是一张真实图片，而不是占位字符串。
    assertThat(new byte[] {png[0], png[1], png[2], png[3]})
        .containsExactly((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    assertThat(png.length).isGreaterThan(200);

    // 校验大小写不敏感。
    service.verify(challenge.captchaId(), "k7qm");
    // 一次性：同一个 captchaId 第二次直接失效，即使字符仍对。
    assertThatThrownBy(() -> service.verify(challenge.captchaId(), "K7QM"))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAPTCHA));
  }

  @Test
  void wrongMissingAndExpiredCaptchasAreRejected() throws Exception {
    CaptchaService service = new CaptchaService(Duration.ofMinutes(2), "");
    CaptchaService.Challenge challenge = service.issue();

    // 随机生成的四位字符：答错即拒绝，并同时消耗该题。
    assertThatThrownBy(() -> service.verify(challenge.captchaId(), "WRONG"))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAPTCHA));
    // 缺失字段按失效处理，而不是抛出空指针。
    assertThatThrownBy(() -> service.verify(null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAPTCHA));

    // 1 毫秒有效期：等一小会儿再校验必然过期。
    CaptchaService shortLived = new CaptchaService(Duration.ofMillis(1), "ABCD");
    CaptchaService.Challenge expired = shortLived.issue();
    Thread.sleep(20);
    assertThatThrownBy(() -> shortLived.verify(expired.captchaId(), "ABCD"))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_CAPTCHA));
  }
}
