package dev.aiqa.api;

import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.ErrorCode;
import dev.aiqa.common.PageQuery;
import dev.aiqa.common.PageView;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.settings.ModelConfigRepository;
import java.util.List;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 全局模型配置（多条）：管理员可增删改查；global_setting 默认指针指向其中一条。 */
@RestController
@RequestMapping("/api/v1/model-configs")
public class ModelConfigController {

  private final CurrentUser current;
  private final ModelConfigRepository configs;
  private final IdempotencyRepository idempotency;

  public ModelConfigController(
      CurrentUser current,
      ModelConfigRepository configs,
      IdempotencyRepository idempotency) {
    this.current = current;
    this.configs = configs;
    this.idempotency = idempotency;
  }

  public record ConfigView(
      String id, String alias, String credentialRef, boolean enabled, long revision,
      boolean isDefault, String protocol, String baseUrl, String modelName) {}

  public record ConfigInput(
      String alias, String credentialRef, Boolean enabled,
      String protocol, String baseUrl, String modelName) {}

  private ConfigView view(ModelConfigRepository.ConfigRow row) {
    return new ConfigView(
        row.id().toString(),
        row.alias(),
        row.credentialRef(),
        row.enabled(),
        row.revision(),
        configs.isDefault(row.id()),
        row.protocol(),
        row.baseUrl(),
        row.modelName());
  }

  private static final List<String> PROTOCOLS = List.of("openai_compatible", "anthropic");
  private static final URI LOCAL_MAGPIE = URI.create("http://127.0.0.1:3425/v1");

  private static void validateInput(ConfigInput input) {
    if (input.alias() == null || input.alias().isBlank()) {
      throw ApiException.invalid("模型别名不能为空");
    }
    if (input.credentialRef() == null || input.credentialRef().isBlank()) {
      throw ApiException.invalid("凭据引用不能为空（只允许服务端登记的不透明标识）");
    }
    String protocol = input.protocol() == null ? "openai_compatible" : input.protocol();
    if (!PROTOCOLS.contains(protocol)) {
      throw ApiException.invalid("协议仅支持 openai_compatible、anthropic");
    }
    if (input.baseUrl() == null || input.baseUrl().isBlank()) {
      throw ApiException.invalid("网关地址不能为空");
    }
    if (input.modelName() == null || input.modelName().isBlank()) {
      throw ApiException.invalid("模型 ID 不能为空");
    }
    try {
      URI uri = URI.create(input.baseUrl().strip());
      boolean localMagpie = "openai_compatible".equals(protocol) && LOCAL_MAGPIE.equals(uri);
      if ((!"https".equalsIgnoreCase(uri.getScheme()) && !localMagpie)
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw ApiException.invalid("网关地址须为不带凭据、查询参数或片段的 HTTPS 地址，或本机 Magpie 的 OpenAI 兼容地址");
      }
    } catch (IllegalArgumentException ex) {
      throw ApiException.invalid("网关地址格式无效");
    }
  }

  @GetMapping
  public PageView<ConfigView> list(
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    current.requireAdmin();
    PageQuery page = PageQuery.of(limit, offset);
    List<dev.aiqa.settings.ModelConfigRepository.ConfigRow> rows = configs.list();
    return PageView.of(page.slice(rows).stream().map(this::view).toList(), rows.size());
  }

  @GetMapping("/options")
  public PageView<ModelConfigRepository.ModelOption> listOptions() {
    current.require();
    return PageView.of(configs.listSelectable());
  }

