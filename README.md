# Craftoria + Trade Theory

[Craftoria](https://github.com/TeamAOF/Craftoria) with the Trade Theory mod, as a self-updating pack for Prism
Launcher, published at **https://craftoria-tradetheory.pages.dev**.

## Players
In Prism: *Add Instance → Import*, paste `https://craftoria-tradetheory.pages.dev/Craftoria-TradeTheory.zip`, OK,
Launch. Every launch brings the pack up to date.

## How it updates
- **Craftoria:** a GitHub Actions workflow checks Craftoria's release tags every day (04:17 UTC). A new one is
  built into the pack, checked (every client and server mod must download; with the EULA variable set, a server
  must boot) and committed to `site/`, which Cloudflare Pages publishes. A failed check publishes nothing.
- **Trade Theory:** `scripts/publish-mod.sh` builds the mod and pushes its jar; the workflow does the rest.
- **What Trade Theory needs newer than Craftoria ships** is raised in the pack: NeoForge (the minimum the
  Trade Theory jar declares) and the mods in `extra/pins/` (GeckoLib), only while Craftoria's are older.
- **Players (now):** a CurseForge-format zip: Craftoria's own for the release, with Trade Theory and pins added as
  overrides (`build.py curseforge`), attached to a GitHub release; the site's link redirects to the latest one.
  Prism imports it and updates it from the same link (Edit instance > Modpack > Update Pack) with its own downloader
  and Blocked mods window. The text below describes the updater's player mode, which is no longer used.
- **Players (updater, unused):** the Prism instance runs `tradetheory-updater.jar` before each launch. Missing download-by-hand mods
  (`manual.json`) get a window of ours that picks them up from the Downloads folder by their SHA-1; then packwiz
  runs without its own window (a small progress window, output in Prism's log) and brings mods and configs up to
  date; when the instance's NeoForge changed, this launch stops and the player presses Launch once more.
  Hand-downloaded files an update replaced are deleted (`.tradetheory/manual.txt`), since packwiz leaves them.
- **Server:** the server kit (`server-kit.zip` on the site) does the same on each start, installs the pack's
  NeoForge server, and while running checks the pack every 10 minutes: on a change it warns players, restarts after
  5 minutes and comes back updated.

## Layout
| Path | What |
|---|---|
| `pack.json` | Name, site address, Craftoria repository, minimum NeoForge |
| `extra/files/` | The Trade Theory jar |
| `extra/pins/` | Mods to raise when Craftoria's are older |
| `scripts/build.py` | `check` / `build` / `site` |
| `updater/` | The updater for Prism and the server |
| `prism/` | The instance template and the players' page |
| `server/` | The server kit's scripts |
| `site/` | The published pack (written by the workflow; don't edit) |
| `state/craftoria-tag.txt` | The Craftoria tag last built |

Run the workflow by hand (*Actions → Build the pack → Run workflow*) to rebuild now, or to build a given Craftoria tag.
