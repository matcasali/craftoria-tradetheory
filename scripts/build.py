"""Builds the Craftoria + Trade Theory pack from Craftoria's latest release tag.

    python scripts/build.py check     # prints needed=true|false and tag=<latest> (for GitHub Actions)
    python scripts/build.py build     # assembles pack/ from the tag (runs packwiz refresh)
    python scripts/build.py site      # copies pack/ to site/ with the Prism instance zip, the server kit and the page

Craftoria (TeamAOF/Craftoria) is a packwiz pack: every release tag holds pack.toml, index.toml and the files they
list. This pack is that, plus the Trade Theory jar from extra/files/, plus pins for what Trade Theory needs newer than
Craftoria ships (NeoForge, and the mods in extra/pins/). Settings are in pack.json.
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tomllib
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = json.loads((ROOT / "pack.json").read_text())
WORK = ROOT / "work"
PACK = WORK / "pack"
SITE = ROOT / "site"
STATE = ROOT / "state" / "craftoria-tag.txt"
UA = {"User-Agent": "craftoria-tradetheory-pack-builder"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()


def version_key(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-+]", v)]


def latest_tag():
    tags = json.loads(get(f"https://api.github.com/repos/{CONFIG['craftoriaRepo']}/tags?per_page=100"))
    names = [t["name"] for t in tags if re.fullmatch(r"\d+(\.\d+)+", t["name"])]
    return max(names, key=version_key)


def output(**values):
    out = os.environ.get("GITHUB_OUTPUT")
    for k, v in values.items():
        print(f"{k}={v}")
        if out:
            with open(out, "a") as f:
                f.write(f"{k}={v}\n")


def check():
    tag = os.environ.get("CRAFTORIA_TAG") or latest_tag()
    built = STATE.read_text().strip() if STATE.exists() else ""
    forced = os.environ.get("FORCE_BUILD") == "true"
    output(tag=tag, needed=str(forced or tag != built).lower())


def pin_version(pw_toml_text):
    """The version in a packwiz mod file's filename (e.g. geckolib-neoforge-1.21.1-4.8.2.jar -> 4.8.2)."""
    name = tomllib.loads(pw_toml_text)["filename"]
    m = re.search(r"(\d+(?:\.\d+){1,3})(?=\.jar$)", name)
    return m.group(1) if m else "0"


def build():
    tag = os.environ.get("CRAFTORIA_TAG") or latest_tag()
    print(f"Building from Craftoria {tag}")
    shutil.rmtree(WORK, ignore_errors=True)
    PACK.mkdir(parents=True)

    # Craftoria at the tag: the files its index lists, and its ignore rules
    src = WORK / "craftoria"
    with tarfile.open(fileobj=io.BytesIO(get(f"https://codeload.github.com/{CONFIG['craftoriaRepo']}/tar.gz/refs/tags/{tag}"))) as tar:
        tar.extractall(src, filter="data")
    src = next(src.iterdir())   # the tarball's single top folder
    upstream = tomllib.loads((src / "pack.toml").read_text())
    index = tomllib.loads((src / "index.toml").read_text())
    skipped = 0
    for entry in index["files"]:
        if not (src / entry["file"]).exists():
            # listed by Craftoria's index but not in their repository: files the game generates (debug output,
            # merged quest translations) that their index was built with; packwiz refresh leaves them out
            skipped += 1
            continue
        target = PACK / entry["file"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src / entry["file"], target)
    if skipped:
        print(f"  skipped {skipped} files Craftoria's index lists but its repository doesn't hold (generated ones)")
    shutil.copy2(src / "index.toml", PACK / "index.toml")   # packwiz refresh rewrites it from what's in the folder
    ignore = (src / ".packwizignore").read_text() if (src / ".packwizignore").exists() else ""
    # our own jars are files of the pack (Craftoria's rules leave jars out of mods/)
    (PACK / ".packwizignore").write_text(ignore.rstrip("\n") + "\n\n# Trade Theory pack\n!/mods/tradetheory-*.jar\n")

    # Trade Theory
    jars = sorted((ROOT / "extra" / "files").glob("tradetheory-*.jar"))
    if len(jars) != 1:
        sys.exit(f"extra/files must hold exactly one tradetheory-*.jar, found {[j.name for j in jars]}")
    shutil.copy2(jars[0], PACK / "mods" / jars[0].name)
    mod_version = re.search(r"tradetheory-(.+)\.jar$", jars[0].name).group(1)

    # pins: a mod Trade Theory needs newer than Craftoria ships
    for pin in sorted((ROOT / "extra" / "pins").glob("*.pw.toml")):
        theirs = PACK / "mods" / pin.name
        ours = pin.read_text()
        if not theirs.exists() or version_key(pin_version(ours)) > version_key(pin_version(theirs.read_text())):
            print(f"  pin {pin.name}: {pin_version(theirs.read_text()) if theirs.exists() else 'absent'} -> {pin_version(ours)}")
            theirs.write_text(ours)

    # NeoForge: Craftoria's, unless Trade Theory needs a newer one
    versions = dict(upstream["versions"])
    if version_key(CONFIG["minNeoForge"]) > version_key(versions.get("neoforge", "0")):
        print(f"  NeoForge {versions.get('neoforge')} -> {CONFIG['minNeoForge']}")
        versions["neoforge"] = CONFIG["minNeoForge"]

    lines = [f'name = "{CONFIG["name"]}"', f'author = "{CONFIG["author"]}"', f'version = "{tag}+tradetheory-{mod_version}"',
             f'pack-format = "{upstream.get("pack-format", "packwiz:1.1.0")}"', "", "[index]", 'file = "index.toml"',
             'hash-format = "sha256"', 'hash = ""', "", "[versions]"]
    lines += [f'{k} = "{v}"' for k, v in versions.items()]
    (PACK / "pack.toml").write_text("\n".join(lines) + "\n")
    subprocess.run(["packwiz", "refresh"], cwd=PACK, check=True)

    (WORK / "built.json").write_text(json.dumps({"tag": tag, "mod": mod_version, "versions": versions}))
    print(f"Pack built: Craftoria {tag}, Trade Theory {mod_version}, NeoForge {versions['neoforge']}")


