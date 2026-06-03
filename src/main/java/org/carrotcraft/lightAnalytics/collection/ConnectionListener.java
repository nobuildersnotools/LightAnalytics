package org.carrotcraft.lightAnalytics.collection;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.PlayerRepository;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates Velocity connection events into session and player records. Each
 * handler stays light: it reads what it needs off the event thread and hands the
 * actual JDBC work to {@link Database}'s background writer.
 *
 * <p>Open session ids are cached in memory by player UUID so disconnects and
 * server switches can update the correct row without an extra lookup.
 */
public final class ConnectionListener {

    private final Database database;
    private final SessionRepository sessions;
    private final PlayerRepository players;
    private final ConcurrentHashMap<UUID, Long> openSessionIds = new ConcurrentHashMap<>();

    public ConnectionListener(Database database, SessionRepository sessions, PlayerRepository players) {
        this.database = database;
        this.sessions = sessions;
        this.players = players;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        long now = System.currentTimeMillis();
        database.write(connection -> {
            players.upsertOnLogin(connection, uuid, username, now);
            long sessionId = sessions.open(connection, uuid, username, null, now);
            openSessionIds.put(uuid, sessionId);
        });
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        player.getCurrentServer().ifPresent(serverConnection -> {
            Long sessionId = openSessionIds.get(uuid);
            if (sessionId == null) {
                return;
            }
            String serverName = serverConnection.getServerInfo().getName();
            database.write(connection -> sessions.updateServer(connection, sessionId, serverName));
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long sessionId = openSessionIds.remove(uuid);
        database.write(connection -> {
            if (sessionId != null) {
                sessions.close(connection, sessionId, now);
            }
            players.markSeen(connection, uuid, now);
        });
    }
}
