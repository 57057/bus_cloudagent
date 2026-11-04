"""Configure this machine's IDEA project; use --language for global Chinese UI.

Existing XML files are backed up below runtime/idea-config-backup.
Restart IDEA after applying settings so it reloads files from disk.
"""
import argparse
import os
from pathlib import Path
import shutil
from datetime import datetime
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / "runtime" / "idea-config-backup" / datetime.now().strftime("%Y%m%d-%H%M%S")


def edit(path, callback):
    if path.exists():
        tree = ET.parse(path)
        BACKUP.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, BACKUP / path.name)
    else:
        tree = ET.ElementTree(ET.Element("project", version="4"))
    callback(tree.getroot())
    ET.indent(tree, space="  ")
    path.parent.mkdir(parents=True, exist_ok=True)
    tree.write(path, encoding="UTF-8", xml_declaration=True)
    ET.parse(path)
    print(f"Configured: {path}")


def component(root, name):
    found = root.find(f"component[@name='{name}']")
    return found if found is not None else ET.SubElement(root, "component", name=name)


def option(parent, name, value):
    found = parent.find(f"option[@name='{name}']")
    if found is None:
        found = ET.SubElement(parent, "option", name=name)
    found.set("value", value)


def language(root):
    state = component(root, "LocalizationStateService")
    option(state, "selectedLocale", "zh-CN")


def maven(root):
    preferences = component(root, "MavenImportPreferences")
    node = preferences.find("option[@name='generalSettings']")
    if node is None:
        node = ET.SubElement(preferences, "option", name="generalSettings")
    general = node.find("MavenGeneralSettings")
    if general is None:
        general = ET.SubElement(node, "MavenGeneralSettings")
    option(general, "mavenHomeTypeForPersistence", "CUSTOM")
    option(general, "customMavenHome", (ROOT / "runtime/tools/apache-maven-3.9.11").as_posix())
    option(general, "localRepository", (ROOT / "runtime/maven-repository").as_posix())


def encodings(root):
    settings = component(root, "Encoding")
    settings.set("defaultCharsetForPropertiesFiles", "UTF-8")
    for file in settings.findall("file"):
        if file.get("url") == "PROJECT":
            file.set("charset", "UTF-8")
            break
    else:
        ET.SubElement(settings, "file", url="PROJECT", charset="UTF-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", action="store_true", help="Set global IDEA language only")
    args = parser.parse_args()
    if args.language:
        edit(Path(os.environ["APPDATA"]) / "JetBrains/IntelliJIdea2026.2/options/ide.general.xml", language)
    else:
        idea = ROOT / "business-java/.idea"
        edit(idea / "workspace.xml", maven)
        edit(idea / "encodings.xml", encodings)
    print(f"Backup directory: {BACKUP}")
