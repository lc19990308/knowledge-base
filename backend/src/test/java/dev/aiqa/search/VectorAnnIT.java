package dev.aiqa.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiqa.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * pgvector 近邻检索的规模与一致性验证。
 *
 * <p>在真实 {@code document_chunk} 表上写入随机向量并建 HNSW 索引，逐条比对
 * 「数据库按 {@code <=>} 召回」与「应用内暴力余弦」的 top-N，确认近似检索没有把正确结果漏掉。
 *
 * <p>需要数据库允许在当前 schema 安装 vector 扩展（CI 用 pgvector/pgvector 镜像、superuser
 * 账号）；本地普通账号无权限时跳过，不影响其余测试。
 */
class VectorAnnIT {

  private static final int CHUNKS = 1000;
  private static final int DIMENSIONS = 1024;
  private static final int TOP = 20;
  private static final int QUERIES = 5;
  /** HNSW 是近似索引：允许每个查询漏 1 条，平均重合不低于 95%。 */
  private static final int MIN_OVERLAP_PER_QUERY = TOP - 1;

  @Test
  void annMatchesBruteForceCosineOnRealChunkTable() {
    var isolated = TestDatabase.fromEnvironment();
    Assumptions.assumeTrue(isolated.isPresent(), "未配置隔离测试数据库");
    try (var database = isolated.orElseThrow()) {
      var source =
          new DriverManagerDataSource(database.jdbcUrl(), database.user(), database.password());
      JdbcClient jdbc = JdbcClient.create(source);
      // 扩展必须先于迁移存在：V100 的 ALTER 语句直接引用 vector 类型。
      try {
        // 与 V100 一致装到 public：测试 schema 会被 DROP CASCADE，扩展对象不能跟着没。
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS vector SCHEMA public").update();
      } catch (RuntimeException ex) {
        Assumptions.abort("数据库不允许安装 pgvector，跳过 ANN 一致性验证：" + ex.getMessage());
      }
      Flyway.configure()
          .dataSource(source)
          .schemas(database.schema())
          .locations("classpath:db/migration", "classpath:db/migration-pgvector")
          .load()
          .migrate();

      UUID userId = UUID.randomUUID();
      UUID spaceId = UUID.randomUUID();
      UUID documentId = UUID.randomUUID();
      insertOwnerRows(jdbc, userId, spaceId, documentId);

      Random random = new Random(42);
      List<float[]> vectors = new ArrayList<>(CHUNKS);
      for (int index = 0; index < CHUNKS; index++) {
        float[] vector = new float[DIMENSIONS];
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
          vector[dimension] = random.nextFloat() * 2 - 1;
        }
        vectors.add(vector);
        insertChunk(jdbc, spaceId, documentId, index, vector);
      }

      for (int query = 0; query < QUERIES; query++) {
        float[] target = new float[DIMENSIONS];
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
          target[dimension] = random.nextFloat() * 2 - 1;
        }
        List<UUID> ann = annTop(jdbc, target);
        List<UUID> brute = bruteTop(jdbc, target, vectors);
        Set<UUID> expected = new HashSet<>(brute);
        long overlap = ann.stream().filter(expected::contains).count();
        assertThat(overlap)
            .as("query %d: ANN 与暴力余弦 top%d 重合", query, TOP)
            .isGreaterThanOrEqualTo(MIN_OVERLAP_PER_QUERY);
      }
    }
  }

  private static void insertOwnerRows(
      JdbcClient jdbc, UUID userId, UUID spaceId, UUID documentId) {
    jdbc.sql(
            "INSERT INTO app_user (id, issuer, subject, display_name) VALUES (:id, 'it', :subject, 'ANN 测试')")
        .param("id", userId)
        .param("subject", "ann-" + userId)
        .update();
    jdbc.sql("INSERT INTO space (id, owner_id, name) VALUES (:id, :owner, 'ANN 测试空间')")
        .param("id", spaceId)
        .param("owner", userId)
        .update();
    jdbc.sql(
            """
            INSERT INTO document_version
              (id, space_id, document_id, normalized_filename, display_filename, object_key,
               sha256, media_type, size_bytes, scan_state, parse_state, created_by)
            VALUES (:id, :space, :doc, 'ann.txt', 'ann.txt', :key, :sha, 'text/plain', 1,
                    'CLEAN', 'READY', :owner)
            """)
        .param("id", documentId)
        .param("space", spaceId)
        .param("doc", UUID.randomUUID())
        .param("key", "ann/" + documentId)
        .param("sha", "0".repeat(64))
        .param("owner", userId)
        .update();
  }

  private static void insertChunk(
      JdbcClient jdbc, UUID spaceId, UUID documentId, int ordinal, float[] vector) {
    jdbc.sql(
            """
            INSERT INTO document_chunk
              (id, space_id, document_version_id, ordinal, body, search_vector,
               embedding, embedding_vec)
            VALUES (:id, :space, :doc, :ordinal, :body, to_tsvector('simple', :body),
                    CAST(:embedding AS real[]), CAST(:vec AS vector))
            """)
        .param("id", UUID.randomUUID())
        .param("space", spaceId)
        .param("doc", documentId)
        .param("ordinal", ordinal)
        .param("body", "测试文本块 " + ordinal)
        .param("embedding", EmbeddingClient.literal(vector))
        .param("vec", EmbeddingClient.vectorLiteral(vector))
        .update();
  }

  /** 数据库按余弦距离召回；与应用检索使用同一条 SQL 形式。 */
  private static List<UUID> annTop(JdbcClient jdbc, float[] target) {
    return jdbc.sql(
            """
            SELECT id FROM document_chunk
            WHERE embedding_vec IS NOT NULL
            ORDER BY embedding_vec <=> CAST(:queryVec AS vector)
            LIMIT :limit
            """)
        .param("queryVec", EmbeddingClient.vectorLiteral(target))
        .param("limit", TOP)
        .query(UUID.class)
        .list();
  }

  /** 应用内暴力余弦，作为正确答案的参照。 */
  private static List<UUID> bruteTop(JdbcClient jdbc, float[] target, List<float[]> vectors) {
    record Row(UUID id, float[] vector) {}
    List<Row> rows = new ArrayList<>(vectors.size());
    List<UUID> ids = jdbc.sql("SELECT id FROM document_chunk ORDER BY ordinal")
        .query(UUID.class)
        .list();
    for (int index = 0; index < ids.size(); index++) {
      rows.add(new Row(ids.get(index), vectors.get(index)));
    }
    return rows.stream()
        .sorted(Comparator.comparingDouble((Row row) -> -EmbeddingClient.cosine(target, row.vector())))
        .limit(TOP)
        .map(Row::id)
        .toList();
  }
}
