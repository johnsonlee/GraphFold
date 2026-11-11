"""Strict JaCoCo coverage exchange format; artifacts contain data, never executable markup."""
import argparse
import json
from enum import Enum
from pathlib import Path
import xml.etree.ElementTree as ET

MODULES = ("engine", "cli", "gfc", "gradle-plugin")
PLATFORMS = ("ubuntu-latest", "macos-latest")
METRICS = ("LINE", "BRANCH")
MAX_COUNT = 1_000_000_000


class CoverageError(str, Enum):
    MISSING_REPORT = "MISSING_REPORT"
    INVALID_REPORT = "INVALID_REPORT"


def decode(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate coverage JSON key")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def counts(value):
    if not isinstance(value, dict) or set(value) != {"missed", "covered"}:
        raise ValueError("Invalid counter")
    if any(type(n) is not int or not 0 <= n <= MAX_COUNT for n in value.values()):
        raise ValueError("Invalid count")
    return value


def validate(document, platform):
    if not isinstance(document, dict) or set(document) != {"schema", "platform", "modules"}:
        raise ValueError("Invalid coverage document")
    if type(document["schema"]) is not int or document["schema"] != 1 or document["platform"] != platform:
        raise ValueError("Invalid coverage identity")
    modules = document["modules"]
    if not isinstance(modules, dict) or set(modules) != set(MODULES):
        raise ValueError("Missing or unexpected module")
    for module in modules.values():
        if not isinstance(module, dict) or set(module) != {"error", *METRICS}:
            raise ValueError("Invalid module")
        if module["error"] is None:
            for metric in METRICS:
                counts(module[metric])
        else:
            module["error"] = CoverageError(module["error"])
            if any(module[metric] is not None for metric in METRICS):
                raise ValueError("Invalid report error")
    return document


def collect(root, platform):
    modules = {}
    for module in MODULES:
        report = root / module / "build/reports/jacoco/test/jacocoTestReport.xml"
        error = None
        values = dict.fromkeys(METRICS)
        try:
            tree = ET.parse(report).getroot()
            if tree.tag != "report":
                raise ValueError("Not a JaCoCo report")
            for metric in METRICS:
                nodes = [node for node in tree.findall("counter") if node.get("type") == metric]
                if len(nodes) != 1:
                    raise ValueError("Missing or duplicate aggregate counter")
                node = nodes[0]
                # Reject signed, fractional, boolean, and whitespace counts before conversion.
                raw = {key: node.get(key, "") for key in ("missed", "covered")}
                if any(not n.isascii() or not n.isdecimal() for n in raw.values()):
                    raise ValueError("Invalid aggregate count")
                values[metric] = counts({key: int(value) for key, value in raw.items()})
        except FileNotFoundError:
            error = CoverageError.MISSING_REPORT
        except (OSError, ET.ParseError, ValueError):
            error = CoverageError.INVALID_REPORT
        modules[module] = {"error": error, **(dict.fromkeys(METRICS) if error else values)}
    return validate({"schema": 1, "platform": platform, "modules": modules}, platform)


def passes(document):
    return all(module["error"] is None and all(
        module[metric]["missed"] == 0 and module[metric]["covered"] > 0 for metric in METRICS
    ) for module in document["modules"].values())


def metric_text(module, metric):
    if module is None:
        return "Missing platform report"
    if module["error"]:
        return CoverageError(module["error"]).value.replace("_", " ").title()
    value = module[metric]
    total = value["covered"] + value["missed"]
    if not total:
        return "N/A — no measured code (fails gate)"
    # Display counts; never let rounded percentages imply zero missed code.
    if value["missed"] == 0:
        return f"100% ({total}/{total})"
    return f"{value['covered']}/{total}; **{value['missed']} missed**"


def table(documents):
    lines = ["| Module | Platform | Line | Branch |", "| --- | --- | --- | --- |"]
    for module in MODULES:
        for platform in PLATFORMS:
            row = documents.get(platform, {}).get("modules", {}).get(module)
            lines.append(f"| `{module}` | {platform} | {metric_text(row, 'LINE')} | {metric_text(row, 'BRANCH')} |")
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--platform", choices=PLATFORMS, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = collect(args.root, args.platform)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, sort_keys=True) + "\n")
    print(table({args.platform: result}))
    raise SystemExit(0 if passes(result) else 1)