def site():
    built = json.loads((WORK / "built.json").read_text())
    tools = WORK / "tools"   # tradetheory-updater.jar and packwiz-installer-bootstrap.jar, made by the workflow
    shutil.rmtree(SITE, ignore_errors=True)
    shutil.copytree(PACK, SITE, ignore=shutil.ignore_patterns(".packwizignore"))
    url = CONFIG["siteUrl"].rstrip("/") + "/pack.toml"
    neoforge = built["versions"]["neoforge"]

    # the Prism instance players import from a link
    cfg = (ROOT / "prism" / "instance.cfg").read_text().replace("@NAME@", CONFIG["name"]).replace("@PACK_URL@", url)
    mmc = {"components": [{"important": True, "uid": "net.minecraft", "version": built["versions"]["minecraft"]},
                          {"uid": "net.neoforged", "version": neoforge}], "formatVersion": 1}
    with zipfile.ZipFile(SITE / CONFIG["instanceZip"], "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("instance.cfg", cfg)
        z.writestr("mmc-pack.json", json.dumps(mmc, indent=4))
        z.write(tools / "tradetheory-updater.jar", "minecraft/tradetheory-updater.jar")
        z.write(tools / "packwiz-installer-bootstrap.jar", "minecraft/packwiz-installer-bootstrap.jar")

    # the server kit
    with zipfile.ZipFile(SITE / "server-kit.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted((ROOT / "server").iterdir()):
            info = zipfile.ZipInfo(f.name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if f.suffix == ".sh" else 0o644) << 16   # start.sh stays executable
            z.writestr(info, f.read_text().replace("@PACK_URL@", url).replace("@NAME@", CONFIG["name"]))
        z.write(tools / "tradetheory-updater.jar", "tradetheory-updater.jar")
        z.write(tools / "packwiz-installer-bootstrap.jar", "packwiz-installer-bootstrap.jar")

    page = (ROOT / "prism" / "index.html").read_text()
    for k, v in {"@NAME@": CONFIG["name"], "@ZIP_URL@": CONFIG["siteUrl"].rstrip("/") + "/" + CONFIG["instanceZip"],
                 "@CRAFTORIA@": built["tag"], "@MOD@": built["mod"], "@NEOFORGE@": neoforge}.items():
        page = page.replace(k, v)
    (SITE / "index.html").write_text(page)

    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(built["tag"] + "\n")
    print(f"Site ready in {SITE}")


if __name__ == "__main__":
    {"check": check, "build": build, "site": site}[sys.argv[1]]()
