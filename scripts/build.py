"""Builds the Craftoria + Trade Theory pack from Craftoria's latest release (its newest version bump commit).

    python scripts/build.py check     # prints needed=true|false and tag=<Craftoria's latest version> (for GitHub Actions)
    python scripts/build.py build     # assembles pack/ from the tag (runs packwiz refresh)
    python scripts/build.py manual L  # from installer log L: lists the blocked files (download by hand), makes check/
    python scripts/build.py curseforge  # the players' pack: Craftoria's own CurseForge zip + Trade Theory (for Prism)
    python scripts/build.py site      # copies pack/ to site/ with the server kit, the page and the zip's redirect

Craftoria (TeamAOF/Craftoria) is a packwiz pack: every release tag holds pack.toml, index.toml and the files they
list. This pack is that, plus the Trade Theory jar from extra/files/, plus pins for what Trade Theory needs newer than
Craftoria ships (NeoForge: the lower bound its jar declares; mods: extra/pins/). Settings are in pack.json.

Two outputs. Players: a CurseForge-format zip, Craftoria's own for that release with Trade Theory (and pinned mods)
added as override files, released on GitHub (too big for Pages); Prism imports it from the site's link (a redirect to
the latest release), downloads with its own downloader, asks for blocked files in its own window, and updates it from
the same link (Edit instance > Modpack > Update Pack). The server: the packwiz pack in site/, which the server kit
keeps up to date.

Downloads: Craftoria's files come from CurseForge, where some authors forbid downloads by anything but the CurseForge
app. Every CurseForge file that exists byte for byte on Modrinth is taken from Modrinth instead (same file, same
hash). What is still blocked shows up when the installer is tried on the pack (`manual`): those files stay in the
pack and are listed (manual.json) for players and the server to download by hand (resource packs: players only).
"""
import hashlib
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
    headers = dict(UA)
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = f"Bearer {token}"   # the API's anonymous limit is low on shared runners
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
        return r.read()


def version_key(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-+]", v)]


def release():
    """Craftoria's release to build: (version, git ref). Its tags stopped at 1.29.0; each release is a commit that bumps
    version_info.json (and points server_files at that release's CurseForge zip). CRAFTORIA_TAG picks another ref."""
    repo = CONFIG["craftoriaRepo"]
    ref = os.environ.get("CRAFTORIA_TAG")
    if not ref:
        ref = json.loads(get(f"https://api.github.com/repos/{repo}/commits?path=version_info.json&per_page=1"))[0]["sha"]
    version = json.loads(get(f"https://raw.githubusercontent.com/{repo}/{ref}/version_info.json"))["version"]
    return version, ref


def output(**values):
    out = os.environ.get("GITHUB_OUTPUT")
    for k, v in values.items():
        print(f"{k}={v}")
        if out:
            with open(out, "a") as f:
                f.write(f"{k}={v}\n")


def check():
    tag, _ = release()
    built = STATE.read_text().strip() if STATE.exists() else ""
    forced = os.environ.get("FORCE_BUILD") == "true"
    output(tag=tag, needed=str(forced or tag != built).lower())


def pin_version(pw_toml_text):
    """The version in a packwiz mod file's filename (e.g. geckolib-neoforge-1.21.1-4.8.2.jar -> 4.8.2)."""
    name = tomllib.loads(pw_toml_text)["filename"]
    m = re.search(r"(\d+(?:\.\d+){1,3})(?=\.jar$)", name)
    return m.group(1) if m else "0"


