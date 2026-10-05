package me.herry.minecraftAI.commands;

import me.herry.minecraftAI.ai.AIController;
import me.herry.minecraftAI.ai.AIDebugger;
import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.goal.GoalType;
import me.herry.minecraftAI.ai.util.Positions;
import me.herry.minecraftAI.ai.world.Base;
import me.herry.minecraftAI.config.AIConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * /ai 명령어. AI 플레이어를 만들고, 시작/중지하고, 상태를 확인하는 관리자용 명령어다.
 */
public class AICommand implements TabExecutor {
    public static final String PERMISSION = "minecraftai.admin";
    private static final List<String> SUB_COMMANDS = List.of("spawn", "remove", "start", "stop", "status", "inv", "goal", "debug",
            "why", "brain", "plan", "observe", "memory", "perf", "home", "say", "save");
    // 디버그를 켜고 끄는 말. "/ai debug start" 처럼 입력해도 알아듣게 한다.
    private static final List<String> SWITCH_ON = List.of("on", "start", "true", "enable", "켜기");
    private static final List<String> SWITCH_OFF = List.of("off", "stop", "false", "disable", "끄기");
    // 강제 목표를 풀고 다시 스스로 목표를 고르게 하는 키워드
    private static final String AUTO_GOAL = "auto";

    private final AIController controller;
    private final AIDebugger debugger;
    private final AIConfig config;

