package me.herry.minecraftAI.discord;

import java.util.Optional;
import java.util.regex.Pattern;

/** Whole, explicit private text corrections only. Old names and input text are never returned for storage. */
final class ConfirmedTextName {
    private static final String NAME = "([가-힣A-Za-z]{1,20}?)";
    private static final Pattern CORRECTION = Pattern.compile("^(?:내|제)\\s*이름(?:은|이)?\\s*[가-힣A-Za-z]{1,20}(?:이|가)?\\s*아니라\\s*"
            + NAME + "(?:이야|야|입니다|이에요|예요)[.!。！]*$");
    private static final Pattern CALL_CORRECTION = Pattern.compile("^(?:(?:내|제)\\s*이름(?:은|이)?\\s*)?[가-힣A-Za-z]{1,20}(?:이|가)?\\s*아니라\\s*"
            + NAME + "(?:이라고|라고)\\s*불러\\s*(?:줘|주세요)[.!。！]*$");
    private static final Pattern FUTURE_CALL = Pattern.compile("^(?:앞으로(?:는)?|이제부터(?:는)?)\\s*"
            + NAME + "(?:이라고|라고)\\s*불러\\s*(?:줘|주세요)[.!。！]*$");
    private static final Pattern PERSONAL_CALL = Pattern.compile("^(?:(?:앞으로|이제부터)\\s*)?(?:나|저)를\\s*"
            + NAME + "(?:이라고|라고)\\s*불러\\s*(?:줘|주세요)[.!。！]*$");
    private ConfirmedTextName() {}
    static Optional<String> read(String text) {
        if (text == null || text.length() > 100 || text.indexOf('?') >= 0 || text.indexOf('？') >= 0) return Optional.empty();
        var introduced = ConfirmedMemoryInput.introducedName(text, true);
        if (introduced.isPresent()) return introduced;
        for (var pattern : new Pattern[] { CORRECTION, CALL_CORRECTION, FUTURE_CALL, PERSONAL_CALL }) {
            var matcher = pattern.matcher(text.strip());
            if (matcher.matches()) return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }
}
