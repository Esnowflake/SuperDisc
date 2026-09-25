"""Only create/complete drafts. Never overwrite bytes or edit published releases."""
import argparse
import json
import subprocess
from pathlib import Path

from release import require, verify_release


class GitHub:
    def __init__(self, repo):
        require(len(repo.split("/")) == 2 and all(x and x not in {".", ".."} for x in repo.split("/")), "Invalid repository")
        self.repo = repo

    def api(self, route, method="GET", payload=None):
        command = ["gh", "api", "--method", method, route]
        if payload is not None:
            command += ["--input", "-"]
        result = subprocess.run(command, input=json.dumps(payload).encode() if payload is not None else None, capture_output=True, check=True)
        return json.loads(result.stdout) if result.stdout else None

    def pages(self, route):
        result = []
        page = 1
        while True:
            batch = self.api(f"{route}?per_page=100&page={page}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1

    def releases(self):
        return self.pages(f"repos/{self.repo}/releases")

    def create(self, tag, title, body, prerelease):
        return self.api(f"repos/{self.repo}/releases", "POST",
                        {"tag_name": tag, "name": title, "body": body, "draft": True, "prerelease": prerelease})

    def assets(self, release_id):
        return self.pages(f"repos/{self.repo}/releases/{release_id}/assets")

    def download(self, asset_id):
        return subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                               f"repos/{self.repo}/releases/assets/{asset_id}"], capture_output=True, check=True).stdout

    def upload(self, tag, path):
        subprocess.run(["gh", "release", "upload", tag, str(path), "--repo", self.repo], check=True)


def check_draft(item, manifest, body):
    require(item["draft"], "Refusing to modify a published release")
    require(item["tag_name"] == manifest["tag"] and item["name"] == manifest["title"]
            and item["body"] == body and item["prerelease"] == manifest["prerelease"], "Existing draft metadata differs")


def publish(api, tag, directory):
    directory = Path(directory)
    manifest = verify_release(tag, directory)
    body = (directory / "NOTES.md").read_text(encoding="utf-8")
    found = [r for r in api.releases() if r["tag_name"] == tag]
    require(len(found) <= 1, "Duplicate release tag")
    item = found[0] if found else api.create(tag, manifest["title"], body, manifest["prerelease"])
    check_draft(item, manifest, body)
    assets = api.assets(item["id"])
    require(len({a["name"] for a in assets}) == len(assets), "Duplicate remote assets")
    require({a["name"] for a in assets} <= set(manifest["files"]), "Unknown remote asset")
    existing = {a["name"]: a for a in assets}
    # Verify ALL existing attachments before uploading anything.
    for name, asset in existing.items():
        require(api.download(asset["id"]) == (directory / name).read_bytes(), f"Different remote bytes: {name}")
    for name in manifest["files"]:
        if name not in existing:
            current = [r for r in api.releases() if r["tag_name"] == tag]
            require(len(current) == 1, "Release disappeared")
            check_draft(current[0], manifest, body)
            api.upload(tag, directory / name)
    final = api.assets(item["id"])
    require(len(final) == len(manifest["files"]) and {a["name"] for a in final} == set(manifest["files"]), "Incomplete remote draft")
    for asset in final:
        require(api.download(asset["id"]) == (directory / asset["name"]).read_bytes(), "Remote verification failed")
    check_draft([r for r in api.releases() if r["tag_name"] == tag][0], manifest, body)
    print(f"Verified draft {tag}; all attachments match")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("directory", type=Path)
    parser.add_argument("--repo", required=True)
    args = parser.parse_args()
    publish(GitHub(args.repo), args.tag, args.directory)
