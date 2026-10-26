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
import secrets
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


def require_runtime_space(output: Path, cache: Path, paper: Path, plugin: Path):
    """Reject before copying a runtime, rather than truncate source/evidence on an almost-full drive."""
    size = paper.stat().st_size + plugin.stat().st_size
    files = 0
    for name in ("cache", "libraries", "versions"):
        directory = cache / name
        if directory.is_dir():
            for path in directory.rglob("*"):
                if path.is_file():
                    size += path.stat().st_size; files += 1
                    if files > 50000:
                        raise ValueError("runtime cache scan exceeds budget")
    parent = output.resolve().parent
    while not parent.exists() and parent != parent.parent:
        parent = parent.parent
    required = size + 128*1024*1024  # Keep headroom for source/checkpoints and normal world generation.
    if shutil.disk_usage(parent).free < required:
        raise ValueError("insufficient disk space for isolated runtime and evidence; no server started")
    return required


def read_plan(path: Path):
    if path.stat().st_size > 65536:
        raise ValueError("oversized experiment plan")
    return validate_plan(json.loads(path.read_text(encoding="utf-8")))


def validate_plan(plan):
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


def reserve_random_seed(directory: Path, base: dict, mode: str, *, draw=None):
    """Persist a unique signed-64-bit seed before use. One registry covers BOTH train/evaluation partitions."""
    from checkpoint_store import durable_replace, encoded
    validate_plan(base)
    if mode not in ("train", "evaluate") or directory.is_symlink():
        raise ValueError("invalid random seed campaign")
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / "seed-campaign.lock"
    descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.write(descriptor, encoded({"pid": os.getpid()}))
        path = directory / "seed-campaign.json"
        plan = json.loads(encoded(base))
        if path.exists() or path.is_symlink():
            if path.is_symlink() or path.stat().st_size > 131072:
                raise ValueError("unsafe seed campaign; original preserved")
            record = json.loads(path.read_bytes())
            if not isinstance(record, dict) or set(record) != {"payload", "sha256"} \
                    or hashlib.sha256(encoded(record["payload"])).hexdigest() != record["sha256"]:
                raise ValueError("seed campaign integrity mismatch; no random reset")
            plan = validate_plan(record["payload"])
            for key in ("trainSeeds", "evaluationSeeds"):
                plan[key] = sorted(set(plan[key]) | set(base[key]))
            validate_plan(plan)  # A seed must never migrate between train and evaluation.
        split = "trainSeeds" if mode == "train" else "evaluationSeeds"
        if len(plan[split]) >= 1000:
            raise ValueError("seed campaign partition budget exhausted")
        used = set(plan["trainSeeds"]) | set(plan["evaluationSeeds"])
        draw = secrets.randbits if draw is None else draw
        for _ in range(64):
            value = draw(64)
            if type(value) is not int or not 0 <= value < 2**64:
                raise ValueError("invalid random seed source")
            seed = str(value if value < 2**63 else value - 2**64)
            if seed not in used:
                plan[split].append(seed)
                durable_replace(path, encoded({"payload": plan, "sha256": hashlib.sha256(encoded(plan)).hexdigest()}))
                return plan, seed
        raise ValueError("random seed collision budget exhausted; no reuse")
    finally:
        os.close(descriptor)
        lock.unlink()  # Interrupted owners leave a visible lock; never remove someone else's lock.


def read_scenario(path: Path):
    if path.stat().st_size > 65536:
        raise ValueError("oversized control scenario")
    scenario = json.loads(path.read_text(encoding="utf-8"))
    if set(scenario) != {"schema", "events"} or scenario["schema"] != "minecraft-brain-control-scenario/v1" \
            or not isinstance(scenario["events"], list) or not 1 <= len(scenario["events"]) <= 32:
        raise ValueError("invalid control scenario")
    previous = -1
    for event in scenario["events"]:
        command = event.get("command")
        keys = {"atSeconds", "command", "enabled"} if command == "learning" else \
               {"atSeconds", "command", "name"} if command in {"model-info", "model-save", "model-load"} else {"atSeconds", "command"}
        if set(event) != keys or type(event["atSeconds"]) is not int or not previous <= event["atSeconds"] <= 86400 \
                or event["atSeconds"] < 0 or command not in {"learning", "stop", "start", "stats", "model-list", "model-info", "model-save", "model-load"}:
            raise ValueError("unsupported scenario command or ordering")
        if event["command"] == "learning" and type(event["enabled"]) is not bool:
            raise ValueError("invalid scenario learning flag")
        if command in {"model-info", "model-save", "model-load"}:
            from model_catalog import model_name
            model_name(event["name"])
        previous = event["atSeconds"]
    return scenario["events"]


