#!/usr/bin/env python3
"""Met à jour la police Material Design Icons embarquée dans l'application.

Télécharge le paquet officiel @mdi/font (npm, via cdn.jsdelivr.net) dans une version donnée et écrit :

- app/src/main/assets/mdi/materialdesignicons-webfont.ttf : la police ;
- app/src/main/assets/mdi/codepoints.txt : la correspondance nom → code, une ligne « nom code_hexa »,
  triée, tirée de css/materialdesignicons.css ;
- third_party/mdi/LICENSE : la licence du paquet (Pictogrammers Free License, police Apache 2.0).

Usage (depuis la racine du dépôt) : python3 tools/mdi/update_mdi.py [version]   (défaut : 7.4.47)
Bibliothèque standard seulement. Les fichiers produits sont commités : le build n'a pas besoin du réseau.
"""
import os
import re
import sys
import urllib.request

DEFAULT_VERSION = "7.4.47"
BASE = "https://cdn.jsdelivr.net/npm/@mdi/font@{version}/{path}"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "mdi")
LICENSE_DIR = os.path.join(ROOT, "third_party", "mdi")
RULE = re.compile(r'\.mdi-([a-z0-9-]+)::before\s*\{\s*content:\s*"\\([0-9A-Fa-f]+)"')


def fetch(version, path):
    with urllib.request.urlopen(BASE.format(version=version, path=path), timeout=60) as response:
        return response.read()


def codepoints(css):
    """{nom: code} d'après les règles « .mdi-<nom>::before { content: "\\F0597"; } »."""
    return {name: int(code, 16) for name, code in RULE.findall(css)}


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_VERSION
    ttf = fetch(version, "fonts/materialdesignicons-webfont.ttf")
    css = fetch(version, "css/materialdesignicons.css").decode("utf-8")
    license_text = fetch(version, "LICENSE")
    table = codepoints(css)
    if len(table) < 5000 or ttf[:4] != b"\x00\x01\x00\x00":
        sys.exit(f"Téléchargement inattendu : {len(table)} icônes, en-tête {ttf[:4]!r}")
    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(LICENSE_DIR, exist_ok=True)
    with open(os.path.join(ASSETS, "materialdesignicons-webfont.ttf"), "wb") as f:
        f.write(ttf)
    with open(os.path.join(ASSETS, "codepoints.txt"), "w", encoding="utf-8") as f:
        f.write(f"# Material Design Icons {version} (@mdi/font) : nom code_hexa. Généré par tools/mdi/update_mdi.py.\n")
        for name in sorted(table):
            f.write(f"{name} {table[name]:X}\n")
    with open(os.path.join(LICENSE_DIR, "LICENSE"), "wb") as f:
        f.write(license_text)
    with open(os.path.join(LICENSE_DIR, "VERSION"), "w", encoding="utf-8") as f:
        f.write(version + "\n")
    print(f"MDI {version} : {len(table)} icônes, police {len(ttf)} octets")


if __name__ == "__main__":
    main()
