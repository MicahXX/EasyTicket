package dranpassung.easyTicket;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TicketManager {
    private final Map<UUID, Ticket> activeTickets = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<UUID, UUID> playersInChat = new ConcurrentHashMap<>();
    private final Set<UUID> playersCreatingTicket = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notificationsEnabled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TicketSortMode> playerSortModes = new ConcurrentHashMap<>();

    private final File logDir;
    private final File activeDir;
    private final File settingsFile;
    private FileConfiguration settingsConfig;
    private final ConfigManager config;

    public TicketManager(File dataFolder, ConfigManager config) {
        this.config = config;
        this.logDir = new File(dataFolder, "logs");
        this.activeDir = new File(dataFolder, "active_tickets");
        this.settingsFile = new File(dataFolder, "players.yml");

        if (!logDir.exists()) logDir.mkdirs();
        if (!activeDir.exists()) activeDir.mkdirs();

        loadSettings();
        loadActiveTickets();
    }

    private void loadSettings() {
        if (!settingsFile.exists()) {
            try {
                settingsFile.getParentFile().mkdirs();
                settingsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        this.settingsConfig = YamlConfiguration.loadConfiguration(settingsFile);
        List<String> uuids = settingsConfig.getStringList("notifications-enabled");
        for (String s : uuids) {
            try {
                notificationsEnabled.add(UUID.fromString(s));
            } catch (Exception ignored) {
            }
        }
    }

    private void saveSettings() {
        List<String> uuids = notificationsEnabled.stream().map(UUID::toString).toList();
        settingsConfig.set("notifications-enabled", uuids);
        try {
            settingsConfig.save(settingsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadActiveTickets() {
        File[] files = activeDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            try {
                UUID id = UUID.fromString(yml.getString("id"));
                UUID creatorUUID = UUID.fromString(yml.getString("creatorUUID"));
                String creatorName = yml.getString("creatorName");
                String message = yml.getString("message");
                long timestamp = yml.getLong("timestamp");
                long lastUpdate = yml.getLong("lastUpdate");
                String lastResponder = yml.getString("lastResponderName");
                List<String> transcript = yml.getStringList("transcript");

                Ticket ticket = new Ticket(id, creatorUUID, creatorName, message, timestamp, lastUpdate, lastResponder, transcript);
                activeTickets.put(ticket.getId(), ticket);
            } catch (Exception e) {
                System.err.println("Failed to load ticket file: " + file.getName());
            }
        }
    }

    public void saveActiveTicket(Ticket ticket) {
        Bukkit.getScheduler().runTaskAsynchronously(JavaPlugin.getPlugin(EasyTicket.class), () -> {
            File file = new File(activeDir, ticket.getId() + ".yml");
            YamlConfiguration yml = new YamlConfiguration();

            yml.set("id", ticket.getId().toString());
            yml.set("creatorUUID", ticket.getCreatorUUID().toString());
            yml.set("creatorName", ticket.getCreatorName());
            yml.set("message", ticket.getMessage());
            yml.set("timestamp", ticket.getTimestamp());
            yml.set("lastUpdate", ticket.getLastUpdate());
            yml.set("lastResponderName", ticket.getLastResponderName());
            yml.set("transcript", ticket.getTranscript());
            try {
                yml.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void createTicket(Ticket ticket) {
        synchronized (activeTickets) {
            activeTickets.put(ticket.getId(), ticket);
        }
        saveActiveTicket(ticket);
    }

    public void closeTicket(UUID id, String staffName) {
        Ticket ticket;
        synchronized (activeTickets) {
            ticket = activeTickets.remove(id);
        }
        if (ticket != null) {
            Bukkit.getScheduler().runTaskAsynchronously(JavaPlugin.getPlugin(EasyTicket.class), () -> {
                saveToPaperTrail(ticket, staffName);

                File file = new File(activeDir, ticket.getId() + ".yml");
                if (file.exists()) file.delete();
            });

            playersInChat.values().removeIf(v -> v.equals(id));
        }
    }

    private void saveToPaperTrail(Ticket ticket, String staffName) {
        File logFile = new File(logDir, ticket.getId() + ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
            writer.println("Closed By: " + staffName + " at " + LocalDateTime.now());
            for (String line : ticket.getTranscript()) writer.println(line);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Ticket getTicket(UUID id) {
        synchronized (activeTickets) {
            return activeTickets.get(id);
        }
    }

    public Collection<Ticket> getActiveTickets() {
        synchronized (activeTickets) {
            return new ArrayList<>(activeTickets.values());
        }
    }

    public boolean canCreateTicket(UUID playerUUID) {
        return getTicketsByPlayer(playerUUID).size() < config.getTicketLimit();
    }

    public List<Ticket> getTicketsByPlayer(UUID uuid) {
        synchronized (activeTickets) {
            return new ArrayList<>(activeTickets.values().stream()
                    .filter(t -> t.getCreatorUUID().equals(uuid)).toList());
        }
    }

    public void enterChat(UUID player, UUID ticketId) {
        playersInChat.put(player, ticketId);
    }

    public void leaveChat(UUID player) {
        playersInChat.remove(player);
    }

    public UUID getActiveTicketForPlayer(UUID player) {
        return playersInChat.get(player);
    }

    public void setCreating(UUID player, boolean state) {
        if (state) playersCreatingTicket.add(player);
        else playersCreatingTicket.remove(player);
    }

    public boolean isCreating(UUID player) {
        return playersCreatingTicket.contains(player);
    }

    public void toggleNotifications(UUID uuid) {
        if (notificationsEnabled.contains(uuid)) notificationsEnabled.remove(uuid);
        else notificationsEnabled.add(uuid);
        saveSettings();
    }

    public boolean hasNotifications(UUID uuid) {
        return notificationsEnabled.contains(uuid);
    }

    public TicketSortMode getSortMode(UUID uuid) {
        return playerSortModes.getOrDefault(uuid, TicketSortMode.DATE_NEWEST);
    }

    public void cycleSortMode(UUID uuid) {
        playerSortModes.put(uuid, getSortMode(uuid).next());
    }

    private final Map<UUID, List<Component>> pendingNotifications = new HashMap<>();

    public void addPendingNotification(UUID uuid, Component message) {
        pendingNotifications.computeIfAbsent(uuid, k -> new ArrayList<>()).add(message);
    }

    public List<Component> getPendingNotifications(UUID uuid) {
        return pendingNotifications.getOrDefault(uuid, Collections.emptyList());
    }

    public void clearPendingNotifications(UUID uuid) {
        pendingNotifications.remove(uuid);
    }
}