def validate_plan_extension(parent: dict, current: dict):
    # Each complete plan has already passed read_plan's overlap/canonical seed checks.
    for key in ("trainSeeds", "evaluationSeeds"):
        if not set(parent[key]).issubset(current[key]):
            raise ValueError("campaign extension cannot remove or reassign existing world seeds")


def validate_options(args, *, plan=None):
    plan = read_plan(args.plan) if plan is None else validate_plan(plan)
    learning = getattr(args, "learning", None)
    if learning not in (None, "on", "off") or learning is not None and (args.mode != "train"
            or getattr(args, "inference_only", False) or getattr(args, "managed_backend", False)):
        raise ValueError("explicit learning mode requires the external training backend and no inference-only override")
    if getattr(args, "admit_models", None):
        if not getattr(args, "managed_backend", False) or not getattr(args, "catalog", None) or len(args.admit_models) > 8:
            raise ValueError("additional snapshot admission requires a managed named catalog and at most eight names")
        from model_catalog import model_name
        for name in args.admit_models:
            model_name(name)
    if getattr(args, "player_persistence", False) and not getattr(args, "managed_backend", False):
        raise ValueError("player persistence acceptance requires the plugin-owned backend")
    if getattr(args, "managed_backend", False) and (args.mode != "train" or not getattr(args, "catalog", None)
            or not getattr(args, "inference_only", False) or args.resume is not None or getattr(args, "save_model_at_end", None)
            or getattr(args, "create_model_at_end", None)):
        raise ValueError("managed backend acceptance requires a named inference-only model and no external resume/final snapshot")
    if getattr(args, "save_model_at_end", None) and getattr(args, "create_model_at_end", None):
        raise ValueError("only one final model operation is allowed")
    final_name = getattr(args, "save_model_at_end", None) or getattr(args, "create_model_at_end", None)
    if final_name:
        from model_catalog import model_name
        model_name(final_name)
        if args.mode != "train" or not getattr(args, "model_catalog", None) or getattr(args, "scenario", None):
            raise ValueError("final snapshot requires a normal backend run and an explicit catalog; prepared scenarios are excluded")
    if getattr(args, "restore_model", None) and not getattr(args, "catalog", None):
        raise ValueError("restore-model requires a catalog")
    if getattr(args, "catalog", None) and (args.mode != "train" or args.resume is not None
                                            or getattr(args, "start_state", None) not in (None, "resume")):
        raise ValueError("catalog restore requires the shared backend and no other weight selection")
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
    if getattr(args, "inference_only", False) and args.mode != "train":
        raise ValueError("backend inference uses the shared backend experiment lane; evaluate is standalone Java inference")
    if getattr(args, "max_decisions", None) is not None and args.max_decisions < 1:
        raise ValueError("max-decisions must be positive")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        raise ValueError("full source commit required")
    # Only reuse clean runtime libraries. Never copy historical worlds, plugin state or experience.
    for path in (args.paper, args.plugin, args.java, args.eula):
        if not path.is_file():
            raise ValueError("required runtime artifact missing: " + path.name)
    if not re.search(r"^\s*eula\s*=\s*true\s*$", args.eula.read_text(), re.MULTILINE):
        raise ValueError("supply an existing accepted EULA; this tool does not accept it for you")
    return plan


def confirm_model_save(process, events, send, name, *, timeout=8):
    return confirm_model_command(process, events, send, "save", name, timeout=timeout)


def confirm_model_command(process, events, send, operation, name, *, timeout=8):
    """A dispatch is not a successful save. Wait for the server's asynchronous success/failure reply."""
    from model_catalog import model_name
    model_name(name)
    if operation not in {"save", "new"}:
        raise ValueError("unsupported final model operation")
    send("ai model " + operation + " " + name)
    deadline = time.monotonic() + timeout
    expected = "현재 두뇌를 '" + name + "' 이름으로 저장했습니다." if operation == "save" else \
               "새 초기 두뇌를 '" + name + "' 이름으로 등록했습니다. 현재 두뇌는 유지됩니다."
    while process.poll() is None and time.monotonic() < deadline:
        try:
            line = re.sub(r"\x1b\[[0-9;]*m", "", events.get(timeout=min(0.25, max(0.001, deadline-time.monotonic()))))
        except queue.Empty:
            continue
        if expected in line:
            return True
        if "모델 명령을 확인하지 못했습니다." in line:
            return False
    return False


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


