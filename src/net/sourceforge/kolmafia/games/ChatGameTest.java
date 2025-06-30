package net.sourceforge.kolmafia.games;

import net.sourceforge.kolmafia.RequestLogger;

/**
 * Test class to verify ChatGameManager functionality
 */
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
        RequestLogger.printLine("Testing basic commands...");
        
        // Test help command
        manager.handleChatMessage("testuser", "help");
        
        // Test roll command
        manager.handleChatMessage("testuser", "roll 1d100");
        
        // Test meat info command
        manager.handleChatMessage("testuser", "howmuchmeat");
        
        // Test host limit command
        manager.handleChatMessage("testuser", "hostlimit");
        
        // Test jackpot command
        manager.handleChatMessage("testuser", "jackpot");
        
        // Test admin commands (should be denied for non-admin)
        manager.handleChatMessage("testuser", "exec ls");
        
        RequestLogger.printLine("Command tests completed");
    }
}
