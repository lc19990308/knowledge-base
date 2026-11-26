#!/usr/bin/env python3
"""语料一致性体检（只读）。

用途：发布前扫一遍已发布快照，找出「同一主体、同一指标出现多个不同数值」的冲突，
以及重名文档、缺向量、无文本等已知问题。只读数据库，不修改任何数据。

用法：
    set -a; . ~/.config/ai-qa/dev.env; set +a
    python3 scripts/check-corpus-consistency.py            # 全部已发布空间
    python3 scripts/check-corpus-consistency.py --space 员工手册
    python3 scripts/check-corpus-consistency.py --markdown # 输出 Markdown（默认）

退出码：发现冲突返回 1，便于接在发布前置检查里；否则 0。

覆盖范围与限制：
- 能解析两种结构化形态：XLSX 行（本系统的「表头上下文：城市：杭州；上限（元）：620」）
  与 Markdown 表格（表头含「限额/上限」且某列是城市或地区）。
- 纯段落里的数字（例如「十五个工作日内」）不做语义解析，避免把不同条款误判成冲突；
  这类冲突仍需人工核对，脚本会在末尾写明未覆盖。
"""

import argparse
import os
import re
import subprocess
import sys
import urllib.parse
from collections import defaultdict

SEP = "\x1f"
NB = " ⏎ "  # 结果按行返回，正文换行被替换成这个标记
METRIC_KEYS = ("上限", "限额", "临时上限", "标准", "额度")
SUBJECT_KEYS = ("城市", "地区", "适用城市", "场景")
NUMBER = re.compile(r"(\d+(?:\.\d+)?)")


def jdbc_conn():
    """把 JDBC URL 拆成 psql 连接参数，凭据只从环境变量读取。"""
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
    """执行一条只读查询，按 SEP 分列返回文本行。"""
    env = dict(os.environ, PGPASSWORD=conn["password"])
    result = subprocess.run(
        ["psql", "-h", conn["host"], "-p", conn["port"], "-U", conn["user"],
         "-d", conn["db"], "-At", "-F", SEP, "-c", sql],
        capture_output=True, text=True, env=env, check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"psql 失败：{result.stderr.strip()[:400]}")
    return [line.split(SEP) for line in result.stdout.splitlines() if line]


def chunks(conn, space_filter):
    """当前已发布快照里的文本块：空间、文件名、位置、正文。"""
    where = "s.current_publication_id is not null"
    if space_filter:
        where += f" and s.name = '{space_filter.replace(chr(39), chr(39) * 2)}'"
    rows = psql(conn, f"""
        select s.name, v.display_filename, coalesce(c.source_location, ''),
               replace(replace(replace(c.body, chr(10), ' ⏎ '), chr(13), ' '), chr(9), ' '),
               (c.embedding is not null)::text
        from document_chunk c
        join document_version v on v.id = c.document_version_id
        join space s on s.id = c.space_id
        join space_snapshot_document sd
          on sd.document_version_id = v.id and sd.space_id = s.id
        join space_publication p
          on p.snapshot_id = sd.snapshot_id and p.space_id = s.id and p.id = s.current_publication_id
        where {where}
        order by s.name, v.display_filename, c.ordinal
    """)
    return [
        {"space": r[0], "file": r[1], "location": r[2], "body": r[3], "embedded": r[4] == "true"}
        for r in rows
    ]


def pairs(text):
    """把「键：值；键：值」拆成字典；XLSX 证据行就是这种形态。"""
    out = {}
    for part in re.split(r"[；;]", text):
        key, sep, value = part.partition("：")
        if sep and key.strip() and value.strip():
            out[key.strip()] = value.strip()
    return out


def canonical_metric(name):
    """把「上限（元）」「差旅住宿限额」「临时上限（元）」归一到同一口径，冲突才比得出来。"""
    if "临时" in name:
        return "临时上限"
    if any(word in name for word in ("上限", "限额")):
        return "住宿限额"
    return name.strip("（）() ")


def metric_of(fields):
    """从字段名里认出指标（上限/限额/临时上限…），返回 (归一指标, 值)。"""
    for key, value in fields.items():
        if any(word in key for word in METRIC_KEYS):
            number = NUMBER.search(value)
            if number:
                unit = "元" if "元" in key or "元" in value else ""
                return canonical_metric(key), number.group(1) + unit
    return None, None


def subject_of(fields):
    """主体：优先「场景/地区」组合，其次城市，最后单个地区。"""
    scene = next((fields[k] for k in ("场景",) if k in fields), None)
    region = next((fields[k] for k in ("地区", "城市") if k in fields), None)
    if scene and region:
        return f"{scene}/{region}"
    return region or scene