def build():
    tag, ref = release()
    print(f"Building from Craftoria {tag} ({ref})")
    shutil.rmtree(WORK, ignore_errors=True)
    PACK.mkdir(parents=True)

    # Craftoria at the release commit: the files its index lists, and its ignore rules
    src = WORK / "craftoria"
    with tarfile.open(fileobj=io.BytesIO(get(f"https://codeload.github.com/{CONFIG['craftoriaRepo']}/tar.gz/{ref}"))) as tar:
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
    pins = []
    for pin in sorted((ROOT / "extra" / "pins").glob("*.pw.toml")):
        theirs = PACK / "mods" / pin.name
        ours = pin.read_text()
        if not theirs.exists() or version_key(pin_version(ours)) > version_key(pin_version(theirs.read_text())):
            print(f"  pin {pin.name}: {pin_version(theirs.read_text()) if theirs.exists() else 'absent'} -> {pin_version(ours)}")
            replaced = tomllib.loads(theirs.read_text()).get("update", {}).get("curseforge", {}) if theirs.exists() else {}
            d = tomllib.loads(ours)
            pins.append({"filename": d["filename"], "url": d["download"]["url"], "hashFormat": d["download"]["hash-format"],
                         "hash": d["download"]["hash"], "replacesCurseForgeProject": replaced.get("project-id")})
            theirs.write_text(ours)

    # sides. Craftoria's files say "both" or, written by Prism's tools, nothing ('' is invalid for packwiz): resource
    # and shader packs only matter to players; client-only mods are the ones its server installer skips
    # (server_files/server-setup-config.yaml, CurseForge project ids), which crash a dedicated server; the rest is
    # "both". Before the Modrinth swap, which drops the CurseForge project id.
    client_only = client_only_projects(src)
    marked = []
    for f in [*PACK.glob("mods/*.pw.toml"), *PACK.glob("resourcepacks/*.pw.toml"), *PACK.glob("shaderpacks/*.pw.toml")]:
        d = tomllib.loads(f.read_text(encoding="utf-8"))
        project = d.get("update", {}).get("curseforge", {}).get("project-id")
        if f.parent.name != "mods":
            side = "client"
        elif project in client_only:
            side = "client"
            marked.append(project)
        else:
            side = d.get("side") if d.get("side") in ("both", "client", "server") else "both"
        set_side(f, side)
    print(f"  {len(marked)} client-only mods kept off the server (Craftoria lists {len(client_only)})")
    for project in sorted(client_only - set(marked)):
        print(f"    listed by Craftoria, not in the pack: CurseForge project {project}")

    # the same file from Modrinth where it's there (CurseForge's API refuses some files to anything but its app)
    moved = to_modrinth(PACK)
    print(f"  {moved} CurseForge files taken from Modrinth instead (identical files)")

    # NeoForge: Craftoria's, unless Trade Theory needs a newer one
    versions = dict(upstream["versions"])
    # (the oldest Trade Theory accepts, from its jar: a newer NeoForge than Craftoria tested is a risk for 500 mods)
    needed = neoforge_min(jars[0])
    if version_key(needed) > version_key(versions.get("neoforge", "0")):
        print(f"  NeoForge {versions.get('neoforge')} -> {needed} (Trade Theory needs it)")
        versions["neoforge"] = needed

    lines = [f'name = "{CONFIG["name"]}"', f'author = "{CONFIG["author"]}"', f'version = "{tag}+tradetheory-{mod_version}"',
             f'pack-format = "{upstream.get("pack-format", "packwiz:1.1.0")}"', "", "[index]", 'file = "index.toml"',
             'hash-format = "sha256"', 'hash = ""', "", "[versions]"]
    lines += [f'{k} = "{v}"' for k, v in versions.items()]
    (PACK / "pack.toml").write_text("\n".join(lines) + "\n")
    subprocess.run(["packwiz", "refresh"], cwd=PACK, check=True)

    (WORK / "built.json").write_text(json.dumps({"tag": tag, "mod": mod_version, "versions": versions, "pins": pins,
                                                 "curseforgeZip": craftoria_curseforge_zip(src)}))
    print(f"Pack built: Craftoria {tag}, Trade Theory {mod_version}, NeoForge {versions['neoforge']}")


