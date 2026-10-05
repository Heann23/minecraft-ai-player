package me.herry.minecraftAI.discord;

import club.minnced.discord.jdave.ffi.LibDave;
import club.minnced.opus.util.OpusLibrary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real native loading without a Discord token or network connection. Runs on the CI platform too. */
class DiscordNativeTest {
    @Test void daveLibraryLoadsOnCurrentPlatformAndSupportsAProtocol() {
        assertTrue(LibDave.getMaxSupportedProtocolVersion() > 0);
    }
    @Test void opusLibraryLoadsFromPackagedPlatformResources() throws Exception {
        assertTrue(OpusLibrary.isSupportedPlatform());
        if (!OpusLibrary.isInitialized()) assertTrue(OpusLibrary.loadFromJar());
        assertTrue(OpusLibrary.isInitialized());
    }
}