def from_xlsx_row(chunk):
    """本系统为 XLSX 行补的表头上下文：城市：杭州；档位：A 档；上限（元）：620。"""
    if "表头上下文" not in chunk["body"]:
        return []
    line = next((l for l in chunk["body"].split(NB) if "表头上下文" in l), "")
    fields = pairs(line.split("：", 1)[-1])
    subject, (metric, value) = subject_of(fields), metric_of(fields)
    if not subject or not metric:
        return []
    return [(subject, metric, value)]


def markdown_rows(chunk):
    """Markdown 表格：表头含「限额/上限」且存在城市/地区列时，逐行取数。"""
    lines = [l.strip() for l in chunk["body"].split(NB) if l.strip().startswith("|")]
    if len(lines) < 2:
        return []
    header = [cell.strip() for cell in lines[0].strip("|").split("|")]
    # 列名优先取「适用城市/地区」；「城市档次」这类档位列不是主体。
    subject_col = None
    for needle in ("适用城市", "地区", "城市"):
        subject_col = next((i for i, c in enumerate(header)
                            if needle in c and "档" not in c), None)
        if subject_col is not None:
            break
    metric_col = next((i for i, c in enumerate(header)
                       if any(k in c for k in METRIC_KEYS)), None)
    if subject_col is None or metric_col is None:
        return []
    found = []
    for line in lines[2:]:
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if len(cells) <= max(subject_col, metric_col):
            continue
        number = NUMBER.search(cells[metric_col])
        if not number:
            continue
        unit = "元" if "元" in header[metric_col] else ""
        metric = canonical_metric(header[metric_col])
        for city in re.split(r"[、,，/]", cells[subject_col]):
            if city.strip():
                found.append((city.strip(), metric, number.group(1) + unit))
    return found


def main():
    parser = argparse.ArgumentParser(description="已发布语料一致性体检（只读）")
    parser.add_argument("--space", help="只检查某个空间名")
    parser.add_argument("--json", action="store_true", help="输出 JSON 而不是 Markdown")
    args = parser.parse_args()

    conn = jdbc_conn()
    rows = chunks(conn, args.space)
    if not rows:
        print("没有可检查的已发布内容。")
        return 0

    values = defaultdict(list)
    for chunk in rows:
        for extractor in (from_xlsx_row, markdown_rows):
            for subject, metric, value in extractor(chunk):
                values[(subject, metric)].append(
                    {"value": value, "space": chunk["space"], "file": chunk["file"],
                     "location": chunk["location"]}
                )

    conflicts = {key: items for key, items in values.items()
                 if len({item["value"] for item in items}) > 1}
    files_by_name = defaultdict(set)
    for chunk in rows:
        files_by_name[chunk["file"]].add(chunk["space"])
    duplicates = {name: spaces for name, spaces in files_by_name.items() if len(spaces) > 1}
    missing_vector = [c for c in rows if not c["embedded"]]

    if args.json:
        import json
        print(json.dumps({
            "spaces": sorted({c["space"] for c in rows}),
            "conflicts": [{"subject": k[0], "metric": k[1], "values": v}
                          for k, v in conflicts.items()],
            "duplicateFiles": [{"file": k, "spaces": sorted(v)} for k, v in duplicates.items()],
            "chunksWithoutVector": len(missing_vector),
        }, ensure_ascii=False, indent=2))
        return 1 if conflicts else 0

    print("# 语料一致性体检\n")
    print(f"检查范围：{', '.join(sorted({c['space'] for c in rows}))}")
    print(f"文本块：{len(rows)}（无向量 {len(missing_vector)}）\n")

    print("## 同主体多值冲突\n")
    if not conflicts:
        print("未发现：结构化提取到的「主体 + 指标 → 数值」均唯一。\n")
    for (subject, metric), items in sorted(conflicts.items()):
        print(f"- **{subject} · {metric}** 出现 {len({i['value'] for i in items})} 个不同值")
        for item in sorted(items, key=lambda i: i["value"]):
            print(f"  - `{item['value']}` — {item['file']} @ {item['location'] or '无位置'}（{item['space']}）")
    if conflicts:
        print()

    print("## 重名文档\n")
    if not duplicates:
        print("未发现。\n")
    for name, spaces in sorted(duplicates.items()):
        print(f"- `{name}` 同时出现在：{', '.join(sorted(spaces))}")
    if duplicates:
        print()

    print("## 其他\n")
    print(f"- 无向量文本块：{len(missing_vector)}" if missing_vector else "- 无向量文本块：0")
    print("- 未覆盖：纯段落里的数值（如「十五个工作日内」）不做语义比对，需人工核对。")
    return 1 if conflicts else 0


if __name__ == "__main__":
    sys.exit(main())
