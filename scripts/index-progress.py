#!/usr/bin/env python3
"""入库切片索引进度（只读）。

回答「文件传到哪一步了、切了多少块、有没有向量、能不能被检索」：
按空间列出每份文档的解析状态、文本块数、向量块数、最近一次索引任务结果，
并在末尾给出汇总。只读数据库，不改任何数据。

用法：
    set -a; . ~/.config/ai-qa/dev.env; set +a
    python3 scripts/index-progress.py                 # 全部空间
    python3 scripts/index-progress.py --space 员工手册
    python3 scripts/index-progress.py --json          # 机器可读

UI 上的对应位置：
- 知识库列表的「文档/可检索」两列 = 本脚本的 文档数/可检索数；
- 空间详情的文档状态 = 本脚本的「解析」列；
- 导入弹窗的逐文件进度 = 本脚本的「解析」列在批次内的实时状态（接口 /imports/{batchId}）。
"""

import argparse
import os
import subprocess
import sys

SEP = "\x1f"


def jdbc_conn():
    """把 JDBC URL 拆成 psql 参数，凭据只读环境变量。"""
    url = os.environ["DB_URL"]
    rest = url.split("jdbc:postgresql://", 1)[-1]
    host_port, _, db = rest.partition("/")
    host, _, port = host_port.partition(":")
    return {
        "host": host or "127.0.0.1",
        "port": port or "5432",
        "db": db.split("?")[0],
        "user": os.environ["DB_USER"],
        "password": os.environ["DB_PASSWORD"],
    }


def psql(conn, sql):
    """执行只读查询，按 SEP 分列返回。"""
    env = dict(os.environ, PGPASSWORD=conn["password"])
    result = subprocess.run(
        ["psql", "-h", conn["host"], "-p", conn["port"], "-U", conn["user"],
         "-d", conn["db"], "-At", "-F", SEP, "-c", sql],
        capture_output=True, text=True, env=env, check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"psql 失败：{result.stderr.strip()[:400]}")
    return [line.split(SEP) for line in result.stdout.splitlines() if line]


def rows(conn, space_filter):
    """每份文档一行：空间、文件名、解析状态、块数、向量数、索引任务、发布状态。"""
    where = ""
    if space_filter:
        where = f"and s.name = '{space_filter.replace(chr(39), chr(39) * 2)}'"
    data = psql(conn, f"""
        with chunk_stats as (
          select document_version_id,
                 count(*) as chunks,
                 count(embedding) as vectors
          from document_chunk group by document_version_id
        ), last_index as (
          select document_version_id, max(created_at) as finished_at,
                 (array_agg(status order by created_at desc))[1] as status,
                 (array_agg(error_code order by created_at desc))[1] as error_code
          from job where job_type = 'INDEX_DOCUMENT' group by document_version_id
        )
        select s.name, v.display_filename, v.parse_state,
               coalesce(cs.chunks, 0), coalesce(cs.vectors, 0),
               coalesce(li.status, '-'), coalesce(li.error_code, '-'),
               coalesce(to_char(li.finished_at, 'MM-DD HH24:MI'), '-'),
               (v.id in (select d.document_version_id from space_snapshot_document d
                          join space_publication p on p.snapshot_id = d.snapshot_id
                          where p.id = s.current_publication_id)) as published
        from document_version v
        join space s on s.id = v.space_id
        left join chunk_stats cs on cs.document_version_id = v.id
        left join last_index li on li.document_version_id = v.id
        where true {where}
        order by s.name, v.display_filename, v.created_at
    """)
    return [
        {"space": r[0], "file": r[1], "parse": r[2], "chunks": int(r[3]),
         "vectors": int(r[4]), "indexJob": r[5], "indexError": r[6],
         "indexedAt": r[7], "published": r[8] == "t"}
        for r in data
    ]


def main():
    parser = argparse.ArgumentParser(description="入库切片索引进度（只读）")
    parser.add_argument("--space", help="只看某个空间")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    args = parser.parse_args()

    conn = jdbc_conn()
    items = rows(conn, args.space)
    if not items:
        print("没有文档。")
        return 0

    if args.json:
        import json
        print(json.dumps(items, ensure_ascii=False, indent=2))
        return 0

    current_space = None
    for item in items:
        if item["space"] != current_space:
            current_space = item["space"]
            print(f"\n## {current_space}")
            print("  文件 | 解析 | 块 | 向量 | 索引任务 | 索引时间 | 已发布")
        flag = "" if item["vectors"] == item["chunks"] else "  ← 向量不全"
        print(f"  {item['file']} | {item['parse']} | {item['chunks']} | "
              f"{item['vectors']} | {item['indexJob']}"
              f"{'' if item['indexError'] == '-' else '/' + item['indexError']} | "
              f"{item['indexedAt']} | {'是' if item['published'] else '否'}{flag}")

    total_chunks = sum(i["chunks"] for i in items)
    total_vectors = sum(i["vectors"] for i in items)
    ready = sum(1 for i in items if i["parse"] == "READY")
    published = sum(1 for i in items if i["published"])
    print("\n## 汇总")
    print(f"  文档 {len(items)} 份，解析成功 {ready}，已加入当前发布 {published}")
    print(f"  文本块 {total_chunks}，其中 {total_vectors} 块有向量"
          f"（{0 if not total_chunks else round(total_vectors * 100 / total_chunks)}%）")
    print("  说明：索引任务为 '-' 表示该文档随发布快照建索引（没有单独的重建任务）；"
          "只查看不可检索的文档不计入「已发布」。")
    if any(i["vectors"] != i["chunks"] and i["parse"] == "READY" for i in items):
        print("  提示：有解析成功但向量不全的文档，可对空间执行 POST /spaces/{id}/reindex 重算。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
