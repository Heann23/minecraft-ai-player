package me.herry.minecraftAI.discord;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordVoiceCommandsTest {
    @Test void onlyServerManagersCanControlAnotherPersonsVoiceSession() {
        for (String action : Set.of("leave", "resume", "quiet", "listen", "backup", "backups", "restore")) {
            assertFalse(DiscordVoiceCommands.allowed("guild", "guild", action, false));
            assertTrue(DiscordVoiceCommands.allowed("guild", "guild", action, true));
        }
        for (String action : Set.of("status", "name", "speech", "forget", "chat", "game")) assertTrue(DiscordVoiceCommands.allowed("guild", "guild", action, false));
    }
    @Test void wrongGuildUnknownCommandAndMissingScopeCannotBeAuthorized() {
        assertFalse(DiscordVoiceCommands.allowed("guild", "other", "leave", true));
        assertFalse(DiscordVoiceCommands.allowed(null, null, "status", true));
        assertFalse(DiscordVoiceCommands.allowed("guild", "guild", "delete-other", true));
        assertFalse(DiscordVoiceCommands.allowed("guild", "guild", null, true));
    }
    @Test void personalCommandsExposeNoTargetUserOrGameExecutionOption() {
        var definition = DiscordVoiceCommands.definition();
        assertEquals("herry", definition.getName()); assertEquals(13, definition.getSubcommands().size());
        for (var command : definition.getSubcommands()) {
            assertTrue(command.getOptions().stream().noneMatch(option -> option.getName().equals("user") || option.getName().equals("target") || option.getName().equals("ai")));
            if (command.getName().equals("name")) assertEquals(20, command.getOptions().getFirst().getMaxLength());
            if (command.getName().equals("speech")) assertTrue(command.getOptions().getFirst().isRequired());
            if (command.getName().equals("restore")) {
                assertEquals(2, command.getOptions().size()); assertTrue(command.getOptions().stream().allMatch(option -> option.isRequired()));
                assertEquals(72, command.getOptions().getFirst().getMaxLength());
                assertEquals("confirm", command.getOptions().get(1).getName());
            }
        }
    }
}
