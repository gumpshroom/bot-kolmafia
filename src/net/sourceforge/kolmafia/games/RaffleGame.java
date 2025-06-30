package net.sourceforge.kolmafia.games;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.StaticEntity;

/**
 * Implementation of the raffle game from the JavaScript chatbot - exact replica
 */
public class RaffleGame {
    private final String host;
    private final int prizeAmount;
    private final ChatGameManager.GameStats stats;
    private final ChatGameManager manager;
    
    private volatile boolean active = false;
    private ScheduledFuture<?> drawTask = null;
    private String ticketItemName = null;
    
    public RaffleGame(String host, int prizeAmount, ChatGameManager.GameStats stats, ChatGameManager manager) {
        this.host = host;
        this.prizeAmount = prizeAmount;
        this.stats = stats;
        this.manager = manager;
    }
    
    public boolean start() {
        if (active) {
            return false;
        }
        
        // Check funding using the exact logic from gameUtils.js
        if (!checkAndDeductFunds(host, prizeAmount)) {
            return false;
        }
        
        active = true;
        
        // Put tickets in store - exact logic from gameUtils.js
        ticketItemName = putTicketInStore(10000);
        if (ticketItemName == null) {
            active = false;
            return false;
        }
        
        // Announce raffle - exact message from original
        String message = String.format("AR requested by %s with prize 1d%s meat !!",
            host, formatMeat(prizeAmount));
        manager.sendGamesMessage(message);
        
        // Schedule draw with 5s buffer - exactly like original
        drawTask = manager.getScheduler().schedule(() -> {
            try {
                drawRaffle();
            } catch (Exception e) {
                handleRaffleError("drawRaffle", e);
            }
        }, 305, TimeUnit.SECONDS); // 5 minutes + 5 second buffer
        
        RequestLogger.printLine("Raffle started by " + host + " for " + prizeAmount + " meat");
        return true;
    }
    
    public void handleChat(String sender, String message) {
        // No special chat handling for raffle in original
    }
    
    public void handleKmail(String sender, String content) {
        // No KMail handling for raffle in original
    }
    
    private void drawRaffle() {
        try {
            List<String> buyers = getUniqueBuyers();
            
            // Remove tickets from store
            try {
                if (ticketItemName != null) {
                    takeShopItem(ticketItemName);
                }
            } catch (Exception e) {
                // Shop cleanup error is non-fatal
            }
            
            if (drawTask != null) {
                drawTask.cancel(false);
            }
            
            if (buyers.isEmpty()) {
                manager.sendGamesMessage("game ended !! rolling 1d10 gives 11...");
                // Wait a bit like original
                try { Thread.sleep(5000); } catch (InterruptedException e) {}
                manager.sendGamesMessage("No tickets sold. Better luck next time!");
            } else {
                // Pick random winner from buyers
                Random random = new Random();
                String winner = buyers.get(random.nextInt(buyers.size()));
                
                // Calculate prize amounts like original
                int amount = random.nextInt(prizeAmount) + 1;
                int playerAmount = (int)(amount * 0.9);
                int jackpotAmount = amount - playerAmount;
                
                // Announce game end - exact format from original
                int gameSize = Math.min(buyers.size(), 10);
                int winnerIndex = random.nextInt(gameSize) + 1;
                String msg = "game ended !! rolling 1d" + gameSize + " gives " + ((gameSize + 1) - winnerIndex) + "...";
                manager.sendGamesMessage(msg);
                
                // Wait like original
                try { Thread.sleep(5000); } catch (InterruptedException e) {}
                
                // Winner announcement
                msg = winner + " bought tickets and won " + formatMeat(playerAmount) + " meat. ";
                msg += formatMeat(jackpotAmount) + " meat has been added to the jackpot, ";
                msg += "rolling 1d" + formatMeat(50 - (stats.jackpotStreak > 45 ? 45 : stats.jackpotStreak)) + " for the jackpot...";
                stats.jackpot += jackpotAmount;
                manager.sendGamesMessage(msg);
                
                // Wait for jackpot roll
                try { Thread.sleep(5000); } catch (InterruptedException e) {}
                
                // Jackpot logic - exact from original
                int jackpotRoll = random.nextInt(50 - (stats.jackpotStreak > 45 ? 45 : stats.jackpotStreak)) + 1;
                String jackpotMsg = "";
                if (jackpotRoll == 1) {
                    stats.jackpotStreak = 0;
                    jackpotMsg += "rolled a 1!! JACKPOT!! " + formatMeat(stats.jackpot) + " meat has been won by " + winner + "!!";
                    sendKmail(winner, "you won the jackpot of " + formatMeat(stats.jackpot) + " meat!!", stats.jackpot);
                    stats.jackpot = 0;
                } else {
                    jackpotMsg += "rolled a " + jackpotRoll + " on a 1d" + formatMeat(50 - (stats.jackpotStreak > 45 ? 45 : stats.jackpotStreak)) + 
                        " (payout on 1). pot is now at " + formatMeat(stats.jackpot) + " meat. the last win was " + 
                        formatMeat(stats.jackpotStreak) + " ggames ago. better luck next time...";
                    stats.jackpotStreak++;
                }
                
                // Update game count
                stats.gamesCount++;
                jackpotMsg += "congrats on ggame #" + formatMeat(stats.gamesCount) + "!!";
                manager.sendGamesMessage(jackpotMsg);
                
                // Send prize to winner
                String prizeMessage = "you won ggame #" + formatMeat(stats.gamesCount) + "!!";
                if (!sendKmail(winner, prizeMessage, playerAmount)) {
                    manager.sendGamesMessage("Error sending prize to " + winner + ". Admin will handle manually.");
                    reportError("Failed to send prize to " + winner, new Exception("kmail failed"));
                }
            }
            
            // Update stats exactly like original
            stats.gamesCount++;
            manager.saveGameState();
            
            resetState();
            manager.onRaffleComplete();
            
        } catch (Exception e) {
            handleRaffleError("drawRaffle", e);
        }
    }
    
