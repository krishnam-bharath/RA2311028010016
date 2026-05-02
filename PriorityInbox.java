import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class PriorityInbox {

    private static final String NOTIFICATIONS_URL = "http://20.207.122.201/evaluation-service/notifications";
    private static final String AUTH_TOKEN = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJNYXBDbGFpbXMiOnsiYXVkIjoiaHR0cDovLzIwLjI0NC41Ni4xNDQvZXZhbHVhdGlvbi1zZXJ2aWNlIiwiZW1haWwiOiJrbDk0MTBAc3JtaXN0LmVkdS5pbiIsImV4cCI6MTc3NzcwMTg1MSwiaWF0IjoxNzc3NzAwOTUxLCJpc3MiOiJBZmZvcmQgTWVkaWNhbCBUZWNobm9sb2dpZXMgUHJpdmF0ZSBMaW1pdGVkIiwianRpIjoiMzY1OWU2YjktYWE5MS00NjVmLWEzNjQtZDcyYWFkMjI3ZTc2IiwibG9jYWxlIjoiZW4tSU4iLCJuYW1lIjoia291c2hpayBsaW5ndXRsYSIsInN1YiI6IjFkMDI5ZjA4LTAzNTYtNGNlMi1iYzYyLTYwNDBmOGQ1MTY4ZCJ9LCJlbWFpbCI6ImtsOTQxMEBzcm1pc3QuZWR1LmluIiwibmFtZSI6ImtvdXNoaWsgbGluZ3V0bGEiLCJyb2xsTm8iOiJyYTIzMTEwMzAwMTAyODMiLCJhY2Nlc3NDb2RlIjoiUWticHhIIiwiY2xpZW50SUQiOiIxZDAyOWYwOC0wMzU2LTRjZTItYmM2Mi02MDQwZjhkNTE2OGQiLCJjbGllbnRTZWNyZXQiOiJObVpCa1ZQVGZ0VGVuV1RWIn0.Mb-QrS9QKaVWpYd8IZYKPQJ_6-MpBU9XGQVp4UmPhaQ";
    private static final int TOP_N = 10;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final LoggingMiddleware logger = new LoggingMiddleware("PriorityInbox");

    static class Notification {
        String id;
        String type;
        String message;
        LocalDateTime timestamp;
        double priorityScore;

        Notification(String id, String type, String message, LocalDateTime timestamp) {
            this.id = id;
            this.type = type;
            this.message = message;
            this.timestamp = timestamp;
            this.priorityScore = calculateScore();
        }

        private double calculateScore() {
            int typeWeight;
            switch (type) {
                case "Placement": typeWeight = 3000; break;
                case "Result":    typeWeight = 2000; break;
                default:          typeWeight = 1000; break;
            }
            long minutesAgo = ChronoUnit.MINUTES.between(timestamp, LocalDateTime.now());
            double recency = 1.0 / (minutesAgo + 1);
            return typeWeight + recency;
        }
    }

    public static void main(String[] args) throws Exception {
        logger.info("Starting Priority Inbox - fetching top " + TOP_N + " notifications");

        String json = httpGet(NOTIFICATIONS_URL);
        List<Notification> all = parseNotifications(json);

        logger.info("Total notifications fetched: " + all.size());

        PriorityQueue<Notification> minHeap = new PriorityQueue<>(
            Comparator.comparingDouble(n -> n.priorityScore)
        );

        for (Notification n : all) {
            minHeap.offer(n);
            if (minHeap.size() > TOP_N) {
                minHeap.poll();
            }
        }

        List<Notification> topN = new ArrayList<>(minHeap);
        topN.sort((a, b) -> Double.compare(b.priorityScore, a.priorityScore));

        logger.info("===== TOP " + TOP_N + " PRIORITY NOTIFICATIONS =====");
        int rank = 1;
        for (Notification n : topN) {
            logger.info("Rank " + rank + " | Type: " + n.type +
                    " | Message: " + n.message +
                    " | Time: " + n.timestamp +
                    " | Score: " + String.format("%.4f", n.priorityScore));
            rank++;
        }
    }

    static String httpGet(String endpoint) throws Exception {
        logger.info("Calling API: " + endpoint);
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", AUTH_TOKEN);
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        logger.info("Response status: " + status);

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    static List<Notification> parseNotifications(String json) {
        List<Notification> list = new ArrayList<>();
        String[] parts = json.split("\\{");
        for (String part : parts) {
            if (part.contains("\"ID\"")) {
                String id        = extractString(part, "ID");
                String type      = extractString(part, "Type");
                String message   = extractString(part, "Message");
                String timestamp = extractString(part, "Timestamp");
                try {
                    LocalDateTime dt = LocalDateTime.parse(timestamp, FORMATTER);
                    list.add(new Notification(id, type, message, dt));
                    logger.debug("Parsed notification ID=" + id + " Type=" + type);
                } catch (Exception e) {
                    logger.warn("Could not parse timestamp for ID=" + id);
                }
            }
        }
        return list;
    }

    static String extractString(String obj, String key) {
        String search = "\"" + key + "\":\"";
        int idx = obj.indexOf(search);
        if (idx < 0) return "";
        int from = idx + search.length();
        int to   = obj.indexOf("\"", from);
        return obj.substring(from, to);
    }
}