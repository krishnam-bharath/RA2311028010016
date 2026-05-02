import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LoggingMiddleware {

    public enum Level { INFO, DEBUG, WARN, ERROR }

    private static final String LOG_FILE = "affordmed.log";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String context;

    public LoggingMiddleware(String context) {
        this.context = context;
    }

    private void log(Level level, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String entry = String.format("[%s] [%s] [%s] %s", timestamp, level, context, message);
        System.out.println(entry);
        try (PrintWriter pw = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            pw.println(entry);
        } catch (IOException e) {
            System.out.println("[LOGGER-ERROR] Could not write to log file: " + e.getMessage());
        }
    }

    public void info(String message)  { log(Level.INFO,  message); }
    public void debug(String message) { log(Level.DEBUG, message); }
    public void warn(String message)  { log(Level.WARN,  message); }
    public void error(String message) { log(Level.ERROR, message); }
}