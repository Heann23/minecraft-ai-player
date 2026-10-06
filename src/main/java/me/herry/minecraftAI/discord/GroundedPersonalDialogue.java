package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import static me.herry.minecraftAI.discord.DiscordMemory.*;

/** Answers narrow personal-setting questions from the caller's confirmed facts. Never writes memory. */
final class GroundedPersonalDialogue implements ResponsePipeline.Model {
    private static final Pattern NAME_QUESTION = Pattern.compile("(?:내|제)(?:이름|호칭)(?:은|이)?(?:뭐(?:야|예요|였지|였죠)|무엇인가요|알려줘|알려주세요|기억(?:해|해요|하세요|하나요))");
    private static final Pattern SPEECH_QUESTION = Pattern.compile("(?:내|제)말투설정(?:은|이)?(?:뭐야|뭐예요|무엇인가요|알려줘|알려주세요)|(?:내가|제가)?반말(?:을)?허락했(?:나요|니|어)|(?:내가|제가)?존댓말(?:로)?(?:말해달라고|해달라고)했(?:나요|니|어)");
    private static final Pattern JOKE_QUESTION = Pattern.compile("(?:내|제)(?:장난|농담)설정(?:은|이)?(?:뭐야|뭐예요|무엇인가요|알려줘|알려주세요)|(?:내가|제가)?(?:장난|농담)(?:을)?(?:하지말라고|그만하라고|해도된다고)했(?:나요|니|어)");
    private final ResponsePipeline.Model model;
    private final DiscordSettings settings;
    private final LongSupplier clock;
    GroundedPersonalDialogue(ResponsePipeline.Model model, DiscordSettings settings, LongSupplier clock) {
        this.model = Objects.requireNonNull(model); this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public String respond(ResponsePipeline.Request request) throws Exception {
        if (!request.current().getAsBoolean()) throw new IOException("dialogue turn retired");
        if (request.permissionQuestion()) throw new IllegalArgumentException("permission questions are code-owned");
        String input = CallWord.body(DialogueContext.currentInput(request)).replaceAll("\\s+", "").replaceAll("[?!！？.。]+$", "");
        Kind kind = NAME_QUESTION.matcher(input).matches() ? Kind.NAME
                : SPEECH_QUESTION.matcher(input).matches() ? Kind.SPEECH_AGREEMENT
                : JOKE_QUESTION.matcher(input).matches() ? Kind.AVOID_JOKE : null;
        if (kind == null) return model.respond(request);
        long now = clock.getAsLong();
        var subject = new Subject(settings.guildId(), settings.characterId(), request.turn().userId());
        String label = switch (kind) { case NAME -> "preferred"; case SPEECH_AGREEMENT -> "casual"; case AVOID_JOKE -> "all"; default -> throw new IllegalStateException("personal query kind"); };
        String value = request.memory().stream().filter(fact -> fact.key().subject().equals(subject)
                && fact.key().kind() == kind && fact.key().label().equals(label)
                && fact.evidence() == Evidence.EXPLICIT && !fact.expired(now))
                .max(Comparator.comparingLong(Fact::revision)).map(Fact::value).orElse("");
        boolean casual = DiscordPersonalSettings.casual(subject, request.memory(), now);
        String answer = switch (kind) {
            case NAME -> nameAnswer(value, casual);
            case SPEECH_AGREEMENT -> speechAnswer(value, casual);
            case AVOID_JOKE -> jokeAnswer(value, casual);
            default -> throw new IllegalStateException("personal query kind");
        };
        if (!request.current().getAsBoolean()) throw new IOException("dialogue turn retired");
        return answer;
    }
    private static String nameAnswer(String name, boolean casual) {
        char last = name.isEmpty() ? ' ' : name.charAt(name.length() - 1);
        boolean finalConsonant = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
        return name.matches("[가-힣A-Za-z]{1,20}")
                ? "저장한 호칭은 " + name + (casual ? finalConsonant ? "이야." : "야." : finalConsonant ? "이에요." : "예요.")
                : casual ? "아직 확인해서 저장한 네 호칭이 없어. 어떻게 부르면 될지 알려 줘."
                : "아직 확인해서 저장한 호칭이 없어요. 어떻게 부르면 될지 알려 주세요.";
    }
    private static String speechAnswer(String value, boolean casual) {
        return switch (value) {
            case "ALLOWED" -> casual ? "나한테 반말을 허락한 걸로 기억하고 있어." : "반말을 허락한 걸로 저장되어 있어요.";
            case "REFUSED" -> "존댓말을 요청한 걸로 기억하고 있어요. 존댓말로 이야기할게요.";
            case "" -> "확정해서 저장한 말투 설정은 없어요. 기본 존댓말로 이야기할게요.";
            default -> "저장한 말투 설정을 확인할 수 없어요. 존댓말로 이야기할게요.";
        };
    }
    private static String jokeAnswer(String value, boolean casual) {
        return switch (value) {
            case "AVOID" -> casual ? "장난은 멈추라고 한 걸로 기억하고 있어. 담백하게 이야기할게." : "장난은 멈추라고 한 걸로 기억하고 있어요. 담백하게 이야기할게요.";
            case "ALLOWED" -> casual ? "가벼운 장난은 허용한 걸로 기억하고 있어. 진지한 이야기에서는 줄일게." : "가벼운 장난은 허용한 걸로 기억하고 있어요. 진지한 이야기에서는 줄일게요.";
            case "" -> casual ? "확정해서 저장한 장난 설정은 없어." : "확정해서 저장한 장난 설정은 없어요.";
            default -> casual ? "저장한 장난 설정을 확인할 수 없어." : "저장한 장난 설정을 확인할 수 없어요.";
        };
    }
}
