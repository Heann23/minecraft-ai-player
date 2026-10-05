package me.herry.minecraftAI.ai.experience;

import me.herry.minecraftAI.ai.observation.Observation;
import me.herry.minecraftAI.ai.primitive.PrimitiveTarget;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 학습용으로 남기는 기록의 모양. 한 줄에 기록 하나(JSON)이고, "type" 으로 어느 기록인지 가린다.
 *
 * 한 에피소드는 episode_start 로 시작해서 episode_end 로 끝난다. 그 사이에 판단(decision)이 번호 순으로 있고,
 * 판단마다 그 계획의 행동 결과(action)와 계획이 어떻게 끝났는지(outcome)가 같은 decisionId 로 이어진다.
 * 판단 사이에 있었던 일(event)은 틱으로 순서를 맞춘다.
 *
 * 보상은 여기에 적지 않는다. 있었던 일만 적어 두면 보상은 나중에 어떤 식으로든 다시 계산할 수 있다.
 * 필드를 더하거나 뜻을 바꾸면 SCHEMA_VERSION 을 올린다.
 */
public final class Experience {
    public static final int SCHEMA_VERSION = 1;

    private Experience() {
    }

    // 에피소드가 시작된 까닭
    public enum StartReason {
        // 자율 행동을 켬 (서버를 다시 켠 뒤 이어서 움직이는 것 포함. aiTicks 가 0 보다 크면 이어서 하는 것이다)
        START,
        // 죽은 뒤 되살아남
        RESPAWN
    }

    // 에피소드가 끝난 까닭. 성공, 죽음, 사람이 멈춤, 서버가 꺼져서 끊김을 섞지 않는다.
    public enum EndReason {
        // 최종 목표를 이룸 (엔더 드래곤 처치)
        COMPLETED,
        DEATH,
        // 관리자가 자율 행동을 끔
        STOPPED,
        // AI 가 서버에서 제거됨
        REMOVED,
        // 서버나 플러그인이 꺼져서 하던 중에 끊김
        SHUTDOWN,
        // 플러그인 안의 오류로 AI 가 멈춤
        ERROR
    }

    // 계획 하나가 어떻게 끝났는지
    public enum Outcome {
        // 모든 행동이 성공함
        SUCCEEDED,
        // 행동 하나가 실패해서 나머지를 버림
        FAILED,
        // 더 급한 목표나 사람의 부탁 때문에 하던 중에 그만둠
        INTERRUPTED,
        // 계획을 세우지 못함
        NO_PLAN
    }

    /**
     * @param aiTicks     AI 가 켜진 뒤로 센 틱. 0 보다 크면 전에 하던 것을 이어서 하는 에피소드다.
     * @param wallTimeMs  실제 시각 (1970년부터의 밀리초)
     * @param worldSeed   시작한 월드의 시드. 같은 지형에서 다시 해 볼 때 쓴다.
     * @param shadowPolicy 같이 돌리는 후보 정책의 이름과 버전 ("이름@버전"). 없으면 빈 문자열
     */
    public record EpisodeStart(String type, int schemaVersion, int observationVersion, String episodeId, String ai,
                               String pluginVersion, StartReason reason, long aiTicks, long wallTimeMs, long worldSeed,
                               String shadowPolicy) {
    }

    /**
     * 판단 하나: 그때 알고 있던 것, Teacher(기존 규칙)가 고른 것, 후보 정책이 골랐을 것, 실제로 실행하기로 한 것.
     * 실제로 실행하는 것은 언제나 Teacher 가 고른 것이다. 후보의 판단은 견주어 보려고 적기만 한다.
     *
     * @param shadow 후보 정책을 돌리지 않으면 null
     */
    public record Decision(String type, String episodeId, long decisionId, long tick, Observation observation,
                           TeacherChoice teacher, @Nullable ShadowChoice shadow, Executed executed) {
    }

    /**
     * @param origin     AUTONOMOUS (스스로 고름), REQUESTED (사람이 부탁함), FORCED (관리자가 고정함).
     *                   스스로 고른 것만 Teacher 의 판단이다. 나머지는 사람이 정한 것이라 흉내 낼 대상이 아니다.
     * @param candidates 그때 점수가 높았던 목표들 (점수 순)
     */
    public record TeacherChoice(String goal, double score, String origin, List<Candidate> candidates) {
    }

    // resting: 계속 실패해서 쉬는 중이라 고를 수 없던 목표인지
    public record Candidate(String goal, double score, boolean resting) {
    }

    /**
     * @param goal          후보 정책이 고른 목표. 고르지 못했으면(오류, 판단 보류) 빈 문자열
     * @param scores        목표별 점수나 확률 (정책이 준 것만)
     * @param latencyMicros 판단에 걸린 시간
     * @param error         판단하다 난 오류. 없으면 빈 문자열
     */
    public record ShadowChoice(String policy, String version, String goal, Map<String, Double> scores, long latencyMicros,
                               String error) {
    }

    /**
     * @param goal     실행한 목표 (GoalType 의 이름)
     * @param goalSpec 그 목표를 "무엇을 얼마나"로 적은 것
     * @param skill    계획을 세운 스킬. 세우지 못했으면 빈 문자열
     * @param escaping 목표의 계획이 아니라, 갇힌 곳에서 길을 파서 나오는 계획인지
     */
    public record Executed(String goal, String goalSpec, String goalCategory, String skill, boolean escaping,
                           List<PlannedAction> actions) {
    }

    /**
     * @param primitive 기본 행동이면 그 종류 (PrimitiveType 의 이름). 여러 종류를 섞어 쓰는 행동이면 빈 문자열
     */
    public record PlannedAction(String name, String primitive, PrimitiveTarget target) {
    }

    /**
     * 행동 하나가 끝남.
     *
     * @param index  계획 안에서의 순서 (0 부터)
     * @param status SUCCESS 또는 FAILED
     */
    public record ActionResult(String type, String episodeId, long decisionId, int index, String name, long startTick,
                               long endTick, String status, String failReason) {
    }

    /**
     * 계획이 끝남.
     *
     * @param tick         계획이 끝난 틱
     * @param detail       실패한 행동과 원인, 또는 그만두게 된 까닭
     * @param goalAchieved 목표의 완료 조건이 채워졌는지. 계획이 끝난 뒤의 첫 관측으로 본다.
     *                     에피소드가 바로 끝나서 그 뒤의 관측이 없으면 끝나기 전의 마지막 관측으로 본다 (judgedAtTick 이 tick 보다 이르다).
     * @param judgedAtTick 완료 조건을 본 관측의 틱. 관측이 하나도 없었으면 -1
     */
    public record PlanOutcome(String type, String episodeId, long decisionId, long tick, Outcome outcome, String detail,
                              boolean goalAchieved, long judgedAtTick) {
    }

    /**
     * 판단 사이에 있었던 일.
     *
     * @param kind MILESTONE (새로 이룬 항목), STAGE (큰 단계가 바뀜)
     */
    public record Event(String type, String episodeId, long tick, String kind, String value) {
    }

    /**
     * @param finalObservation 마지막으로 남긴 관측. 한 번도 판단하지 않고 끝났으면 null
     */
    public record EpisodeEnd(String type, String episodeId, EndReason reason, long aiTicks, long wallTimeMs, long decisions,
                             @Nullable Observation finalObservation) {
    }
}
