package me.herry.minecraftAI.discord;

import java.io.IOException;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Narrow, explicit game questions use verified values. Other dialogue stays with the model; no game commands. */
public final class GroundedGameDialogue implements ResponsePipeline.Model {
    private enum Kind { STATUS, LOCATION, REASON, COUNT, HISTORY }
    private record Question(Kind kind, String material, String label) {}
    private static final Pattern STATUS = Pattern.compile("(?:지금|현재)?(?:뭐|뭘|무엇을)(?:해(?:요)?|하(?:세요|나요|니)|하고(?:있(?:어(?:요)?|나요|니)|계(?:세요|신가요))|하는중(?:이야|이에요|인가요))|(?:지금|현재)움직이고(?:계세요|있나요|있어(?:요)?)|(?:지금|현재)(?:게임)?상태(?:알려줘|알려주세요|어때요)");
    private static final Pattern LOCATION = Pattern.compile("(?:지금|현재)?(?:어디(?:에)?(?:있(?:어(?:요)?|나요|니)|계(?:세요|신가요))|어디(?:야|예요|인가요)|위치(?:가)?어디(?:야|예요|인가요)|좌표(?:알려줘|알려주세요))");
    private static final Pattern REASON = Pattern.compile("왜(?:그일|그거|그걸)(?:하고있어(?:요)?|하고계세요|하나요)|왜멈춰(?:있어(?:요)?|있나요|계세요)|(?:지금|현재)하는일(?:의)?이유(?:알려줘|알려주세요)");
    private static final Pattern HISTORY = Pattern.compile("(?:전에|지난번에|예전에|최근에)?(?:발견한|주운|캔)(?:돌|아이템|곡괭이)(?:의)?(?:이름|별명|사연)(?:은|이)?(?:뭐(?:야|예요|였나요|였어)|무엇인가요|알려줘|알려주세요)");
    private static final Pattern COUNT_END = Pattern.compile("(?:은|는|이|가)?(?:몇개(?:가지고|갖고)?(?:있어(?:요)?|있나요|있니|야|인가요)|(?:가지고|갖고)(?:있어(?:요)?|있나요|있니)|있어(?:요)?|있나요|있니)");
    private static final Map<String, String> ITEMS = Map.ofEntries(
            Map.entry("다이아몬드", "DIAMOND"), Map.entry("다이아", "DIAMOND"), Map.entry("조약돌", "COBBLESTONE"),
            Map.entry("석탄", "COAL"), Map.entry("철주괴", "IRON_INGOT"), Map.entry("철원석", "RAW_IRON"),
            Map.entry("금주괴", "GOLD_INGOT"), Map.entry("횃불", "TORCH"), Map.entry("철곡괭이", "IRON_PICKAXE"),
            Map.entry("돌곡괭이", "STONE_PICKAXE"), Map.entry("다이아몬드곡괭이", "DIAMOND_PICKAXE"));
    private final ResponsePipeline.Model model;
    private final Supplier<DiscordGameState.View> game;
    private final DiscordSettings settings;
    private final LongSupplier clock;
    public GroundedGameDialogue(ResponsePipeline.Model model, Supplier<DiscordGameState.View> game, DiscordSettings settings, LongSupplier clock) {
        this.model = java.util.Objects.requireNonNull(model); this.game = java.util.Objects.requireNonNull(game);
        this.settings = java.util.Objects.requireNonNull(settings); this.clock = java.util.Objects.requireNonNull(clock);
    }
    @Override public String respond(ResponsePipeline.Request request) throws Exception {
        if (!request.current().getAsBoolean()) throw new IOException("dialogue turn retired");
        if (request.permissionQuestion()) throw new IllegalArgumentException("permission questions are code-owned");
        var question = question(DialogueContext.currentInput(request));
        if (question == null) return model.respond(request);
        boolean casual = casual(request);
        String answer = answer(question, java.util.Objects.requireNonNull(game.get()), casual);
        if (!request.current().getAsBoolean()) throw new IOException("dialogue turn retired");
        return answer;
    }
    private static Question question(String text) {
        String normalized = CallWord.body(text).replaceAll("\\s+", "").replaceAll("[?!！？.。]+$", "");
        if (STATUS.matcher(normalized).matches()) return new Question(Kind.STATUS, "", "");
        if (LOCATION.matcher(normalized).matches()) return new Question(Kind.LOCATION, "", "");
        if (REASON.matcher(normalized).matches()) return new Question(Kind.REASON, "", "");
        if (HISTORY.matcher(normalized).matches()) return new Question(Kind.HISTORY, "", "");
        for (var item : ITEMS.entrySet()) {
            String body = normalized.replaceFirst("^(?:지금|현재)(?:인벤토리에)?", "");
            if (body.startsWith(item.getKey()) && COUNT_END.matcher(body.substring(item.getKey().length())).matches())
                return new Question(Kind.COUNT, item.getValue(), item.getKey());
        }
        return null;
    }
    private boolean casual(ResponsePipeline.Request request) {
        var subject = new DiscordMemory.Subject(settings.guildId(), settings.characterId(), request.turn().userId());
        return DiscordPersonalSettings.casual(subject, request.memory(), clock.getAsLong());
    }
    private static String answer(Question question, DiscordGameState.View view, boolean casual) {
        if (question.kind == Kind.HISTORY) return casual
                ? "최근 게임 상태만으로는 전에 발견한 돌이나 아이템의 이름·사연을 확인할 수 없어. 실제로 정한 이름이 있으면 알려 줘."
                : "최근 게임 상태만으로는 전에 발견한 돌이나 아이템의 이름·사연을 확인할 수 없어요. 실제로 정한 이름이 있으면 알려 주세요.";
        if (view.code() != DiscordGameState.Code.FRESH) return switch (view.code()) {
            case NOT_CONFIGURED -> casual ? "아직 내 게임 캐릭터가 연결되지 않아서 게임 상태를 확인할 수 없어."
                    : "아직 제 게임 캐릭터가 연결되지 않아서 게임 상태를 확인할 수 없어요.";
            case NOT_FOUND -> casual ? "지금은 내가 게임에 들어가 있지 않아서 게임 상태를 확인할 수 없어."
                    : "지금은 제가 게임에 들어가 있지 않아서 게임 상태를 확인할 수 없어요.";
            default -> casual ? "현재 게임 상태를 확인할 수 없어. 오래된 자료나 추측으로 지금 하는 일을 말하지 않을게."
                    : "현재 게임 상태를 확인할 수 없어요. 오래된 자료나 추측으로 지금 하는 일을 말하지 않을게요.";
        };
        // The configured game AI is Herry's own body, so game facts are told in the first person like every other answer.
        var state = view.snapshot(); String me = casual ? "나는" : "저는";
        return switch (question.kind) {
            case STATUS -> me + " 지금 " + switch (state.state()) {
                case "STOPPED" -> "자율 행동이 중지된 상태" + (casual ? "야." : "예요.");
                case "DEAD" -> "리스폰을 기다리는 중" + (casual ? "이야." : "이에요.");
                default -> state.activity() + (casual ? "이야." : "이에요.");
            } + " 체력은 " + number(state.health()) + ", 허기는 " + state.food() + (casual ? " 남았어." : " 남았어요.");
            case COUNT -> {
                int count = state.items().getOrDefault(question.material, 0);
                yield "지금 " + topic(question.label) + (count == 0 ? casual ? " 하나도 없어." : " 하나도 없어요."
                        : " " + count + "개 가지고 " + (casual ? "있어." : "있어요."));
            }
            case LOCATION -> me + " 지금 " + switch (state.dimension()) {
                case "NORMAL" -> "오버월드"; case "NETHER" -> "네더"; case "THE_END" -> "엔드"; default -> "별도 차원";
            } + "의 " + state.x() + ", " + state.y() + ", " + state.z() + (casual ? "에 있어." : "에 있어요.");
            case REASON -> state.reason().isEmpty() || !state.state().equals("RUNNING")
                    ? "지금 하는 일의 이유는 현재 자료에 " + (casual ? "없어." : "없어요.")
                    : (casual ? "내가 기록해 둔 이유는 이거야: " : "제가 기록해 둔 이유는 이래요: ") + state.reason();
            case HISTORY -> throw new IllegalStateException("history already handled");
        };
    }
    private static String number(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }
    private static String topic(String word) {
        char last = word.charAt(word.length() - 1);
        return word + (last >= '가' && last <= '힣' && (last - '가') % 28 != 0 ? "은" : "는");
    }
}
