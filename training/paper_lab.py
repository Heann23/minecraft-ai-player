"""Explicit, finite isolated Paper experiments. Never starts on plugin load or touches a production server.

Use separate train/evaluation seeds in a frozen private plan. All outputs, worlds and model files stay private.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
import time


def sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def read_plan(path: Path):
    if path.stat().st_size > 65536:
        raise ValueError("oversized experiment plan")
    plan = json.loads(path.read_text(encoding="utf-8"))
    if set(plan) != {"schema", "trainSeeds", "evaluationSeeds"} or plan["schema"] != "minecraft-paper-seed-plan/v1":
        raise ValueError("experiment plan schema mismatch")
    groups = []
    for key in ("trainSeeds", "evaluationSeeds"):
        values = plan[key]
        if not isinstance(values, list) or not 1 <= len(values) <= 1000:
            raise ValueError("both seed partitions must be explicitly populated")
        for value in values:
            # Strings preserve all 64 bits in JSON and match the server's numeric seed parser exactly.
            if not isinstance(value, str) or not re.fullmatch(r"0|-?[1-9][0-9]*", value) or not -(2**63) <= int(value) < 2**63:
                raise ValueError("world seed must be a canonical signed 64-bit decimal string")
        if len(set(values)) != len(values):
            raise ValueError("duplicate seed in partition")
        groups.append(set(values))
    if groups[0] & groups[1]:
        raise ValueError("training and held-out world seeds overlap")
    return plan


def validate_plan_extension(parent: dict, current: dict):
    # Each complete plan has already passed read_plan's overlap/canonical seed checks.
    for key in ("trainSeeds", "evaluationSeeds"):
        if not set(parent[key]).issubset(current[key]):
            raise ValueError("campaign extension cannot remove or reassign existing world seeds")


def validate_options(args):
    plan = read_plan(args.plan)
    split = "trainSeeds" if args.mode == "train" else "evaluationSeeds"
    if args.world_seed not in plan[split]:
        raise ValueError("world seed is not assigned to this experiment split")
    if not 1024 <= args.port <= 65535 or not 10 <= args.seconds <= 86400:
        raise ValueError("invalid explicit port or finite wall-clock budget")
    if not 0 <= getattr(args, "start_time", 0) < 24000:
        raise ValueError("initial world time must be within one Minecraft day")
    if args.mode == "train" and (not 32 <= args.steps <= 10_000_000 or args.model is not None):
        raise ValueError("training requires a finite transition budget; deployment actors cannot resume PPO")
    if args.mode == "evaluate" and (args.model is None or args.resume is not None):
        raise ValueError("evaluation requires a frozen actor and cannot resume/update an optimizer")
    if getattr(args, "upgrade_context", False) and (args.mode != "train" or args.resume is None):
        raise ValueError("context upgrade requires explicit training resume")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        raise ValueError("full source commit required")
    # Only reuse clean runtime libraries. Never copy historical worlds, plugin state or experience.
    for path in (args.paper, args.plugin, args.java, args.eula):
        if not path.is_file():
            raise ValueError("required runtime artifact missing: " + path.name)
    if not re.search(r"^\s*eula\s*=\s*true\s*$", args.eula.read_text(), re.MULTILINE):
        raise ValueError("supply an existing accepted EULA; this tool does not accept it for you")
    return plan


def summarize_game_log(path: Path):
    """Observed diagnostics, never an inferred task success or an invented missing sample."""
    choices, endings = Counter(), Counter()
    positions, health, food = [], [], []
    deaths = respawns = rejected = 0
    inventory = None
    if not path.is_file():
        return {"available": False, "allWorldAutonomyProven": False}
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            selected = re.search(r"Model [0-9a-f]{64} selected ([^\s]+)", line)
            if selected:
                choices[selected[1].split(":", 1)[0]] += 1
            ended = re.search(r"Model action ended: (\S+) (SUCCESS|FAILED)", line)
            if ended:
                endings[ended[1] + ":" + ended[2]] += 1
            at = re.search(r"위치: (-?\d+), (-?\d+), (-?\d+) \(([^)]+)\)", line)
            if at:
                positions.append({"x": int(at[1]), "y": int(at[2]), "z": int(at[3]), "world": at[4]})
            for key, values in (("체력", health), ("허기", food)):
                match = re.search(key + r": (\d+)", line)
                if match:
                    values.append(int(match[1]))
            deaths += "[AI:RLLearner] Died" in line
            respawns += "[AI:RLLearner] Respawned" in line
            rejected += "PPO request deferred" in line
            if "RLLearner has the following entity data: [" in line:
                inventory = line.split("following entity data: ", 1)[1].strip()
    return {"available": True, "modelChoiceCounts": dict(choices), "inputOutcomeCounts": dict(endings),
            "sampledPositions": positions, "sampledHealthCeilings": health, "sampledFood": food,
            "recordedDeaths": deaths, "recordedRespawns": respawns, "deferredRequests": rejected,
            "finalInventoryNbt": inventory, "allWorldAutonomyProven": False,
            "limitations": "Periodic diagnostics are partial; input success is not task completion or proof of learned competence."}


def run(args):
    plan = validate_options(args)
    source = Path(__file__).resolve().parents[1]
    actual_commit = subprocess.check_output(["git", "-c", "safe.directory=" + source.as_posix(),
                                              "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if actual_commit != args.source_commit:
        raise ValueError("source commit does not match this tool checkout")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    shutil.copy2(args.plan, output / "seed-plan.json")
    server = output / "server"
    server.mkdir()
    for directory in ("cache", "libraries", "versions"):
        origin = args.runtime_cache / directory
        if origin.is_dir():
            shutil.copytree(origin, server / directory)
    shutil.copy2(args.paper, server / "paper.jar")
    shutil.copy2(args.eula, server / "eula.txt")
    plugin = server / "plugins/MinecraftAI"
    plugin.mkdir(parents=True)
    shutil.copy2(args.plugin, plugin.parent / "MinecraftAI.jar")
    config = (source / "src/main/resources/config.yml").read_text(encoding="utf-8")
    # Disable persistence and chat in the new experiment only. No old AI state or credentials are loaded.
    config = config.replace("enabled: true", "enabled: false")
    if args.mode == "train":
        config = config.replace("mode: learned", "mode: training").replace("allow-training: false", "allow-training: true")
    else:
        (plugin / "models").mkdir()
        shutil.copy2(args.model, plugin / "models/minecraft-rl-actor.json")
    (plugin / "config.yml").write_text(config, encoding="utf-8")
    (plugin / "discord.yml").write_text("enabled: false\n", encoding="utf-8")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\n"
        f"level-name=world\nlevel-seed={args.world_seed}\nlevel-type=minecraft:normal\n"
        "gamemode=survival\ndifficulty=normal\nspawn-protection=0\npause-when-empty-seconds=-1\n"
        "view-distance=4\nsimulation-distance=4\nmax-players=2\n", encoding="ascii")
    manifest = {"schema": "minecraft-paper-lab/v1", "sourceCommit": args.source_commit, "mode": args.mode,
                "worldSeed": args.world_seed, "seedPlanSha256": sha256(output / "seed-plan.json"),
                "paperJarSha256": sha256(server / "paper.jar"), "pluginSha256": sha256(plugin.parent / "MinecraftAI.jar"),
                "configurationSha256": sha256(plugin / "config.yml"), "serverPropertiesSha256": sha256(server / "server.properties"),
                "port": args.port, "maxSeconds": args.seconds, "maxTransitions": args.steps if args.mode == "train" else 0,
                "preEpisodeWorldTime": args.start_time,
                "tools": {name: sha256(source / "training" / name) for name in ("paper_lab.py", "rl_actor.py", "train_paper_ppo.py")},
                "teacherLabels": False, "grantedItems": False, "teleportDuringEpisode": False,
                "allWorldAutonomyProven": False}
    if args.model:
        manifest["actorSha256"] = sha256(plugin / "models/minecraft-rl-actor.json")
    if args.resume:
        # The parent lab manifest must belong to the same frozen training partition.
        parent_directory = args.resume.resolve().parent.parent
        parent = json.loads((parent_directory / "manifest.json").read_text(encoding="utf-8"))
        if parent.get("mode") != "train" or parent.get("worldSeed") not in plan["trainSeeds"]:
            raise ValueError("parent checkpoint does not belong to the same training campaign")
        if parent.get("seedPlanSha256") != manifest["seedPlanSha256"]:
            prior_plan = parent_directory / "seed-plan.json"
            if not prior_plan.is_file() or sha256(prior_plan) != parent.get("seedPlanSha256"):
                raise ValueError("parent campaign plan provenance is unavailable")
            validate_plan_extension(read_plan(prior_plan), plan)
            manifest["parentSeedPlanSha256"] = parent["seedPlanSha256"]
        manifest["parentCheckpointSha256"] = sha256(args.resume)
    write_json(output / "manifest.json", manifest)
    trainer = process = reader = None
    started, gameplay_start, reason = time.monotonic(), None, "startup-failed"
    events = queue.Queue()
    with (output / "trainer.log").open("w", encoding="utf-8") as trainer_log, \
            (output / "server-console.log").open("w", encoding="utf-8") as log:
        try:
            if args.mode == "train":
                command = [sys.executable, "-u", str(source / "training/train_paper_ppo.py"), "--channel",
                    str(plugin / "training-session/channel"), "--output", str(output / "training"),
                    "--max-transitions", str(args.steps), "--rollout-size", "32", "--seed", str(args.policy_seed),
                    "--stop-file", str(output / "trainer.stop.request")]
                if args.resume:
                    command.extend(["--resume", str(args.resume.resolve())])
                if args.upgrade_context:
                    command.append("--upgrade-context")
                env = dict(os.environ, OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1", MKL_NUM_THREADS="1")
                trainer = subprocess.Popen(command, stdout=trainer_log, stderr=subprocess.STDOUT, env=env)
                ready_deadline = time.monotonic() + 30
                while not (output / "training/run.json").is_file():
                    if trainer.poll() is not None:
                        raise RuntimeError("trainer initialization failed before server startup; see trainer.log")
                    if time.monotonic() >= ready_deadline:
                        raise RuntimeError("trainer initialization deadline exceeded before server startup")
                    time.sleep(0.1)
            process = subprocess.Popen([str(args.java), "-Xms512M", "-Xmx2G", "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=UTF-8", "-jar", "paper.jar", "--nogui"], cwd=server,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                encoding="utf-8", errors="replace", bufsize=1)
            write_json(output / "processes.json", {"server": process.pid, "trainer": trainer.pid if trainer else None})
            def read():
                for line in process.stdout:
                    log.write(line); log.flush(); events.put(line)
            reader = threading.Thread(target=read, daemon=True)
            reader.start()
            def send(text):
                if process.poll() is None:
                    process.stdin.write(text + "\n"); process.stdin.flush()
            reason, next_report = "server-exited", started
            while process.poll() is None:
                now = time.monotonic()
                if (output / "stop.request").is_file():
                    reason = "operator-stop-request"; break
                if gameplay_start is None and now - started >= 180:
                    reason = "startup-time-budget"; break
                if gameplay_start is not None and now - gameplay_start >= args.seconds:
                    reason = "gameplay-time-budget"; break
                try:
                    line = events.get(timeout=0.5)
                    if "Done (" in line and gameplay_start is None:
                        gameplay_start = time.monotonic()
                        for text in ("seed", "time set " + str(args.start_time), "ai spawn RLLearner", "ai start RLLearner", "ai debug on"):
                            send(text)
                        print("Paper ready; actual survival episode admitted", flush=True)
                except queue.Empty:
                    pass
                if now >= next_report:
                    next_report = now + 30
                    if gameplay_start is not None:
                        send("ai status RLLearner")
                    report = output / "training/training-report.json"
                    status = json.loads(report.read_text()) if report.exists() else {}
                    print(json.dumps({"elapsedSeconds": round(now-started), "ready": gameplay_start is not None,
                          "environmentSteps": status.get("environmentSteps", 0), "modelVersion": status.get("modelVersion")}), flush=True)
                if trainer and trainer.poll() is not None:
                    reason = "trainer-budget-complete" if trainer.returncode == 0 else "trainer-failed"; break
        finally:
            if process and process.poll() is None:
                if gameplay_start is None:
                    process.terminate(); process.wait(timeout=20)
                    reason += ":startup-child-terminated"
                else:
                    for text in ("ai status RLLearner", "data get entity RLLearner Health", "data get entity RLLearner Inventory", "ai stop RLLearner"):
                        send(text)
                    time.sleep(2)
                    send("stop")
                    try:
                        process.wait(timeout=60)
                    except subprocess.TimeoutExpired:
                        process.terminate(); process.wait(timeout=20)
                        reason += ":forced-server-termination"
            if trainer and trainer.poll() is None:
                (output / "trainer.stop.request").touch(exist_ok=True)
                try:
                    trainer.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    trainer.terminate(); trainer.wait(timeout=20)
                    reason += ":trainer-terminated"
            if reader:
                reader.join(timeout=5)
            manifest.update({"stopReason": reason, "serverExitCode": process.returncode if process else None,
                             "trainerExitCode": trainer.returncode if trainer else None,
                             "elapsedSeconds": round(time.monotonic()-started), "serverReady": gameplay_start is not None})
            write_json(output / "result.json", manifest)
            write_json(output / "observed-gameplay.json", summarize_game_log(server / "logs/latest.log"))
    print(json.dumps(manifest, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("plan", "output", "paper", "plugin", "java", "eula", "runtime-cache"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--mode", choices=("train", "evaluate"), required=True)
    parser.add_argument("--world-seed", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--seconds", type=int, required=True)
    parser.add_argument("--steps", type=int, default=512)
    parser.add_argument("--policy-seed", type=int, default=777)
    parser.add_argument("--start-time", type=int, default=0, help="explicit world time prepared before spawning; no time changes during play")
    parser.add_argument("--model", type=Path)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--upgrade-context", action="store_true", help="explicitly expand v1 trained context when resuming PPO")
    run(parser.parse_args())


if __name__ == "__main__":
    main()
