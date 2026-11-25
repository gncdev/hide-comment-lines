"""Run the editor integration check in a disposable macOS IDE profile."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("ide", type=Path, help="Path to the installed .app")
parser.add_argument("--disabled-plugins", type=Path, help="Optional IDE disabled_plugins.txt for the test profile")
args = parser.parse_args()
ide = args.ide.resolve() / "Contents"
info = json.loads((ide / "Resources/product-info.json").read_text())
launch = info["launch"][0]
subprocess.run([sys.executable, str(root / "build.py"), "--ide", str(ide)], check=True)
with tempfile.TemporaryDirectory(prefix="hide-comment-lines-check-") as temp:
    base = Path(temp)
    if args.disabled_plugins:
        (base / "config").mkdir()
        shutil.copyfile(args.disabled_plugins, base / "config/disabled_plugins.txt")
    plugins = base / "plugins"
    version = ET.parse(root / "src/main/resources/META-INF/plugin.xml").findtext("version")
    archive_path = root / f"build/hide-comment-lines-{version}.zip"
    language = {"CL": "cpp", "GO": "go", "PY": "py", "PC": "py"}.get(info["productCode"], "java")
    if language != "go":
        with ZipFile(archive_path) as package:
            package.extractall(plugins)
    classes = base / "classes"
    classes.mkdir()
    subprocess.run(["javac", "--release", "21", "-cp", str(ide / "lib/*"), "-d", str(classes),
                    str(root / "tests/EditorCheck.java")], check=True)
    jar = plugins / "check/lib/check.jar"
    jar.parent.mkdir(parents=True)
    with ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/plugin.xml", """<idea-plugin>
          <id>dev.hidecommentlines.check</id><name>Editor Check</name><version>1</version><vendor>Local</vendor>
          <depends>com.intellij.modules.platform</depends>
          <extensions defaultExtensionNs="com.intellij">
            <appStarter id="checkCommentLines" implementation="check.EditorCheck"/>
          </extensions>
        </idea-plugin>""")
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes))
    options = base / "check.vmoptions"
    options.write_text("\n".join([
        "-Djava.awt.headless=true",
        *(f"-Didea.{name}.path={base / name}" for name in ["config", "system", "plugins", "log"]),
    ]) + "\n")
    env = os.environ.copy()
    env[info["envVarBaseName"] + "_VM_OPTIONS"] = str(options)
    launcher = (ide / "Resources" / launch["launcherPath"]).resolve()
    result = subprocess.run([str(launcher), "checkCommentLines", str(base),
                             language, str(archive_path)],
                            env=env, capture_output=True, text=True, timeout=120)
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    log = base / "log/idea.log"
    severe = log.is_file() and " SEVERE - " in log.read_text(errors="replace")
    if "ALL PASSED" not in result.stdout or result.returncode or severe:
        if log.is_file():
            target = root / "build/editor-check-failure.log"
            target.write_bytes(log.read_bytes())
            print(f"IDE log: {target}", file=sys.stderr)
        sys.exit(1)
