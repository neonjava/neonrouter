package dev.neon.router;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

@Plugin(id = "neonrouter", name = "NeonRouter", version = "1.0.0", description = "Redis-based Cross-Server Routing", authors = {"NeonJava"})
public class NeonRouterPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private JedisPool redisPool;

    @Inject
    public NeonRouterPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            // Hardcoded Redis config as it is identical on the current node
            redisPool = new JedisPool("127.0.0.1", 6379);
            try (Jedis jedis = redisPool.getResource()) {
                jedis.ping();
            }
            logger.info("NeonRouter connected to Redis successfully.");
        } catch (Exception e) {
            logger.error("Failed to connect to Redis for NeonRouter", e);
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
