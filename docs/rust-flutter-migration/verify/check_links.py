import os
import re
import sys


def strip_code(text):
    """去掉围栏代码块与行内代码，避免把其中的正则和路径当成链接"""
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    text = re.sub(r"`[^`\n]*`", "", text)
    return text


def check_links(path):
    base = os.path.dirname(os.path.abspath(path))
    text = open(path, encoding="utf-8").read()
    broken = []
    for match in re.finditer(r"\]\(([^)h][^)]*)\)", strip_code(text)):
        ref = match.group(1).split("#")[0]
        if not ref:
            continue
        if not os.path.exists(os.path.join(base, ref)):
            broken.append(ref)
    return broken


def check_tables(path):
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    blocks = []
    current = []
    in_fence = False
    for lineno, line in enumerate(lines, 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        if in_fence:
            continue
        if line.strip().startswith("|"):
            current.append((lineno, line))
        elif current:
            blocks.append(current)
            current = []
    if current:
        blocks.append(current)

    problems = []
    for block in blocks:
        counts = {}
        for lineno, line in block:
            # 表格单元内的竖线用 \| 转义，切分前先占位替换
            cell_line = line.replace("\\|", "\x00")
            width = len(cell_line.split("|")) - 2
            counts.setdefault(width, []).append(lineno)
        if len(counts) > 1:
            problems.append((block[0][0], {k: v[:3] for k, v in counts.items()}))
    return problems, len(blocks)


def collect(targets):
    files = []
    for target in targets:
        if os.path.isdir(target):
            for root, _, names in os.walk(target):
                files.extend(
                    os.path.join(root, name)
                    for name in sorted(names)
                    if name.endswith(".md")
                )
        else:
            files.append(target)
    return files


def main():
    targets = sys.argv[1:]
    if not targets:
        print("用法: check_links.py <Markdown 文件或目录> ...")
        return 2
    failed = False
    total_tables = 0
    for path in collect(targets):
        broken = check_links(path)
        problems, tables = check_tables(path)
        total_tables += tables
        if broken or problems:
            failed = True
            print(f"--- {path} ---")
            for ref in broken:
                print("  失效链接:", ref)
            for start, counts in problems:
                print("  表格列数不一致，起始行", start, counts)
    if failed:
        return 1
    print(f"全部有效：{len(collect(targets))} 个文档，{total_tables} 个表格")
    return 0


sys.exit(main())
