package dev.neon.router;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

@Plugin(id = "neonrouter", name = "NeonRouter", version = "1.0.0", description = "Redis-based Cross-Server Routing", authors = {"NeonJava"})
public class NeonRouterPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private JedisPool redisPool;
    private static final String DEFAULT_REDIS_HOST = "127.0.0.1";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final String DEFAULT_REDIS_PASSWORD = "";
    private static final String REDIS_CONFIG_FILE = "config.properties";

    @Inject
    public NeonRouterPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            RedisConfig redis = loadRedisConfig();
            if (redis.password.isBlank()) {
                redisPool = new JedisPool(redis.host, redis.port);
            } else {
                redisPool = new JedisPool("redis://:" + redis.password + "@" + redis.host + ":" + redis.port + "/0");
            }
            try (Jedis jedis = redisPool.getResource()) {
                jedis.ping();
            }
            logger.info("NeonRouter connected to Redis successfully ({}:{}).", redis.host, redis.port);
        } catch (Exception e) {
            logger.error("Failed to connect to Redis for NeonRouter", e);
        }
    }

    private String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private int intEnvOrDefault(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private RedisConfig loadRedisConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve(REDIS_CONFIG_FILE);
        Properties props = new Properties();

        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            }
        }

        String host = readProp(props, "redis.host", envOrDefault("REDIS_HOST", DEFAULT_REDIS_HOST));
        int port = parseInt(readProp(props, "redis.port", String.valueOf(intEnvOrDefault("REDIS_PORT", DEFAULT_REDIS_PORT))), DEFAULT_REDIS_PORT);
        String password = readProp(props, "redis.password", envOrDefault("REDIS_PASSWORD", DEFAULT_REDIS_PASSWORD));

        props.setProperty("redis.host", host);
        props.setProperty("redis.port", String.valueOf(port));
        props.setProperty("redis.password", password);
        if (!Files.exists(configPath)) {
            try (OutputStream out = Files.newOutputStream(configPath)) {
                props.store(out, "NeonRouter Redis configuration");
            }
            logger.info("Created NeonRouter config file at {}", configPath);
        }
        return new RedisConfig(host, port, password);
    }

    private static String readProp(Properties props, String key, String fallback) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) {
            return fallback;
        }
        return val.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class RedisConfig {
        private final String host;
        private final int port;
        private final String password;

        private RedisConfig(String host, int port, String password) {
            this.host = host;
            this.port = port;
            this.password = password;
        }
    }

    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (redisPool == null || redisPool.isClosed()) return;

        String uuid = event.getPlayer().getUniqueId().toString();
        String targetServerName = null;

        try (Jedis jedis = redisPool.getResource()) {
            String lastServer = jedis.get("last_server:" + uuid);

            if (lastServer != null && !lastServer.equalsIgnoreCase("spawn")) {
                targetServerName = lastServer;
                logger.info("Routing " + event.getPlayer().getUsername() + " to last_server: " + targetServerName);
            } else {
                // If no last server, or last server was spawn, send to global spawn
                String globalSpawn = jedis.get("global_spawn");
                if (globalSpawn != null) {
                    if (globalSpawn.contains("|")) {
                        String[] parts = globalSpawn.split("\\|");
                        if (parts.length > 0 && !parts[0].isBlank()) {
                            targetServerName = parts[0]; // Legacy: server|world|x|y|z|yaw|pitch
                        }
                    } else {
                        // Current format stores only world/coords, so default server is spawn.
                        targetServerName = "spawn";
                    }
                }
                
                if (targetServerName != null) {
                    logger.info("Routing " + event.getPlayer().getUsername() + " to global spawn server: " + targetServerName);
                } else {
                    targetServerName = "spawn"; // Safe fallback
                    logger.info("Routing " + event.getPlayer().getUsername() + " to default fallback: spawn");
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching routing data from Redis for " + event.getPlayer().getUsername(), e);
            return;
        }

        if (targetServerName != null) {
            Optional<RegisteredServer> targetServer = server.getServer(targetServerName.toLowerCase());
            if (targetServer.isPresent()) {
                event.setInitialServer(targetServer.get());
            } else {
                logger.warn("Could not find mapped server for name: " + targetServerName);
            }
        }
    }
}
