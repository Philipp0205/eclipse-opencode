#!/usr/bin/env python3
"""Validate the built p2 repository and decide whether GitHub Pages needs an update."""

from __future__ import annotations

import argparse
import io
import pathlib
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_POMS = (
    "com.opencode.eclipse.core/pom.xml",
    "com.opencode.eclipse.feature/pom.xml",
    "com.opencode.eclipse.repository/pom.xml",
    "com.opencode.eclipse.target/pom.xml",
    "com.opencode.eclipse.ui/pom.xml",
)
MANIFESTS = (
    "com.opencode.eclipse.core/META-INF/MANIFEST.MF",
    "com.opencode.eclipse.ui/META-INF/MANIFEST.MF",
)


def pom_version(path: pathlib.Path, parent: bool = False) -> str:
    root = ET.parse(path).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    node = root.find("m:parent/m:version" if parent else "m:version", namespace)
    if node is None or not node.text:
        raise ValueError(f"{path}: version is missing")
    return node.text.strip()


def p2_feature_version(content: bytes, source: str) -> str:
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        xml = ET.fromstring(archive.read("content.xml"))
    versions = {
        unit.attrib["version"]
        for unit in xml.findall(".//unit")
        if unit.attrib.get("id") == "com.opencode.eclipse.feature.feature.group"
    }
    if len(versions) != 1:
        raise ValueError(f"{source}: expected one feature group version, found {sorted(versions)}")
    return versions.pop()


def semantic(version: str) -> tuple[int, int, int]:
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[.-].*)?", version)
    if not match:
        raise ValueError(f"not a semantic version: {version}")
    return tuple(map(int, match.groups()))


def validate_source_versions() -> str:
    root_version = pom_version(ROOT / "pom.xml")
    if not root_version.endswith("-SNAPSHOT"):
        raise ValueError(f"root POM version must end in -SNAPSHOT: {root_version}")
    expected = root_version.removesuffix("-SNAPSHOT")

    for relative in MODULE_POMS:
        actual = pom_version(ROOT / relative, parent=True)
        if actual != root_version:
            raise ValueError(f"{relative}: expected parent version {root_version}, found {actual}")

    expected_bundle = f"{expected}.qualifier"
    for relative in MANIFESTS:
        text = (ROOT / relative).read_text(encoding="utf-8")
        match = re.search(r"^Bundle-Version:\s*(\S+)\s*$", text, re.MULTILINE)
        actual = match.group(1) if match else None
        if actual != expected_bundle:
            raise ValueError(f"{relative}: expected Bundle-Version {expected_bundle}, found {actual}")

    feature = ET.parse(ROOT / "com.opencode.eclipse.feature/feature.xml").getroot()
    versions = [feature.attrib.get("version")] + [
        plugin.attrib.get("version") for plugin in feature.findall("plugin")
    ]
    if any(version != expected_bundle for version in versions):
        raise ValueError(
            "com.opencode.eclipse.feature/feature.xml: expected every version to be "
            f"{expected_bundle}, found {versions}"
        )
    return expected


def fetch_current(url: str) -> bytes | None:
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def write_output(path: pathlib.Path | None, name: str, value: str) -> None:
    if path is not None:
        with path.open("a", encoding="utf-8") as output:
            output.write(f"{name}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=pathlib.Path)
    parser.add_argument("--current-site")
    parser.add_argument("--github-output", type=pathlib.Path)
    args = parser.parse_args()

    expected = validate_source_versions()
    content_path = args.repository / "content.jar"
    if not content_path.is_file():
        raise ValueError(f"built p2 metadata is missing: {content_path}")
    built = p2_feature_version(content_path.read_bytes(), str(content_path))
    if semantic(built) != semantic(expected):
        raise ValueError(f"built feature version {built} does not match source version {expected}")

    publish = True
    if args.current_site:
        current_content = fetch_current(args.current_site)
        if current_content is not None:
            current = p2_feature_version(current_content, args.current_site)
            if semantic(current) > semantic(built):
                raise ValueError(
                    f"refusing to replace newer update site {current} with older build {built}"
                )
            if semantic(current) == semantic(built):
                publish = False
                print(
                    f"GitHub Pages already contains semantic version {expected} ({current}); "
                    "skipping deployment. Bump the semantic plugin version to publish an update."
                )

    if publish:
        print(f"p2 repository {built} verified; publishing semantic version {expected}")
    write_output(args.github_output, "publish", str(publish).lower())
    write_output(args.github_output, "version", expected)


if __name__ == "__main__":
    main()
