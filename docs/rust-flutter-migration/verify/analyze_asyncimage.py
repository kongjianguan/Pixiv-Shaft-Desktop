import re
import pathlib

root = pathlib.Path("app/src/main/kotlin")
pattern = re.compile(r"AsyncImage\(")

# 提取每个 AsyncImage( 调用的完整括号块
for path in sorted(root.rglob("*.kt")):
    text = path.read_text()
    for m in pattern.finditer(text):
        start = m.start()
        depth = 0
        i = m.end() - 1
        while i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        block = text[m.end():i]
        line_no = text[:start].count("\n") + 1
        params = re.findall(r"^\s*(\w+)\s*=", block, re.M)
        print(f"{path.relative_to(root)}:{line_no}  参数: {params}")