  @PostMapping
  public ResponseEntity<ConfigView> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ConfigInput input) {
    AppUser admin = current.requireAdmin();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    validateInput(input);
    String alias = input.alias().strip();
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "model-create|" + alias + "|" + input.credentialRef() + "|" + input.enabled()
            + "|" + input.protocol() + "|" + input.baseUrl() + "|" + input.modelName());
    var reservation = idempotency.reserve(admin.id(), "CREATE_MODEL_CONFIG", idempotencyKey, digest);
    if (reservation.replay()) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(HttpHeaders.LOCATION, "/api/v1/model-configs/" + reservation.resourceId())
          .header(HttpHeaders.ETAG, Http.etag(configs.revision(reservation.resourceId())))
          .body(view(configs.find(reservation.resourceId()).orElseThrow(ApiException::notFound)));
    }
    if (configs.existsByAlias(alias)) {
      throw new ApiException(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "模型别名已存在");
    }
    UUID id;
    try {
      id = configs.create(
          alias,
          input.credentialRef().strip(),
          input.enabled() != null && input.enabled(),
          input.protocol() == null ? "openai_compatible" : input.protocol(),
          input.baseUrl() == null ? "" : input.baseUrl().strip(),
          input.modelName() == null ? "" : input.modelName().strip());
      idempotency.complete(admin.id(), "CREATE_MODEL_CONFIG", idempotencyKey, id, 201);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "CREATE_MODEL_CONFIG", idempotencyKey);
      throw ex;
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/v1/model-configs/" + id)
        .header(HttpHeaders.ETAG, Http.etag(configs.revision(id)))
        .body(view(configs.find(id).orElseThrow(ApiException::notFound)));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ConfigView> update(
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody ConfigInput input) {
    AppUser admin = current.requireAdmin();
    UUID configId = Http.pathUuid(id, "id");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    validateInput(input);
    ModelConfigRepository.ConfigRow row = configs.find(configId).orElseThrow(ApiException::notFound);
    long expected = Http.requireIfMatch(ifMatch);
    if (row.revision() != expected) {
      throw ApiException.preconditionFailed(ErrorCode.ETAG_MISMATCH);
    }
    String alias = input.alias().strip();
    String digest = dev.aiqa.common.Digests.sha256Hex(
        "model-update|" + configId + "|" + expected + "|" + alias + "|" + input.credentialRef()
            + "|" + input.enabled() + "|" + input.protocol() + "|" + input.baseUrl()
            + "|" + input.modelName());
    var reservation = idempotency.reserve(admin.id(), "UPDATE_MODEL_CONFIG", idempotencyKey, digest);
    if (reservation.replay()) {
      return ResponseEntity.ok()
          .header(HttpHeaders.ETAG, Http.etag(configs.revision(configId)))
          .body(view(configs.find(configId).orElseThrow(ApiException::notFound)));
    }
    if (!alias.equals(row.alias()) && configs.existsByAlias(alias)) {
      idempotency.release(admin.id(), "UPDATE_MODEL_CONFIG", idempotencyKey);
      throw new ApiException(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "模型别名已存在");
    }
    if (configs.isDefault(configId) && (input.enabled() == null || !input.enabled())) {
      idempotency.release(admin.id(), "UPDATE_MODEL_CONFIG", idempotencyKey);
      throw new ApiException(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "默认模型不可停用，请先切换默认模型");
    }
    try {
      configs.update(
          configId,
          alias,
          input.credentialRef().strip(),
          input.enabled() != null && input.enabled(),
          expected,
          input.protocol() == null ? "openai_compatible" : input.protocol(),
          input.baseUrl() == null ? "" : input.baseUrl().strip(),
          input.modelName() == null ? "" : input.modelName().strip());
      idempotency.complete(admin.id(), "UPDATE_MODEL_CONFIG", idempotencyKey, configId, 200);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "UPDATE_MODEL_CONFIG", idempotencyKey);
      throw ex;
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, Http.etag(configs.revision(configId)))
        .body(view(configs.find(configId).orElseThrow(ApiException::notFound)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    AppUser admin = current.requireAdmin();
    UUID configId = Http.pathUuid(id, "id");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, HttpStatus.BAD_REQUEST);
    }
    ModelConfigRepository.ConfigRow row = configs.find(configId).orElseThrow(ApiException::notFound);
    if (configs.isDefault(configId)) {
      throw new ApiException(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "默认模型不可删除，请先在全局设置中切换默认模型");
    }
    long expected = Http.requireIfMatch(ifMatch);
    if (row.revision() != expected) {
      throw ApiException.preconditionFailed(ErrorCode.ETAG_MISMATCH);
    }
    String digest = dev.aiqa.common.Digests.sha256Hex("model-delete|" + configId + "|" + expected);
    var reservation = idempotency.reserve(admin.id(), "DELETE_MODEL_CONFIG", idempotencyKey, digest);
    if (reservation.replay()) {
      return ResponseEntity.noContent().build();
    }
    try {
      configs.delete(configId, expected);
      idempotency.complete(admin.id(), "DELETE_MODEL_CONFIG", idempotencyKey, configId, 204);
    } catch (RuntimeException ex) {
      idempotency.release(admin.id(), "DELETE_MODEL_CONFIG", idempotencyKey);
      throw ex;
    }
    return ResponseEntity.noContent().build();
  }
}
