package dev.aiqa.auth;

import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 登录图形验证码：服务端出题、服务端判分，前端只负责展示与重取。
 *
 * <p>验证码是登录接口的必填项：每次登录都要带上一次 {@link #issue()} 得到的 captchaId 与图上字符。
 * 题面保存在进程内存里（不落库），默认 2 分钟过期，校验一次即作废——无论对错，
 * 同一个 captchaId 都不能第二次使用，避免拿同一张图反复试。
 *
 * <p>图片用 JDK 自带的 java.awt + ImageIO 绘制，不引入第三方验证码库。
 */
@Service
public class CaptchaService {

  /** 去掉 I、L、O、0、1 这类容易看混的字符，减少用户无谓重试。 */
  private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 4;
  /** 内存里最多同时挂多少枚验证码；超过上限先清过期项，仍然超限就整体清空。 */
  private static final int MAX_PENDING = 10_000;
  private static final int IMAGE_WIDTH = 132;
  private static final int IMAGE_HEIGHT = 44;
  private static final int FONT_SIZE = 28;

  private final SecureRandom random = new SecureRandom();
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final String fixedCode;

  /**
   * @param ttl 有效期，默认 2 分钟
   * @param fixedCode 固定验证码，仅隔离 E2E 与集成测试使用；留空表示每次随机生成
   */
  public CaptchaService(
      @Value("${aiqa.login.captcha.ttl:PT2M}") Duration ttl,
      @Value("${aiqa.login.captcha.fixed-code:}") String fixedCode) {
    this.ttl = ttl;
    this.fixedCode = fixedCode == null ? "" : fixedCode.strip();
  }

  /** 发放一枚验证码：返回标识与可直接放进 img 的 PNG data URL。 */
  public Challenge issue() {
    purgeExpired();
    if (pending.size() >= MAX_PENDING) {
      pending.clear();
    }
    String captchaId = UUID.randomUUID().toString();
    String code = fixedCode.isEmpty() ? randomCode() : fixedCode.toUpperCase(Locale.ROOT);
    pending.put(captchaId, new Pending(code, Instant.now().plus(ttl)));
    return new Challenge(captchaId, "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(png(code)));
  }

  /**
   * 校验验证码：缺失、过期、已用过或字符不匹配都按 400 INVALID_CAPTCHA 处理。
   * 只返回稳定错误码、不区分具体原因：前端按码给统一提示，避免把「过期」与「输错」拆成两条文案。
   * 校验动作本身会消耗该 captchaId，因此调用方不需要再手动清理。
   */
  public void verify(String captchaId, String answer) {
    String id = captchaId == null ? "" : captchaId.strip();
    String code = answer == null ? "" : answer.strip();
    Pending entry = id.isEmpty() ? null : pending.remove(id);
    if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
      throw new ApiException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST);
    }
    if (!entry.code().equalsIgnoreCase(code)) {
      throw new ApiException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST);
    }
  }

  /** 清理已过期的题面，避免长期运行时内存随刷新次数增长。 */
  private void purgeExpired() {
    Instant now = Instant.now();
    pending.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
  }

  private String randomCode() {
    StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /** 画图：浅色底 + 干扰线与噪点 + 轻微旋转的字符，保证人眼可读、脚本难认。 */
  private byte[] png(String code) {
    BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setColor(new Color(0xF3, 0xF6, 0xFA));
      graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
      drawNoise(graphics);
      drawCode(graphics, code);
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException ex) {
      throw new IllegalStateException("生成验证码图片失败", ex);
    }
    return out.toByteArray();
  }

  private void drawNoise(Graphics2D graphics) {
    graphics.setStroke(new BasicStroke(1.2f));
    for (int i = 0; i < 6; i++) {
      graphics.setColor(ink(120));
      graphics.drawLine(
          random.nextInt(IMAGE_WIDTH),
          random.nextInt(IMAGE_HEIGHT),
          random.nextInt(IMAGE_WIDTH),
          random.nextInt(IMAGE_HEIGHT));
    }
    for (int i = 0; i < 80; i++) {
      graphics.setColor(ink(110));
      graphics.fillRect(random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT), 1, 1);
    }
  }

  private void drawCode(Graphics2D graphics, String code) {
    graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE));
    int slot = IMAGE_WIDTH / (code.length() + 1);
    for (int i = 0; i < code.length(); i++) {
      AffineTransform origin = graphics.getTransform();
      int x = slot * (i + 1);
      // 每个字符单独旋转 ±15°，字符位置仍按等距槽位排布，避免相互重叠。
      graphics.rotate(Math.toRadians(random.nextInt(31) - 15), x, IMAGE_HEIGHT / 2.0);
      graphics.setColor(ink(90));
      graphics.drawString(
          String.valueOf(code.charAt(i)), x - 8, (int) (IMAGE_HEIGHT / 2.0 + 10));
      graphics.setTransform(origin);
    }
  }

  /** 深色系随机色：亮度限制在 40–130，干扰与文字都不会浅到看不清。 */
  private Color ink(int max) {
    return new Color(
        40 + random.nextInt(max), 40 + random.nextInt(max), 40 + random.nextInt(max));
  }

  /** 下发给前端的验证码：captchaId 用于登录时回传，image 是 PNG 的 data URL。 */
  public record Challenge(String captchaId, String image) {}

  private record Pending(String code, Instant expiresAt) {}
}
