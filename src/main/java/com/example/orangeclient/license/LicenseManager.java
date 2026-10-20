package com.example.orangeclient.license;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CompletableFuture;

public final class LicenseManager {
    // >>> ZMIEN NA ADRES SWOJEGO SERWERA LICENCJI <<<
    private static final String API_URL = "https://Vixo.pythonanywhere.com/validate";

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orangeclient_license.json");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private static volatile boolean valid = false;
    private static volatile boolean checking = false;
    private static volatile long expiresAtMillis = -1L;
    private static volatile long serverDaysRemaining = -1L;
    private static volatile long lastValidationMillis = 0L;
    private static final long REVALIDATE_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static String savedKey = "";

    private LicenseManager() { }

    public static boolean isValid() { return valid; }
    public static boolean isChecking() { return checking; }
    public static String getSavedKey() { return savedKey; }

    /** Pozostale dni odczytane z odpowiedzi serwera licencji. -1 oznacza brak danych o terminie. */
    public static long getDaysRemaining() {
        long expiry = expiresAtMillis;
        if (expiry >= 0L) {
            long remaining = expiry - System.currentTimeMillis();
            if (remaining <= 0L) return 0L;
            return (remaining + 86_400_000L - 1L) / 86_400_000L;
        }
        return serverDaysRemaining;
    }

    private static void readExpiry(JsonObject response) {
        expiresAtMillis = -1L;
        serverDaysRemaining = -1L;

        String[] dayFields = {"days_left", "daysRemaining", "remaining_days", "days_remaining"};
        for (String field : dayFields) {
            if (response.has(field) && response.get(field).isJsonPrimitive()) {
                try {
                    long days = response.get(field).getAsLong();
                    if (days >= 0L) {
                        serverDaysRemaining = days;
                        return;
                    }
                } catch (Exception ignored) { }
            }
        }

        String[] expiryFields = {"expires_at", "expiresAt", "expiry", "expires", "expiration", "expiration_date", "expiry_date"};
        for (String field : expiryFields) {
            if (!response.has(field) || response.get(field).isJsonNull()) continue;
            try {
                var value = response.get(field).getAsString().trim();
                if (value.isEmpty()) continue;
                try {
                    long numeric = Long.parseLong(value);
                    expiresAtMillis = numeric < 10_000_000_000L ? numeric * 1000L : numeric;
                    return;
                } catch (NumberFormatException ignored) { }
                try {
                    expiresAtMillis = Instant.parse(value).toEpochMilli();
                    return;
                } catch (DateTimeParseException ignored) { }
                try {
                    expiresAtMillis = OffsetDateTime.parse(value).toInstant().toEpochMilli();
                    return;
                } catch (DateTimeParseException ignored) { }
                try {
                    expiresAtMillis = LocalDate.parse(value).atTime(LocalTime.MAX)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    return;
                } catch (DateTimeParseException ignored) { }
            } catch (Exception ignored) { }
        }
    }

    /** Ponawia kontrole z serwerem co 5 minut, aby szybciej wykrywac cofniecie licencji. */
    public static void refreshIfDue() {
        if (checking || savedKey.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastValidationMillis >= REVALIDATE_INTERVAL_MILLIS) {
            lastValidationMillis = now;
            validate(savedKey);
        }
    }

    /** Wywolaj raz przy starcie (OrangeClient.onInitializeClient). */
    public static void init() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
                if (o != null && o.has("key")) savedKey = o.get("key").getAsString();
            }
        } catch (Exception ignored) { }
        if (!savedKey.isEmpty()) validate(savedKey);
    }

    /** Future zwraca null gdy klucz OK, w przeciwnym razie tekst bledu. */
    public static CompletableFuture<String> validate(String key) {
        if (key == null || key.trim().isEmpty()) {
            valid = false;
            checking = false;
            return CompletableFuture.completedFuture("Nieprawidlowy klucz");
        }
        checking = true;
        lastValidationMillis = System.currentTimeMillis();
        String k = key.trim();
        JsonObject body = new JsonObject();
        body.addProperty("key", k);
        body.addProperty("hwid", hwid());

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    checking = false;
                    try {
                        if (res.statusCode() < 200 || res.statusCode() >= 300) {
                            valid = false;
                            expiresAtMillis = -1L;
                            serverDaysRemaining = -1L;
                            return "Serwer odrzucil weryfikacje licencji";
                        }
                        JsonObject o = GSON.fromJson(res.body(), JsonObject.class);
                        if (o == null || !o.has("valid") || !o.get("valid").isJsonPrimitive()) {
                            valid = false;
                            expiresAtMillis = -1L;
                            serverDaysRemaining = -1L;
                            return "Nieprawidlowa odpowiedz serwera licencji";
                        }
                        if (o.get("valid").getAsBoolean()) {
                            valid = true;
                            readExpiry(o);
                            save(k);
                            return (String) null;
                        }
                        valid = false;
                        expiresAtMillis = -1L;
                        serverDaysRemaining = -1L;
                        return o.has("message") ? o.get("message").getAsString() : "Nieprawidlowy klucz";
                    } catch (Exception e) {
                        valid = false;
                        expiresAtMillis = -1L;
                        serverDaysRemaining = -1L;
                        return "Blad odpowiedzi serwera";
                    }
                })
                .exceptionally(e -> {
                    checking = false;
                    valid = false;
                    expiresAtMillis = -1L;
                    serverDaysRemaining = -1L;
                    return "Brak polaczenia z serwerem licencji";
                });
    }

    private static void save(String key) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            Files.writeString(FILE, o.toString());
            savedKey = key;
        } catch (Exception ignored) { }
    }

    private static String hwid() {
        try {
            String raw = System.getProperty("user.name") + System.getProperty("os.name")
                    + System.getProperty("os.arch") + System.getenv("COMPUTERNAME")
                    + System.getenv("PROCESSOR_IDENTIFIER") + Runtime.getRuntime().availableProcessors();
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Pokazuje okno licencji, jesli klucz nie jest aktywny. */
    public static void requireLicense(MinecraftClient mc) {
        if (valid || checking) return;
        if (!(mc.currentScreen instanceof LicenseScreen)) {
            mc.setScreen(new LicenseScreen(mc.currentScreen));
        }
    }
}
