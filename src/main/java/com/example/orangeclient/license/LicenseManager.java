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
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public final class LicenseManager {
    private static final String API_URL = "https://Vixo.pythonanywhere.com/validate";
    // Public Ed25519 key only. The matching private key must remain on the server.
    private static final String PUBLIC_KEY_X509_B64 = "MCowBQYDK2VwAyEANxglvfaj6XgHv5WSoPncL+woXMcO8Kj9WdlLhUhhEGE=";
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 300L;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orangeclient_license.json");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private static volatile boolean valid = false;
    private static volatile boolean checking = false;
    private static volatile long expiresAtMillis = -1L;
    private static volatile long tokenExpiresAtMillis = -1L;
    private static volatile long lastValidationMillis = 0L;
    private static final long REVALIDATE_INTERVAL_MILLIS = Duration.ofMinutes(3).toMillis();
    private static String savedKey = "";

    private LicenseManager() { }

    /** Licencja jest ważna tylko do wygaśnięcia podpisanego, krótkotrwałego tokenu. */
    public static boolean isValid() {
        if (valid && (tokenExpiresAtMillis <= 0L || System.currentTimeMillis() >= tokenExpiresAtMillis)) {
            valid = false;
        }
        return valid;
    }

    public static boolean isChecking() { return checking; }
    public static String getSavedKey() { return savedKey; }

    public static long getDaysRemaining() {
        long expiry = expiresAtMillis;
        if (expiry > 0L) {
            long remaining = expiry - System.currentTimeMillis();
            if (remaining <= 0L) return 0L;
            return (remaining + 86_400_000L - 1L) / 86_400_000L;
        }
        return -1L;
    }

    public static void refreshIfDue() {
        if (!valid || checking || savedKey.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastValidationMillis >= REVALIDATE_INTERVAL_MILLIS) {
            lastValidationMillis = now;
            validate(savedKey);
        }
    }

    public static void init() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
                if (o != null && o.has("key") && o.get("key").isJsonPrimitive()) {
                    savedKey = o.get("key").getAsString();
                }
            }
        } catch (Exception ignored) { }
    }

    /** Future zwraca null, gdy podpis serwera i wszystkie roszczenia są poprawne. */
    public static CompletableFuture<String> validate(String key) {
        if (key == null || key.trim().isEmpty()) {
            invalidate();
            checking = false;
            return CompletableFuture.completedFuture("Nieprawidlowy klucz");
        }
        final String k = key.trim();
        final String machineId = hwid();
        checking = true;
        lastValidationMillis = System.currentTimeMillis();
        JsonObject body = new JsonObject();
        body.addProperty("key", k);
        body.addProperty("hwid", machineId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    try {
                        if (res.statusCode() < 200 || res.statusCode() >= 300) {
                            invalidate();
                            JsonObject error = GSON.fromJson(res.body(), JsonObject.class);
                            return error != null && error.has("message")
                                    ? error.get("message").getAsString()
                                    : "Serwer odrzucil weryfikacje licencji";
                        }
                        JsonObject response = GSON.fromJson(res.body(), JsonObject.class);
                        if (response == null || !response.has("valid") || !response.get("valid").getAsBoolean()) {
                            invalidate();
                            return response != null && response.has("message")
                                    ? response.get("message").getAsString() : "Nieprawidlowy klucz";
                        }
                        if (!response.has("token") || !response.has("signature")) {
                            invalidate();
                            return "Brak podpisanego tokenu licencji";
                        }

                        byte[] payload = Base64.getUrlDecoder().decode(response.get("token").getAsString());
                        byte[] signature = Base64.getUrlDecoder().decode(response.get("signature").getAsString());
                        Signature verifier = Signature.getInstance("Ed25519");
                        byte[] publicKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY_X509_B64);
                        verifier.initVerify(KeyFactory.getInstance("Ed25519")
                                .generatePublic(new X509EncodedKeySpec(publicKeyBytes)));
                        verifier.update(payload);
                        if (!verifier.verify(signature)) {
                            invalidate();
                            return "Nieprawidlowy podpis serwera licencji";
                        }

                        JsonObject claims = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
                        long now = System.currentTimeMillis() / 1000L;
                        if (claims == null || !claims.has("v") || claims.get("v").getAsInt() != 1
                                || !claims.has("iat") || !claims.has("exp")
                                || !claims.has("key_hash") || !claims.has("hwid_hash")
                                || !claims.has("license_exp")) {
                            invalidate();
                            return "Niekompletny token licencji";
                        }
                        long issuedAt = claims.get("iat").getAsLong();
                        long tokenExpiry = claims.get("exp").getAsLong();
                        long licenseExpiry = claims.get("license_exp").getAsLong();
                        if (issuedAt > now + 60L || tokenExpiry <= now
                                || tokenExpiry <= issuedAt || tokenExpiry - issuedAt > MAX_TOKEN_LIFETIME_SECONDS
                                || (licenseExpiry > 0L && licenseExpiry <= now)
                                || !constantTimeEquals(claims.get("key_hash").getAsString(), sha256Hex(k))
                                || !constantTimeEquals(claims.get("hwid_hash").getAsString(), sha256Hex(machineId))) {
                            invalidate();
                            return "Token licencji jest niewazny lub wygasl";
                        }

                        expiresAtMillis = licenseExpiry > 0L ? licenseExpiry * 1000L : -1L;
                        tokenExpiresAtMillis = tokenExpiry * 1000L;
                        valid = true;
                        save(k);
                        return (String) null;
                    } catch (Exception e) {
                        invalidate();
                        return "Nie mozna zweryfikowac podpisu licencji";
                    } finally {
                        checking = false;
                    }
                })
                .exceptionally(e -> {
                    checking = false;
                    invalidate();
                    return "Brak polaczenia z serwerem licencji";
                });
    }

    private static void invalidate() {
        valid = false;
        expiresAtMillis = -1L;
        tokenExpiresAtMillis = -1L;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
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
            return sha256Hex(raw);
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static void requireLicense(MinecraftClient mc) {
        if (isValid() || checking) return;
        if (!(mc.currentScreen instanceof LicenseScreen)) {
            mc.setScreen(new LicenseScreen(mc.currentScreen));
        }
    }
}
