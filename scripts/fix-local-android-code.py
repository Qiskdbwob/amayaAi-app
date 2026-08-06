#!/usr/bin/env python3
"""Fix conservative unused imports and verified dead files in local/core Android code."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

SOURCE_ROOT = Path("app/src/main/java")
EXCLUDED_PARTS = {"remote", "opencode", "antigravity", "bridge"}
EXCLUDED_NAMES = {"WindowsBridge", "RemoteIdeIcon"}
IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$")
TOKEN_RE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
IMPLICIT_OPERATOR_IMPORTS = {"getValue", "setValue", "provideDelegate"}
DEAD_FILES = (
    "app/src/main/java/com/amaya/intelligence/impl/common/conversation/ConversationPersistence.kt",
    "app/src/main/java/com/amaya/intelligence/impl/local/browser/BrowserScriptAssets.kt",
    "app/src/main/java/com/amaya/intelligence/impl/local/chat/LocalSessionProjection.kt",
    "app/src/main/java/com/amaya/intelligence/impl/local/chat/LocalTurnCoordinator.kt",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def local_core(path: Path) -> bool:
    return not (set(path.parts) & EXCLUDED_PARTS) and not any(
        marker in path.as_posix() for marker in EXCLUDED_NAMES
    )


def remove_unused_imports(path: Path, *, check: bool) -> list[str]:
    source = read(path)
    body = "\n".join("" if line.lstrip().startswith("import ") else line for line in source.splitlines())
    tokens = set(TOKEN_RE.findall(body))
    removed: list[str] = []
    kept: list[str] = []
    for line in source.splitlines(keepends=True):
        match = IMPORT_RE.match(line.strip())
        if not match or match.group(1).endswith(".*"):
            kept.append(line)
            continue
        name = match.group(2) or match.group(1).rsplit(".", 1)[-1]
        if name in tokens or name in IMPLICIT_OPERATOR_IMPORTS:
            kept.append(line)
        else:
            removed.append(line.strip())
    if removed and not check:
        path.write_text("".join(kept), encoding="utf-8", newline="")
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true", help="Report fixes without writing")
    args = parser.parse_args()
    root = args.root.resolve()
    source_root = root / SOURCE_ROOT
    removed_imports = 0
    changed_files = 0

    for path in sorted(source_root.rglob("*.kt")):
        if not local_core(path.relative_to(root)):
            continue
        removed = remove_unused_imports(path, check=args.check)
        if removed:
            changed_files += 1
            removed_imports += len(removed)
            print(f"imports {path.relative_to(root).as_posix()}: {len(removed)}")

    removed_dead = 0
    for relative in DEAD_FILES:
        path = root / relative
        if path.exists():
            removed_dead += 1
            print(f"dead-file {relative}")
            if not args.check:
                path.unlink()

    print(f"{'remaining' if args.check else 'removed'}: imports={removed_imports}, files={changed_files}, dead={removed_dead}")
    return 1 if args.check and (removed_imports or removed_dead) else 0


if __name__ == "__main__":
    raise SystemExit(main())
