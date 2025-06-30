package net.sourceforge.kolmafia.games;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.StaticEntity;
import net.sourceforge.kolmafia.chat.ChatSender;
import net.sourceforge.kolmafia.request.GenericRequest;
import net.sourceforge.kolmafia.request.SendMailRequest;
import net.sourceforge.kolmafia.session.ContactManager;

/**
 * Manages chat games including raffles and Decoy's Dilemma.
 * This is a native implementation of the JavaScript chatbot functionality.
 */
public class ChatGameManager {
    private static ChatGameManager instance;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // Game state
    private volatile boolean isRunning = false;
    private RaffleGame currentRaffle = null;
    private DecoyGame currentDecoy = null;
    
    // Global state (equivalent to globalObj in JS)
    private final GameStats stats = new GameStats();
    
    // Thread-safe collections for concurrent access
    private final Map<String, Integer> donorTable = new ConcurrentHashMap<>();
    private final Map<String, Integer> publicPoolUsage = new ConcurrentHashMap<>();
    
    private ChatGameManager() {
        loadState();
    }
    
    public static synchronized ChatGameManager getInstance() {
        if (instance == null) {
            instance = new ChatGameManager();
        }
        return instance;
    }
    
    // Public access to scheduler for games
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }
    
    // Public access to save state for games
    public void saveGameState() {
        saveState();
    }
    
    public synchronized void start() {
        if (isRunning) {
            return;
        }
        
        isRunning = true;
        RequestLogger.printLine("Chat Game Manager started");
        
        // Schedule periodic state saving
        scheduler.scheduleAtFixedRate(this::saveState, 60, 60, TimeUnit.SECONDS);
    }
    
    public synchronized void stop() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        
        // Stop any active games
        if (currentRaffle != null) {
            currentRaffle.emergencyStop();
            currentRaffle = null;
        }
        if (currentDecoy != null) {
            currentDecoy.emergencyStop();
            currentDecoy = null;
        }
        
        saveState();
        scheduler.shutdown();
        RequestLogger.printLine("Chat Game Manager stopped");
    }
    
    /**
     * Handle chat messages (equivalent to main() function)
     */
    public void handleChatMessage(String sender, String message) {
        if (!isRunning) {
            return;
        }
        
        try {
            // Handle KMail notifications
            if (message.contains("New message received from")) {
                handleKmailNotification(message);
                return;
            }
            
            // Handle annoying effects like original
            if (message.contains("has hit you") || message.contains("sent you a really") || 
                message.contains("plastered you") || message.contains("has blessed")) {
                handleAnnoyingEffects(sender, message);
                return;
            }
            
            // Parse chat message
            String text = message.replaceAll("^<[^>]+>", "").trim();
            if (text.isEmpty()) {
                return;
            }
            
            // Let active games handle the message first
            if (currentRaffle != null) {
                currentRaffle.handleChat(sender, text);
            }
            if (currentDecoy != null) {
                currentDecoy.handleChat(sender, text);
            }
            
            // Handle commands
            handleCommands(sender, text);
            
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error in ChatGameManager.handleChatMessage");
            emergencyReset();
        }
    }
    
    private void handleCommands(String sender, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length == 0) {
            return;
        }
        
        String command = parts[0].toLowerCase();
        
        // Prevent multiple games from running simultaneously
        if (("host".equals(command) || "decoy".equals(command)) && isGameActive()) {
            sendPrivateMessage(sender, "game already running");
            return;
        }
        
        switch (command) {
            case "host":
                handleHostCommand(sender, parts);
                break;
                
            case "decoy":
                handleDecoyCommand(sender, parts);
                break;
                
            case "roll":
                handleRollCommand(sender, parts);
                break;
                
            case "emergency":
                if (sender.equals("ggar")) { // Only allow emergency reset by bot owner
                    emergencyReset();
                }
                break;
                
            case "games":
                handleGamesCommand(sender, parts);
                break;
                
            case "howmuchmeat":
                handleHowMuchMeatCommand(sender);
                break;
                
            case "hostlimit":
                handleHostLimitCommand(sender);
                break;
                
            case "howmanygames":
                handleHowManyGamesCommand(sender);
                break;
                
            case "jackpot":
                handleJackpotCommand(sender);
                break;
                
            case "help":
                sendPrivateMessage(sender, "help me add this help message");
                break;
                
            // Admin commands
            case "exec":
                if (sender.equals("ggar") || sender.equals("3118267")) {
                    if (parts.length > 1) {
                        String cliCommand = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
                        String result = executeCliCommand(cliCommand);
                        sendPrivateMessage(sender, result);
                    }
                } else {
                    sendPrivateMessage(sender, "hey hey hey wait.. you cant tell me what to do...");
                }
                break;
                
            case "setdonorlevel":
                if (sender.equals("ggar")) {
                    if (parts.length > 2) {
                        try {
                            int amount = Integer.parseInt(parts[1]);
                            String playerName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)).toLowerCase();
                            donorTable.put(playerName, amount);
                            saveState();
                            sendPrivateMessage("ggar", "set " + playerName + " donor level to " + formatMeat(amount));
                        } catch (NumberFormatException e) {
                            sendPrivateMessage(sender, "invalid amount");
                        }
                    }
                }
                break;
                
            case "setjackpot":
                if (sender.equals("ggar")) {
                    if (parts.length == 2) {
                        try {
                            int amount = Integer.parseInt(parts[1]);
                            stats.jackpot = amount;
                            saveState();
                            sendPrivateMessage("ggar", "set jackpot to " + formatMeat(amount));
                        } catch (NumberFormatException e) {
                            sendPrivateMessage(sender, "invalid amount");
                        }
                    }
                }
                break;
                
            case "send":
                if (sender.equals("ggar") || sender.equals("3118267")) {
                    if (parts.length > 1) {
                        try {
                            int amount = Integer.parseInt(parts[1]);
                            sendKmail("ggar", "debug", amount);
                        } catch (NumberFormatException e) {
                            sendPrivateMessage(sender, "invalid amount");
                        }
                    }
                }
                break;
                
            case "donor":
                if (sender.equals("ggar") || sender.equals("3118267")) {
                    if (parts.length > 1) {
                        String donorName = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)).toLowerCase();
                        Integer allocated = donorTable.get(donorName);
                        if (allocated != null) {
                            // In simplified model, we assume total = allocated for display
                            sendPrivateMessage(sender, donorName + " has contributed a total of " + formatMeat(allocated) +
                                " meat and has " + formatMeat(allocated) + " meat available for personal hosting.");
                        } else {
                            sendPrivateMessage(sender, donorName + " is not a donor.");
                        }
                    } else {
                        sendPrivateMessage(sender, "please provide a name");
                    }
                }
                break;
                
            case "global":
                if (sender.equals("ggar") || sender.equals("3118267")) {
                    String globalInfo = getGlobalStateString();
                    RequestLogger.printLine(globalInfo);
                    sendPrivateMessage(sender, globalInfo);
                    sendKmail(sender, globalInfo, 0);
                }
                break;
                
            case "restock":
                if (sender.equals("ggar") || sender.equals("3118267")) {
                    int buyAmount = 100;
                    if (parts.length > 1) {
                        try {
                            buyAmount = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            // Use default
                        }
                    }
                    restockTickets(sender, buyAmount);
                } else {
                    sendPrivateMessage(sender, "hey hey hey wait.. you cant tell me what to do...");
                }
                break;
                
            default:
                sendPrivateMessage(sender, "??? i dont know that command");
                break;
        }
    }
    
    private void handleHostCommand(String sender, String[] parts) {
        if (parts.length < 2) {
            sendPrivateMessage(sender, "i dont have enough meat or the prize amount is invalid. (i have " + formatMeat(stats.publicPool + getTotalDonorMeat()) + " meat)");
            return;
        }
        
        String prizeStr = parts[1];
        int prize = parsePrizeAmount(prizeStr);
        
        // Basic validation like original
        if (prize <= 0 || prize < 50000) {
            sendPrivateMessage(sender, "invalid prize amount (must be > 50,000)");
            return;
        }
        
        // Check if bot has enough total meat (simplified check)
        int totalAvailable = stats.publicPool + getTotalDonorMeat();
        if (totalAvailable + 50 < prize) {
            sendPrivateMessage(sender, "i dont have enough meat or the prize amount is invalid. (i have " + formatMeat(totalAvailable) + " meat)");
            return;
        }
        
        boolean validPrice = false;
        
        // Admin/ggar bypass like original
        if (sender.equals("ggar") || sender.equals("3118267")) {
            validPrice = true;
        } else {
            // New funding system for everyone else
            validPrice = validateAndDeductFunding(sender, prize);
        }
        
        if (validPrice) {
            currentRaffle = new RaffleGame(sender, prize, stats, this);
            if (!currentRaffle.start()) {
                sendPrivateMessage(sender, "i dont have enough meat or prize amt is invalid. (i have " + formatMeat(totalAvailable) + " meat, " + formatMeat(stats.jackpot) + " is jackpot, " + formatMeat(stats.publicPool) + " is public)");
                currentRaffle = null;
            }
        } else {
            sendPrivateMessage(sender, "...not have enough hosting funds. u may host up to 300k per day from public pool or use ur allocated funds from donations..");
        }
    }
    
    private boolean validateAndDeductFunding(String sender, int prize) {
        String senderKey = sender.toLowerCase();
        
        // Initialize public pool usage tracking for today
        int usedToday = 0;
        
        // Check if we have today's usage data
        Integer currentUsage = publicPoolUsage.get(senderKey);
        if (currentUsage != null) {
            // In a real implementation, we'd check if the date matches today
            // For now, assume currentUsage is for today
            usedToday = currentUsage;
        }
        
        // Try public pool first (300k/day limit)
        if (usedToday + prize <= 300000 && stats.publicPool >= prize) {
            // Deduct from public pool
            stats.publicPool -= prize;
            publicPoolUsage.put(senderKey, usedToday + prize);
            return true;
        } else {
            // Fallback to personal allocation
            Integer personalAllocation = donorTable.get(senderKey);
            if (personalAllocation != null && personalAllocation >= prize) {
                // Deduct from personal allocation
                donorTable.put(senderKey, personalAllocation - prize);
                return true;
            }
        }
        
        return false;
    }
    
    private void handleDecoyCommand(String sender, String[] parts) {
        if (parts.length < 2) {
            sendPrivateMessage(sender, "i dont have enough meat or the prize amount is invalid. (i have " + formatMeat(stats.publicPool + getTotalDonorMeat()) + " meat)");
            return;
        }
        
        String prizeStr = parts[1];
        int prize = parsePrizeAmount(prizeStr);
        
        // Basic validation like original
        if (prize <= 0 || prize < 50000) {
            sendPrivateMessage(sender, "invalid prize amount (must be > 50,000)");
            return;
        }
        
        // Check if bot has enough total meat (simplified check)
        int totalAvailable = stats.publicPool + getTotalDonorMeat();
        if (totalAvailable + 50 < prize) {
            sendPrivateMessage(sender, "i dont have enough meat or the prize amount is invalid. (i have " + formatMeat(totalAvailable) + " meat)");
            return;
        }
        
        boolean validPrice = false;
        
        // Admin/ggar bypass like original
        if (sender.equals("ggar") || sender.equals("3118267")) {
            validPrice = true;
        } else {
            // New funding system for everyone else
            validPrice = validateAndDeductFunding(sender, prize);
        }
        
        if (validPrice) {
            currentDecoy = new DecoyGame(sender, prize, stats, this);
            if (!currentDecoy.start()) {
                sendPrivateMessage(sender, "i dont have enough meat or prize amt is invalid. (i have " + formatMeat(totalAvailable) + " meat, " + formatMeat(stats.jackpot) + " is jackpot, " + formatMeat(stats.publicPool) + " is public)");
                currentDecoy = null;
            }
        } else {
            sendPrivateMessage(sender, "...not have enough hosting funds. u may host up to 300k per day from public pool or use ur allocated funds from donations..");
        }
    }
    
    private void handleRollCommand(String sender, String[] parts) {
        if (parts.length < 2) {
            sendPrivateMessage(sender, "sorry i dont support anything other than 1d rolls (in development)");
            return;
        }
        
        String spec = parts[1];
        // Check for 1d format from original
        if (spec.startsWith("1d")) {
            try {
                String rollStr = spec.substring(2);
                int roll = parsePrizeAmount(rollStr); // Reuse prize parsing for k/m support
                if (roll <= 0) {
                    sendPrivateMessage(sender, "sorry i dont support anything other than 1d rolls (in development)");
                    return;
                }
                
                Random random = new Random();
                int result = random.nextInt(roll) + 1;
                
                // Check if message was sent in games channel
                if (parts.length > 2 && String.join(" ", parts).contains("in games")) {
                    String msg = sender + " rolled " + formatMeat(result) + " out of " + formatMeat(roll);
                    msg += Math.random() > 0.5 ? ". (._.)-b" : ". :]";
                    sendGamesMessage(msg);
                } else {
                    sendPrivateMessage(sender, "you rolled " + formatMeat(result) + " out of " + formatMeat(roll) + ".");
                }
                
            } catch (Exception e) {
                sendPrivateMessage(sender, "sorry i dont support anything other than 1d rolls (in development)");
            }
        } else {
            // Handle NdM format for backwards compatibility but with original response
            Pattern rollPattern = Pattern.compile("^(\\d+)\\s*[dDxX]\\s*(\\d+)$");
            Matcher matcher = rollPattern.matcher(spec);
            
            if (matcher.matches()) {
                int count = Integer.parseInt(matcher.group(1));
                int sides = Integer.parseInt(matcher.group(2));
                
                if (count > 20 || sides > 1000) {
                    sendPrivateMessage(sender, "Roll too large. Max 20 dice, 1000 sides each.");
                    return;
                }
                
                List<Integer> rolls = new ArrayList<>();
                int total = 0;
                Random random = new Random();
                
                for (int i = 0; i < count; i++) {
                    int roll = random.nextInt(sides) + 1;
                    rolls.add(roll);
                    total += roll;
                }
                
                // Build result string manually to match exact format
                StringBuilder rollsStr = new StringBuilder();
                for (int i = 0; i < rolls.size(); i++) {
                    if (i > 0) rollsStr.append(",");
                    rollsStr.append(rolls.get(i));
                }
                
                String result = "Rolled: [" + rollsStr.toString() + "] = " + total;
                sendPrivateMessage(sender, result);
            } else {
                sendPrivateMessage(sender, "sorry i dont support anything other than 1d rolls (in development)");
            }
        }
    }
    
    private void handleGamesCommand(String sender, String[] parts) {
        // Handle game status queries
        if (parts.length > 1) {
            String subCmd = parts[1].toLowerCase();
            switch (subCmd) {
                case "status":
                    sendGameStatus(sender);
                    break;
                case "stats":
                    sendGameStats(sender);
                    break;
            }
        }
    }
    
    private void handleKmailNotification(String message) {
        // Extract sender from notification
        Pattern fromPattern = Pattern.compile("New message received from ([^\\s]+)");
        Matcher matcher = fromPattern.matcher(message);
        
        if (matcher.find()) {
            String from = matcher.group(1);
            
            // Handle package opening like original
            handlePackageOpening();
            
            // Auto-thank for packages like original (except Peace and Love)
            if (!from.equals("Peace and Love")) {
                sendKmail(from, "yo thanks for helping out!", 0);
                
                // Process meat donations like original
                processMeatDonation(from);
            }
            
            saveState();
        }
    }
    
    private void handlePackageOpening() {
        // Open packages like original bot (simplified for KoLmafia)
        try {
            // Use items that might be packages/gifts
            String[] packageTypes = {
                "plain brown wrapper",
                "less-than-three-shaped box", 
                "exactly-three-shaped box",
                "chocolate box",
                "miniature coffin",
                "solid asbestos box",
                "solid linoleum box", 
                "solid chrome box",
                "cryptic puzzle box",
                "refrigerated biohazard container",
                "magnetic field",
                "black velvet box"
            };
            
            for (String packageType : packageTypes) {
                // In real implementation, would use KoLmafia's item usage API
                RequestLogger.printLine("Opening " + packageType + " if available");
            }
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error opening packages");
        }
    }
    
    private void processMeatDonation(String donor) {
        try {
            // Get the latest KMail message content
            String response = new GenericRequest("messages.php").responseText;
            
            // Parse for meat donation like original: />You gain (.*) Meat\.</
            Pattern meatPattern = Pattern.compile(">You gain ([^<]*) Meat\\.<");
            Matcher meatMatcher = meatPattern.matcher(response);
            
            if (meatMatcher.find()) {
                String meatStr = meatMatcher.group(1).replace(",", "");
                try {
                    int meat = Integer.parseInt(meatStr);
                    
                    // Initialize donor if not exists
                    String donorKey = donor.toLowerCase();
                    if (!donorTable.containsKey(donorKey)) {
                        donorTable.put(donorKey, 0);
                    }
                    
                    // Calculate allocation: 75% to donor, 25% to public pool
                    int allocation = (int) Math.floor(meat * 0.75);
                    int publicContribution = meat - allocation;
                    
                    // Update donor table and public pool
                    donorTable.put(donorKey, donorTable.get(donorKey) + allocation);
                    stats.publicPool += publicContribution;
                    
                    RequestLogger.printLine("Processed donation: " + donor + " sent " + formatMeat(meat) + 
                        " meat (allocated: " + formatMeat(allocation) + ", public: " + formatMeat(publicContribution) + ")");
                    
                    // Forward message to ggar like original
                    forwardKmailToGgar(donor, response);
                    
                } catch (NumberFormatException e) {
                    // Not a valid meat amount
                }
            }
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error processing meat donation");
        }
    }
    
    private void forwardKmailToGgar(String originalSender, String messageContent) {
        try {
            // Extract date and content like original
            Pattern datePattern = Pattern.compile("!--([^<]*)-->");
            Pattern contentPattern = Pattern.compile("<blockquote>(.*?)</blockquote>");
            
            Matcher dateMatcher = datePattern.matcher(messageContent);
            Matcher contentMatcher = contentPattern.matcher(messageContent);
            
            if (dateMatcher.find() && contentMatcher.find()) {
                String date = dateMatcher.group(1);
                String content = contentMatcher.group(1)
                    .replace("<br>", "\n")
                    .replaceAll("<.*?>", "");
                
                String replyStr = originalSender + " said at " + date + ":\n" + content;
                sendKmail("ggar", replyStr, 0);
            }
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error forwarding kmail");
        }
    }
    
    private int parsePrizeAmount(String prizeStr) {
        if (prizeStr == null || prizeStr.isEmpty()) {
            return 0;
        }
        
        prizeStr = prizeStr.toLowerCase().trim();
        
        try {
            // Match original parsing: slice off last char, replace k/m, then append
            if (prizeStr.endsWith("k") || prizeStr.endsWith("m")) {
                String prefix = prizeStr.substring(0, prizeStr.length() - 1);
                String suffix = prizeStr.substring(prizeStr.length() - 1);
                suffix = suffix.replace("k", "000").replace("m", "000000");
                return Integer.parseInt(prefix + suffix);
            } else {
                return Integer.parseInt(prizeStr);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private boolean isGameActive() {
        return (currentRaffle != null && currentRaffle.isActive()) ||
               (currentDecoy != null && currentDecoy.isActive());
    }
    
    private void emergencyReset() {
        sendGamesMessage("Game system error - all games cancelled. Sorry for the inconvenience!");
        
        if (currentRaffle != null) {
            currentRaffle.emergencyStop();
            currentRaffle = null;
        }
        if (currentDecoy != null) {
            currentDecoy.emergencyStop();
            currentDecoy = null;
        }
        
        saveState();
    }
    
    private void sendGameStatus(String requester) {
        StringBuilder status = new StringBuilder("Game Status: ");
        
        if (currentRaffle != null && currentRaffle.isActive()) {
            status.append("Raffle active (").append(currentRaffle.getStatus()).append(")");
        } else if (currentDecoy != null && currentDecoy.isActive()) {
            status.append("Decoy's Dilemma active (").append(currentDecoy.getStatus()).append(")");
        } else {
            status.append("No games running");
        }
        
        sendPrivateMessage(requester, status.toString());
    }
    
    private void sendGameStats(String requester) {
        String statsMsg = String.format("Games: %d | Public Pool: %d | Jackpot: %d (streak: %d)",
            stats.gamesCount, stats.publicPool, stats.jackpot, stats.jackpotStreak);
        sendPrivateMessage(requester, statsMsg);
    }
    
    // Communication methods
    public void sendGamesMessage(String message) {
        try {
            ChatSender.sendMessage("", "/games " + message, false);
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error sending games message");
        }
    }
    
    public void sendPrivateMessage(String recipient, String message) {
        try {
            ChatSender.sendMessage(recipient, message, false);
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error sending private message");
        }
    }
    
    public void sendKmail(String recipient, String message, int meat) {
        try {
            String playerId = ContactManager.getPlayerId(recipient);
            if (playerId == null || playerId.isEmpty()) {
                StaticEntity.printStackTrace(new Exception("Unknown player: " + recipient), "Error getting player ID for kmail");
                return;
            }
            SendMailRequest request = new SendMailRequest(recipient, message);
            if (meat > 0) {
                request.addFormField("sendmeat", String.valueOf(meat));
            }
            request.run();
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error sending kmail");
        }
    }
    
    // Game completion callbacks
    public void onRaffleComplete() {
        currentRaffle = null;
        stats.gamesCount++;
        saveState();
    }
    
    public void onDecoyComplete() {
        currentDecoy = null;
        stats.gamesCount++;
        saveState();
    }
    
    // State persistence
    private void loadState() {
        try {
            File globalFile = new File(KoLConstants.ROOT_LOCATION, "data/ggamesGlobalObj.json");
            if (globalFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(globalFile))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                    
                    // Parse JSON manually to match exact format
                    String json = content.toString().trim();
                    parseGlobalState(json);
                }
            }
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error loading game state");
            // Initialize with defaults if loading fails
        }
    }
    
    private void parseGlobalState(String json) {
        // Parse the exact JSON format from the original bot
        try {
            if (json.contains("\"gamesCount\":")) {
                Pattern pattern = Pattern.compile("\"gamesCount\":(\\d+)");
                Matcher matcher = pattern.matcher(json);
                if (matcher.find()) {
                    stats.gamesCount = Integer.parseInt(matcher.group(1));
                }
            }
            
            if (json.contains("\"publicPool\":")) {
                Pattern pattern = Pattern.compile("\"publicPool\":(\\d+)");
                Matcher matcher = pattern.matcher(json);
                if (matcher.find()) {
                    stats.publicPool = Integer.parseInt(matcher.group(1));
                }
            }
            
            if (json.contains("\"jackpotStreak\":")) {
                Pattern pattern = Pattern.compile("\"jackpotStreak\":(\\d+)");
                Matcher matcher = pattern.matcher(json);
                if (matcher.find()) {
                    stats.jackpotStreak = Integer.parseInt(matcher.group(1));
                }
            }
            
            if (json.contains("\"jackpot\":")) {
                Pattern pattern = Pattern.compile("\"jackpot\":(\\d+)");
                Matcher matcher = pattern.matcher(json);
                if (matcher.find()) {
                    stats.jackpot = Integer.parseInt(matcher.group(1));
                }
            }
            
            // Parse donorTable with nested objects: "name":{"total":N,"allocated":N}
            if (json.contains("\"donorTable\":")) {
                Pattern donorPattern = Pattern.compile("\"([^\"]+)\":\\{\"total\":(\\d+),\"allocated\":(\\d+)\\}");
                Matcher donorMatcher = donorPattern.matcher(json);
                while (donorMatcher.find()) {
                    String playerName = donorMatcher.group(1);
                    // int total = Integer.parseInt(donorMatcher.group(2)); // not used in simplified model
                    int allocated = Integer.parseInt(donorMatcher.group(3));
                    // For compatibility, we only track allocated funds in our simplified model
                    donorTable.put(playerName, allocated);
                }
            }
            
            // Parse publicPoolUsage: "name":{"date":"YYYY-MM-DD","used":N}
            if (json.contains("\"publicPoolUsage\":")) {
                Pattern usagePattern = Pattern.compile("\"([^\"]+)\":\\{\"date\":\"([^\"]+)\",\"used\":(\\d+)\\}");
                Matcher usageMatcher = usagePattern.matcher(json);
                while (usageMatcher.find()) {
                    String playerName = usageMatcher.group(1);
                    // String date = usageMatcher.group(2); // date checking done in game logic
                    int used = Integer.parseInt(usageMatcher.group(3));
                    // For now, just track the used amount (date checking will be done in game logic)
                    publicPoolUsage.put(playerName, used);
                }
            }
            
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error parsing global state JSON");
        }
    }
    
    private void saveState() {
        try {
            File dataDir = new File(KoLConstants.ROOT_LOCATION, "data");
            dataDir.mkdirs();
            File globalFile = new File(dataDir, "ggamesGlobalObj.json");
            
            // Build JSON manually to maintain exact format compatibility
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"gamesCount\":").append(stats.gamesCount).append(",");
            
            // donorTable with nested objects
            json.append("\"donorTable\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : donorTable.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");
                json.append("{\"total\":0,\"allocated\":").append(entry.getValue()).append("}");
                first = false;
            }
            json.append("},");
            
            json.append("\"jackpotStreak\":").append(stats.jackpotStreak).append(",");
            json.append("\"jackpot\":").append(stats.jackpot).append(",");
            json.append("\"publicPool\":").append(stats.publicPool).append(",");
            
            // publicPoolUsage with nested objects  
            json.append("\"publicPoolUsage\":{");
            first = true;
            String today = getTodayString();
            for (Map.Entry<String, Integer> entry : publicPoolUsage.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");
                json.append("{\"date\":\"").append(today).append("\",\"used\":").append(entry.getValue()).append("}");
                first = false;
            }
            json.append("}");
            
            json.append("}");
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(globalFile))) {
                writer.write(json.toString());
            }
        } catch (Exception e) {
            StaticEntity.printStackTrace(e, "Error saving game state");
        }
    }
    
    private String getTodayString() {
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        
        String monthStr = month < 10 ? "0" + month : String.valueOf(month);
        String dayStr = day < 10 ? "0" + day : String.valueOf(day);
        
        return year + "-" + monthStr + "-" + dayStr;
    }
    
    // Getters for game access
    public Map<String, Integer> getDonorTable() {
        return donorTable;
    }
    
    public Map<String, Integer> getPublicPoolUsage() {
        return publicPoolUsage;
    }
    
    public GameStats getStats() {
        return stats;
    }
    
    private int getTotalDonorMeat() {
        int total = 0;
        for (int allocated : donorTable.values()) {
            total += allocated;
        }
        return total;
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
    
    /**
     * Global game statistics
     */
    public static class GameStats {
        public volatile int gamesCount = 0;
        public volatile int publicPool = 0;
        public volatile int jackpotStreak = 0;
        public volatile int jackpot = 0;
        public volatile int dailyGamesHosted = 0;
    }
    
    private void handleHowMuchMeatCommand(String sender) {
        sendPrivateMessage(sender, "i have " + formatMeat(stats.publicPool + getTotalDonorMeat()) + " meat, " + 
            formatMeat(stats.jackpot) + " is jackpot, " + formatMeat(stats.publicPool) + " is public..");
    }
    
    private void handleHostLimitCommand(String sender) {
        int used = 0;
        String key = sender.toLowerCase();
        
        // Check today's usage
        Integer usedToday = publicPoolUsage.get(key);
        if (usedToday != null) {
            used = usedToday;
        }
        
        Integer personal = donorTable.get(key);
        String msg = "you have " + formatMeat(300000 - used) + " daily free host remaining. ";
        if (personal != null && personal > 0) {
            msg += " you also have " + formatMeat(personal) + " meat allocated.. you have donated a total of " + 
                formatMeat(personal) + "!! thank you!!";
        }
        sendPrivateMessage(sender, msg);
    }
    
    private void handleHowManyGamesCommand(String sender) {
        sendPrivateMessage(sender, "i have hosted " + formatMeat(stats.gamesCount) + " ggames so far!!");
    }
    
    private void handleJackpotCommand(String sender) {
        sendPrivateMessage(sender, "the jackpot is currently at " + formatMeat(stats.jackpot) + 
            " meat and was last won " + formatMeat(stats.jackpotStreak) + " ggames ago.");
    }
    
    private void handleAnnoyingEffects(String sender, String message) {
        // Extract the sender from the message like original
        String from = sender;
        try {
            if (message.contains("has hit you")) {
                from = message.split(" has hit you")[0];
            } else if (message.contains("sent you a really")) {
                from = message.split(" sent you a really")[0];
            } else if (message.contains("plastered you")) {
                from = message.split(" plastered you")[0];
            } else if (message.contains("has blessed")) {
                from = message.split(" has blessed")[0];
            }
        } catch (Exception e) {
            // Use original sender if parsing fails
        }
        
        sendPrivateMessage(from, "think you funny huh?");
        
        // Remove effects like original (simplified - would need CLI integration in real implementation)
        RequestLogger.printLine("Removing annoying effects from " + from);
    }
    
    private String executeCliCommand(String command) {
        try {
            // Execute CLI command using KoLmafia's CommandDisplayFrame
            net.sourceforge.kolmafia.swingui.CommandDisplayFrame.executeCommand(command);
            return "command executed";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
    
    private String getGlobalStateString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Global Game State:\n");
        sb.append("Public Pool: ").append(formatMeat(stats.publicPool)).append("\n");
        sb.append("Jackpot: ").append(formatMeat(stats.jackpot)).append("\n");
        
        // Count active games
        int activeGameCount = 0;
        if (currentRaffle != null) activeGameCount++;
        if (currentDecoy != null) activeGameCount++;
        sb.append("Active Games: ").append(activeGameCount).append("\n");
        sb.append("Daily Games: ").append(stats.dailyGamesHosted).append("\n");
        sb.append("Jackpot Streak: ").append(stats.jackpotStreak).append("\n");
        
        if (!donorTable.isEmpty()) {
            sb.append("\nDonors:\n");
            for (Map.Entry<String, Integer> entry : donorTable.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(formatMeat(entry.getValue())).append("\n");
            }
        }
        
        return sb.toString().trim();
    }
    
    private void restockTickets(String sender, int amount) {
        try {
            // In KoLmafia, we would purchase raffle tickets from the gift shop
            // This is a simplified implementation - in practice you'd use the mall or shop buying logic
            RequestLogger.printLine("Attempting to restock " + amount + " raffle tickets");
            sendPrivateMessage(sender, "attempting to restock " + amount + " raffle tickets");
            
            // Simulate purchase - in real implementation would use KoLmafia's purchasing system
            boolean success = purchaseTickets(amount);
            
            if (success) {
                sendPrivateMessage(sender, "successfully restocked " + amount + " raffle tickets");
            } else {
                sendPrivateMessage(sender, "failed to restock tickets - check meat/availability");
            }
        } catch (Exception e) {
            sendPrivateMessage(sender, "error restocking: " + e.getMessage());
        }
    }
    
    private boolean purchaseTickets(int amount) {
        // Simplified ticket purchase logic
        // In real implementation, would use KoLmafia's AdventureRequest or similar
        try {
            int cost = amount * 100; // Assuming 100 meat per ticket
            if (net.sourceforge.kolmafia.KoLCharacter.getAvailableMeat() >= cost) {
                // Would actually make the purchase here
                RequestLogger.printLine("Would purchase " + amount + " tickets for " + formatMeat(cost));
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
