import copy
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import release
import publish


def zipped(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return data.getvalue()


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.original = release.ROOT
        self.patcher = patch.object(release, "ROOT", self.root)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        (self.root / "scripts").mkdir()
        (self.root / "scripts/targets.json").write_bytes((self.original / "scripts/targets.json").read_bytes())
        self.set_version("2.1.0-alpha.1")

    def set_version(self, value):
        self.value = value
        self.tag = "v" + value
        (self.root / "gradle.properties").write_text(f"mod_version={value}\n")
        (self.root / "CHANGELOG.md").write_text(f"# Changes\n\n## [Unreleased]\n\n- Not released\n\n## [{value}] - 2026-09-25\n\n- Actual changes\n\n## [2.0.0]\n\n- Old changes\n")

    def fixtures(self):
        for target in release.targets():
            symbol = {"srg": b"m_91087_", "official": b"getInstance", "intermediary": b"net/minecraft/class_310"}[target["runtime_mappings"]]
            entries = {n: b"\xca\xfe\xba\xbe\x00\x00" + (int(target["java"])+44).to_bytes(2, "big") + symbol for n in [
                "dev/superdisc/SuperDisc.class", "dev/superdisc/Net.class", "dev/superdisc/ServerPlayback.class",
                "dev/superdisc/DiscData.class", "dev/superdisc/client/ClientPlayback.class", "dev/superdisc/client/DiscScreen.class",
                "dev/superdisc/client/DiscSound.class", "dev/superdisc/client/AudioDecoder.class"]}
            entries.update({
                "assets/super_disc/models/item/super_disc.json": "{}",
                "assets/super_disc/textures/item/super_disc.png": b"\x89PNG\r\n\x1a\n",
                "assets/super_disc/lang/zh_cn.json": "{}", "assets/super_disc/lang/en_us.json": "{}",
                "META-INF/THIRD-PARTY-NOTICES.txt": "JLayer", "META-INF/licenses/JLayer-LGPL-2.0.txt": "LGPL",
                "META-INF/licenses/jlayer-1.0.1-sources.zip": zipped({"javazoom/jl/decoder/Decoder.java": "source"}),
            })
            nested = "META-INF/jarjar/jlayer-1.0.1.jar" if target["loader"] != "fabric" else "META-INF/jars/jlayer-1.0.1.jar"
            entries[nested] = zipped({"javazoom/jl/decoder/Decoder.class": b"test decoder"})
            if target["loader"] != "fabric":
                metadata = "META-INF/neoforge.mods.toml" if target["loader"] == "neoforge" else "META-INF/mods.toml"
                entries[metadata] = (self.original / target["project"] / "src/main/resources" / metadata).read_text(encoding="utf-8").replace("${version}", self.value)
                entries["META-INF/jarjar/metadata.json"] = json.dumps({"jars": [{"identifier": {"group": "javazoom", "artifact": "jlayer"}, "version": {"artifactVersion": "1.0.1"}, "path": nested}]})
                recipe_dir = "recipes" if target["minecraft"] == "1.20.1" else "recipe"
                entries[f"data/super_disc/{recipe_dir}/super_disc.json"] = "{}"
                entries["pack.mcmeta"] = json.dumps({"pack": {"pack_format": target["pack_format"]}})
            else:
                mod = json.loads((self.original / target["project"] / "src/main/resources/fabric.mod.json").read_text().replace("${version}", self.value))
                mod["jars"] = [{"file": nested}]
                entries["fabric.mod.json"] = json.dumps(mod)
                entries["super_disc.client.mixins.json"] = (self.original / "fabric/src/main/resources/super_disc.client.mixins.json").read_text()
                entries["dev/superdisc/client/mixin/ChannelMixin.class"] = entries["dev/superdisc/SuperDisc.class"] + b"net/minecraft/class_4224 method_19643"
                entries["assets/super_disc/items/super_disc.json"] = "{}"
                entries["data/super_disc/recipe/super_disc.json"] = "{}"
            path = self.root / target["artifact"].format(version=self.value)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(zipped(entries))

    def bundles(self):
        self.fixtures()
        for target in release.targets():
            release.prepare(self.tag, target["id"], self.root / "release/bundles" / target["id"])

    def assembled(self):
        self.bundles()
        dest = self.root / "release/final"
        release.assemble(self.tag, self.root / "release/bundles", dest)
        return dest

    def test_versions(self):
        for value in ["0.0.0", "2.1.0", "2.1.0-alpha.1", "2.1.0-beta.12", "2.1.0-rc.1"]:
            with self.subTest(value=value):
                self.set_version(value)
                self.assertEqual(release.validate(self.tag)[0], value)
        for value in ["v2.1.0", "02.1.0", "2.01.0", "2.1.00", "2.1.0-rc.0", "2.1.0-rc.01", "../2.1.0", "2.1.0/evil", "2.1.0-final.1", "2.1.0\n"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.set_version(value)
                release.validate(self.tag)

    def test_version_mismatch(self):
        with self.assertRaises(ValueError):
            release.validate("v2.1.0")

    def test_changelog(self):
        self.assertEqual(release.notes(self.value), "- Actual changes")
        for text in ["## [Unreleased]\n- pending", f"## [{self.value}] - 2026-09-25\n", f"## [{self.value}] - 2026-09-25\n- one\n## [{self.value}] - 2026-09-25\n- two"]:
            with self.subTest(text=text):
                (self.root / "CHANGELOG.md").write_text(text)
                with self.assertRaises(ValueError):
                    release.validate(self.tag)

    def test_unknown_target(self):
        with self.assertRaises(ValueError):
            release.prepare(self.tag, "fabric-1.20.1", self.root / "release/no")

    def test_jar_corruption_and_metadata(self):
        self.fixtures()
        for target in release.targets():
            path = self.root / target["artifact"].format(version=self.value)
            with zipfile.ZipFile(path) as archive:
                original = {n: archive.read(n) for n in archive.namelist()}
            meta = {"fabric": "fabric.mod.json", "forge": "META-INF/mods.toml", "neoforge": "META-INF/neoforge.mods.toml"}[target["loader"]]
            changes = [
                ("dev/superdisc/Net.class", None),
                ("META-INF/licenses/JLayer-LGPL-2.0.txt", None),
                (meta, original[meta].replace(self.value.encode(), b"9.9.9")),
                (next(n for n in original if n.endswith(".jar")), b"broken"),
                ("dev/superdisc/SuperDisc.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x01"),
                ("META-INF/licenses/jlayer-1.0.1-sources.zip", b"broken"),
                ("extra.jar", zipped({})),
            ]
            if target["loader"] == "fabric":
                changes += [("super_disc.client.mixins.json", b'{"required":false}'),
                            ("fabric.mod.json", original[meta].replace(b"dev.superdisc.SuperDisc", b"bad.Entry"))]
            changes += [("dev/superdisc/client/ClientPlayback.class", original["dev/superdisc/client/ClientPlayback.class"][:8])]
            for name, content in changes:
                with self.subTest(target=target["id"], name=name):
                    entries = dict(original)
                    if content is None:
                        entries.pop(name)
                    else:
                        entries[name] = content
                    path.write_bytes(zipped(entries))
                    with self.assertRaises((ValueError, KeyError, zipfile.BadZipFile)):
                        release.inspect_jar(path, target, self.value)

    def test_complete_release(self):
        output = self.assembled()
        manifest = release.verify_release(self.tag, output)
        self.assertEqual(len(manifest["files"]), len(release.targets()) + 1)
        with self.assertRaises(ValueError):
            release.assemble(self.tag, self.root / "release/bundles", output)

    def test_missing_and_extra_bundle(self):
        self.bundles()
        folder = self.root / "release/bundles"
        path = next(folder.glob("*/bundle.json"))
        content = path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):
            release.assemble(self.tag, folder, self.root / "release/final")
        path.write_bytes(content)
        (folder / "stale").mkdir()
        with self.assertRaises(ValueError):
            release.assemble(self.tag, folder, self.root / "release/final")

    def test_bundle_tamper(self):
        self.bundles()
        path = next((self.root / "release/bundles").glob("*/bundle.json"))
        original = json.loads(path.read_text())
        for key, value in [("target", "unknown"), ("version", "9.9.9"), ("file", "../escape.jar"), ("body", "wrong"), ("sha256", "0"*64)]:
            with self.subTest(key=key):
                data = dict(original);data[key] = value;path.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    release.assemble(self.tag, self.root / "release/bundles", self.root / "release/final")
        path.write_text(json.dumps(original))
        (path.parent / "stale.jar").write_bytes(b"stale")
        with self.assertRaises(ValueError):
            release.assemble(self.tag, self.root / "release/bundles", self.root / "release/final")

    def test_output_path_guard(self):
        for path in [self.root / "outside", self.root / "release", self.root / "release/../../escape"]:
            with self.assertRaises(ValueError):
                release.fresh(path)

    def test_publish_recovery_and_immutability(self):
        output = self.assembled()
        api = FakeGitHub()
        publish.publish(api, self.tag, output)
        self.assertEqual(api.uploads, len(release.targets()) + 1)
        publish.publish(api, self.tag, output)
        self.assertEqual(api.uploads, len(release.targets()) + 1)
        original = copy.deepcopy(api.release)
        for key, value in [("draft", False), ("name", "wrong"), ("body", "wrong"), ("prerelease", False)]:
            api.release = dict(original);api.release[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                publish.publish(api, self.tag, output)
        api.release = original
        first = next(iter(api.data))
        api.data[first] = b"different"
        with self.assertRaises(ValueError):
            publish.publish(api, self.tag, output)
        self.assertEqual(api.uploads, len(release.targets()) + 1)

    def test_publish_partial_upload(self):
        output = self.assembled()
        api = FakeGitHub()
        api.fail_after = 1
        with self.assertRaises(RuntimeError):
            publish.publish(api, self.tag, output)
        self.assertTrue(api.release["draft"])
        api.fail_after = None
        publish.publish(api, self.tag, output)
        self.assertEqual(api.uploads, len(release.targets()) + 1)

    def test_publish_query_error(self):
        output = self.assembled()
        api = FakeGitHub()
        with patch.object(api, "releases", side_effect=RuntimeError("network")):
            with self.assertRaises(RuntimeError):
                publish.publish(api, self.tag, output)
        self.assertIsNone(api.release)

    def test_pagination(self):
        api = publish.GitHub("owner/repo")
        with patch.object(api, "api", side_effect=[[{"id": i} for i in range(100)], [{"id": 100}]]) as call:
            self.assertEqual(len(api.releases()), 101)
            self.assertIn("page=2", call.call_args.args[0])
        with patch.object(api, "api", side_effect=subprocess.CalledProcessError(1, "gh")):
            with self.assertRaises(subprocess.CalledProcessError):
                api.releases()


class FakeGitHub:
    def __init__(self):
        self.release = None
        self.data = {}
        self.uploads = 0
        self.fail_after = None

    def releases(self):
        return [self.release] if self.release else []

    def create(self, tag, title, body, prerelease):
        self.release = {"id": 1, "tag_name": tag, "name": title, "body": body, "prerelease": prerelease, "draft": True}
        return self.release

    def assets(self, release_id):
        return [{"id": n, "name": n} for n in self.data]

    def download(self, asset_id):
        return self.data[asset_id]

    def upload(self, tag, path):
        if self.fail_after == self.uploads:
            raise RuntimeError("interrupted upload")
        self.data[path.name] = path.read_bytes()
        self.uploads += 1


if __name__ == "__main__":
    unittest.main()
