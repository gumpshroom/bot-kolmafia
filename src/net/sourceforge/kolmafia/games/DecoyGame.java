package net.sourceforge.kolmafia.games;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.sourceforge.kolmafia.AdventureResult;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.RequestThread;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.request.AutoMallRequest;
import net.sourceforge.kolmafia.request.ManageStoreRequest;
import net.sourceforge.kolmafia.session.InventoryManager;
import net.sourceforge.kolmafia.session.StoreManager;

/** Implementation of Decoy's Dilemma game from the JavaScript chatbot */
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
  private ScheduledFuture<?> answerTask = null;
  private ScheduledFuture<?> votingTask = null;
  private String ticketItemName = null;

  public DecoyGame(
      String host, int prizeAmount, ChatGameManager.GameStats stats, ChatGameManager manager) {
    this.host = host;
    this.prizeAmount = prizeAmount;
    this.stats = stats;
    this.manager = manager;
  }

  public boolean start() {
    if (active) {
      return false;
    }

    // Set up shop with tickets like original
    if (!setupShop()) {
      return false;
    }

    active = true;
    phase = "entry";

    // Announce the game - exact message from original
    String announcement =
        String.format(
            "decoy's dilemma by %s: buy tickets for next 5m. prize: %s meat!",
            host, formatMeat(prizeAmount));
    manager.sendGamesMessage(announcement);

    // Schedule player collection after 5 minutes + 5 second buffer
    manager
        .getScheduler()
        .schedule(
            () -> {
              try {
                collectPlayers();
              } catch (Exception e) {
                handleGameError("collectPlayers", e);
              }
            },
            305,
            TimeUnit.SECONDS);

    RequestLogger.printLine("Decoy's Dilemma started by " + host + " for " + prizeAmount + " meat");
    return true;
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

  private boolean setupShop() {
    try {
      // Select a random ticket item like original bot
      String[] ticketList = getTicketList();
      String selectedItem = null;

      for (String item : ticketList) {
        // Check if we have enough of this item (simplified)
        if (hasEnoughItems(item, 10)) {
          selectedItem = item;
          break;
        }
      }

      if (selectedItem == null) {
        manager.sendPrivateMessage(host, "no suitable ticket items available");
        return false;
      }

      ticketItemName = selectedItem;

      // Put item in shop: price=100, limit=1, qty=10
      return putItemInShop(selectedItem, 100, 1, 10);

    } catch (Exception e) {
      RequestLogger.printLine("Error setting up shop: " + e.getMessage());
      return false;
    }
  }

  private String[] getTicketList() {
    // Same ticket list as original bot
    return new String[] {
      "red drunki-bear",
      "yellow drunki-bear",
      "green drunki-bear",
      "gnocchetti di Nietzsche",
      "glistening fish meat",
      "gingerbread nylons",
      "ghostly ectoplasm",
      "frozen danish",
      "frat brats",
      "expired MRE",
      "enticing mayolus",
      "eagle's milk",
      "crudles",
      "cream of pointy mushroom soup",
      "chaos popcorn",
      "candy carrot",
      "bowl of prescription candy",
      "bowl of maggots",
      "badass pie",
      "alien sandwich",
      "small box",
      "large box",
      "jumping horseradish",
      "perfect cosmopolitan",
      "perfect dark and stormy",
      "perfect mimosa",
      "perfect negroni",
      "perfect old-fashioned",
      "perfect paloma",
      "Sacramento wine",
      "hacked gibson",
      "red pixel potion",
      "octolus oculus",
      "spooky hi mein",
      "stinky hi mein",
      "hot hi mein",
      "cold hi mein",
      "sleazy hi mein",
      "zombie",
      "elemental caipiroska",
      "perfect ice cube",
      "golden gum",
      "snow berries",
      "Game Grid ticket",
      "scrumptious reagent",
      "milk of magnesium",
      "tiny bottle of absinthe",
      "Bloody Nora",
      "llama lama gong",
      "van key",
      "tattered scrap of paper",
      "ice harvest"
    };
  }

  private boolean hasEnoughItems(String itemName, int needed) {
    // Use KoLmafia InventoryManager to check accessible item count
    // This checks inventory, closet, storage, etc. depending on settings
    int itemId = ItemDatabase.getItemId(itemName);
    if (itemId <= 0) {
      RequestLogger.printLine("unknown item: " + itemName);
      return false;
    }

    int availableCount = InventoryManager.getAccessibleCount(itemId);
    boolean hasEnough = availableCount >= needed;

    RequestLogger.printLine(
        "Checking "
            + itemName
            + ": have "
            + availableCount
            + ", need "
            + needed
            + " - "
            + (hasEnough ? "OK" : "INSUFFICIENT"));
    return hasEnough;
  }

  private boolean putItemInShop(String itemName, int price, int limit, int qty) {
    try {
      RequestLogger.printLine(
          "Putting " + qty + " " + itemName + " in shop at " + price + " meat each");

      // Get the item ID and create AdventureResult
      int itemId = ItemDatabase.getItemId(itemName);
      if (itemId <= 0) {
        RequestLogger.printLine("unknown item: " + itemName);
        return false;
      }

      // Check if we have enough items in inventory
      if (InventoryManager.getCount(itemId) < qty) {
        RequestLogger.printLine("not enough " + itemName + " in inventory to add to shop");
        return false;
      }

      // Create AdventureResult for the items to add
      AdventureResult[] items = {ItemPool.get(itemId, qty)};
      long[] prices = {price};
      int[] limits = {limit};

      // Use AutoMallRequest to add items to shop (from inventory)
      AutoMallRequest request = new AutoMallRequest(items, prices, limits);
      RequestThread.postRequest(request);

      if (request.responseText.contains("You don't have a store")) {
        RequestLogger.printLine("error: player doesn't have a store");
        return false;
      }

      RequestLogger.printLine("successfully added " + qty + " " + itemName + " to shop");
      return true;

    } catch (Exception e) {
      RequestLogger.printLine("Error adding item to shop: " + e.getMessage());
      return false;
    }
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
            manager.sendPrivateMessage(
                sender, "registered guess #" + guessNum + ": " + guessedAnswer);
          } else {
            manager.sendPrivateMessage(sender, "invalid guess number. choose 1-" + answers.size());
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
      manager.sendPrivateMessage(sender, "you already submitted your answer.");
      return;
    }

    String answer = content.trim();
    if (answer.isEmpty()) {
      manager.sendPrivateMessage(sender, "empty answer not allowed. please send a fake answer.");
      return;
    }

    if (answer.length() > 200) {
      manager.sendPrivateMessage(sender, "answer too long. please keep it under 200 characters.");
      return;
    }

    fakeAnswers.put(senderLower, answer);
    manager.sendPrivateMessage(sender, "got your fake answer: " + answer);
  }

  private void collectPlayers() {
    try {
      // Get participants from shop buyers using KoLmafia's store log
      List<String> buyers = getUniqueBuyers();

      if (buyers.size() < 3) {
        manager.sendGamesMessage(
            "decoy's dilemma cancelled - need at least 3 players. only "
                + buyers.size()
                + " bought tickets.");
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

      manager.sendGamesMessage("question (" + participants.size() + " players): " + question);

      // Send private messages to all participants
      for (String participant : participants) {
        manager.sendPrivateMessage(
            participant, "please pm me your fake answer within 2 minutes for: " + question);
      }

      // Schedule voting phase after 2 minutes + 5 second buffer
      answerTask =
          manager
              .getScheduler()
              .schedule(
                  () -> {
                    try {
                      beginVoting();
                    } catch (Exception e) {
                      handleGameError("beginVoting", e);
                    }
                  },
                  125,
                  TimeUnit.SECONDS);

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
      StringBuilder msg = new StringBuilder("vote! type 'guess <#>' for the real answer: ");
      for (int i = 0; i < answers.size(); i++) {
        msg.append("[").append(i + 1).append("] ").append(answers.get(i)).append("  ");
      }
      manager.sendGamesMessage(msg.toString());

      // Schedule finalization after 2 minutes + 5 second buffer
      votingTask =
          manager
              .getScheduler()
              .schedule(
                  () -> {
                    try {
                      finalizeDecoy();
                    } catch (Exception e) {
                      handleGameError("finalizeDecoy", e);
                    }
                  },
                  125,
                  TimeUnit.SECONDS);

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
      manager.sendGamesMessage("real answer: " + realAnswer);

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
        manager.sendGamesMessage("no winners this round.");
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
            String message =
                "you placed in the game! you receive " + formatMeat(perPlayer) + " meat.";
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
      manager.sendGamesMessage("error distributing prizes - admin has been notified.");
    }
  }

  private List<String> getUniqueBuyers() {
    try {
      // Get the store log from KoLmafia's StoreManager
      // First, retrieve the latest store log
      ManageStoreRequest logRequest = new ManageStoreRequest(true);
      RequestThread.postRequest(logRequest);

      // Parse the store log to extract unique buyers
      Set<String> uniqueBuyers = new HashSet<>();

      // Get the store log entries from StoreManager
      var storeLog = StoreManager.getStoreLog();

      for (int i = 0; i < storeLog.size(); i++) {
        var logEntry = storeLog.get(i);
        String logText = logEntry.toString();

        // Parse log entries like "player bought X item(s)"
        // Store log format is typically "playername bought quantity itemname"
        if (logText.contains(" bought ")) {
          String[] parts = logText.split(" bought ");
          if (parts.length >= 2) {
            // Extract the player name (everything before " bought ")
            String playerPart = parts[0];
            // Remove any leading numbers/IDs (format might be "ID: playername")
            if (playerPart.contains(": ")) {
              String[] idParts = playerPart.split(": ", 2);
              if (idParts.length >= 2) {
                playerPart = idParts[1];
              }
            }

            String buyerName = playerPart.trim();
            if (!buyerName.isEmpty() && !buyerName.equals("nobody")) {
              uniqueBuyers.add(buyerName);
              RequestLogger.printLine("Found buyer in shop log: " + buyerName);
            }
          }
        }
      }

      List<String> result = new ArrayList<>(uniqueBuyers);
      RequestLogger.printLine("Total unique buyers found: " + result.size());
      return result;

    } catch (Exception e) {
      RequestLogger.printLine("Error parsing shop log: " + e.getMessage());
      // Fallback to using participants who bought tickets
      return new ArrayList<>(participants);
    }
  }

  private QuestionAnswer fetchQuestionAnswer() {
    // For now, use a simple question/answer pair
    // In real implementation, would fetch from gemini api and parse to json
    String question = getRandomTriviaQuestion();
    String answer = getAnswerForQuestion(question);
    return new QuestionAnswer(question, answer);
  }

  private String getAnswerForQuestion(String question) {
    // Simple mapping for demo questions
    switch (question) {
      case "What is the capital of France?":
        return "Paris";
      case "What year did World War II end?":
        return "1945";
      case "What is the largest planet in our solar system?":
        return "Jupiter";
      case "Who painted the Mona Lisa?":
        return "Leonardo da Vinci";
      case "What is the chemical symbol for gold?":
        return "Au";
      case "In what year was the first iPhone released?":
        return "2007";
      case "What is the longest river in the world?":
        return "Nile";
      case "Who wrote Romeo and Juliet?":
        return "Shakespeare";
      case "What is the smallest country in the world?":
        return "Vatican City";
      case "What gas makes up about 78% of Earth's atmosphere?":
        return "Nitrogen";
      default:
        return "Unknown";
    }
  }

  private String getRandomTriviaQuestion() {
    // Sample trivia questions for Decoy's Dilemma
    String[] questions = {
      "What is the capital of France?",
      "What year did World War II end?",
      "What is the largest planet in our solar system?",
      "Who painted the Mona Lisa?",
      "What is the chemical symbol for gold?",
      "In what year was the first iPhone released?",
      "What is the longest river in the world?",
      "Who wrote Romeo and Juliet?",
      "What is the smallest country in the world?",
      "What gas makes up about 78% of Earth's atmosphere?"
    };

    Random random = new Random();
    return questions[random.nextInt(questions.length)];
  }

  private void handleGameError(String phase, Exception error) {
    try {
      reportError("Decoy game error in " + phase, error);
      manager.sendGamesMessage(
          "Decoy's Dilemma encountered an error and has been cancelled. Sorry!");
      cleanup();
    } catch (Exception e) {
      // Final fallback
      resetState();
    }
  }

  private void cleanup() {
    try {
      removeShopItems();
    } catch (Exception e) {
      // Silent cleanup
    }
    resetState();
    manager.onDecoyComplete();
  }

  private void removeShopItems() {
    try {
      if (ticketItemName != null) {
        RequestLogger.printLine("Removing " + ticketItemName + " from shop");

        // Get the item ID for the ticket item
        int itemId = ItemDatabase.getItemId(ticketItemName);
        if (itemId <= 0) {
          RequestLogger.printLine("unknown item: " + ticketItemName);
          return;
        }

        // Check how many of this item are currently in the shop
        int shopQuantity = StoreManager.shopAmount(itemId);
        if (shopQuantity <= 0) {
          RequestLogger.printLine("No " + ticketItemName + " found in shop to remove");
          return;
        }

        // Remove all instances of this item from the shop
        // ManageStoreRequest with itemId and quantity removes items from shop
        ManageStoreRequest removeRequest = new ManageStoreRequest(itemId, shopQuantity);
        RequestThread.postRequest(removeRequest);

        RequestLogger.printLine("Removed " + shopQuantity + " " + ticketItemName + " from shop");
      }
    } catch (Exception e) {
      RequestLogger.printLine("Error removing shop items: " + e.getMessage());
    }
  }

  private void resetState() {
    active = false;
    phase = "setup";
    participants.clear();
    fakeAnswers.clear();
    guesses.clear();
    answers.clear();

    if (answerTask != null) {
      answerTask.cancel(false);
      answerTask = null;
    }
    if (votingTask != null) {
      votingTask.cancel(false);
      votingTask = null;
    }

    ticketItemName = null;
    realAnswer = null;
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
