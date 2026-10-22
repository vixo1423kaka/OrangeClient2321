package com.example.orangeclient.license;

import com.example.orangeclient.OrangeClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LicenseIpcServer {
    private static final Gson GSON = new Gson();
    private static volatile HttpServer server;

    private LicenseIpcServer() { }

    public static synchronized void start() {
        if (server != null) return;
        try {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String secret = HexFormat.of().formatHex(random);

            HttpServer created = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            created.createContext("/activate", exchange -> handleActivate(exchange, secret));
            created.setExecutor(Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "OrangeClient-LicenseIPC");
                thread.setDaemon(true);
                return thread;
            }));

            Path config = FabricLoader.getInstance().getConfigDir();
            Files.createDirectories(config);
            JsonObject endpoint = new JsonObject();
            endpoint.addProperty("port", created.getAddress().getPort());
            endpoint.addProperty("pid", ProcessHandle.current().pid());
            endpoint.addProperty("token", secret);
            Files.writeString(config.resolve("orangeclient_ipc.json"), GSON.toJson(endpoint),
                    StandardCharsets.UTF_8);

            created.start();
            server = created;
            OrangeClient.LOGGER.info("Orange Client local activation endpoint started on 127.0.0.1:{}",
                    created.getAddress().getPort());
        } catch (Exception e) {
            OrangeClient.LOGGER.error("Could not start local license activation endpoint", e);
        }
    }

    private static void handleActivate(HttpExchange exchange, String secret) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, false, "POST required");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            if (body.length > 4096) {
                respond(exchange, 413, false, "Request too large");
                return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("X-Orange-Token");
            if (supplied == null || !MessageDigest.isEqual(
                    secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 403, false, "Local authorization failed");
                return;
            }

            JsonObject request;
            try {
                request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                respond(exchange, 400, false, "Invalid JSON");
                return;
            }
            String key = request.has("key") && request.get("key").isJsonPrimitive()
                    ? request.get("key").getAsString().trim() : "";
            if (key.isEmpty() || key.length() > 128) {
                respond(exchange, 400, false, "License key is required");
                return;
            }

            final String error;
            try {
                error = LicenseManager.validate(key).get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                OrangeClient.LOGGER.warn("License validation request failed", e);
                respond(exchange, 503, false, "License server unavailable");
                return;
            }
            if (error != null) {
                respond(exchange, 403, false, error);
                return;
            }

            MinecraftClient.getInstance().execute(LicenseScreen::closeIfShowing);
            long daysRemaining = LicenseManager.getDaysRemaining();
            String message = daysRemaining >= 0
                    ? "Orange Client activated; " + daysRemaining + " day(s) remaining"
                    : "Orange Client activated; license has no reported expiry";
            respond(exchange, 200, true, message);
        }
    }

    private static void respond(HttpExchange exchange, int status, boolean ok, String message)
            throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        result.addProperty("message", message);
        byte[] bytes = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