def neoforge_min(jar):
    """The lower bound of the NeoForge version range the mod jar declares."""
    with zipfile.ZipFile(jar) as z:
        mods = tomllib.loads(z.read("META-INF/neoforge.mods.toml").decode("utf-8"))
    for deps in mods.get("dependencies", {}).values():
        for dep in deps:
            if dep.get("modId") == "neoforge":
                m = re.match(r"\[([^,\]]+)", dep.get("versionRange", ""))
                if m:
                    return m.group(1).strip()
    sys.exit(f"{jar.name} declares no NeoForge version range")


def set_side(pw_toml, side):
    """Sets a packwiz file's side (either quote style; added when missing)."""
    text = pw_toml.read_text(encoding="utf-8")
    line = f'side = "{side}"'
    new, n = re.subn(r"""(?m)^side\s*=\s*(?:"[^"\n]*"|'[^'\n]*')[ \t]*$""", line, text, count=1)
    if n == 0:
        new = line + "\n" + text
    if new != text:
        pw_toml.write_text(new, encoding="utf-8")


def craftoria_curseforge_zip(src):
    """The URL of Craftoria's own CurseForge pack for this release (its server installer's modpackUrl)."""
    config = (src / "server_files" / "server-setup-config.yaml").read_text(encoding="utf-8")
    m = re.search(r"(?m)^\s*modpackUrl:\s*(\S+)", config)
    if not m:
        sys.exit("Craftoria's server-setup-config.yaml names no modpackUrl: find where its CurseForge zip is")
    return m.group(1)


def curseforge():
    """The players' pack: Craftoria's CurseForge zip, renamed, with Trade Theory and the pins as override files."""
    built = json.loads((WORK / "built.json").read_text())
    print(f"Craftoria's CurseForge pack: {built['curseforgeZip']}")
    original = get(built["curseforgeZip"])
    jar = next((ROOT / "extra" / "files").glob("tradetheory-*.jar"))
    out = WORK / CONFIG["instanceZip"]
    with zipfile.ZipFile(io.BytesIO(original)) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        manifest = json.loads(src.read("manifest.json"))
        overrides = manifest.get("overrides") or "overrides"
        # a pinned mod replaces Craftoria's copy: its file goes in as an override, their entry goes out
        replaced = {p["replacesCurseForgeProject"] for p in built["pins"] if p.get("replacesCurseForgeProject")}
        files = [f for f in manifest["files"] if f["projectID"] not in replaced]
        if len(manifest["files"]) - len(files) != len(replaced):
            sys.exit(f"pins replace CurseForge projects {sorted(replaced)}, but the manifest lists "
                     f"{len(manifest['files']) - len(files)} of them")
        manifest["files"] = files
        manifest["name"], manifest["author"] = CONFIG["name"], CONFIG["author"]
        manifest["version"] = f"{built['tag']}+tradetheory-{built['mod']}"
        neoforge = [l for l in manifest["minecraft"]["modLoaders"] if l["id"].startswith("neoforge-")]
        if len(neoforge) != 1:
            sys.exit(f"expected one NeoForge loader in Craftoria's manifest, found {manifest['minecraft']['modLoaders']}")
        neoforge[0]["id"] = "neoforge-" + built["versions"]["neoforge"]
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for info in src.infolist():
            if info.filename != "manifest.json":
                z.writestr(info, src.read(info))
        z.write(jar, f"{overrides}/mods/{jar.name}")
        for pin in built["pins"]:
            blob = get(pin["url"])
            if hashlib.new(pin["hashFormat"], blob).hexdigest() != pin["hash"]:
                sys.exit(f"{pin['filename']} doesn't match its pin's hash")
            z.writestr(f"{overrides}/mods/{pin['filename']}", blob)
    print(f"Players' pack: {out.name}, {len(files)} CurseForge files + overrides, NeoForge {built['versions']['neoforge']},"
          f" {out.stat().st_size // (1024 * 1024)} MiB")


