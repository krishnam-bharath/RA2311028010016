import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class VehicleMaintenanceScheduler {

    private static final String DEPOTS_URL = "http://20.207.122.201/evaluation-service/depots";
    private static final String VEHICLES_URL = "http://20.207.122.201/evaluation-service/vehicles";
    private static final String AUTH_TOKEN = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJNYXBDbGFpbXMiOnsiYXVkIjoiaHR0cDovLzIwLjI0NC41Ni4xNDQvZXZhbHVhdGlvbi1zZXJ2aWNlIiwiZW1haWwiOiJrbDk0MTBAc3JtaXN0LmVkdS5pbiIsImV4cCI6MTc3NzcwMTM2NSwiaWF0IjoxNzc3NzAwNDY1LCJpc3MiOiJBZmZvcmQgTWVkaWNhbCBUZWNobm9sb2dpZXMgUHJpdmF0ZSBMaW1pdGVkIiwianRpIjoiNDBkMDFmYjktOWFkYi00YThhLWI4YjEtNTE0YjI4MjhmODY4IiwibG9jYWxlIjoiZW4tSU4iLCJuYW1lIjoia291c2hpayBsaW5ndXRsYSIsInN1YiI6IjFkMDI5ZjA4LTAzNTYtNGNlMi1iYzYyLTYwNDBmOGQ1MTY4ZCJ9LCJlbWFpbCI6ImtsOTQxMEBzcm1pc3QuZWR1LmluIiwibmFtZSI6ImtvdXNoaWsgbGluZ3V0bGEiLCJyb2xsTm8iOiJyYTIzMTEwMzAwMTAyODMiLCJhY2Nlc3NDb2RlIjoiUWticHhIIiwiY2xpZW50SUQiOiIxZDAyOWYwOC0wMzU2LTRjZTItYmM2Mi02MDQwZjhkNTE2OGQiLCJjbGllbnRTZWNyZXQiOiJObVpCa1ZQVGZ0VGVuV1RWIn0.nO94Y1-lYSq06rnxoYKiXgcESZBaF19wA5Ho0ELGQ6A";

    private static final LoggingMiddleware logger = new LoggingMiddleware("VehicleMaintenanceScheduler");

    static class Depot {
        int id, mechanicHours;
        Depot(int id, int mechanicHours) { this.id = id; this.mechanicHours = mechanicHours; }
    }

    static class Vehicle {
        String taskId;
        int duration, impact;
        Vehicle(String taskId, int duration, int impact) {
            this.taskId = taskId; this.duration = duration; this.impact = impact;
        }
    }

    public static void main(String[] args) throws Exception {
        logger.info("Starting Vehicle Maintenance Scheduler");

        String depotJson = httpGet(DEPOTS_URL);
        String vehicleJson = httpGet(VEHICLES_URL);

        List<Depot> depots = parseDepots(depotJson);
        List<Vehicle> vehicles = parseVehicles(vehicleJson);

        for (Depot depot : depots) {
            logger.info("Processing Depot ID=" + depot.id + " Budget=" + depot.mechanicHours);
            solveKnapsack(depot, vehicles);
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

    static List<Depot> parseDepots(String json) {
        List<Depot> list = new ArrayList<>();
        String[] parts = json.split("\\{");
        for (String part : parts) {
            if (part.contains("\"ID\"")) {
                int id = extractInt(part, "ID");
                int hours = extractInt(part, "MechanicHours");
                list.add(new Depot(id, hours));
                logger.debug("Parsed depot ID=" + id + " hours=" + hours);
            }
        }
        return list;
    }

    private static List<Vehicle> parseVehicles(String json) {
        List<Vehicle> list = new ArrayList<>();
        String[] parts = json.split("\\{");
        for (String part : parts) {
            if (part.contains("\"TaskID\"")) {
                String taskId   = extractString(part, "TaskID");
                int    duration = extractInt(part, "Duration");
                int    impact   = extractInt(part, "Impact");
                list.add(new Vehicle(taskId, duration, impact));
                logger.debug("Parsed vehicle=" + taskId + " duration=" + duration + " impact=" + impact);
            }
        }
        return list;
    }

    private static int extractInt(String obj, String key) {
        String search = "\"" + key + "\":";
        int idx = obj.indexOf(search);
        if (idx < 0) return 0;
        int from = idx + search.length();
        while (from < obj.length() && obj.charAt(from) == ' ') from++;
        int to = from;
        while (to < obj.length() && Character.isDigit(obj.charAt(to))) to++;
        return Integer.parseInt(obj.substring(from, to));
    }

    private static String extractString(String obj, String key) {
        String search = "\"" + key + "\":\"";
        int idx = obj.indexOf(search);
        if (idx < 0) return "";
        int from = idx + search.length();
        int to   = obj.indexOf("\"", from);
        return obj.substring(from, to);
    }

    private static void solveKnapsack(Depot depot, List<Vehicle> vehicles) {
        int n = vehicles.size();
        int W = depot.mechanicHours;
        int[][] dp = new int[n + 1][W + 1];

        for (int i = 1; i <= n; i++) {
            Vehicle v = vehicles.get(i - 1);
            for (int w = 0; w <= W; w++) {
                dp[i][w] = dp[i - 1][w];
                if (v.duration <= w && dp[i - 1][w - v.duration] + v.impact > dp[i][w]) {
                    dp[i][w] = dp[i - 1][w - v.duration] + v.impact;
                }
            }
        }

        List<Vehicle> selected = new ArrayList<>();
        int w = W;
        for (int i = n; i >= 1; i--) {
            if (dp[i][w] != dp[i - 1][w]) {
                selected.add(vehicles.get(i - 1));
                w -= vehicles.get(i - 1).duration;
            }
        }

        int totalImpact   = dp[n][W];
        int totalDuration = W - w;

        logger.info("=== Depot " + depot.id + " Results ===");
        logger.info("Budget: " + depot.mechanicHours + " hours | Used: " + totalDuration + " hours | Total Impact: " + totalImpact);
        for (Vehicle v : selected) {
            logger.info("  Selected -> TaskID=" + v.taskId + " Duration=" + v.duration + " Impact=" + v.impact);
        }
    }
}