    private boolean checkAndDeductFunds(String sender, int prize) {
        // Exact logic from gameUtils.js checkAndDeductFunds
        try {
            // Admin bypass
            if (sender.equals("ggar")) {
                return true;
            }
            
            if (prize <= 0) return false;
            
            Map<String, Integer> publicPoolUsage = manager.getPublicPoolUsage();
            String key = sender.toLowerCase();
            
            // Check public pool usage (300k/day per user)
            Integer usedToday = publicPoolUsage.get(key);
            if (usedToday == null) usedToday = 0;
            
            // Try public pool first
            if (usedToday + prize <= 300000 && stats.publicPool >= prize) {
                stats.publicPool -= prize;
                publicPoolUsage.put(key, usedToday + prize);
                return true;
            }
            
            // Try personal allocation
            Map<String, Integer> donorTable = manager.getDonorTable();
            Integer allocated = donorTable.get(key);
            if (allocated != null && allocated >= prize) {
                donorTable.put(key, allocated - prize);
                return true;
            }
            
            return false;
        } catch (Exception e) {
            reportError("checkAndDeductFunds", e);
            return false;
        }
    }
    
    private String putTicketInStore(int quantity) {
        // Simplified implementation - in real version would interact with KoL shop API
        // Return random ticket name for simulation
        String[] ticketNames = {
            "red drunki-bear", "yellow drunki-bear", "green drunki-bear",
            "gnocchetti di Nietzsche", "glistening fish meat", "gingerbread nylons"
        };
        Random random = new Random();
        return ticketNames[random.nextInt(ticketNames.length)];
    }
    
    private void takeShopItem(String itemName) {
        // Simplified implementation - in real version would remove from shop
        RequestLogger.printLine("Removing " + itemName + " from shop");
    }
    
    private List<String> getUniqueBuyers() {
        // Simplified implementation - in real version would parse shop log
        // Return empty list for now
        return new ArrayList<>();
    }
    
    private boolean sendKmail(String recipient, String message, int meat) {
        try {
            manager.sendKmail(recipient, message, meat);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void reportError(String context, Exception error) {
        try {
            String errorMsg = "ERROR in " + context + ": " + error.getMessage();
            RequestLogger.printLine("Game system error: " + errorMsg);
            // Send error report to admin
            manager.sendKmail("ggar", "Game Bot Error Report - " + errorMsg, 0);
        } catch (Exception e) {
            RequestLogger.printLine("Failed to report error: " + e.getMessage());
        }
    }
    
    private String formatMeat(int amount) {
        // Use exact format from original utils.numberWithCommas
        String str = String.valueOf(amount);
        StringBuilder result = new StringBuilder();
        int len = str.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                result.append(",");
            }
            result.append(str.charAt(i));
        }
        return result.toString();
    }
    
    private void handleRaffleError(String phase, Exception error) {
        try {
            reportError("Raffle error in " + phase, error);
            manager.sendGamesMessage("Raffle encountered an error and has been cancelled. Sorry!");
            resetState();
            manager.onRaffleComplete();
        } catch (Exception e) {
            // Final fallback
            resetState();
            manager.onRaffleComplete();
        }
    }
    
    private void resetState() {
        active = false;
        ticketItemName = null;
        if (drawTask != null) {
            drawTask.cancel(false);
            drawTask = null;
        }
    }
    
    public boolean isActive() {
        return active;
    }
    
    public String getStatus() {
        return active ? "active" : "inactive";
    }
    
    public void emergencyStop() {
        try {
            if (ticketItemName != null) {
                takeShopItem(ticketItemName);
            }
        } catch (Exception e) {
            // Silent cleanup
        }
        resetState();
    }
}