def client_only_projects(src):
    """The CurseForge project ids Craftoria's server installer skips (install.formatSpecific.ignoreProject)."""
    config = src / "server_files" / "server-setup-config.yaml"
    if not config.exists():
        sys.exit("Craftoria has no server_files/server-setup-config.yaml any more: find where it lists its client-only mods")
    ids, inside = set(), False
    for line in config.read_text(encoding="utf-8").splitlines():
        if re.match(r"\s*ignoreProject:\s*$", line):
            inside = True
            continue
        if inside:
            m = re.match(r"\s*-\s*(\d+)", line)
            if m:
                ids.add(int(m.group(1)))
            elif line.strip() and not line.strip().startswith("#"):
                break
    if not ids:
        sys.exit("found no ignoreProject list in Craftoria's server-setup-config.yaml")
    return ids


def toml_str(text):
    """A TOML basic string (JSON's escaping, but characters outside ASCII as they are: TOML refuses surrogate escapes)."""
    return json.dumps(text, ensure_ascii=False)


def to_modrinth(pack):
    """Points every CurseForge file that exists identically on Modrinth (by SHA-1) at Modrinth; how many."""
    entries = {}
    for f in [*pack.glob("mods/*.pw.toml"), *pack.glob("resourcepacks/*.pw.toml"), *pack.glob("shaderpacks/*.pw.toml")]:
        d = tomllib.loads(f.read_text(encoding="utf-8"))
        dl = d.get("download", {})
        if dl.get("mode") == "metadata:curseforge" and dl.get("hash-format") == "sha1":
            entries[dl["hash"]] = (f, d)
    if not entries:
        return 0
    req = urllib.request.Request("https://api.modrinth.com/v2/version_files",
                                 data=json.dumps({"hashes": list(entries), "algorithm": "sha1"}).encode(),
                                 headers={**UA, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        found = json.loads(r.read())
    moved = 0
    for sha1, version in found.items():
        f, d = entries[sha1]
        file = next((x for x in version["files"] if x["hashes"].get("sha1") == sha1), None)
        if file is None:
            continue
        f.write_text("\n".join([
            f'name = {toml_str(d["name"])}', f'filename = {toml_str(d["filename"])}', f'side = {toml_str(d.get("side", "both"))}', "",
            "[download]", f'url = {toml_str(file["url"])}', 'hash-format = "sha1"', f'hash = "{sha1}"', "",
            "[update]", "[update.modrinth]", f'mod-id = "{version["project_id"]}"', f'version = "{version["id"]}"', ""]), encoding="utf-8")
        moved += 1
    return moved


def manual():
    """From an installer log: list the blocked files (mods and resource/shader packs), make check/ without them.

    Nothing Craftoria lists is left out: a blocked file stays in the pack and is downloaded by hand once (the updater's
    window picks it up from the Downloads folder; the server kit lists the server's). Packs are client-only.
    """
    log = Path(sys.argv[2]).read_text(encoding="utf-8", errors="replace")
    blocked = {}
    for m in re.finditer(r"Please go to (https://www\.curseforge\.com/\S+) and save this file to (.+?)\s*$", log, re.M):
        path = m.group(2).strip().replace("\\", "/")
        blocked[path.rsplit("/", 1)[-1]] = m.group(1)
    manual_files = []
    for f in [*PACK.glob("mods/*.pw.toml"), *PACK.glob("resourcepacks/*.pw.toml"), *PACK.glob("shaderpacks/*.pw.toml")]:
        d = tomllib.loads(f.read_text(encoding="utf-8"))
        # only CurseForge files can be blocked (a file now taken from Modrinth isn't, whatever an older log says)
        if d["filename"] not in blocked or d["download"].get("mode") != "metadata:curseforge":
            continue
        manual_files.append({"name": d["name"], "filename": d["filename"], "folder": f.parent.name,
                             "side": d.get("side", "both"), "url": blocked[d["filename"]],
                             "sha1": d["download"].get("hash", ""), "pwtoml": f.name})
    manual_files.sort(key=lambda m: (m["folder"] != "mods", m["name"].lower()))
    for m in manual_files:
        print(f"  download by hand ({m['folder']}, {m['side']}): {m['name']} ({m['url']})")
    (WORK / "manual.json").write_text(json.dumps(manual_files, indent=2))
    # check/: the pack without them, for the strict checks (nothing else may fail)
    check = WORK / "check"
    shutil.rmtree(check, ignore_errors=True)
    shutil.copytree(PACK, check)
    for m in manual_files:
        (check / m["folder"] / m["pwtoml"]).unlink()
    subprocess.run(["packwiz", "refresh"], cwd=check, check=True)


def site():
    built = json.loads((WORK / "built.json").read_text())
    manual_mods = json.loads((WORK / "manual.json").read_text()) if (WORK / "manual.json").exists() else []
    tools = WORK / "tools"   # tradetheory-updater.jar and packwiz-installer-bootstrap.jar, made by the workflow
    shutil.rmtree(SITE, ignore_errors=True)
    shutil.copytree(PACK, SITE, ignore=shutil.ignore_patterns(".packwizignore"))
    url = CONFIG["siteUrl"].rstrip("/") + "/pack.toml"
    neoforge = built["versions"]["neoforge"]

    # the players' pack (too big for Pages): this link stays, it leads to the latest GitHub release's zip
    (SITE / "_redirects").write_text(f"/{CONFIG['instanceZip']} https://github.com/{CONFIG['repo']}/releases/latest/download/"
                                     f"{CONFIG['instanceZip']} 302\n")

    # the server updater itself, for servers whose copy is older (it fetches and runs the published one)
    shutil.copy2(tools / "tradetheory-updater.jar", SITE / "tradetheory-updater.jar")
    (SITE / "tradetheory-updater.sha256").write_text(
        hashlib.sha256((tools / "tradetheory-updater.jar").read_bytes()).hexdigest() + "\n")

    # the server kit
    with zipfile.ZipFile(SITE / "server-kit.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted((ROOT / "server").iterdir()):
            info = zipfile.ZipInfo(f.name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if f.suffix == ".sh" else 0o644) << 16   # start.sh stays executable
            z.writestr(info, f.read_text().replace("@PACK_URL@", url).replace("@NAME@", CONFIG["name"]))
        z.write(tools / "tradetheory-updater.jar", "tradetheory-updater.jar")
        z.write(tools / "packwiz-installer-bootstrap.jar", "packwiz-installer-bootstrap.jar")

    (SITE / "manual.json").write_text(json.dumps(manual_mods, indent=2))
    if manual_mods:
        items = "".join(f'<li><a href="{m["url"]}">{m["name"]}</a> <span class="file">{m["filename"]}</span></li>' for m in manual_mods)
        manual_html = ('<div class="card"><h2>Files to download by hand</h2><p>Their authors allow downloads only from'
                       ' CurseForge\'s website, so Prism shows a <b>Blocked mods</b> window while it installs or updates:'
                       ' open each link and click Download; Prism finds the files in your Downloads folder and goes on.'
                       f'</p><ul>{items}</ul></div>')
    else:
        manual_html = ""
    page = (ROOT / "prism" / "index.html").read_text()
    page = page.replace("@MANUAL@", manual_html)
    for k, v in {"@NAME@": CONFIG["name"], "@ZIP_URL@": CONFIG["siteUrl"].rstrip("/") + "/" + CONFIG["instanceZip"],
                 "@CRAFTORIA@": built["tag"], "@MOD@": built["mod"], "@NEOFORGE@": neoforge}.items():
        page = page.replace(k, v)
    (SITE / "index.html").write_text(page)

    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(built["tag"] + "\n")
    (STATE.parent / "manual.json").write_text(json.dumps(manual_mods, indent=2))
    print(f"Site ready in {SITE}")


if __name__ == "__main__":
    {"check": check, "build": build, "manual": manual, "curseforge": curseforge, "site": site}[sys.argv[1]]()
