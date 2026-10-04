package me.herry.minecraftAI.ai;

import me.herry.minecraftAI.ai.brain.DecisionExplainer;
import me.herry.minecraftAI.ai.brain.Directive;
import me.herry.minecraftAI.ai.comm.CommunicationHub;
import me.herry.minecraftAI.ai.comm.IncomingMessage;
import me.herry.minecraftAI.ai.comm.Intent;
import me.herry.minecraftAI.ai.goal.Situation;
import me.herry.minecraftAI.ai.inventory.InventorySystem;
import me.herry.minecraftAI.ai.team.Phrases;
import me.herry.minecraftAI.ai.world.Base;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Predicate;

/**
 * 사람의 말(해석된 의도)에 AI 한 명이 어떻게 대답하고 무엇을 할지 정한다.
 * 말이 어디서 왔는지(인게임, Discord)나 어떻게 해석됐는지(규칙, LLM)는 알지 못하고, 의도만 본다.
 * 질문에는 누구에게나 대답하지만, 일을 시키는 말은 권한이 있는 사람의 것만 듣는다.
 */
final class Conversation implements CommunicationHub.Participant {
    private final AIPlayer ai;

    Conversation(AIPlayer ai) {
        this.ai = ai;
    }

    @Override
    public String name() {
        return ai.getName();
    }

    @Override
    public List<String> respond(IncomingMessage message, Intent intent) {
        if (!ai.getBody().isUsable()) return List.of(Phrases.status(ai.getState(), ai.getCurrentGoal(), 0, 0));
        return switch (intent.type()) {
            case ASK_REASON -> List.of(explain());
            case ASK_HAVE -> List.of(have(intent.subject()));
            case ASK_LOCATION -> List.of(location());
            case GO_HOME -> List.of(command(message, Directive.goHome(message.sender(), ai.getTicks())));
            case GATHER -> List.of(command(message, Directive.gather(intent.subject(), message.sender(), ai.getTicks())));
            case STOP -> List.of(stop(message));
            case RESUME -> List.of(resume(message));
            case AUTONOMOUS -> List.of(autonomous(message));
            case UNSUPPORTED -> List.of("그건 아직 제가 할 줄 모르는 일이에요. " + stageNote());
            // 무슨 말인지 모르겠으면 지금 하는 일을 알려 준다.
            case ASK_STATUS, NONE -> List.of(status());
        };
    }

    private String status() {
        Player player = ai.getPlayer();
        return Phrases.status(ai.getState(), ai.getCurrentGoal(), (int) Math.ceil(player.getHealth()), player.getFoodLevel());
    }

    private String explain() {
        if (ai.getState() != AIState.RUNNING) return status();
        return DecisionExplainer.brief(ai.getDecision());
    }

    private String stageNote() {
        Situation situation = ai.getLastSituation();
        return situation == null ? "" : "지금은 '" + situation.stage.label() + "' 단계예요.";
    }

    private String location() {
        Location location = ai.getPlayer().getLocation();
        String where = location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + " 에 있어요.";
        Base home = ai.getWorldModel().homeIn(ai.getWorldId());
        if (home == null) return where;
        int distance = (int) Math.round(home.center().distance(ai.getPosition()));
        return where + " 집에서 " + distance + "칸 떨어져 있어요.";
    }

    // 가방과 집 상자에 있는 개수를 알려 준다.
    private String have(Intent.Subject subject) {
        Predicate<Material> filter = filterOf(subject);
        int carried = ai.getInventory().count(filter);
        int stored = 0;
        for (var contents : ai.getWorldModel().getStorage().values()) {
            for (var entry : contents.entrySet()) {
                Material type = Material.getMaterial(entry.getKey());
                if (type != null && filter.test(type)) stored += entry.getValue();
            }
        }
        String what = Phrases.subjectName(subject);
        if (carried == 0 && stored == 0) return Phrases.topic(what) + " 아직 하나도 없어요.";
        String answer = what + " " + carried + "개 가지고 있어요.";
        return stored > 0 ? answer + " 집 상자에도 " + stored + "개 있어요." : answer;
    }

    private static Predicate<Material> filterOf(Intent.Subject subject) {
        return switch (subject) {
            case WOOD -> type -> Tag.LOGS.isTagged(type) || Tag.PLANKS.isTagged(type);
            case STONE -> Tag.ITEMS_STONE_TOOL_MATERIALS::isTagged;
            case COAL -> type -> type == Material.COAL || type == Material.CHARCOAL;
            case IRON -> type -> type == Material.IRON_INGOT || type == Material.RAW_IRON;
            case DIAMOND -> type -> type == Material.DIAMOND;
            case FOOD -> InventorySystem::isSafeFood;
            case NONE -> type -> false;
        };
    }

    private String command(IncomingMessage message, Directive directive) {
        if (!message.trusted()) return Phrases.notAllowed();
        if (ai.getState() != AIState.RUNNING) return status();
        Situation situation = ai.getLastSituation();
        String rejection = situation == null ? null : directive.rejection(situation);
        if (rejection != null) return rejection;
        ai.setDirective(directive);
        return directive.kind() == Directive.Kind.GO_HOME ? "알겠어요, 집으로 돌아갈게요."
                : "알겠어요, " + Directive.subjectLabel(directive.subject()) + " 구해 올게요.";
    }

    private String stop(IncomingMessage message) {
        if (!message.trusted()) return Phrases.notAllowed();
        return ai.stop() ? "알겠어요, 멈출게요." : status();
    }

    private String resume(IncomingMessage message) {
        if (!message.trusted()) return Phrases.notAllowed();
        return ai.start() ? "다시 움직일게요." : status();
    }

    private String autonomous(IncomingMessage message) {
        if (!message.trusted()) return Phrases.notAllowed();
        ai.setDirective(null);
        return "알겠어요, 제가 알아서 할게요.";
    }
}
