package me.herry.minecraftAI.discord;

import java.util.Set;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordVoiceCommandsTest {
    @Test void onlyServerManagersCanControlAnotherPersonsVoiceSession() {
        for (String action : Set.of("leave", "resume", "quiet", "listen", "backup", "backups", "restore", "config")) {
            assertFalse(DiscordVoiceCommands.allowed("guild", "guild", action, false));
            assertTrue(DiscordVoiceCommands.allowed("guild", "guild", action, true));
        }
        for (String action : Set.of("status", "me", "name", "speech", "joke", "forget", "chat", "game", "cancel", "reset")) {
            assertTrue(DiscordVoiceCommands.allowed("guild", "guild", action, false));
            assertFalse(DiscordVoiceCommands.allowed("guild", "other", action, true));
        }
    }
    @Test void wrongGuildUnknownCommandAndMissingScopeCannotBeAuthorized() {
        assertFalse(DiscordVoiceCommands.allowed("guild", "other", "leave", true));
        assertFalse(DiscordVoiceCommands.allowed(null, null, "status", true));
        assertFalse(DiscordVoiceCommands.allowed("guild", "guild", "delete-other", true));
        assertFalse(DiscordVoiceCommands.allowed("guild", "guild", null, true));
    }
    @Test void personalCommandsExposeNoTargetUserOrGameExecutionOption() {
        var definition = DiscordVoiceCommands.definition();
        assertEquals("herry", definition.getName()); assertEquals(18, definition.getSubcommands().size());
        assertEquals(Set.of("status", "me", "game", "chat", "cancel", "reset", "forget", "name", "speech", "joke",
                "leave", "resume", "quiet", "listen", "backup", "backups", "restore", "config"),
                definition.getSubcommands().stream().map(command -> command.getName()).collect(Collectors.toSet()));
        for (var command : definition.getSubcommands()) {
            if (command.getName().equals("me")) assertTrue(command.getOptions().isEmpty());
            assertTrue(command.getOptions().stream().noneMatch(option -> option.getName().equals("user") || option.getName().equals("target") || option.getName().equals("ai")));
            if (command.getName().equals("name")) assertEquals(20, command.getOptions().getFirst().getMaxLength());
            if (command.getName().equals("speech")) assertTrue(command.getOptions().getFirst().isRequired());
            if (command.getName().equals("joke")) {
                assertEquals(1, command.getOptions().size()); assertTrue(command.getOptions().getFirst().isRequired());
                assertEquals("allowed", command.getOptions().getFirst().getName());
                assertEquals(net.dv8tion.jda.api.interactions.commands.OptionType.BOOLEAN, command.getOptions().getFirst().getType());
            }
            if (command.getName().equals("restore")) {
                assertEquals(2, command.getOptions().size()); assertTrue(command.getOptions().stream().allMatch(option -> option.isRequired()));
                assertEquals(72, command.getOptions().getFirst().getMaxLength());
                assertEquals("confirm", command.getOptions().get(1).getName());
            }
        }
    }
    @Test void forgetOffersOnlyOptionalPersonalMemoryScopes() {
        var command = DiscordVoiceCommands.definition().getSubcommands().stream()
                .filter(value -> value.getName().equals("forget")).findFirst().orElseThrow();
        assertEquals(1, command.getOptions().size());
        var scope = command.getOptions().getFirst();
        assertEquals("scope", scope.getName());
        assertEquals(OptionType.STRING, scope.getType());
        assertFalse(scope.isRequired(), "Omitting scope must remain compatible with forgetting all of one's memories");
        assertEquals(4, scope.getChoices().size());
        assertEquals(Set.of("all", "name", "speech", "joke"),
                scope.getChoices().stream().map(choice -> choice.getAsString()).collect(Collectors.toSet()));
    }
    @Test void cancelAndResetCannotTargetAnotherPersonOrAcceptExecutionArguments() {
        for (String action : Set.of("cancel", "reset")) {
            var command = DiscordVoiceCommands.definition().getSubcommands().stream()
                    .filter(value -> value.getName().equals(action)).findFirst().orElseThrow();
            assertTrue(command.getOptions().isEmpty());
            assertTrue(DiscordVoiceCommands.allowed("guild", "guild", action, false));
            assertFalse(DiscordVoiceCommands.allowed("guild", "other", action, true));
            assertFalse(DiscordVoiceCommands.allowed(null, null, action, true));
        }
    }
}
