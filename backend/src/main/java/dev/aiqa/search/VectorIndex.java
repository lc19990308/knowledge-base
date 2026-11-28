package dev.aiqa.search;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 数据库是否具备 pgvector 近邻检索能力。
 *
 * <p>应用内存里的余弦只能扫有限条候选（当前 2000 条），文本块一多就变成随机抽样。
 * 安装了 pgvector 并启用 {@code pgvector} profile 时，检索改由 HNSW 索引按余弦排序；
 * 扩展没装（部分私有化客户不允许装）时保持内存余弦，并把降级状态暴露成指标与日志，
 * 避免「检索悄悄退化」。
 */
@Component
public class VectorIndex {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(VectorIndex.class);

  private final boolean ann;

  public VectorIndex(JdbcClient jdbc, MeterRegistry meters) {
    this.ann = detect(jdbc);
    if (ann) {
      log.info("向量检索模式：ann（pgvector HNSW 近邻）");
    } else {
      log.info("向量检索模式：memory（应用内余弦，候选上限 2000 条）；安装 pgvector 并启用 profile 可切换");
    }
    meters.gauge("aiqa.retrieval.vector.mode", ann ? 1 : 0);
  }

  /** 扩展与索引列同时存在才算可用；任一缺失都按内存余弦处理。 */
  private static boolean detect(JdbcClient jdbc) {
    try {
      Long columns =
          jdbc.sql(
                  """
                  SELECT count(*) FROM information_schema.columns
                  WHERE table_schema = current_schema()
                    AND table_name = 'document_chunk' AND column_name = 'embedding_vec'
                  """)
              .query(Long.class)
              .single();
      if (columns == null || columns == 0) {
        return false;
      }
      Long extension =
          jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
              .query(Long.class)
              .single();
      return extension != null && extension > 0;
    } catch (RuntimeException ex) {
      // 检测失败不能阻塞启动：按降级处理，检索仍可用（只是慢且不完整）。
      log.warn("向量索引能力检测失败，回退内存余弦：{}", ex.toString());
      return false;
    }
  }

  /** true 表示可以走 {@code embedding_vec <=> query} 的数据库近邻检索。 */
  public boolean ann() {
    return ann;
  }
}
