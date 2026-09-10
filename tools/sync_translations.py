#!/usr/bin/env python3
"""Copy newly added default strings into every locale file.

Android falls back to the default locale for a string a translation is missing, but lint
treats MissingTranslation as an error, so a release build fails the moment a string is added
to values/strings.xml without also appearing in values-<locale>/strings.xml. This copies the
English text across as a placeholder so the build stays green and translators have a row to
work from.

It only ever adds keys that are absent, so real translations are never overwritten. Strings
marked translatable="false" are skipped, and non-locale qualifier folders (values-night,
values-hdpi and friends) are left alone - a string does not vary by density or theme.

    python3 tools/sync_translations.py [--check]

--check reports what is missing and exits non-zero instead of writing, for CI.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# A locale qualifier is a two-letter language, optionally +region ("es-rES", "pt-rBR").
LOCALE_DIR = re.compile(r"^values-([a-z]{2})(-r[A-Z]{2})?$")

STRING_ELEMENT = re.compile(
    r'^[ \t]*<string name="([^"]+)".*?</string>\s*$', re.S | re.M
)


def string_names(path: Path) -> list[str]:
    return [e.get("name") for e in ET.parse(path).getroot() if e.tag == "string"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="report missing keys and exit non-zero instead of writing",
    )
    args = parser.parse_args()

    default = RES / "values/strings.xml"
    root = ET.parse(default).getroot()
    translatable = [
        e.get("name")
        for e in root
        if e.tag == "string" and e.get("translatable") != "false"
    ]
    source = default.read_text(encoding="utf-8")
    # Copy each element verbatim so entities and CDATA sections survive intact.
    blocks = {m.group(1): m.group(0).rstrip("\n") for m in STRING_ELEMENT.finditer(source)}

    incomplete = False
    for directory in sorted(RES.iterdir()):
        if not LOCALE_DIR.match(directory.name):
            continue
        target = directory / "strings.xml"
        if not target.exists():
            continue

        present = set(string_names(target))
        missing = [name for name in translatable if name not in present]
        # A key that is translatable="false" has no business in a locale file.
        untranslatable = [
            name for name in present if name in blocks and name not in translatable
        ]

        if not missing and not untranslatable:
            continue

        incomplete = True
        if args.check:
            detail = []
            if missing:
                detail.append(f"{len(missing)} missing")
            if untranslatable:
                detail.append(f"{len(untranslatable)} untranslatable")
            print(f"{directory.name}: {', '.join(detail)}")
            continue

        text = target.read_text(encoding="utf-8")
        for name in untranslatable:
            text = re.sub(
                rf'^[ \t]*<string name="{re.escape(name)}".*?</string>\n',
                "",
                text,
                flags=re.S | re.M,
            )
        if missing:
            addition = "\n".join(blocks[name] for name in missing)
            text = text.replace("</resources>", addition + "\n</resources>")
        target.write_text(text, encoding="utf-8")
        print(f"{directory.name}: +{len(missing)} -{len(untranslatable)}")

    if args.check and incomplete:
        print("\nRun: python3 tools/sync_translations.py", file=sys.stderr)
        return 1
    if not incomplete:
        print("All locales are in sync.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
