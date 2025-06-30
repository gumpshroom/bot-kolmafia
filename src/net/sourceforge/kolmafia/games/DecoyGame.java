package net.sourceforge.kolmafia.games;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.StaticEntity;

/**
 * Implementation of Decoy's Dilemma game from the JavaScript chatbot
 */
public class DecoyGame {
    private final String host;
    private final int prizeAmount;
    private final ChatGameManager.GameStats stats;
    private final ChatGameManager manager;
    
    private volatile boolean active = false;
    private volatile String phase = "none"; // none, entry, answering, voting, finished
    private final List<String> participants = new ArrayList<>();
    private final Map<String, String> fakeAnswers = new ConcurrentHashMap<>();
    private final Map<String, Integer> guesses = new ConcurrentHashMap<>();
    private String question = "";
    private String realAnswer = "";
    private List<String> answers = new ArrayList<>();
    private ScheduledFuture<?> entryTask = null;
    private ScheduledFuture<?> answerTask = null;
    private ScheduledFuture<?> votingTask = null;
    
    public DecoyGame(String host, int prizeAmount, ChatGameManager.GameStats stats, ChatGameManager manager) {
        this.host = host;
        this.prizeAmount = prizeAmount;
        this.stats = stats;
        this.manager = manager;
    }
    
    public boolean start() {
        if (active) {
            return false;
        }
        
        // TODO: Check funding using gameUtils logic
        // For now, assume funding is valid
        
        active = true;
        phase = "entry";
        
        // Announce the game - exact message from original
        String announcement = String.format("Decoy's Dilemma by %s: buy tickets for next 5m. Prize: %s meat!",
            host, formatMeat(prizeAmount));
        manager.sendGamesMessage(announcement);
        
        // Schedule player collection after 5 minutes + 5 second buffer
        entryTask = manager.getScheduler().schedule(() -> {
            try {
                collectPlayers();
            } catch (Exception e) {
                handleGameError("collectPlayers", e);
            }
        }, 305, TimeUnit.SECONDS);
        
        RequestLogger.printLine("Decoy's Dilemma started by " + host + " for " + prizeAmount + " meat");
        return true;
    }
    
    public void handleChat(String sender, String message) {
        if (!active || !"voting".equals(phase)) {
            return;
        }
        
        String text = message.toLowerCase().trim();
        
        // Handle "guess N" commands
        if (text.startsWith("guess ")) {
            String[] parts = text.split("\\s+");
            if (parts.length >= 2) {
                try {
                    int guessNum = Integer.parseInt(parts[1]);
                    String senderLower = sender.toLowerCase();
                    
                    if (!participants.contains(senderLower)) {
                        return; // Not a participant
                    }
                    
                    if (guessNum >= 1 && guessNum <= answers.size()) {
                        guesses.put(senderLower, guessNum - 1); // Convert to 0-based index
                        String guessedAnswer = answers.get(guessNum - 1);
                        manager.sendPrivateMessage(sender, "Registered guess #" + guessNum + ": " + guessedAnswer);
                    } else {
                        manager.sendPrivateMessage(sender, "Invalid guess number. Choose 1-" + answers.size());
                    }
                } catch (NumberFormatException e) {
                    // Invalid number, ignore
                }
            }
        }
    }
    
    public void handleKmail(String sender, String content) {
        if (!active || !"answering".equals(phase)) {
            return;
        }
        
        String senderLower = sender.toLowerCase();
        if (!participants.contains(senderLower)) {
            return; // Not a participant
        }
        
        if (fakeAnswers.containsKey(senderLower)) {
            manager.sendPrivateMessage(sender, "You already submitted your answer.");
            return;
        }
        
        String answer = content.trim();
        if (answer.isEmpty()) {
            manager.sendPrivateMessage(sender, "Empty answer not allowed. Please send a fake answer.");
            return;
        }
        
        if (answer.length() > 200) {
            manager.sendPrivateMessage(sender, "Answer too long. Please keep it under 200 characters.");
            return;
        }
        
        fakeAnswers.put(senderLower, answer);
        manager.sendPrivateMessage(sender, "Got your fake answer: " + answer);
    }
    
