"""Validate installed JARs and assemble exact, immutable release bundles."""
import argparse
import hashlib
import io
import json
import re
import shutil
import sys
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.([1-9]\d*))?")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version():
    values = re.findall(r"^mod_version=(.+)$", (ROOT / "gradle.properties").read_text(), re.M)
    require(len(values) == 1, "Expected one mod_version")
    return values[0].strip()


def targets():
    items = json.loads((ROOT / "scripts/targets.json").read_text())["include"]
    require(len({x["id"] for x in items}) == len(items) and items, "Invalid target matrix")
    for item in items:
        require(item["id"] == f'{item["loader"]}-{item["minecraft"]}', "Target identity mismatch")
        require(item["loader"] in {"forge", "fabric", "neoforge"}, "Unknown loader")
        path = Path(item["artifact"].format(version=version()))
        require(not path.is_absolute() and ".." not in path.parts, "Unsafe artifact path")
    return items


def validate(tag):
    require(tag.startswith("v") and VERSION.fullmatch(tag[1:]), "Invalid release tag")
    value = tag[1:]
    require(value == version(), "Tag does not match mod_version")
    return value, notes(value)


def notes(value):
    text = (ROOT / "README.md").read_text(encoding="utf-8")
    headings = list(re.finditer(r"^## (.+)$", text, re.M))
    matches = [i for i, h in enumerate(headings) if re.fullmatch(rf"\[{re.escape(value)}\] - \d{{4}}-\d{{2}}-\d{{2}}", h[1])]
    require(len(matches) == 1, "Expected one dated changelog section")
    # Also reject a duplicate version heading with a malformed date.
    require(len(re.findall(rf"^## \[{re.escape(value)}\]", text, re.M)) == 1, "Duplicate changelog version")
    i = matches[0]
    body = text[headings[i].end():headings[i+1].start() if i+1 < len(headings) else len(text)].strip()
    require(re.search(r"^- \S", body, re.M), "Empty changelog section")
    return body


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inspect_jar(path, target, value):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        require(len(set(names)) == len(names), "Duplicate ZIP entries")
        require(jar.testzip() is None, "Corrupt JAR")
        common = [
            "dev/superdisc/SuperDisc.class", "dev/superdisc/Net.class",
            "dev/superdisc/ServerPlayback.class", "dev/superdisc/DiscData.class",
            "dev/superdisc/client/ClientPlayback.class", "dev/superdisc/client/DiscScreen.class",
            "dev/superdisc/client/DiscSound.class", "dev/superdisc/client/AudioDecoder.class",
            "assets/super_disc/models/item/super_disc.json",
            "assets/super_disc/textures/item/super_disc.png",
            "assets/super_disc/lang/zh_cn.json", "assets/super_disc/lang/en_us.json",
            "META-INF/THIRD-PARTY-NOTICES.txt", "META-INF/licenses/JLayer-LGPL-2.0.txt",
            "META-INF/licenses/jlayer-1.0.1-sources.zip",
        ]
        for name in common:
            require(name in names, f"Missing JAR entry: {name}")
        for name in names:
            if name.endswith(".class") and name.startswith("dev/superdisc/"):
                data = jar.read(name)
                require(data[:4] == b"\xca\xfe\xba\xbe", "Invalid class")
                require(int.from_bytes(data[6:8], "big") == int(target["java"]) + 44, "Wrong Java bytecode target")
        if target["loader"] in {"forge", "neoforge"}:
            require("fabric.mod.json" not in names, "Wrong loader")
            metadata = "META-INF/neoforge.mods.toml" if target["loader"] == "neoforge" else "META-INF/mods.toml"
            other_metadata = "META-INF/mods.toml" if target["loader"] == "neoforge" else "META-INF/neoforge.mods.toml"
            require(other_metadata not in names, "Mixed loader metadata")
            mod = tomllib.loads(jar.read(metadata).decode())
            require(mod["mods"][0]["modId"] == "super_disc" and mod["mods"][0]["version"] == value, "Forge identity/version mismatch")
            require(mod["modLoader"] == "javafml" and mod["loaderVersion"] == target["loader_range"], "FML loader mismatch")
            deps = {d["modId"]: d for d in mod["dependencies"]["super_disc"]}
            require(deps["minecraft"]["versionRange"] == target["minecraft_range"], "Minecraft mismatch")
            require(target["loader"] in deps, "Missing loader dependency")
            recipe_dir = "recipes" if target["minecraft"] == "1.20.1" else "recipe"
            require(f"data/super_disc/{recipe_dir}/super_disc.json" in names, "Missing recipe")
            require(json.loads(jar.read("pack.mcmeta"))["pack"]["pack_format"] == target["pack_format"], "Resource pack format mismatch")
            entries = json.loads(jar.read("META-INF/jarjar/metadata.json"))["jars"]
            require(len(entries) == 1, "Unexpected bundled dependencies")
            entry = entries[0]
            require(entry["identifier"] == {"group": "javazoom", "artifact": "jlayer"}, "Wrong JLayer identity")
            require(entry["version"]["artifactVersion"] == "1.0.1", "Wrong JLayer version")
            nested = entry["path"]
        else:
            require("META-INF/mods.toml" not in names and "META-INF/neoforge.mods.toml" not in names, "Wrong loader")
            mod = json.loads(jar.read("fabric.mod.json"))
            require(mod["id"] == "super_disc" and mod["version"] == value, "Fabric identity/version mismatch")
            require(mod["environment"] == "*", "Fabric environment mismatch")
            require(mod["depends"]["minecraft"] == target["minecraft"] and mod["depends"]["java"] == ">=" + target["java"], "Fabric target mismatch")
            require(mod["depends"]["fabricloader"] == target["loader_range"] and mod["depends"]["fabric-api"] == target["api_range"], "Fabric dependency mismatch")
            require(mod["entrypoints"] == {"main": ["dev.superdisc.SuperDisc"], "client": ["dev.superdisc.client.ClientEvents"]}, "Wrong entrypoints")
            require(mod["mixins"] == [{"config": "super_disc.client.mixins.json", "environment": "client"}], "Wrong mixin environment")
            mixin = json.loads(jar.read("super_disc.client.mixins.json"))
            require(mixin["required"] and mixin["client"] == ["ChannelMixin"] and mixin["injectors"]["defaultRequire"] == 1, "Missing required mixin")
            require("dev/superdisc/client/mixin/ChannelMixin.class" in names, "Missing mixin class")
            require("data/super_disc/recipe/super_disc.json" in names, "Missing recipe")
            if target["minecraft"] == "26.2":
                require("assets/super_disc/items/super_disc.json" in names, "Missing item definition")
            if target["runtime_mappings"] == "intermediary":
                # Modern Loom remaps mixin selectors directly instead of emitting a refmap.
                mixin_class = jar.read("dev/superdisc/client/mixin/ChannelMixin.class")
                require(b"net/minecraft/class_4224" in mixin_class and b"method_19643" in mixin_class, "Mixin target was not remapped")
            require(len(mod["jars"]) == 1, "Unexpected nested JARs")
            nested = mod["jars"][0]["file"]
            require(nested.endswith("/jlayer-1.0.1.jar"), "Wrong JLayer version")
        symbol = {"srg": b"m_91087_", "official": b"getInstance", "intermediary": b"net/minecraft/class_310"}[target["runtime_mappings"]]
        require(symbol in jar.read("dev/superdisc/client/ClientPlayback.class"), "Wrong runtime mappings / unfinished remap")
        require([n for n in names if n.endswith(".jar")] == [nested], "Unexpected nested library")
        with zipfile.ZipFile(io.BytesIO(jar.read(nested))) as library:
            require(library.testzip() is None and "javazoom/jl/decoder/Decoder.class" in library.namelist(), "Invalid JLayer")
        with zipfile.ZipFile(io.BytesIO(jar.read("META-INF/licenses/jlayer-1.0.1-sources.zip"))) as sources:
            require(sources.testzip() is None and any(n.endswith("Decoder.java") for n in sources.namelist()), "Missing LGPL sources")
        require(jar.read("assets/super_disc/textures/item/super_disc.png").startswith(b"\x89PNG\r\n\x1a\n"), "Invalid item texture")


