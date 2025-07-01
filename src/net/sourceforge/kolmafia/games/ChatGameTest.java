package net.sourceforge.kolmafia.games;

import net.sourceforge.kolmafia.RequestLogger;

/** Test class to verify ChatGameManager functionality */
public class ChatGameTest {

  public static void main(String[] args) {
    try {
      // Initialize the chat game manager
      ChatGameManager manager = ChatGameManager.getInstance();
      manager.start();

      RequestLogger.printLine("ChatGameManager started successfully");

      // Test basic commands
      testCommands(manager);

      // Stop the manager
      manager.stop();
      RequestLogger.printLine("ChatGameManager test completed");

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void testCommands(ChatGameManager manager) {
    RequestLogger.printLine("testing basic commands...");

    // Test help command
    manager.handleChatMessage("testuser", "help");

    // Test roll command (valid and invalid)
    manager.handleChatMessage("testuser", "roll 1d100");
    manager.handleChatMessage("testuser", "roll 2d20"); // should get unsupported message
    manager.handleChatMessage("testuser", "roll"); // missing argument

    // Test meat info command
    manager.handleChatMessage("testuser", "howmuchmeat");

    // Test host limit command
    manager.handleChatMessage("testuser", "hostlimit");

    // Test jackpot command
    manager.handleChatMessage("testuser", "jackpot");

    // Test howmanygames command
    manager.handleChatMessage("testuser", "howmanygames");

    // Test unknown command
    manager.handleChatMessage("testuser", "foobar");

    // Test admin commands (should be denied for non-admin)
    manager.handleChatMessage("testuser", "exec ls");
    manager.handleChatMessage("testuser", "setdonorlevel testuser 100000");
    manager.handleChatMessage("testuser", "setjackpot 500000");
    manager.handleChatMessage("testuser", "restock 10");

    // Test raffle/host command (should fail if not enough funds)
    manager.handleChatMessage("testuser", "host 1000000");
    manager.handleChatMessage("testuser", "host"); // missing argument
    manager.handleChatMessage("testuser", "host 10000"); // below min

    // Test decoy command (should fail if not enough funds)
    manager.handleChatMessage("testuser", "decoy 1000000");
    manager.handleChatMessage("testuser", "decoy"); // missing argument
    manager.handleChatMessage("testuser", "decoy 10000"); // below min

    // Test admin commands as admin
    manager.handleChatMessage("ggar", "exec echo hi");
    manager.handleChatMessage("ggar", "setdonorlevel testuser 100000");
    manager.handleChatMessage("ggar", "setjackpot 500000");
    manager.handleChatMessage("ggar", "restock 10");

    // Test emergency reset
    manager.handleChatMessage("ggar", "emergency");

    RequestLogger.printLine("command tests completed");
  }
}
