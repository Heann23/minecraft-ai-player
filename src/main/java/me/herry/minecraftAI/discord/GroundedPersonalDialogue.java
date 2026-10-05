package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import static me.herry.minecraftAI.discord.DiscordMemory.*;

/** Answers narrow, first-person name questions from the caller's confirmed facts. Never writes memory. */
final class GroundedPersonalDialogue implements ResponsePipeline.Model {
    private static final Pattern NAME_QUESTION = Pattern.compile("(?:내|제)(?:이름|호칭)(?:은|이)?(?:뭐(?:야|예요|였지|였죠)|무엇인가요|알려줘|알려주세요|기억(?:해|해요|하세요|하나요))");
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
        String input = DialogueContext.currentInput(request).strip()
                .replaceFirst("(?iu)^(?:해리|Herry)(?:님|씨|야|아)?(?:\\s*[,，:]\\s*|\\s+)", "")
                .replaceAll("\\s+", "").replaceAll("[?!！？.。]+$", "");
        if (!NAME_QUESTION.matcher(input).matches()) return model.respond(request);
        long now = clock.getAsLong();
        var subject = new Subject(settings.guildId(), settings.characterId(), request.turn().userId());
        String name = request.memory().stream().filter(fact -> fact.key().subject().equals(subject)
                && fact.key().kind() == Kind.NAME && fact.key().label().equals("preferred")
                && fact.evidence() == Evidence.EXPLICIT && !fact.expired(now))
                .max(Comparator.comparingLong(Fact::revision)).map(Fact::value).orElse("");
        boolean casual = DiscordPersonalSettings.casual(subject, request.memory(), now);
        char last = name.isEmpty() ? ' ' : name.charAt(name.length() - 1);
        boolean finalConsonant = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
        String answer = name.matches("[가-힣A-Za-z]{1,20}")
                ? "저장한 호칭은 " + name + (casual ? finalConsonant ? "이야." : "야." : finalConsonant ? "이에요." : "예요.")
                : casual ? "아직 확인해서 저장한 네 호칭이 없어. 어떻게 부르면 될지 알려 줘."
                : "아직 확인해서 저장한 호칭이 없어요. 어떻게 부르면 될지 알려 주세요.";
        if (!request.current().getAsBoolean()) throw new IOException("dialogue turn retired");
        return answer;
    }
}