def filename(target, value):
    return f'super-disc-{value}+mc{target["minecraft"]}-{target["loader"]}.jar'


def release_body(value):
    lines = [f"# Super Disc {value}", "", notes(value), "", "## Build Targets", "",
             "| Minecraft | Loader | Java | Status |", "| --- | --- | --- | --- |"]
    for target in targets():
        lines.append(f'| {target["minecraft"]} | {target["loader"]} | {target["java"]} | {target["status"]} |')
    lines += ["", "## Installation", "", "Install only the JAR for your Minecraft version and loader on both the server and every client."]
    lines += [f'- {t["id"]}: {t["dependencies"]}.' for t in targets()]
    lines += ["", "Use the same Minecraft version and loader on every client and the server. Cross-loader connectivity is not supported. Existing Forge 2.0.0 worlds have not been migration-tested.",
              "Compilation, core regression tests and static package checks are not in-game or multiplayer acceptance.",
              "New ports are experimental. Keep this release as a draft until manual acceptance is complete.",
              "", "## Verification", "", "Download all target JARs and SHA256SUMS.txt, then run `sha256sum -c SHA256SUMS.txt`.",
              "For one JAR, compare `Get-FileHash <downloaded.jar> -Algorithm SHA256` with its exact line in SHA256SUMS.txt.",
              "Do not install sources, development JARs or both loader variants."]
    return "\n".join(lines) + "\n"


