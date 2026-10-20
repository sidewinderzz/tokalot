"""Keeps the README's downloads number from ever going down.

GitHub only counts downloads of files that still exist. Each Windows build replaces the files on the
rolling "desktop" release (and deleting a release drops its files), so a plain sum of today's counts
shrinks. This remembers the highest count ever seen for every file (by its GitHub id) in a ledger on
the "stats" branch, and writes the total there as a shields.io badge the README shows.

Run it on a schedule and right before a workflow deletes or replaces release files. Needs GITHUB_TOKEN
(contents: write) and GITHUB_REPOSITORY, and runs inside a checkout of the repository.
"""

import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import datetime, timezone

REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ.get("GITHUB_TOKEN", "")
BRANCH = "stats"
LEDGER = "downloads-ledger.json"
BADGE = "downloads.json"


def releases():
    page, out = 1, []
    while True:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{REPO}/releases?per_page=100&page={page}",
            headers={"Accept": "application/vnd.github+json", **({"Authorization": f"Bearer {TOKEN}"} if TOKEN else {})},
        )
        with urllib.request.urlopen(req, timeout=60) as r:
            batch = json.load(r)
        if not batch:
            return out
        out += batch
        page += 1


def short(n):
    # The same style as shields.io's own downloads badge: 950, 1.2k, 3.4M.
    for size, unit in ((1_000_000, "M"), (1_000, "k")):
        if n >= size:
            return f"{n / size:.1f}".rstrip("0").rstrip(".") + unit
    return str(n)


def git(*args, cwd=None, check=True):
    return subprocess.run(["git", *args], cwd=cwd, check=check, capture_output=True, text=True)


def main():
    live = [
        (str(a["id"]), rel["tag_name"], a["name"], a["download_count"])
        for rel in releases()
        for a in rel["assets"]
    ]
    for attempt in range(5):
        work = tempfile.mkdtemp()
        if git("fetch", "--depth=1", "origin", BRANCH, check=False).returncode == 0:
            git("worktree", "add", "--detach", work, "FETCH_HEAD")
            with open(os.path.join(work, LEDGER)) as f:
                ledger = json.load(f)
        else:
            git("branch", "-D", BRANCH, check=False)
            git("worktree", "add", "--detach", work)
            git("checkout", "--orphan", BRANCH, cwd=work)
            git("rm", "-rf", "--quiet", ".", cwd=work, check=False)
            ledger = {"assets": {}}

        assets = ledger["assets"]
        before = json.dumps(assets, sort_keys=True)
        for aid, tag, name, count in live:
            seen = assets.get(aid, {}).get("count", 0)
            assets[aid] = {"release": tag, "name": name, "count": max(seen, count)}
        total = sum(a["count"] for a in assets.values())
        if json.dumps(assets, sort_keys=True) == before and "total" in ledger:
            print(f"Downloads: {total} (unchanged)")
            git("worktree", "remove", "--force", work)
            return
        ledger["total"] = total
        ledger["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

        with open(os.path.join(work, LEDGER), "w") as f:
            json.dump(ledger, f, indent=1, sort_keys=True)
            f.write("\n")
        with open(os.path.join(work, BADGE), "w") as f:
            json.dump({"schemaVersion": 1, "label": "downloads", "message": short(total), "color": "3A3A3C"}, f)
            f.write("\n")

        git("add", LEDGER, BADGE, cwd=work)
        git("-c", "user.name=github-actions[bot]",
            "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "--quiet", "-m", f"Downloads: {total}", cwd=work)
        pushed = git("push", "origin", f"HEAD:refs/heads/{BRANCH}", cwd=work, check=False)
        git("worktree", "remove", "--force", work)
        if pushed.returncode == 0:
            print(f"Downloads: {total}")
            return
        # Another run pushed first. Its counts are merged in on the next pass (highest wins).
        print(pushed.stderr, file=sys.stderr)
        time.sleep(2 + attempt * 3)
    sys.exit("Couldn't push the downloads ledger")


if __name__ == "__main__":
    main()