    private void collectPlayers() {
        try {
            // TODO: Get participants from shop buyers like original
            // For now, simulate with empty list
            List<String> buyers = getUniqueBuyers();
            
            if (buyers.size() < 3) {
                manager.sendGamesMessage("Decoy's Dilemma cancelled - need at least 3 players. Only " + buyers.size() + " bought tickets.");
                cleanup();
                return;
            }
            
            participants.clear();
            participants.addAll(buyers);
            phase = "answering";
            
            // Get trivia question
            QuestionAnswer qa = fetchQuestionAnswer();
            question = qa.question;
            realAnswer = qa.realAnswer.toLowerCase().trim();
            
            manager.sendGamesMessage("QUESTION (" + participants.size() + " players): " + question);
            
            // Send private messages to all participants
            for (String participant : participants) {
                manager.sendPrivateMessage(participant, "Please PM me your FAKE answer within 2 minutes for: " + question);
            }
            
            // Schedule voting phase after 2 minutes + 5 second buffer
            answerTask = manager.getScheduler().schedule(() -> {
                try {
                    beginVoting();
                } catch (Exception e) {
                    handleGameError("beginVoting", e);
                }
            }, 125, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            handleGameError("collectPlayers", e);
        }
    }
    
    private void beginVoting() {
        try {
            phase = "voting";
            
            // Ensure every participant has an answer entry
            for (String participant : participants) {
                if (!fakeAnswers.containsKey(participant)) {
                    fakeAnswers.put(participant, "(no answer submitted)");
                }
            }
            
            // Collect all answers
            answers.clear();
            answers.add(realAnswer);
            answers.addAll(fakeAnswers.values());
            
            // Remove exact duplicates but keep original order preference for real answer
            Set<String> seen = new HashSet<>();
            List<String> uniqueAnswers = new ArrayList<>();
            for (String answer : answers) {
                String normalized = answer.toLowerCase().trim();
                if (!seen.contains(normalized)) {
                    seen.add(normalized);
                    uniqueAnswers.add(answer);
                }
            }
            
            answers = uniqueAnswers;
            
            // Shuffle the answers
            Collections.shuffle(answers);
            
            // Send voting message - exact format from original
            StringBuilder msg = new StringBuilder("VOTE! Type 'guess <#>' for the real answer: ");
            for (int i = 0; i < answers.size(); i++) {
                msg.append("[").append(i + 1).append("] ").append(answers.get(i)).append("  ");
            }
            manager.sendGamesMessage(msg.toString());
            
            // Schedule finalization after 2 minutes + 5 second buffer
            votingTask = manager.getScheduler().schedule(() -> {
                try {
                    finalizeDecoy();
                } catch (Exception e) {
                    handleGameError("finalizeDecoy", e);
                }
            }, 125, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            handleGameError("beginVoting", e);
        }
    }
    
    private void finalizeDecoy() {
        try {
            phase = "finished";
            
            // Calculate points for each participant
            Map<String, Integer> points = new HashMap<>();
            for (String participant : participants) {
                points.put(participant, 0);
            }
            
            // 2 points for guessing correctly
            for (Map.Entry<String, Integer> entry : guesses.entrySet()) {
                String player = entry.getKey();
                int guessIndex = entry.getValue();
                if (guessIndex >= 0 && guessIndex < answers.size()) {
                    String guessedAnswer = answers.get(guessIndex);
                    if (guessedAnswer.toLowerCase().trim().equals(realAnswer)) {
                        points.put(player, points.get(player) + 2);
                    }
                }
            }
            
            // 1 point for each person who guesses your fake answer
            for (Map.Entry<String, String> fakeEntry : fakeAnswers.entrySet()) {
                String fakePlayer = fakeEntry.getKey();
                String fakeAnswer = fakeEntry.getValue();
                
                for (Map.Entry<String, Integer> guessEntry : guesses.entrySet()) {
                    String voter = guessEntry.getKey();
                    int voterIndex = guessEntry.getValue();
                    
                    if (!voter.equals(fakePlayer) && voterIndex >= 0 && voterIndex < answers.size()) {
                        String votedAnswer = answers.get(voterIndex);
                        if (votedAnswer.equals(fakeAnswer)) {
                            points.put(fakePlayer, points.get(fakePlayer) + 1);
                        }
                    }
                }
            }
            
            // Create winners list sorted by points
            List<Winner> winners = new ArrayList<>();
            for (String participant : participants) {
                winners.add(new Winner(participant, points.get(participant)));
            }
            winners.sort((a, b) -> Integer.compare(b.points, a.points));
            
            // Announce real answer
            manager.sendGamesMessage("REAL ANSWER: " + realAnswer);
            
            // Award prizes using the exact logic from gameUtils
            awardPrizes(winners, prizeAmount);
            
            // Update stats
            stats.gamesCount++;
            manager.saveGameState();
            
            cleanup();
            
        } catch (Exception e) {
            handleGameError("finalizeDecoy", e);
        }
    }
    
    private void awardPrizes(List<Winner> winners, int prize) {
        try {
            if (winners.isEmpty()) {
                manager.sendGamesMessage("No winners this round.");
                return;
            }
            
            double[] shares = {0.60, 0.20, 0.10};
            int awarded = 0;
            List<String> messages = new ArrayList<>();
            
            // Group by points for tie handling
            Map<Integer, List<String>> pointGroups = new HashMap<>();
            for (int i = 0; i < Math.min(winners.size(), 3); i++) {
                Winner w = winners.get(i);
                pointGroups.computeIfAbsent(w.points, k -> new ArrayList<>()).add(w.player);
            }
            
            // Sort points in descending order
            List<Integer> sortedPoints = new ArrayList<>(pointGroups.keySet());
            sortedPoints.sort(Collections.reverseOrder());
            
            int positionIndex = 0;
            
            // Allocate top 3 positions
            for (int pts : sortedPoints) {
                if (positionIndex >= 3) break;
                
                List<String> players = pointGroups.get(pts);
                if (players != null && !players.isEmpty()) {
                    int share = (int) (prize * shares[positionIndex]);
                    int perPlayer = share / players.size();
                    
                    for (String player : players) {
                        String message = "You placed in the game! You receive " + formatMeat(perPlayer) + " meat.";
                        if (sendKmail(player, message, perPlayer)) {
                            messages.add(player + " gets " + formatMeat(perPlayer));
                            awarded += perPlayer;
                        } else {
                            messages.add(player + " prize failed - admin notified");
                            reportError("Prize payment failed to " + player, new Exception("kmail failed"));
                        }
                    }
                    
                    positionIndex += players.size();
                }
            }
            
            // Remainder to jackpot
            int remainder = prize - awarded;
            if (remainder > 0) {
                stats.jackpot += remainder;
                stats.jackpotStreak++;
                messages.add(formatMeat(remainder) + " meat added to jackpot");
            }
            
            if (!messages.isEmpty()) {
                manager.sendGamesMessage(String.join("; ", messages));
            }
            
        } catch (Exception e) {
            reportError("awardPrizes", e);
            manager.sendGamesMessage("Error distributing prizes - admin has been notified.");
        }
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
            // Send error report to admin (ggar)
            manager.sendKmail("ggar", "Game Bot Error Report", 0);
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
    
    private List<String> getUniqueBuyers() {
        // TODO: Implement shop log parsing like original
        // For now return empty list
        return new ArrayList<>();
    }
    
    private QuestionAnswer fetchQuestionAnswer() {
        // TODO: Implement API call to get trivia question
        // For now return fallback
        return new QuestionAnswer("What color is the sky at noon?", "blue");
    }
    
    private void handleGameError(String phase, Exception error) {
        try {
            reportError("Decoy game error in " + phase, error);
            manager.sendGamesMessage("Decoy's Dilemma encountered an error and has been cancelled. Sorry!");
            cleanup();
        } catch (Exception e) {
            // Final fallback
            resetState();
        }
    }
    
    private void cleanup() {
        try {
            cancelTasks();
            // TODO: Remove items from shop like original
        } catch (Exception e) {
            // Silent cleanup
        }
        resetState();
        manager.onDecoyComplete();
    }
    
    private void cancelTasks() {
        if (entryTask != null) {
            entryTask.cancel(false);
            entryTask = null;
        }
        if (answerTask != null) {
            answerTask.cancel(false);
            answerTask = null;
        }
        if (votingTask != null) {
            votingTask.cancel(false);
            votingTask = null;
        }
    }
    
    private void resetState() {
        active = false;
        phase = "none";
        participants.clear();
        fakeAnswers.clear();
        guesses.clear();
        question = "";
        realAnswer = "";
        answers.clear();
        cancelTasks();
    }
    
    public boolean isActive() {
        return active;
    }
    
    public String getStatus() {
        return phase + " phase";
    }
    
    public void emergencyStop() {
        cleanup();
    }
    
    // Helper classes
    private static class Winner {
        final String player;
        final int points;
        
        Winner(String player, int points) {
            this.player = player;
            this.points = points;
        }
    }
    
    private static class QuestionAnswer {
        final String question;
        final String realAnswer;
        
        QuestionAnswer(String question, String realAnswer) {
            this.question = question;
            this.realAnswer = realAnswer;
        }
    }
}
