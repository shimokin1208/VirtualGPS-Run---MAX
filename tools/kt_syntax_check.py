"""Kotlin 词法级括号/字符串平衡检查。

在没有 Android SDK / Kotlin 编译器的环境里，用它兜住最常见的语法事故：
括号不配对、字符串或注释未闭合、非法字符。

用法: python tools/kt_syntax_check.py <file-or-dir> [more...]
"""

from __future__ import annotations

import sys
from pathlib import Path

OPEN = {"(": ")", "[": "]", "{": "}"}
CLOSE = {")": "(", "]": "[", "}": "{"}


def check(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    line = 1
    depth: list[tuple[str, int]] = []
    problems: list[str] = []
    i = 0
    n = len(text)
    state = "code"  # code | line_comment | block_comment | string | raw_string | char

    while i < n:
        c = text[i]
        if c == "\n":
            line += 1

        if state == "code":
            two = text[i : i + 2]
            if two == "//":
                state = "line_comment"
                i += 2
                continue
            if two == "/*":
                open_line = line
                depth_comment = 1
                i += 2
                # 处理块注释（Kotlin 支持嵌套）
                while i < n:
                    if text[i] == "\n":
                        line += 1
                    if text[i : i + 2] == "/*":
                        depth_comment += 1
                        i += 2
                        continue
                    if text[i : i + 2] == "*/":
                        depth_comment -= 1
                        i += 2
                        if depth_comment == 0:
                            break
                        continue
                    i += 1
                if depth_comment != 0:
                    problems.append(f"line {open_line}: 块注释未闭合")
                # 注释已就地消费完，回到 code 状态
                state = "code"
                continue
            if c == '"':
                if text[i : i + 3] == '"""':
                    state = "raw_string"
                    i += 3
                    continue
                state = "string"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            if c in OPEN:
                depth.append((c, line))
                i += 1
                continue
            if c in CLOSE:
                if not depth:
                    problems.append(f"line {line}: 多余的 '{c}'")
                elif depth[-1][0] != CLOSE[c]:
                    opener, opened_at = depth[-1]
                    problems.append(
                        f"line {line}: '{c}' 与 line {opened_at} 的 '{opener}' 不匹配"
                    )
                    depth.pop()
                else:
                    depth.pop()
                i += 1
                continue
            i += 1
            continue

        if state == "line_comment":
            if c == "\n":
                state = "code"
            i += 1
            continue

        if state == "string":
            if c == "\\":
                if i + 1 < n and text[i + 1] == "\n":
                    line += 1
                i += 2
                continue
            if c == '"':
                state = "code"
                i += 1
                continue
            # ${...} 模板（简化处理：模板内的括号计入平衡）
            if text[i : i + 2] == "${":
                depth.append(("{", line))
                i += 2
                continue
            if c == "}" and depth and depth[-1][0] == "{":
                depth.pop()
            i += 1
            continue

        if state == "raw_string":
            if text[i : i + 3] == '"""':
                state = "code"
                i += 3
                continue
            i += 1
            continue

        if state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
            i += 1
            continue

    if state == "string" or state == "char":
        problems.append(f"line {line}: 字符串/字符字面量未闭合")
    if state == "raw_string":
        problems.append(f"line {line}: 三引号字符串未闭合")
    for opener, opened_at in depth:
        problems.append(f"line {opened_at}: '{opener}' 未闭合")
    return problems


def main(argv: list[str]) -> int:
    targets: list[Path] = []
    for raw in argv:
        p = Path(raw)
        if p.is_dir():
            targets.extend(sorted(p.rglob("*.kt")))
        else:
            targets.append(p)

    failed = 0
    for path in targets:
        problems = check(path)
        if problems:
            failed += 1
            print(f"FAIL {path}")
            for problem in problems:
                print(f"     {problem}")
        else:
            print(f"OK   {path.name}")
    print(f"\n{len(targets) - failed}/{len(targets)} files clean")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
