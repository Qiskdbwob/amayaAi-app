#!/usr/bin/env python3
"""Reusable static inventory for the Android Kotlin source tree.

Produces JSON plus a concise text report. Findings are candidates; Android entry
points, Hilt/Room/Moshi-generated references, reflection, and Compose previews need
manual validation before deletion.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

SOURCE_EXTENSIONS = {".kt", ".java"}
TEXT_EXTENSIONS = SOURCE_EXTENSIONS | {".xml", ".kts", ".gradle", ".json", ".js", ".md"}
DECLARATION_RE = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|open|abstract|final|sealed|data|enum|"
    r"annotation|value|expect|actual|inline|tailrec|suspend|operator|infix|external|"
    r"const|lateinit)\s+)*(class|object|interface|fun|typealias|val|var)\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)"
)
IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$")
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)\s*$", re.MULTILINE)
TOKEN_RE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
BRANCH_RE = re.compile(r"\b(?:if|when|for|while|catch)\b|&&|\|\|")
URL_RE = re.compile(r"(?:https?|wss?)://[^\s\"']+")
LITERAL_RE = re.compile(r'"(?:\\.|[^"\\])*"')
NUMBER_RE = re.compile(r"(?<![A-Za-z0-9_])(?:\d[\d_]{3,})(?:L|f|F)?\b")
SENSITIVE_RE = re.compile(
    r"(?:api[_ -]?key|password|passwd|secret|access[_ -]?token|refresh[_ -]?token|bearer)",
    re.IGNORECASE,
)
GENERATED_OR_FRAMEWORK_NAMES = {
    "Composable", "Preview", "Entity", "Dao", "Database", "Module", "Provides", "Binds",
    "Inject", "Singleton", "JsonClass", "Json", "Parcelize", "HiltViewModel", "AndroidEntryPoint",
}


@dataclass(frozen=True)
class FileMetric:
    path: str
    lines: int
    nonblank_lines: int
    max_line: int
    lines_over_160: int
    lines_over_240: int
    declarations: int
    branches: int


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    name: str
    detail: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--source", default="app/src/main/java")
    parser.add_argument("--tests", default="app/src/test/java")
    parser.add_argument("--debug", default="app/src/debug/java")
    parser.add_argument("--large-lines", type=int, default=500)
    parser.add_argument("--very-large-lines", type=int, default=1000)
    parser.add_argument("--long-line", type=int, default=160)
    parser.add_argument("--very-long-line", type=int, default=240)
    parser.add_argument("--json", type=Path, default=Path("app/build/reports/python-code-audit.json"))
    parser.add_argument("--text", type=Path, default=Path("app/build/reports/python-code-audit.txt"))
    parser.add_argument("--fail-on-package-mismatch", action="store_true")
    return parser.parse_args()


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig", errors="replace")


def strip_comments_and_strings(source: str) -> str:
    """Preserve line count while blanking comments and literals."""
    result: list[str] = []
    i = 0
    state = "code"
    while i < len(source):
        if state == "code":
            if source.startswith("//", i):
                result.extend("  ")
                i += 2
                state = "line_comment"
            elif source.startswith("/*", i):
                result.extend("  ")
                i += 2
                state = "block_comment"
            elif source.startswith('"""', i):
                result.extend("   ")
                i += 3
                state = "triple_string"
            else:
                result.append(source[i])
                i += 1
        elif state == "line_comment":
            if source[i] == "\n":
                result.append("\n")
                state = "code"
            else:
                result.append(" ")
            i += 1
        elif state == "block_comment":
            if source.startswith("*/", i):
                result.extend("  ")
                i += 2
                state = "code"
            else:
                result.append("\n" if source[i] == "\n" else " ")
                i += 1
        elif state == "triple_string":
            if source.startswith('"""', i):
                result.extend("   ")
                i += 3
                state = "code"
            else:
                result.append("\n" if source[i] == "\n" else " ")
                i += 1
    return "".join(result)


def top_level_declarations(source: str) -> list[tuple[str, str, int, str]]:
    clean = strip_comments_and_strings(source)
    depth = 0
    declarations: list[tuple[str, str, int, str]] = []
    for line_number, line in enumerate(clean.splitlines(), 1):
        if depth == 0:
            match = DECLARATION_RE.match(line)
            if match:
                declarations.append((match.group(1), match.group(2), line_number, line.strip()))
        depth = max(0, depth + line.count("{") - line.count("}"))
    return declarations


def relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def package_mismatches(source_root: Path, root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in sorted(source_root.rglob("*.kt")):
        match = PACKAGE_RE.search(read_text(path))
        if not match:
            findings.append(Finding(relative(path, root), 1, "missing package"))
            continue
        expected = ".".join(path.relative_to(source_root).parts[:-1])
        if match.group(1) != expected:
            findings.append(Finding(relative(path, root), 1, match.group(1), f"expected {expected}"))
    return findings


def manifest_missing_classes(root: Path, source_roots: list[Path]) -> list[Finding]:
    manifest = root / "app/src/main/AndroidManifest.xml"
    if not manifest.is_file():
        return []
    text = read_text(manifest)
    namespace_match = re.search(r'namespace\s*=\s*"([^"]+)"', read_text(root / "app/build.gradle.kts"))
    namespace = namespace_match.group(1) if namespace_match else ""
    available: set[str] = set()
    class_re = re.compile(r"\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)")
    for source_root in source_roots:
        if not source_root.exists():
            continue
        for path in source_root.rglob("*.kt"):
            package = PACKAGE_RE.search(read_text(path))
            if not package:
                continue
            for class_name in class_re.findall(strip_comments_and_strings(read_text(path))):
                available.add(f"{package.group(1)}.{class_name}")
    findings: list[Finding] = []
    for match in re.finditer(r'<(?:activity|service|receiver|provider|application)\b[^>]*?android:name="([^"]+)"', text, re.DOTALL):
        raw = match.group(1)
        if raw.startswith("android.") or raw.startswith("androidx.") or raw.startswith("com.google."):
            continue
        fqcn = namespace + raw if raw.startswith(".") else raw
        if fqcn.startswith(namespace) and fqcn not in available:
            findings.append(Finding(relative(manifest, root), text[:match.start()].count("\n") + 1, fqcn))
    return findings


def duplicate_blocks(files: list[Path], root: Path, minimum_lines: int = 8) -> list[dict[str, object]]:
    """Find exact normalized blocks. Overlapping windows collapse by hash."""
    hashes: dict[str, list[tuple[Path, int, list[str]]]] = collections.defaultdict(list)
    for path in files:
        source = read_text(path)
        # Imports and short syntax-only runs create noisy duplicates, not reusable logic.
        body = "\n".join("" if line.strip().startswith("import ") else line for line in source.splitlines())
        clean_lines = [re.sub(r"\s+", " ", line.strip()) for line in strip_comments_and_strings(body).splitlines()]
        for start in range(0, max(0, len(clean_lines) - minimum_lines + 1)):
            block = clean_lines[start:start + minimum_lines]
            if sum(bool(line) for line in block) < minimum_lines - 1:
                continue
            joined = "\n".join(block)
            if len(joined) < 240 or len(set(block)) < minimum_lines // 2:
                continue
            digest = hashlib.sha256("\n".join(block).encode()).hexdigest()
            hashes[digest].append((path, start + 1, block))
    result: list[dict[str, object]] = []
    for digest, occurrences in hashes.items():
        unique = {(path, line) for path, line, _ in occurrences}
        unique_files = {path for path, _, _ in occurrences}
        if len(unique) < 2 or len(unique_files) < 2:
            continue
        first_block = occurrences[0][2]
        result.append({
            "hash": digest[:12],
            "lines": minimum_lines,
            "preview": next((line for line in first_block if line), ""),
            "occurrences": [
                {"path": relative(path, root), "line": line}
                for path, line in sorted(unique, key=lambda item: (str(item[0]), item[1]))
            ],
        })
    result.sort(key=lambda item: (-len(item["occurrences"]), item["preview"]))
    # Overlapping windows for the same file set are one duplication family.
    collapsed: list[dict[str, object]] = []
    seen_file_sets: set[tuple[str, ...]] = set()
    for item in result:
        file_set = tuple(sorted({occurrence["path"] for occurrence in item["occurrences"]}))
        if file_set in seen_file_sets:
            continue
        seen_file_sets.add(file_set)
        collapsed.append(item)
    return collapsed[:100]


def audit(args: argparse.Namespace) -> dict[str, object]:
    root = args.root.resolve()
    source_root = root / args.source
    extra_roots = [root / args.tests, root / args.debug]
    production_files = sorted(path for path in source_root.rglob("*") if path.suffix in SOURCE_EXTENSIONS)
    reference_files = production_files + sorted(
        path for extra_root in extra_roots if extra_root.exists()
        for path in extra_root.rglob("*") if path.suffix in SOURCE_EXTENSIONS
    )
    texts = {path: read_text(path) for path in reference_files}
    reference_text = "\n".join(texts.values())
    manifest = root / "app/src/main/AndroidManifest.xml"
    if manifest.exists():
        reference_text += "\n" + read_text(manifest)
    token_counts = collections.Counter(TOKEN_RE.findall(reference_text))

    metrics: list[FileMetric] = []
    dead_candidates: list[Finding] = []
    unused_imports: list[Finding] = []
    hardcoded_urls: list[Finding] = []
    hardcoded_sensitive_literals: list[Finding] = []
    magic_numbers: list[Finding] = []
    replacement_characters: list[Finding] = []

    for path in production_files:
        source = texts[path]
        lines = source.splitlines()
        clean = strip_comments_and_strings(source)
        declarations = top_level_declarations(source)
        metrics.append(FileMetric(
            path=relative(path, root),
            lines=len(lines),
            nonblank_lines=sum(bool(line.strip()) for line in lines),
            max_line=max((len(line) for line in lines), default=0),
            lines_over_160=sum(len(line) > args.long_line for line in lines),
            lines_over_240=sum(len(line) > args.very_long_line for line in lines),
            declarations=len(declarations),
            branches=len(BRANCH_RE.findall(clean)),
        ))
        for kind, name, line_number, signature in declarations:
            if kind in {"class", "object", "interface", "fun", "typealias"} and token_counts[name] <= 1:
                if name not in GENERATED_OR_FRAMEWORK_NAMES:
                    dead_candidates.append(Finding(relative(path, root), line_number, name, f"{kind}; textual refs={token_counts[name]}; {signature}"))
        body = "\n".join(line for line in source.splitlines() if not line.startswith("import "))
        for line_number, line in enumerate(source.splitlines(), 1):
            import_match = IMPORT_RE.match(line.strip())
            if import_match and not import_match.group(1).endswith(".*"):
                name = import_match.group(2) or import_match.group(1).rsplit(".", 1)[-1]
                if not re.search(rf"\b{re.escape(name)}\b", body):
                    unused_imports.append(Finding(relative(path, root), line_number, name))
            for url in URL_RE.findall(line):
                hardcoded_urls.append(Finding(relative(path, root), line_number, url[:160]))
            if "\ufffd" in line:
                replacement_characters.append(Finding(relative(path, root), line_number, "U+FFFD", line.strip()[:160]))
            for literal_match in LITERAL_RE.finditer(line):
                literal = literal_match.group(0)
                if SENSITIVE_RE.search(literal):
                    hardcoded_sensitive_literals.append(Finding(relative(path, root), line_number, literal[:160]))
            if "const val" not in line and "enum class" not in line:
                for number in NUMBER_RE.findall(line):
                    magic_numbers.append(Finding(relative(path, root), line_number, number, line.strip()[:160]))

    duplicate_names: dict[str, list[dict[str, str]]] = collections.defaultdict(list)
    for path in production_files:
        package_match = PACKAGE_RE.search(texts[path])
        package_name = package_match.group(1) if package_match else ""
        for kind, name, _, _ in top_level_declarations(texts[path]):
            if kind in {"class", "object", "interface", "typealias"}:
                duplicate_names[name].append({
                    "path": relative(path, root),
                    "package": package_name,
                    "kind": kind,
                })

    empty_dirs = [
        relative(path, root) for path in sorted(source_root.rglob("*"))
        if path.is_dir() and not any(path.iterdir())
    ]
    stale_markers: list[Finding] = []
    for path in sorted((root / "app/src/main").rglob("*")):
        if not path.is_file() or path.suffix not in TEXT_EXTENSIONS or "/assets/icons/" in path.as_posix():
            continue
        for line_number, line in enumerate(read_text(path).splitlines(), 1):
            if re.search(r"\b(?:TODO|FIXME|HACK|XXX)\b|reserved placeholder|target extraction placeholder", line, re.IGNORECASE):
                stale_markers.append(Finding(relative(path, root), line_number, "marker", line.strip()[:200]))

    large = [metric for metric in metrics if metric.lines >= args.large_lines]
    very_large = [metric for metric in metrics if metric.lines >= args.very_large_lines]
    long_files = [metric for metric in metrics if metric.lines_over_160 or metric.lines_over_240]
    report: dict[str, object] = {
        "config": {
            "root": root.as_posix(),
            "source": args.source,
            "large_lines": args.large_lines,
            "very_large_lines": args.very_large_lines,
            "long_line": args.long_line,
            "very_long_line": args.very_long_line,
        },
        "summary": {
            "production_source_files": len(production_files),
            "production_lines": sum(metric.lines for metric in metrics),
            "large_files": len(large),
            "very_large_files": len(very_large),
            "long_line_files": len(long_files),
            "dead_code_candidates": len(dead_candidates),
            "unused_import_candidates": len(unused_imports),
            "package_mismatches": 0,
            "manifest_missing_classes": 0,
            "duplicate_name_groups": sum(
                len({item["package"] for item in declarations if item["kind"] != "typealias"}) > 1
                for declarations in duplicate_names.values()
            ),
            "duplicate_block_groups": 0,
            "empty_directories": len(empty_dirs),
            "stale_markers": len(stale_markers),
            "replacement_characters": len(replacement_characters),
        },
        "large_files": [asdict(metric) for metric in sorted(large, key=lambda metric: (-metric.lines, metric.path))],
        "very_large_files": [asdict(metric) for metric in sorted(very_large, key=lambda metric: (-metric.lines, metric.path))],
        "long_line_files": [asdict(metric) for metric in sorted(long_files, key=lambda metric: (-metric.lines_over_240, -metric.lines_over_160, metric.path))],
        "highest_branch_counts": [asdict(metric) for metric in sorted(metrics, key=lambda metric: (-metric.branches, metric.path))[:40]],
        "dead_code_candidates": [asdict(item) for item in dead_candidates],
        "unused_import_candidates": [asdict(item) for item in unused_imports],
        "package_mismatches": [asdict(item) for item in package_mismatches(source_root, root)],
        "manifest_missing_classes": [asdict(item) for item in manifest_missing_classes(root, [source_root, root / args.debug])],
        "duplicate_names": [
            {"name": name, "declarations": declarations}
            for name, declarations in sorted(duplicate_names.items())
            if len({item["package"] for item in declarations if item["kind"] != "typealias"}) > 1
        ],
        "duplicate_blocks": duplicate_blocks(production_files, root),
        "hardcoded_urls": [asdict(item) for item in hardcoded_urls],
        "hardcoded_sensitive_literals": [asdict(item) for item in hardcoded_sensitive_literals],
        "magic_number_candidates": [asdict(item) for item in magic_numbers],
        "replacement_characters": [asdict(item) for item in replacement_characters],
        "empty_directories": empty_dirs,
        "stale_markers": [asdict(item) for item in stale_markers],
    }
    report["summary"]["package_mismatches"] = len(report["package_mismatches"])
    report["summary"]["manifest_missing_classes"] = len(report["manifest_missing_classes"])
    report["summary"]["duplicate_block_groups"] = len(report["duplicate_blocks"])
    return report


def render_text(report: dict[str, object]) -> str:
    summary = report["summary"]
    lines = ["Android Python code audit", "=" * 25]
    lines.extend(f"{key}: {value}" for key, value in summary.items())
    sections = [
        ("Very large files", "very_large_files", lambda item: f"{item['lines']:5}  {item['path']}"),
        ("Large files", "large_files", lambda item: f"{item['lines']:5}  {item['path']}"),
        ("Dead-code candidates", "dead_code_candidates", lambda item: f"{item['path']}:{item['line']}  {item['name']}  {item['detail']}"),
        ("Unused-import candidates", "unused_import_candidates", lambda item: f"{item['path']}:{item['line']}  {item['name']}"),
        ("Package mismatches", "package_mismatches", lambda item: f"{item['path']}:{item['line']}  {item['name']}  {item['detail']}"),
        ("Manifest missing classes", "manifest_missing_classes", lambda item: f"{item['path']}:{item['line']}  {item['name']}"),
        ("Duplicate declarations", "duplicate_names", lambda item: f"{item['name']}: {', '.join(decl['path'] for decl in item['declarations'])}"),
        ("Empty directories", "empty_directories", str),
        ("Stale markers", "stale_markers", lambda item: f"{item['path']}:{item['line']}  {item['detail']}"),
        ("Replacement characters", "replacement_characters", lambda item: f"{item['path']}:{item['line']}  {item['detail']}"),
    ]
    for title, key, formatter in sections:
        lines.extend(("", title, "-" * len(title)))
        values = report[key]
        lines.extend(formatter(item) for item in values)
        if not values:
            lines.append("none")
    lines.extend(("", "Notes", "-----", "Candidates require manual validation. JSON contains URLs, literals, magic numbers, long-line metrics, branch counts, and exact duplicate blocks."))
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    report = audit(args)
    root = args.root.resolve()
    json_path = args.json if args.json.is_absolute() else root / args.json
    text_path = args.text if args.text.is_absolute() else root / args.text
    json_path.parent.mkdir(parents=True, exist_ok=True)
    text_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    rendered = render_text(report)
    text_path.write_text(rendered, encoding="utf-8")
    sys.stdout.buffer.write(rendered.encode("utf-8", errors="replace"))
    print(f"JSON: {relative(json_path, root)}")
    print(f"Text: {relative(text_path, root)}")
    if args.fail_on_package_mismatch and report["package_mismatches"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