def fresh(path):
    path = Path(path).resolve()
    base = (ROOT / "release").resolve()
    require(path.is_relative_to(base) and path != base, "Output must be beneath release/")
    require(not path.exists(), f"Output already exists; choose a fresh directory: {path}")
    path.mkdir(parents=True)
    return path


def prepare(tag, target_id, output):
    value, _ = validate(tag)
    found = [x for x in targets() if x["id"] == target_id]
    require(len(found) == 1, "Unsupported target")
    target = found[0]
    source = ROOT / target["artifact"].format(version=value)
    inspect_jar(source, target, value)
    dest = fresh(output)
    name = filename(target, value)
    shutil.copyfile(source, dest / name)
    manifest = {"target": target_id, "version": value, "file": name, "sha256": sha(dest / name), "body": release_body(value)}
    (dest / "bundle.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


def assemble(tag, inputs, output):
    value, _ = validate(tag)
    expected = {t["id"]: t for t in targets()}
    manifests = sorted(Path(inputs).glob("*/bundle.json"))
    require(len(manifests) == len(expected), "Incomplete or duplicate target bundles")
    seen = set()
    checked = []
    body = release_body(value)
    for path in manifests:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        target_id = manifest["target"]
        require(target_id in expected and target_id not in seen, "Unknown or duplicate target")
        seen.add(target_id)
        target = expected[target_id]
        name = filename(target, value)
        require(manifest["version"] == value and manifest["file"] == name and manifest["body"] == body, "Inconsistent bundle")
        require({p.name for p in path.parent.iterdir()} == {"bundle.json", name}, "Stale bundle contents")
        jar = path.parent / name
        require(sha(jar) == manifest["sha256"], "Bundle hash mismatch")
        inspect_jar(jar, target, value)
        checked.append((jar, name))
    require(seen == set(expected), "Missing target")
    require({p.name for p in Path(inputs).iterdir()} == {p.parent.name for p in manifests}, "Unexpected bundle input")
    checked.sort(key=lambda item: [filename(t, value) for t in targets()].index(item[1]))
    dest = fresh(output)
    for source, name in checked:
        shutil.copyfile(source, dest / name)
    sums = "".join(f"{sha(dest / name)}  {name}\n" for _, name in checked)
    (dest / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
    (dest / "NOTES.md").write_text(body, encoding="utf-8")
    (dest / "manifest.json").write_text(json.dumps({
        "tag": tag, "title": f"Super Disc {value}", "prerelease": "-" in value,
        "files": {name: sha(dest / name) for name in [n for _, n in checked] + ["SHA256SUMS.txt"]},
    }, indent=2), encoding="utf-8")


def verify_release(tag, directory):
    value, _ = validate(tag)
    directory = Path(directory)
    manifest = json.loads((directory / "manifest.json").read_text())
    names = [filename(t, value) for t in targets()]
    require(manifest["tag"] == tag and manifest["title"] == f"Super Disc {value}" and manifest["prerelease"] == ("-" in value), "Release identity mismatch")
    require(set(manifest["files"]) == set(names + ["SHA256SUMS.txt"]), "Wrong asset set")
    require({p.name for p in directory.iterdir()} == set(names + ["SHA256SUMS.txt", "NOTES.md", "manifest.json"]), "Stale release files")
    for name, digest in manifest["files"].items():
        require(sha(directory / name) == digest, "Release hash mismatch")
    for target in targets():
        inspect_jar(directory / filename(target, value), target, value)
    require((directory / "NOTES.md").read_text(encoding="utf-8") == release_body(value), "Notes mismatch")
    require((directory / "SHA256SUMS.txt").read_text() == "".join(f"{sha(directory / n)}  {n}\n" for n in names), "Checksum list mismatch")
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["matrix", "validate", "prepare", "assemble", "verify"])
    parser.add_argument("tag", nargs="?")
    parser.add_argument("--target")
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.stage == "matrix":
        print(json.dumps({"include": targets()}, separators=(",", ":")))
    elif args.stage == "validate":
        validate(args.tag)
    elif args.stage == "prepare":
        prepare(args.tag, args.target, args.output)
    elif args.stage == "assemble":
        assemble(args.tag, args.inputs, args.output)
    else:
        verify_release(args.tag, args.inputs)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as exc:
        sys.exit(str(exc))