def named_session(revision):
    from model_catalog import model_name
    return dict(revision["session"], modelName=model_name(revision["name"]))


def run(args):
    random_seed = getattr(args, "random_world_seed", False)
    if random_seed:
        if args.world_seed is not None or args.seed_campaign is None:
            raise ValueError("random world selection requires one explicit shared seed campaign")
        plan, args.world_seed = reserve_random_seed(args.seed_campaign, read_plan(args.plan), args.mode)
        plan = validate_options(args, plan=plan)
    else:
        if getattr(args, "seed_campaign", None) is not None:
            raise ValueError("seed campaign requires random world selection")
        plan = validate_options(args)
    require_runtime_space(args.output, args.runtime_cache, args.paper, args.plugin)
    scenario = read_scenario(args.scenario) if args.scenario else []
    source = Path(__file__).resolve().parents[1]
    actual_commit = subprocess.check_output(["git", "-c", "safe.directory=" + source.as_posix(),
                                              "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if actual_commit != args.source_commit:
        raise ValueError("source commit does not match this tool checkout")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    catalog_state = None
    if args.catalog:
        from model_catalog import ModelCatalog
        catalog = ModelCatalog(args.catalog)
        catalog_state = catalog.load(args.restore_model) if args.restore_model else catalog.selected()
        # Pin this exact revision before process startup; later catalog edits cannot change this run's model.
        import brain_session
        brain_session.save(output / "catalog-source-session.json", named_session(catalog_state))
        args.resume = Path(catalog_state["session"]["checkpoint"])
    if random_seed:
        write_json(output / "seed-plan.json", plan)
    else:
        shutil.copy2(args.plan, output / "seed-plan.json")
    if args.scenario:
        shutil.copy2(args.scenario, output / "control-scenario.json")
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
    if args.player_persistence:
        config, replaced = re.subn(r"(?m)^(persistence:\n(?:  #[^\n]*\n)*  enabled:) false$", r"\1 true", config)
        if replaced != 1:
            raise ValueError("player persistence setting was not enabled; acceptance cannot continue")
    if args.managed_backend:
        from model_catalog import ModelCatalog
        private_catalog = ModelCatalog(plugin / "brain-catalog")
        private_catalog.save(catalog_state["name"], catalog_state["session"])
        for name in args.admit_models:
            if name != catalog_state["name"]:
                admitted = catalog.load(name)
                private_catalog.save(name, admitted["session"])
        private_catalog.select(catalog_state["name"])
        config = config.replace("  runtime:\n    enabled: false", "  runtime:\n    enabled: true")
        config = config.replace("    python: python", "    python: " + json.dumps(sys.executable))
        config = config.replace("    script: training/train_paper_ppo.py", "    script: " + json.dumps(str(source / "training/train_paper_ppo.py")))
    elif args.mode == "train":
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
    from train_paper_ppo import resolve_learning_mode
    learning_enabled = args.mode == "train" and resolve_learning_mode(
            None if catalog_state is None else catalog_state["session"],
            learning=getattr(args, "learning", None), inference_only=args.inference_only)
    manifest = {"schema": "minecraft-paper-lab/v1", "sourceCommit": args.source_commit, "mode": args.mode,
                "worldSeed": args.world_seed, "seedPlanSha256": sha256(output / "seed-plan.json"),
                "paperJarSha256": sha256(server / "paper.jar"), "pluginSha256": sha256(plugin.parent / "MinecraftAI.jar"),
                "configurationSha256": sha256(plugin / "config.yml"), "serverPropertiesSha256": sha256(server / "server.properties"),
                "port": args.port, "maxSeconds": args.seconds, "maxTransitions": args.steps if learning_enabled else 0,
                "backendLearningEnabled": learning_enabled, "requestedLearningOverride": getattr(args, "learning", None),
                "requestedStartState": args.start_state, "maxDecisions": args.max_decisions,
                "preEpisodeWorldTime": args.start_time,
                "tools": {name: sha256(source / "training" / name) for name in ("paper_lab.py", "rl_actor.py", "train_paper_ppo.py", "checkpoint_store.py", "brain_session.py", "model_catalog.py")},
                "teacherLabels": False, "grantedItems": False, "teleportDuringEpisode": False,
                "allWorldAutonomyProven": False}
    manifest["backendOwner"] = "plugin" if args.managed_backend else "external-lab"
    manifest["seedSelection"] = "random-per-world-run" if random_seed else "explicit-frozen-plan"
    manifest["playerPersistence"] = args.player_persistence
    if args.save_model_at_end:
        manifest.update({"requestedFinalModelSave": args.save_model_at_end, "finalModelSaveAcknowledged": False,
                         "finalModelSaveVerified": False})
    if args.create_model_at_end:
        selection = args.model_catalog / "selected-model.json"
        manifest.update({"requestedFinalModelCreation": args.create_model_at_end,
                         "finalModelCreationAcknowledged": False, "finalModelCreationVerified": False,
                         "selectionSha256BeforeCreation": sha256(selection) if selection.is_file() else None})
    if catalog_state:
        manifest.update({"catalogName": catalog_state["name"], "catalogBrainId": catalog_state["session"]["brainId"],
                         "catalogSessionSha256": sha256(output / "catalog-source-session.json")})
    if args.scenario:
        manifest.update({"preparedScenario": True, "notForAutonomousTraining": True,
                         "scenarioSha256": sha256(output / "control-scenario.json")})
    if args.model:
        manifest["actorSha256"] = sha256(plugin / "models/minecraft-rl-actor.json")
    if args.resume:
        # The parent lab manifest must belong to the same frozen training partition.
        selected_resume = args.resume.resolve()
        if selected_resume.is_dir():
            training_directory = selected_resume
        elif selected_resume.parent.parent.name == "checkpoints":
            training_directory = selected_resume.parent.parent.parent
        else:
            training_directory = selected_resume.parent
        parent_directory = training_directory.parent
        parent = json.loads((parent_directory / "manifest.json").read_text(encoding="utf-8"))
        if parent.get("mode") != "train" or parent.get("worldSeed") not in plan["trainSeeds"]:
            raise ValueError("parent checkpoint does not belong to the same training campaign")
        if parent.get("seedPlanSha256") != manifest["seedPlanSha256"]:
            prior_plan = parent_directory / "seed-plan.json"
            if not prior_plan.is_file() or sha256(prior_plan) != parent.get("seedPlanSha256"):
                raise ValueError("parent campaign plan provenance is unavailable")
            validate_plan_extension(read_plan(prior_plan), plan)
            manifest["parentSeedPlanSha256"] = parent["seedPlanSha256"]
        if selected_resume.is_dir():
            manifest["resumeSelection"] = "verified-directory-recovery"
        else:
            manifest["parentCheckpointSha256"] = sha256(selected_resume)
    write_json(output / "manifest.json", manifest)
    trainer = process = reader = None
    started, gameplay_start, reason = time.monotonic(), None, "startup-failed"
    server_ready, managed_ready = False, False
    events = queue.Queue()
    with (output / "trainer.log").open("w", encoding="utf-8") as trainer_log, \
            (output / "server-console.log").open("w", encoding="utf-8") as log:
        try:
            if args.mode == "train" and not args.managed_backend:
                command = [sys.executable, "-u", str(source / "training/train_paper_ppo.py"), "--channel",
                    str(plugin / "training-session/channel"), "--output", str(output / "training"),
                    "--max-transitions", str(args.steps), "--rollout-size", "32", "--seed", str(args.policy_seed),
                    "--stop-file", str(output / "trainer.stop.request")]
                if catalog_state:
                    command.extend(["--restore-session", str(output / "catalog-source-session.json")])
                elif args.resume:
                    command.extend(["--resume", str(args.resume.resolve())])
                if args.upgrade_context:
                    command.append("--upgrade-context")
                if args.inference_only:
                    command.append("--inference-only")
                if getattr(args, "learning", None) is not None:
                    command.extend(["--learning", args.learning])
                if args.start_state:
                    command.extend(["--start-state", args.start_state])
                if args.max_decisions is not None:
                    command.extend(["--max-decisions", str(args.max_decisions)])
                if args.model_catalog:
                    command.extend(["--model-catalog", str(args.model_catalog.resolve())])
                env = dict(os.environ, OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1", MKL_NUM_THREADS="1")
                trainer = subprocess.Popen(command, stdout=trainer_log, stderr=subprocess.STDOUT, env=env)
                ready_deadline = time.monotonic() + 30
                while not (output / "training/run.json").is_file():
                    if trainer.poll() is not None:
                        raise RuntimeError("trainer initialization failed before server startup; see trainer.log")
                    if time.monotonic() >= ready_deadline:
                        raise RuntimeError("trainer initialization deadline exceeded before server startup")
                    time.sleep(0.1)
                initialized = json.loads((output / "training/run.json").read_text(encoding="utf-8"))
                manifest["actualStartState"] = initialized.get("trainingStartState")
                manifest["actualLearningEnabled"] = initialized.get("learningEnabled")
                manifest["backendLearningEnabled"] = initialized["learningEnabled"]
                manifest["maxTransitions"] = args.steps if initialized["learningEnabled"] else 0
                write_json(output / "manifest.json", manifest)
                if initialized.get("parents"):
                    manifest["resolvedParentCheckpointSha256"] = initialized["parents"][-1]["checkpointSha256"]
                    manifest["resumeRecovery"] = initialized.get("resumeRecovery")
                    write_json(output / "manifest.json", manifest)
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
            scenario_index = 0
            while process.poll() is None:
                now = time.monotonic()
                while gameplay_start is not None and scenario_index < len(scenario) \
                        and now - gameplay_start >= scenario[scenario_index]["atSeconds"]:
                    event = scenario[scenario_index]
                    if event["command"] == "learning":
                        send("ai training " + ("on" if event["enabled"] else "off"))
                    elif event["command"] == "stats":
                        send("ai status RLLearner"); send("ai stats RLLearner")
                    elif event["command"] == "model-list":
                        send("ai model list")
                    elif event["command"] in {"model-info", "model-save", "model-load"}:
                        send("ai model " + event["command"].removeprefix("model-") + " " + event["name"])
                    else:
                        send("ai " + event["command"] + " RLLearner")
                    with (output / "scenario-dispatch.jsonl").open("a", encoding="utf-8") as dispatched:
                        dispatched.write(json.dumps({"event": event, "dispatchSeconds": round(now-gameplay_start, 3)}) + "\n")
                    scenario_index += 1
                if (output / "stop.request").is_file():
                    reason = "operator-stop-request"; break
                if gameplay_start is None and now - started >= 180:
                    reason = "startup-time-budget"; break
                if gameplay_start is not None and now - gameplay_start >= args.seconds:
                    reason = "gameplay-time-budget"; break
                try:
                    line = events.get(timeout=0.5)
                    if "Done (" in line:
                        server_ready = True
                    if args.managed_backend and "Managed brain ready:" in line:
                        managed_ready = True
                    if args.managed_backend and "Managed brain unavailable;" in line:
                        reason = "managed-startup-failed"; break
                    if server_ready and (not args.managed_backend or managed_ready) and gameplay_start is None:
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
                        send("ai stats RLLearner")
                    report = output / "training/training-report.json"
                    status = json.loads(report.read_text()) if report.exists() else {}
                    print(json.dumps({"elapsedSeconds": round(now-started), "ready": gameplay_start is not None,
                          "environmentSteps": status.get("environmentSteps", 0), "modelVersion": status.get("modelVersion")}), flush=True)
                if trainer and trainer.poll() is not None:
                    reason = ("backend-inference-budget-complete" if not manifest["actualLearningEnabled"] else "trainer-budget-complete") if trainer.returncode == 0 else "trainer-failed"; break
        finally:
            if process and process.poll() is None:
                if gameplay_start is None:
                    process.terminate(); process.wait(timeout=20)
                    reason += ":startup-child-terminated"
                else:
                    if args.create_model_at_end and trainer and trainer.poll() is None:
                        acknowledged = confirm_model_command(process, events, send, "new", args.create_model_at_end)
                        manifest["finalModelCreationAcknowledged"] = acknowledged
                        if acknowledged:
                            try:
                                from model_catalog import ModelCatalog
                                created = ModelCatalog(args.model_catalog).load(args.create_model_at_end)["session"]
                                selection = args.model_catalog / "selected-model.json"
                                after_selection = sha256(selection) if selection.is_file() else None
                                if created["brainId"] == initialized["brainId"] or not created["initializationOnly"] \
                                        or created["policyVersion"] != 0 or created["gradientUpdates"] != 0 or created["learningEnabled"] \
                                        or after_selection != manifest["selectionSha256BeforeCreation"]:
                                    raise ValueError("new model/active selection identity mismatch")
                                manifest["finalModelCreationVerified"] = True
                                manifest["createdModel"] = {key: created[key] for key in ("brainId", "initializationOnly",
                                                                                       "policyVersion", "gradientUpdates", "learningEnabled")}
                            except (OSError, ValueError, KeyError, TypeError) as failure:
                                manifest["finalModelCreationError"] = type(failure).__name__
                        if not manifest["finalModelCreationVerified"]:
                            reason += ":model-creation-unconfirmed"
                    if args.save_model_at_end and trainer and trainer.poll() is None:
                        acknowledged = confirm_model_save(process, events, send, args.save_model_at_end)
                        manifest["finalModelSaveAcknowledged"] = acknowledged
                        if acknowledged:
                            try:
                                from model_catalog import ModelCatalog
                                saved = ModelCatalog(args.model_catalog).load(args.save_model_at_end)["session"]
                                if saved["brainId"] != initialized["brainId"]:
                                    raise ValueError("saved model belongs to another brain")
                                manifest["finalModelSaveVerified"] = True
                                manifest["savedModel"] = {key: saved[key] for key in ("brainId", "checkpointSha256",
                                                                                   "policyVersion", "gradientUpdates", "learningEnabled")}
                            except (OSError, ValueError, KeyError, TypeError) as failure:
                                manifest["finalModelSaveError"] = type(failure).__name__
                        if not manifest["finalModelSaveVerified"]:
                            reason += ":model-save-unconfirmed"
                    for text in ("ai status RLLearner", "ai stats RLLearner", "data get entity RLLearner Health", "data get entity RLLearner Inventory", "ai stop RLLearner"):
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
            if args.managed_backend:
                import brain_session
                sessions = []
                for path in sorted((plugin / "brain-runtime").glob("run-*/runtime-session.json")):
                    state = brain_session.load(path)
                    sessions.append({key: state[key] for key in ("brainId", "checkpointSha256", "policyVersion",
                                                                "gradientUpdates", "learningEnabled", "initializationOnly")})
                manifest["managedRuntimeSessions"] = sessions
                manifest["managedBackendReady"] = managed_ready
            write_json(output / "result.json", manifest)
            write_json(output / "observed-gameplay.json", summarize_game_log(server / "logs/latest.log"))
    print(json.dumps(manifest, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("plan", "output", "paper", "plugin", "java", "eula", "runtime-cache"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--mode", choices=("train", "evaluate"), required=True)
    worlds = parser.add_mutually_exclusive_group(required=True)
    worlds.add_argument("--world-seed")
    worlds.add_argument("--random-world-seed", action="store_true", help="new random seed per world run; observations/actions stay in the same world")
    parser.add_argument("--seed-campaign", type=Path, help="shared private train/evaluation reservation registry, required for random worlds")
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--seconds", type=int, required=True)
    parser.add_argument("--steps", type=int, default=512)
    parser.add_argument("--policy-seed", type=int, default=777)
    parser.add_argument("--start-time", type=int, default=0, help="explicit world time prepared before spawning; no time changes during play")
    parser.add_argument("--model", type=Path)
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--catalog", type=Path, help="restore an exact named catalog snapshot using the shared backend")
    parser.add_argument("--restore-model", help="catalog model name; omitted uses the saved selection")
    parser.add_argument("--model-catalog", type=Path, help="explicit private catalog for in-game list/info/save")
    parser.add_argument("--save-model-at-end", help="save the normal live brain after gameplay, await acknowledgement and verify its reference")
    parser.add_argument("--create-model-at-end", help="register a distinct INITIAL model after a normal run, without changing active/selected brain")
    parser.add_argument("--managed-backend", action="store_true", help="acceptance of plugin-owned inference backend; never starts a second external trainer")
    parser.add_argument("--player-persistence", action="store_true", help="enable player persistence in the new isolated managed-backend acceptance server")
    parser.add_argument("--admit-models", nargs="*", default=[], help="up to eight verified named snapshots admitted to the private managed-backend catalog")
    parser.add_argument("--upgrade-context", action="store_true", help="explicitly expand v1 trained context when resuming PPO")
    parser.add_argument("--start-state", choices=("fresh", "pretrained", "resume"))
    parser.add_argument("--inference-only", action="store_true", help="shared backend with learning disabled, for startup/mode scenarios")
    parser.add_argument("--learning", choices=("on", "off"), help="explicit external-backend learning state; omitted preserves the restored mode")
    parser.add_argument("--max-decisions", type=int)
    parser.add_argument("--scenario", type=Path, help="prepared control scenario; outputs must never enter autonomous training")
    run(parser.parse_args())


if __name__ == "__main__":
    main()