    public AICommand(AIController controller, AIDebugger debugger, AIConfig config) {
        this.controller = controller;
        this.debugger = debugger;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            error(sender, "이 명령어를 사용할 권한이 없습니다.");
            return true;
        }
        if (args.length == 0) return false;

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, args);
            case "remove" -> remove(sender, args);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "status" -> status(sender, args);
            case "inv", "inventory" -> inventory(sender, args);
            case "goal" -> goal(sender, args);
            case "debug" -> debug(sender, args);
            case "why" -> inspect(sender, args, "판단 이유", AIInspector::why);
            case "brain" -> inspect(sender, args, "판단 기록", AIInspector::brain);
            case "plan" -> inspect(sender, args, "현재 계획", AIInspector::plan);
            case "observe" -> inspect(sender, args, "관측", AIInspector::observe);
            case "memory" -> inspect(sender, args, "기억", AIInspector::memory);
            case "perf" -> inspect(sender, args, "성능", ai -> AIInspector.perf(ai, controller.getBudget()));
            case "home" -> home(sender, args);
            case "say" -> say(sender, args);
            case "save" -> save(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    // AI 의 속을 들여다보는 명령들. 이름을 생략하면 AI 가 하나뿐일 때 그 AI 를 대상으로 한다.
    private void inspect(CommandSender sender, String[] args, String title, Function<AIPlayer, List<String>> report) {
        AIPlayer ai = resolve(sender, args, 1);
        if (ai == null) return;
        sender.sendMessage(Component.text("[" + ai.getName() + "] " + title, NamedTextColor.YELLOW));
        for (String line : report.apply(ai)) sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
    }

    /**
     * 거점을 지금 서 있는 자리로 정하거나(set) 지운다(clear). 집 짓기를 특정 장소에서 시험할 때 쓴다.
     */
    private void home(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "사용법: /ai home <set|clear> [이름]");
            return;
        }
        AIPlayer ai = resolve(sender, args, 2);
        if (ai == null) return;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                // 플레이어가 실행하면 그 플레이어가 선 자리, 콘솔에서 실행하면 AI 가 선 자리가 거점이 된다.
                Location location = sender instanceof Player player ? player.getLocation() : ai.getPlayer().getLocation();
                if (location.getWorld() == null) {
                    error(sender, "월드를 알 수 없습니다.");
                    return;
                }
                ai.getWorldModel().setHome(new Base(location.getWorld().getUID(), Positions.feet(location)));
                ai.setBuildJob(null);
                info(sender, "AI '" + ai.getName() + "' 의 거점을 " + Positions.feet(location) + " (으)로 정했습니다.");
            }
            case "clear" -> {
                ai.getWorldModel().setHome(null);
                ai.setBuildJob(null);
                info(sender, "AI '" + ai.getName() + "' 의 거점을 지웠습니다.");
            }
            default -> error(sender, "사용법: /ai home <set|clear> [이름]");
        }
    }

    /**
     * 채팅으로 말한 것과 똑같이 AI 에게 말을 건다. 콘솔에서 대화 기능을 시험할 때 쓴다.
     * 예: /ai say AI_Player 지금 뭐 하고 있어?
     */
    private void say(CommandSender sender, String[] args) {
        if (args.length < 3) {
            error(sender, "사용법: /ai say <이름> <할 말>");
            return;
        }
        AIPlayer ai = controller.get(args[1]);
        if (ai == null) {
            error(sender, "'" + args[1] + "' 이라는 AI 가 없습니다.");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        controller.tell(ai, sender.getName(), text);
    }

    private void save(CommandSender sender) {
        controller.saveAll(false);
        info(sender, "AI " + controller.count() + "명의 상태를 저장했습니다.");
    }

    private void spawn(CommandSender sender, String[] args) {
        String name = args.length >= 2 ? args[1] : config.defaultName;
        Location location = spawnLocation(sender);
        if (location == null) {
            error(sender, "AI 를 생성할 월드를 찾을 수 없습니다.");
            return;
        }

        // 스킨을 지정했으면 모장 서버에서 스킨을 받아 온 뒤에 생성된다.
        if (args.length >= 3) {
            AIController.SpawnResult check = controller.checkSpawn(name, location);
            if (check != AIController.SpawnResult.OK) {
                reportSpawn(sender, name, check);
                return;
            }
            info(sender, "'" + args[2] + "' 의 스킨을 받아 오는 중입니다...");
            controller.spawnWithSkin(name, location, args[2], (result, skinFound) -> {
                reportSpawn(sender, name, result);
                if (result == AIController.SpawnResult.OK && !skinFound) {
                    error(sender, "'" + args[2] + "' 의 스킨을 찾지 못해 기본 스킨으로 생성했습니다.");
                }
            });
            return;
        }
        reportSpawn(sender, name, controller.spawn(name, location, null));
    }

    private void reportSpawn(CommandSender sender, String name, AIController.SpawnResult result) {
        switch (result) {
            case OK -> info(sender, "AI '" + name + "' 을(를) 생성했습니다. /ai start " + name + " 로 자율 행동을 시작합니다.");
            case INVALID_NAME -> error(sender, "이름은 영문, 숫자, 밑줄로 3~16자여야 합니다.");
            case NAME_TAKEN -> error(sender, "'" + name + "' 이름은 이미 사용 중입니다.");
            case LIMIT_REACHED -> error(sender, "AI 는 최대 " + config.maxCount + "개까지 생성할 수 있습니다.");
            case FAILED -> error(sender, "AI 생성에 실패했습니다. 콘솔 로그를 확인하세요.");
        }
    }

    // 플레이어가 실행하면 그 자리에, 콘솔에서 실행하면 기본 월드의 스폰 지점에 생성한다.
    private static @Nullable Location spawnLocation(CommandSender sender) {
        if (sender instanceof Player player) return player.getLocation();
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }


    private void remove(CommandSender sender, String[] args) {
        AIPlayer ai = resolve(sender, args, 1);
        if (ai == null) return;
        controller.remove(ai.getName());
        info(sender, "AI '" + ai.getName() + "' 을(를) 제거했습니다.");
    }

    private void start(CommandSender sender, String[] args) {
        AIPlayer ai = resolve(sender, args, 1);
        if (ai == null) return;
        if (ai.start()) info(sender, "AI '" + ai.getName() + "' 의 자율 행동을 시작했습니다.");
        else error(sender, "AI '" + ai.getName() + "' 은(는) 지금 시작할 수 없습니다. (상태: " + ai.getState() + ")");
    }

    private void stop(CommandSender sender, String[] args) {
        AIPlayer ai = resolve(sender, args, 1);
        if (ai == null) return;
        if (ai.stop()) info(sender, "AI '" + ai.getName() + "' 의 자율 행동을 중지했습니다.");
        else error(sender, "AI '" + ai.getName() + "' 은(는) 이미 멈춰 있습니다.");
    }

    private void status(CommandSender sender, String[] args) {
        AIPlayer ai = resolve(sender, args, 1);
        if (ai == null) return;

        Player player = ai.getPlayer();
        Location location = player.getLocation();
        GoalType forced = ai.getForcedGoal();
        line(sender, "AI 이름", ai.getName());
        line(sender, "AI 상태", ai.getState().name());
        line(sender, "현재 목표", ai.getCurrentGoal().name() + (forced != null ? " (강제 지정)" : ""));
        line(sender, "현재 행동", ai.getCurrentActionName());
        line(sender, "위치", location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + " (" + player.getWorld().getName() + ")");
        line(sender, "체력", String.valueOf((int) Math.ceil(player.getHealth())));
        line(sender, "허기", String.valueOf(player.getFoodLevel()));
    }

    // AI 의 인벤토리를 읽기 전용 창으로 연다. AI 를 우클릭해도 같은 창이 열린다.
    private void inventory(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "인벤토리 창은 게임 안에서만 열 수 있습니다.");
            return;
        }
        AIPlayer ai = resolve(sender, args, 1);
        if (ai == null) return;
        controller.openInventory(player, ai);
    }

    private void goal(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "사용법: /ai goal <목표|auto> [이름]");
            return;
        }
        AIPlayer ai = resolve(sender, args, 2);
        if (ai == null) return;

        if (args[1].equalsIgnoreCase(AUTO_GOAL)) {
            ai.setForcedGoal(null);
            info(sender, "AI '" + ai.getName() + "' 이(가) 다시 스스로 목표를 선택합니다.");
            return;
        }

        GoalType goal;
        try {
            goal = GoalType.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            error(sender, "알 수 없는 목표입니다: " + args[1]);
            return;
        }
        ai.setForcedGoal(goal);
        info(sender, "AI '" + ai.getName() + "' 의 목표를 " + goal + " (으)로 고정했습니다. /ai goal auto 로 해제합니다.");
    }

    private void debug(CommandSender sender, String[] args) {
        Boolean enable = args.length >= 2 ? parseSwitch(args[1]) : Boolean.valueOf(!debugger.isEnabled());
        if (enable == null) {
            error(sender, "사용법: /ai debug <on|off>");
            return;
        }
        debugger.setEnabled(enable);
        if (enable) {
            info(sender, "디버그 출력을 켰습니다. 서버 콘솔과 logs/latest.log 에만 [AI:이름] 으로 기록됩니다.");
        } else {
            info(sender, "디버그 출력을 껐습니다.");
        }
    }

    private static @Nullable Boolean parseSwitch(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (SWITCH_ON.contains(lower)) return Boolean.TRUE;
        if (SWITCH_OFF.contains(lower)) return Boolean.FALSE;
        return null;
    }

    // 이름을 생략하면 AI 가 하나뿐일 때 그 AI 를 대상으로 한다.
    private @Nullable AIPlayer resolve(CommandSender sender, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            AIPlayer ai = controller.get(args[nameIndex]);
            if (ai == null) error(sender, "'" + args[nameIndex] + "' 이라는 AI 가 없습니다.");
            return ai;
        }

        List<AIPlayer> all = controller.getAll();
        if (all.size() == 1) return all.getFirst();
        if (all.isEmpty()) error(sender, "생성된 AI 가 없습니다. /ai spawn 으로 먼저 생성하세요.");
        else error(sender, "AI 가 여러 개입니다. 이름을 지정하세요: " + String.join(", ", names()));
        return null;
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (AIPlayer ai : controller.getAll()) names.add(ai.getName());
        return names;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();

        if (args.length == 1) return filter(SUB_COMMANDS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "remove", "start", "stop", "status", "inv", "inventory", "why", "brain", "plan", "observe", "memory", "perf", "say" ->
                        filter(names(), args[1]);
                case "goal" -> filter(goalNames(), args[1]);
                case "debug" -> filter(List.of("on", "off"), args[1]);
                case "home" -> filter(List.of("set", "clear"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && (sub.equals("goal") || sub.equals("home"))) return filter(names(), args[2]);
        return List.of();
    }

    private static List<String> goalNames() {
        List<String> names = new ArrayList<>();
        names.add(AUTO_GOAL);
        for (GoalType goal : GoalType.values()) names.add(goal.name());
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private static void line(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text(label + ": ", NamedTextColor.YELLOW).append(Component.text(value, NamedTextColor.WHITE)));
    }
}
