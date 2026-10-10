package me.herry.minecraftAI.ai.primitive.runtime;

import me.herry.minecraftAI.ai.AIPlayer;
import me.herry.minecraftAI.ai.action.Action;
import me.herry.minecraftAI.ai.action.ActionStatus;
import me.herry.minecraftAI.ai.primitive.control.*;

import java.util.ArrayList;
import java.util.List;

/** One bounded externally supplied sequence, executed by the brain's existing action lane. */
public final class PrimitiveProgramAction implements Action {
    private final PrimitiveProgram program;
    private final List<PrimitiveTransition> transitions = new ArrayList<>();
    private ActionStatus status = ActionStatus.READY;
    private String reason = "";
    private int elapsed;
    private int index;
    private Action child;
    private PrimitiveSnapshot before;
    private PrimitiveResult result;
    private String interruptReason;

    public PrimitiveProgramAction(PrimitiveProgram program) {
        this.program = program;
    }

    public PrimitiveResult result() { return result; }
    public PrimitiveProgram program() { return program; }
    public void requestInterrupt(String reason) { interruptReason = reason; }
    @Override public String getName() { return "PrimitiveProgram[" + program.provenance() + "]"; }
    @Override public ActionStatus getStatus() { return status; }
    @Override public String getFailReason() { return reason; }

    @Override
    public void update(AIPlayer ai) {
        if (status == ActionStatus.SUCCESS || status == ActionStatus.FAILED) return;
        status = ActionStatus.RUNNING;
        if (interruptReason != null) { finish(ai, "CANCELLED", interruptReason); return; }
        if (++elapsed > program.maxTicks()) { finish(ai, "TIMEOUT", "program tick budget exceeded"); return; }
        if (!program.worldId().equals(ai.getWorldId())) { finish(ai, "CANCELLED", "world changed"); return; }
        if (!ai.getBody().isUsable() || ai.getPlayer().isDead()) { finish(ai, "FAILED", "body unavailable"); return; }
        if (ai.getPlayer().getHealth() <= 6.0 || ai.getPlayer().isInLava()
                || ai.getPlayer().isInWater() && ai.getPlayer().getRemainingAir() <= 60) {
            finish(ai, "UNSAFE", "survival interrupt");
            return;
        }
        PrimitiveCommand command = program.steps().get(index);
        if (child == null) {
            before = PrimitiveAdapter.capture(ai);
            PrimitiveCheck check = PrimitiveAdapter.check(ai, command);
            if (!check.allowed()) {
                if (check.reason().startsWith("work budget exhausted")) { before = null; return; }
                finish(ai, "FAILED", check.reason());
                return;
            }
            try {
                child = PrimitiveAdapter.create(ai, command);
            } catch (IllegalArgumentException | IllegalStateException e) {
                finish(ai, "FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return;
            }
        }
        child.update(ai);
        if (child.getStatus() == ActionStatus.FAILED) {
            finish(ai, "FAILED", child.getFailReason());
        } else if (child.getStatus() == ActionStatus.SUCCESS) {
            record(ai, "SUCCEEDED", "");
            child = null;
            before = null;
            if (++index == program.steps().size()) finish(ai, "SUCCEEDED", "");
        }
    }

    @Override public void cancel(AIPlayer ai) { cancel(ai, "cancelled"); }

    public void cancel(AIPlayer ai, String detail) {
        if (status == ActionStatus.SUCCESS || status == ActionStatus.FAILED) return;
        finish(ai, "CANCELLED", detail);
    }

    private void record(AIPlayer ai, String outcome, String detail) {
        if (before == null) return;
        PrimitiveSnapshot after = PrimitiveAdapter.capture(ai);
        transitions.add(new PrimitiveTransition(index, program.steps().get(index), before, after, outcome, detail,
                OutcomeSignals.between(before, after, objectiveReached(after))));
    }

    private void finish(AIPlayer ai, String outcome, String detail) {
        if (result != null) return;
        if (child != null) child.cancel(ai);
        if (before != null) record(ai, outcome, detail);
        child = null;
        before = null;
        ai.getNavigation().stop();
        ai.getBody().clearInputs();
        PrimitiveSnapshot end = PrimitiveAdapter.capture(ai);
        boolean reached = objectiveReached(end);
        if ("SUCCEEDED".equals(outcome) && !reached) {
            outcome = "OBJECTIVE_NOT_REACHED";
            detail = "actions finished but task postcondition is false";
        }
        status = "SUCCEEDED".equals(outcome) ? ActionStatus.SUCCESS : ActionStatus.FAILED;
        reason = detail;
        result = new PrimitiveResult(program.id(), outcome, detail, reached, transitions, end);
    }

    private boolean objectiveReached(PrimitiveSnapshot state) {
        return program.worldId().equals(state.worldId()) && program.objective().isAchieved(state);
    }
}
