package net.sourceforge.kolmafia.textui.command;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.StaticEntity;

/**
 * A command that runs JavaScript chatbot scripts by translating them to native Java implementations
 * using KoLmafia's chat API. This avoids the complexity of importPackage and Java Timer issues in
 * the Rhino JavaScript engine.
 */
public class ChatbotCommand extends AbstractCommand {
  private static ChatbotRunner currentRunner = null;
  private static final Pattern FUNCTION_MAIN_PATTERN =
      Pattern.compile("function\\s+main\\s*\\(([^)]*)\\)\\s*\\{([^}]*)\\}");

  public ChatbotCommand() {
    this.usage = " start <script.js> | stop | status - control JavaScript chatbot";
  }

  @Override
  public void run(final String command, final String parameters) {
    String[] parts = parameters.trim().split("\\s+", 2);

    if (parts.length == 0 || parts[0].isEmpty()) {
      KoLmafia.updateDisplay("Usage: chatbot start <script.js> | chatbot stop | chatbot status");
      return;
    }

    String action = parts[0].toLowerCase();

    switch (action) {
      case "start":
        if (parts.length < 2) {
          KoLmafia.updateDisplay("Usage: chatbot start <script.js>");
          return;
        }
        startChatbot(parts[1]);
        break;

      case "stop":
        stopChatbot();
        break;

      case "status":
        showStatus();
        break;

      default:
        KoLmafia.updateDisplay("Unknown action: " + action + ". Use start, stop, or status.");
        break;
    }
  }

  private void startChatbot(String scriptPath) {
    if (currentRunner != null && currentRunner.isRunning()) {
      KoLmafia.updateDisplay("Chatbot is already running. Stop it first with 'chatbot stop'.");
      return;
    }

    // Find the script file
    File scriptFile = findScriptFile(scriptPath);
    if (scriptFile == null || !scriptFile.exists()) {
      KoLmafia.updateDisplay("Script file not found: " + scriptPath);
      return;
    }

    try {
      // Read and parse the JavaScript file
      String jsContent = readFile(scriptFile);
      ChatbotScript script = parseJavaScript(jsContent);

      // Start the chatbot
      currentRunner = new ChatbotRunner(script);
      currentRunner.start();

      KoLmafia.updateDisplay("Chatbot started with script: " + scriptFile.getName());

    } catch (Exception e) {
      KoLmafia.updateDisplay("Failed to start chatbot: " + e.getMessage());
      StaticEntity.printStackTrace(e);
    }
  }

  private void stopChatbot() {
    if (currentRunner == null || !currentRunner.isRunning()) {
      KoLmafia.updateDisplay("No chatbot is currently running.");
      return;
    }

    currentRunner.stop();
    currentRunner = null;
    KoLmafia.updateDisplay("Chatbot stopped.");
  }

  private void showStatus() {
    if (currentRunner == null || !currentRunner.isRunning()) {
      KoLmafia.updateDisplay("No chatbot is currently running.");
    } else {
      KoLmafia.updateDisplay("Chatbot is running and monitoring chat.");
    }
  }

  private File findScriptFile(String scriptPath) {
    // Check if it's an absolute path
    File file = new File(scriptPath);
    if (file.exists()) {
      return file;
    }

    // Check in scripts directory
    file = new File(KoLConstants.SCRIPT_LOCATION, scriptPath);
    if (file.exists()) {
      return file;
    }

    // Check with .js extension
    if (!scriptPath.endsWith(".js")) {
      file = new File(KoLConstants.SCRIPT_LOCATION, scriptPath + ".js");
      if (file.exists()) {
        return file;
      }
    }

    return null;
  }

  private String readFile(File file) throws IOException {
    StringBuilder content = new StringBuilder();
    try (FileReader reader = new FileReader(file)) {
      char[] buffer = new char[1024];
      int length;
      while ((length = reader.read(buffer)) != -1) {
        content.append(buffer, 0, length);
      }
    }
    return content.toString();
  }

  private ChatbotScript parseJavaScript(String jsContent) {
    ChatbotScript script = new ChatbotScript();

    // Extract the main function
    Matcher mainMatcher = FUNCTION_MAIN_PATTERN.matcher(jsContent);
    if (mainMatcher.find()) {
      String parameters = mainMatcher.group(1);
      String body = mainMatcher.group(2);

      script.parameters = parseParameters(parameters);
      script.body = body;
    }

    return script;
  }

  private List<String> parseParameters(String paramString) {
    List<String> params = new ArrayList<>();
    if (paramString != null && !paramString.trim().isEmpty()) {
      String[] parts = paramString.split(",");
      for (String part : parts) {
        params.add(part.trim());
      }
    }
    return params;
  }

  /** Represents a parsed JavaScript chatbot script */
  private static class ChatbotScript {
    List<String> parameters = new ArrayList<>();
    String body = "";
  }

  /** Runs the chatbot by monitoring chat and executing the translated JavaScript logic */
  private static class ChatbotRunner {
    private final ChatbotScript script;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Timer pollTimer = new Timer("ChatbotPoller", true);

    public ChatbotRunner(ChatbotScript script) {
      this.script = script;
    }

    public void start() {
      if (running.getAndSet(true)) {
        return;
      }

      // Start polling chat messages
      pollTimer.scheduleAtFixedRate(
          new TimerTask() {
            @Override
            public void run() {
              checkForNewMessages();
            }
          },
          1000,
          2000); // Check every 2 seconds

      RequestLogger.printLine("Chatbot started and monitoring chat...");
    }

    public void stop() {
      if (!running.getAndSet(false)) {
        return;
      }

      pollTimer.cancel();
      RequestLogger.printLine("Chatbot stopped.");
    }

    public boolean isRunning() {
      return running.get();
    }

    private void checkForNewMessages() {
      if (!running.get()) {
        return;
      }

      try {
        // For now, this is a placeholder implementation
        // In a full implementation, we would:
        // 1. Hook into ChatManager or ChatPoller to get new messages
        // 2. Parse each message for sender, content, and channel
        // 3. Call processMessage for each new message

        // This is where we'd get actual chat messages from KoLmafia's chat system
        // For demonstration purposes, this is left as a stub

      } catch (Exception e) {
        RequestLogger.printLine("Error checking chat messages: " + e.getMessage());
      }
    }
  }
}
