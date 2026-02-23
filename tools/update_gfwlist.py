#!/usr/bin/env python3
import argparse
import base64
import re
import urllib.request
from pathlib import Path

SOURCE_URL = "https://raw.githubusercontent.com/gfwlist/gfwlist/master/gfwlist.txt"
OUTPUT_FILE = Path("app/src/main/java/com/simple/proxyconnect/service/GeneratedGfwDomains.kt")


def parse_domain(rule: str) -> str | None:
    line = rule.strip()
    if not line or line.startswith("!") or line.startswith("["):
        return None
    if line.startswith("@@"):
        line = line[2:]

    for prefix in ("||", "|http://", "|https://", "http://", "https://"):
        if line.startswith(prefix):
            line = line[len(prefix):]

    line = line.split("$", 1)[0].split("/", 1)[0].split(":", 1)[0]
    line = line.lstrip(".").lstrip("*").split("^", 1)[0].lower()

    if line.startswith("~"):
        line = line[1:]
    if line.startswith("www."):
        line = line[4:]

    if re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}", line):
        return None
    if "." not in line:
        return None
    if any(ch in line for ch in ("*", "?", "[", "]", "(", ")", "{", "}")):
        return None
    return line


def fetch_domains(url: str) -> list[str]:
    raw = urllib.request.urlopen(url, timeout=30).read().decode("utf-8", "ignore")
    decoded = base64.b64decode(raw).decode("utf-8", "ignore")
    domains = sorted({d for d in (parse_domain(line) for line in decoded.splitlines()) if d})
    return domains


def render_kotlin(domains: list[str]) -> str:
    lines = [
        "package com.simple.proxyconnect.service",
        "",
        "internal object GeneratedGfwDomains {",
        "    val domains: Set<String> = setOf(",
    ]
    lines.extend([f'        "{domain}",' for domain in domains])
    lines.extend([
        "    )",
        "}",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Update static GFW domain list from official gfwlist")
    parser.add_argument("--url", default=SOURCE_URL, help="gfwlist source URL")
    parser.add_argument("--output", default=str(OUTPUT_FILE), help="output Kotlin file path")
    args = parser.parse_args()

    output_path = Path(args.output)
    domains = fetch_domains(args.url)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(render_kotlin(domains), encoding="utf-8")

    print(f"Updated {output_path} with {len(domains)} domains from {args.url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
