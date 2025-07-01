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

/**
 * Implementation of Raffle/AR game from the JavaScript chatbot Exact replica of the original
 * functionality using Java and KoLmafia APIs
 */
public class RaffleGame {
  // Game timing constants from original (5 minutes)
  private static final int GAME_TIME_MINUTES = 5;
  private static final int GAME_TIME_SECONDS = GAME_TIME_MINUTES * 60;
  private static final int WARNING_TIME_SECONDS = GAME_TIME_SECONDS - 60; // 1 minute warning
  private static final int FINAL_WARNING_SECONDS = GAME_TIME_SECONDS - 30; // 30 second warning

  // Ticket list from original JavaScript - exact same items
  private static final String[] TICKET_LIST = {
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

  private final String host;
  private final int prizeAmount;
  private final ChatGameManager.GameStats stats;
  private final ChatGameManager manager;

  private volatile boolean active = false;
  private final Map<String, ShopPurchase> shopLog = new ConcurrentHashMap<>();
  private AdventureResult ticketItem = null;
  private ScheduledFuture<?> gameTask = null;
  private ScheduledFuture<?> warningTask1 = null;
  private ScheduledFuture<?> warningTask2 = null;
  private long startTime;
  private int gameSize = 10; // Number of tickets in shop

  // Helper class to track shop purchases like the original shopLog
  private static class ShopPurchase {
    final String buyer;
    final int quantity;
    final String itemName;
    final long timestamp;

    ShopPurchase(String buyer, int quantity, String itemName, long timestamp) {
      this.buyer = buyer;
      this.quantity = quantity;
      this.itemName = itemName;
      this.timestamp = timestamp;
    }
  }

  public RaffleGame(
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
    startTime = System.currentTimeMillis();

    // Announce game start like original
    manager.sendGamesMessage(
        "AR requested by " + host + " with prize 1d" + formatNumber(prizeAmount) + " meat !!");

    // Schedule warning messages
    warningTask1 =
        manager
            .getScheduler()
            .schedule(
                () -> {
                  if (active) {
                    manager.sendGamesMessage("pulling in 1 minute.");
                  }
                },
                WARNING_TIME_SECONDS,
                TimeUnit.SECONDS);

    warningTask2 =
        manager
            .getScheduler()
            .schedule(
                () -> {
                  if (active) {
                    manager.sendGamesMessage("pulling in 30 seconds.");
                  }
                },
                FINAL_WARNING_SECONDS,
                TimeUnit.SECONDS);

    // Schedule game end
    gameTask =
        manager
            .getScheduler()
            .schedule(
                () -> {
                  try {
                    endGame();
                  } catch (Exception e) {
                    RequestLogger.printLine("Error ending raffle game: " + e.getMessage());
                    manager.emergencyReset();
                  }
                },
                GAME_TIME_SECONDS,
                TimeUnit.SECONDS);

    return true;
  }

  private boolean setupShop() {
    // Find an available ticket item like original
    AdventureResult foundItem = null;
    int foundItemId = -1;
    for (String itemName : TICKET_LIST) {
      int itemId = ItemDatabase.getItemId(itemName);
      if (itemId > 0 && InventoryManager.getCount(itemId) >= 10) {
        foundItem = ItemPool.get(itemId, 10);
        foundItemId = itemId;
        break;
      }
    }
    if (foundItem == null) {
      RequestLogger.printLine("No suitable ticket items found in inventory");
      return false;
    }
    ticketItem = foundItem;
    try {
      // Remove all ticket items from shop (like DecoyGame)
      removeShopItems();
      // Add ticket item to shop using AutoMallRequest
      AdventureResult[] items = {ticketItem};
      long[] prices = {100};
      int[] limits = {1};
      AutoMallRequest request = new AutoMallRequest(items, prices, limits);
      RequestThread.postRequest(request);
      if (request.responseText.contains("You don't have a store")) {
        RequestLogger.printLine("Error: Player doesn't have a store");
        return false;
      }
      RequestLogger.printLine(
          "Set up shop with item: " + ticketItem.getName() + " (qty: 10, price: 100, limit: 1)");
      return true;
    } catch (Exception e) {
      RequestLogger.printLine("Error setting up shop: " + e.getMessage());
      return false;
    }
  }

  private void removeShopItems() {
    try {
      if (ticketItem != null) {
        int itemId = ticketItem.getItemId();
        int shopQuantity = StoreManager.shopAmount(itemId);
        if (shopQuantity > 0) {
          ManageStoreRequest removeRequest = new ManageStoreRequest(itemId, shopQuantity);
          RequestThread.postRequest(removeRequest);
          RequestLogger.printLine(
              "Removed " + shopQuantity + " " + ticketItem.getName() + " from shop");
        }
      }
    } catch (Exception e) {
      RequestLogger.printLine("Error removing shop items: " + e.getMessage());
    }
  }

  public boolean isActive() {
    return active;
  }

  public String getHost() {
    return host;
  }

  public int getPrizeAmount() {
    return prizeAmount;
  }

  /**
   * Monitor shop purchases by checking shop log periodically This mimics how the original JS
   * checked the shop every 5 seconds
   */
  private void monitorShopPurchases() {
    if (!active) return;

    try {
      // Get current shop log
      List<String> currentLog = getShopLogEntries();
      // Process new purchases
      for (int i = shopLog.size(); i < currentLog.size(); i++) {
        String logEntry = currentLog.get(i);
        processShopLogEntry(logEntry, i + 1);
      }
      // Schedule next check in 5 seconds
      if (active) {
        manager.getScheduler().schedule(this::monitorShopPurchases, 5, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      RequestLogger.printLine("Error monitoring shop purchases: " + e.getMessage());
    }
  }

  private void processShopLogEntry(String logEntry, int index) {
    // Parse shop log entry format: "HH:MM:SS player bought quantity (itemname)"
    // This matches the original JavaScript parsing
    try {
      String[] parts = logEntry.split(" ");
      if (parts.length >= 4 && parts[2].equals("bought")) {
        String time = parts[0];
        String buyer = parts[1];
        String quantityStr = parts[3];

        // Extract item name from parentheses
        int openParen = logEntry.indexOf('(');
        int closeParen = logEntry.indexOf(')', openParen);
        if (openParen > 0 && closeParen > openParen) {
          String itemName = logEntry.substring(openParen + 1, closeParen);
          int quantity = Integer.parseInt(quantityStr);

          ShopPurchase purchase =
              new ShopPurchase(buyer, quantity, itemName, System.currentTimeMillis());
          shopLog.put(String.valueOf(index), purchase);

          RequestLogger.printLine(buyer + " bought " + quantity + " " + itemName + " at " + time);
        }
      }
    } catch (Exception e) {
      RequestLogger.printLine("Error parsing shop log entry: " + logEntry + " - " + e.getMessage());
    }
  }

  private void endGame() {
    if (!active) {
      return;
    }

    active = false;

    // Cancel any pending tasks
    if (gameTask != null && !gameTask.isDone()) {
      gameTask.cancel(false);
    }
    if (warningTask1 != null && !warningTask1.isDone()) {
      warningTask1.cancel(false);
    }
    if (warningTask2 != null && !warningTask2.isDone()) {
      warningTask2.cancel(false);
    }

    // Remove remaining tickets from shop like original
    manager.sendGamesMessage("pulling tickets.");
    gameSize = 10 - getRemainingTicketsInShop();
    cleanupShop();

    if (gameSize == 0) {
      manager.sendGamesMessage("No tickets sold! Game cancelled.");
      manager.onRaffleComplete();
      return;
    }

    // Select winner exactly like original JavaScript
    String winner = selectWinner();

    if (winner != null) {
      // Calculate winnings like original
      Random random = new Random();
      int amount = random.nextInt(prizeAmount) + 1;
      int playerAmount = (int) Math.floor(amount * 0.9);
      int jackpotAmount = amount - playerAmount;

      // Announce results exactly like original
      String msg =
          "game ended !! rolling 1d"
              + gameSize
              + " gives "
              + ((gameSize + 1) - getWinnerIndex(winner))
              + "...";
      manager.sendGamesMessage(msg);

      // Wait 5 seconds like original
      manager
          .getScheduler()
          .schedule(
              () -> continueEndGame(winner, playerAmount, jackpotAmount), 5, TimeUnit.SECONDS);

    } else {
      manager.sendGamesMessage("Error selecting winner! Game cancelled.");
      manager.onRaffleComplete();
    }
  }

  private void continueEndGame(String winner, int playerAmount, int jackpotAmount) {
    // Get purchase details for winner
    ShopPurchase winnerPurchase = getWinnerPurchase(winner);
    if (winnerPurchase == null) {
      manager.sendGamesMessage("Error finding winner details! Game cancelled.");
      manager.onRaffleComplete();
      return;
    }

    // Update global stats
    stats.gamesCount++;

    // Build announcement like original
    String msg =
        winner
            + " bought "
            + winnerPurchase.quantity
            + " "
            + winnerPurchase.itemName
            + " and won "
            + formatNumber(playerAmount)
            + " meat. ";
    msg += formatNumber(jackpotAmount) + " meat has been added to the jackpot, ";

    // Update jackpot
    stats.jackpot += jackpotAmount;

    // Calculate jackpot roll like original
    int jackpotOdds = 50 - (stats.jackpotStreak > 45 ? 45 : stats.jackpotStreak);
    msg += "rolling 1d" + formatNumber(jackpotOdds) + " for the jackpot...";

    manager.sendGamesMessage(msg);

    // Wait 5 seconds then do jackpot roll
    manager
        .getScheduler()
        .schedule(() -> doJackpotRoll(winner, playerAmount, jackpotOdds), 5, TimeUnit.SECONDS);
  }

  private void doJackpotRoll(String winner, int playerAmount, int jackpotOdds) {
    Random random = new Random();
    int jackpotRoll = random.nextInt(jackpotOdds) + 1;

    String jackpotMsg = "";
    boolean jackpotWon = (jackpotRoll == 1);

    if (jackpotWon) {
      stats.jackpotStreak = 0;
      jackpotMsg +=
          "rolled a 1!! JACKPOT!! "
              + formatNumber(stats.jackpot)
              + " meat has been won by "
              + winner
              + "!!";

      // Send jackpot kmail
      manager.sendKmail(
          winner,
          "you won the jackpot of " + formatNumber(stats.jackpot) + " meat!!",
          stats.jackpot);

      // Add jackpot to player amount for total
      playerAmount += stats.jackpot;
      stats.jackpot = 0;
    } else {
      jackpotMsg +=
          "rolled a "
              + jackpotRoll
              + " on a 1d"
              + formatNumber(jackpotOdds)
              + " (payout on 1). pot is now at "
              + formatNumber(stats.jackpot)
              + " meat. the last win was "
              + formatNumber(stats.jackpotStreak)
              + " ggames ago. better luck next time...";
      stats.jackpotStreak++;
    }

    jackpotMsg += "congrats on ggame #" + formatNumber(stats.gamesCount) + "!!";
    manager.sendGamesMessage(jackpotMsg);

    // Send winner kmail like original
    manager.sendKmail(
        winner, "you won ggame #" + formatNumber(stats.gamesCount) + "!!", playerAmount);

    manager.saveGameState();
    manager.onRaffleComplete();
  }

  private String selectWinner() {
    if (shopLog.isEmpty()) {
      return null;
    }

    // Select winner exactly like original: random number from 1 to gameSize
    Random random = new Random();
    int winnerIndex = random.nextInt(gameSize) + 1;

    // Find the winner by index in shop log
    ShopPurchase purchase = shopLog.get(String.valueOf(winnerIndex));
    return purchase != null ? purchase.buyer : null;
  }

  private int getWinnerIndex(String winner) {
    // Find the index of the winner's purchase
    for (Map.Entry<String, ShopPurchase> entry : shopLog.entrySet()) {
      if (entry.getValue().buyer.equals(winner)) {
        return Integer.parseInt(entry.getKey());
      }
    }
    return 1; // Default fallback
  }

  private ShopPurchase getWinnerPurchase(String winner) {
    // Find the purchase details for the winner
    for (ShopPurchase purchase : shopLog.values()) {
      if (purchase.buyer.equals(winner)) {
        return purchase;
      }
    }
    return null;
  }

  private int getRemainingTicketsInShop() {
    try {
      if (ticketItem != null) {
        int itemId = ticketItem.getItemId();
        return StoreManager.shopAmount(itemId);
      }
    } catch (Exception e) {
      RequestLogger.printLine("Error getting remaining tickets: " + e.getMessage());
    }
    return 0;
  }

  private void cleanupShop() {
    try {
      if (ticketItem != null) {
        int itemId = ticketItem.getItemId();
        int shopQuantity = StoreManager.shopAmount(itemId);
        if (shopQuantity > 0) {
          ManageStoreRequest removeRequest = new ManageStoreRequest(itemId, shopQuantity);
          RequestThread.postRequest(removeRequest);
          RequestLogger.printLine(
              "Removed " + shopQuantity + " " + ticketItem.getName() + " from shop");
        }
      }
      RequestLogger.printLine("Cleaned up shop items");
    } catch (Exception e) {
      RequestLogger.printLine("Error cleaning up shop: " + e.getMessage());
    }
  }

  public void emergencyStop() {
    if (!active) {
      return;
    }

    active = false;

    // Cancel all tasks
    if (gameTask != null && !gameTask.isDone()) {
      gameTask.cancel(false);
    }
    if (warningTask1 != null && !warningTask1.isDone()) {
      warningTask1.cancel(false);
    }
    if (warningTask2 != null && !warningTask2.isDone()) {
      warningTask2.cancel(false);
    }

    cleanupShop();

    manager.sendGamesMessage("Game cancelled! Emergency stop.");
    manager.onRaffleComplete();
  }

  public Map<String, Object> getGameInfo() {
    Map<String, Object> info = new HashMap<>();
    info.put("type", "raffle");
    info.put("host", host);
    info.put("prize", prizeAmount);
    info.put("active", active);
    info.put("gameSize", gameSize);
    info.put("ticketsSold", shopLog.size());

    if (active) {
      long elapsed = System.currentTimeMillis() - startTime;
      long remaining = Math.max(0, (GAME_TIME_SECONDS * 1000) - elapsed);
      info.put("timeRemaining", remaining);
    }

    return info;
  }

  public List<String> getParticipants() {
    List<String> participants = new ArrayList<>();
    for (ShopPurchase purchase : shopLog.values()) {
      if (!participants.contains(purchase.buyer)) {
        participants.add(purchase.buyer);
      }
    }
    return participants;
  }

  public int getTicketCount(String player) {
    int count = 0;
    for (ShopPurchase purchase : shopLog.values()) {
      if (purchase.buyer.equals(player)) {
        count += purchase.quantity;
      }
    }
    return count;
  }

  public int getTotalTickets() {
    return shopLog.size();
  }

  private String formatNumber(int number) {
    return String.format("%,d", number);
  }

  // Status messages like original
  public String getStatusMessage() {
    if (!active) {
      return "No raffle currently running.";
    }

    long elapsed = System.currentTimeMillis() - startTime;
    long remaining = Math.max(0, (GAME_TIME_SECONDS * 1000) - elapsed);
    int seconds = (int) (remaining / 1000);

    StringBuilder sb = new StringBuilder();
    sb.append("AR: ").append(formatNumber(prizeAmount)).append(" meat prize! ");
    sb.append(getParticipants().size()).append(" players, ");
    sb.append(getTotalTickets()).append(" tickets sold. ");
    sb.append(seconds).append(" seconds remaining.");

    return sb.toString();
  }

  // Methods for command responses like original
  public String getParticipantList() {
    List<String> participants = getParticipants();
    if (participants.isEmpty()) {
      return "No participants yet.";
    }

    StringBuilder sb = new StringBuilder("Participants (");
    sb.append(participants.size()).append("): ");

    for (int i = 0; i < participants.size(); i++) {
      if (i > 0) sb.append(", ");
      String player = participants.get(i);
      int tickets = getTicketCount(player);
      sb.append(player).append(" (").append(tickets).append(")");
    }

    return sb.toString();
  }

  public String getTimeRemainingMessage() {
    if (!active) {
      return "No raffle currently running.";
    }

    long elapsed = System.currentTimeMillis() - startTime;
    long remaining = Math.max(0, (GAME_TIME_SECONDS * 1000) - elapsed);
    int seconds = (int) (remaining / 1000);

    if (seconds <= 0) {
      return "Raffle ending now!";
    } else if (seconds <= 10) {
      return "Raffle ending in " + seconds + " seconds!";
    } else {
      return "Raffle ending in " + seconds + " seconds.";
    }
  }

  // Methods required by ChatGameManager
  public void handleChat(String sender, String message) {
    // Raffle games don't need to handle chat during the game
    // Tickets are purchased through shop, not chat
    // But we can start monitoring if this is the first interaction
    if (active && shopLog.isEmpty()) {
      // Start monitoring shop purchases
      monitorShopPurchases();
    }
  }

  public String getStatus() {
    return getStatusMessage();
  }

  private List<String> getShopLogEntries() {
    // Use StoreManager.getStoreLog() to get the shop log, as in DecoyGame
    List<String> entries = new ArrayList<>();
    try {
      var storeLog = StoreManager.getStoreLog();
      for (int i = 0; i < storeLog.size(); i++) {
        entries.add(storeLog.get(i).toString());
      }
    } catch (Exception e) {
      RequestLogger.printLine("Error retrieving shop log: " + e.getMessage());
    }
    return entries;
  }
}
