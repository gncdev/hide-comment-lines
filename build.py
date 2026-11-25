"""Build an installable plugin against an installed JetBrains IDE."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ide", type=Path, default=os.environ.get("IDE_HOME", "/Applications/CLion.app"))
ide = parser.parse_args().ide.resolve()
if (ide / "Contents").is_dir():
    ide = ide / "Contents"
if not (ide / "lib").is_dir():
    parser.error("Pass the JetBrains IDE installation with --ide")
resources = root / "src/main/resources"
version = ET.parse(resources / "META-INF/plugin.xml").findtext("version")
build = root / "build"
build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(dir=build) as staging:
    classes = Path(staging) / "classes"
    classes.mkdir()
    sources = sorted((root / "src/main/java").rglob("*.java"))
    subprocess.run(["javac", "--release", "21", "-encoding", "UTF-8",
                    "-classpath", str(ide / "lib/*"), "-d", str(classes), *sources], check=True)
    jar = Path(staging) / "hide-comment-lines.jar"
    with ZipFile(jar, "w", ZIP_DEFLATED) as archive:
        for base in (resources, classes):
            for file in sorted(base.rglob("*")):
                if file.is_file() and file.name != ".DS_Store":
                    archive.write(file, file.relative_to(base))
    package = Path(staging) / f"hide-comment-lines-{version}.zip"
    with ZipFile(package, "w", ZIP_DEFLATED) as archive:
        archive.write(jar, f"hide-comment-lines/lib/{jar.name}")
    package.replace(build / package.name)
    print(build / package.name)
