package dranpassung.easyTicket;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TicketListener implements Listener {
    private final TicketManager manager;
    private final ConfigManager config;
    private final Map<UUID, List<Component>> missedGlobalChat = new ConcurrentHashMap<>();

    public TicketListener(TicketManager manager, ConfigManager config) {
        this.manager = manager;
        this.config = config;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.contains("Staff Dashboard") && !title.contains("My Tickets")) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        // --- ACTION BUTTONS ---
        if (item.getPersistentDataContainer().has(TicketGUI.ACTION_KEY, PersistentDataType.STRING)) {
            String action = item.getPersistentDataContainer().get(TicketGUI.ACTION_KEY, PersistentDataType.STRING);

            if (action.equals("CREATE")) {
                if (!manager.canCreateTicket(player.getUniqueId())) {
                    player.sendMessage(config.getPrefix().append(Component.text("You hit the ticket limit! Please wait.", NamedTextColor.RED)));
                    player.playSound(player.getLocation(), config.getErrorSound(), 1f, 1f);
                    player.closeInventory();
                    return;
                }
                manager.setCreating(player.getUniqueId(), true);
                player.closeInventory();
                player.sendMessage(config.getPrefix().append(Component.text("Type your ticket subject in chat now.", NamedTextColor.YELLOW)));
                player.playSound(player.getLocation(), config.getUiClickSound(), 1f, 1f);

            } else if (action.startsWith("PAGE_")) {
                int targetPage = Integer.parseInt(action.replace("PAGE_", ""));
                TicketGUI.openDashboard(player, manager, player.hasPermission("easyticket.staff"), targetPage);
                player.playSound(player.getLocation(), config.getUiClickSound(), 1f, 1f);

            } else if (action.equals("CYCLE_SORT")) {
                manager.cycleSortMode(player.getUniqueId());
                TicketGUI.openDashboard(player, manager, player.hasPermission("easyticket.staff"), 0);
                player.playSound(player.getLocation(), config.getUiClickSound(), 1f, 1.5f);

            } else if (action.equals("TOGGLE_NOTIFS")) {
                manager.toggleNotifications(player.getUniqueId());
                TicketGUI.openDashboard(player, manager, true, 0);
                player.playSound(player.getLocation(), config.getUiClickSound(), 1f, 1f);
            }
            return;
        }

        // --- TICKET HEADS ---
        String idStr = item.getItemMeta().getPersistentDataContainer().get(TicketGUI.TICKET_ID_KEY, PersistentDataType.STRING);
        if (idStr == null) return;
        UUID tid = UUID.fromString(idStr);
        Ticket t = manager.getTicket(tid);

        if (t == null) {
            player.sendMessage(config.getPrefix().append(Component.text("Ticket no longer exists.", NamedTextColor.RED)));
            player.playSound(player.getLocation(), config.getErrorSound(), 1f, 1f);
            player.closeInventory();
            return;
        }

        if (event.isLeftClick()) {
            manager.enterChat(player.getUniqueId(), tid);
            player.closeInventory();
            player.sendMessage(config.getPrefix().append(Component.text("--- ENTERED CHATROOM ---", NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Type your messages. Type 'exit' to leave.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("(Global chat is hidden while in this ticket.)", NamedTextColor.DARK_GRAY));
            for (String line : t.getTranscript()) player.sendMessage(Component.text(line, NamedTextColor.WHITE));
            player.playSound(player.getLocation(), config.getSuccessSound(), 1f, 1f);

        } else if (event.isRightClick() && player.hasPermission("easyticket.staff")) {
            Component closeNotice = config.getPrefix()
                    .append(Component.text("Your ticket has been closed by ", NamedTextColor.RED))
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(".", NamedTextColor.RED));

            Player creator = Bukkit.getPlayer(t.getCreatorUUID());
            if (creator != null && creator.isOnline()) {
                creator.sendMessage(closeNotice);
                creator.playSound(creator.getLocation(), config.getErrorSound(), 1f, 0.8f);
            } else {
                manager.addPendingNotification(t.getCreatorUUID(), closeNotice);
            }

            manager.closeTicket(tid, player.getName());
            player.sendMessage(config.getPrefix().append(Component.text("Ticket Closed.", NamedTextColor.RED)));
            player.playSound(player.getLocation(), config.getUiClickSound(), 1f, 0.5f);
            TicketGUI.openDashboard(player, manager, true, 0);

        } else if (event.getClick() == ClickType.MIDDLE && player.hasPermission("easyticket.staff")) {
            Player target = Bukkit.getPlayer(t.getCreatorUUID());
            if (target != null && target.isOnline()) {
                player.teleport(target.getLocation());
                player.sendMessage(config.getPrefix().append(Component.text("Teleported to " + target.getName(), NamedTextColor.YELLOW)));
            } else {
                player.sendMessage(config.getPrefix().append(Component.text("Player is offline.", NamedTextColor.RED)));
                player.playSound(player.getLocation(), config.getErrorSound(), 1f, 1f);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message());

        // --- TICKET CREATION ---
        if (manager.isCreating(player.getUniqueId())) {
            event.setCancelled(true);
            manager.setCreating(player.getUniqueId(), false);
            Ticket t = new Ticket(player.getUniqueId(), player.getName(), msg);
            manager.createTicket(t);
            manager.enterChat(player.getUniqueId(), t.getId());
            player.sendMessage(config.getPrefix().append(Component.text("Ticket created! You are now in the ticket chat.", NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Type your messages. Type 'exit' to leave. (Global chat is hidden)", NamedTextColor.GRAY));
            player.playSound(player.getLocation(), config.getSuccessSound(), 1f, 1f);
            return;
        }

        // --- ACTIVE TICKET CHAT ---
        UUID activeTid = manager.getActiveTicketForPlayer(player.getUniqueId());
        if (activeTid != null) {
            event.setCancelled(true);
            Ticket t = manager.getTicket(activeTid);

            if (t == null) {
                manager.leaveChat(player.getUniqueId());
                return;
            }

            if (msg.equalsIgnoreCase("exit")) {
                manager.leaveChat(player.getUniqueId());
                player.sendMessage(config.getPrefix().append(Component.text("You left the ticket chat.", NamedTextColor.GOLD)));
                replaymissedGlobalChat(player);
                return;
            }

            t.addMessage(player.getName(), msg);
            manager.saveActiveTicket(t);

            Component chatLine = config.getPrefix().append(Component.text(player.getName() + ": " + msg, NamedTextColor.WHITE));

            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getUniqueId().equals(t.getCreatorUUID()) || p.hasPermission("easyticket.staff"))
                    .forEach(p -> {
                        p.sendMessage(chatLine);
                        if (p.hasPermission("easyticket.staff") && manager.hasNotifications(p.getUniqueId())) {
                            p.playSound(p.getLocation(), config.getNotifySound(), 1f, 1f);
                        } else if (p.getUniqueId().equals(t.getCreatorUUID())
                                && !p.getUniqueId().equals(player.getUniqueId())) {
                            p.playSound(p.getLocation(), config.getNotifySound(), 1f, 1f);
                        }
                    });

            // If the creator is offline, queue the message for them
            if (Bukkit.getPlayer(t.getCreatorUUID()) == null) {
                manager.addPendingNotification(t.getCreatorUUID(), chatLine);
            }
            return;
        }

        event.viewers().removeIf(viewer -> {
            if (viewer instanceof Player viewingPlayer) {
                if (manager.getActiveTicketForPlayer(viewingPlayer.getUniqueId()) != null) {
                    // Build a readable copy of the message to replay later
                    Component captured = Component.text("[Global] ", NamedTextColor.DARK_GRAY)
                            .append(player.name().colorIfAbsent(NamedTextColor.GRAY))
                            .append(Component.text(": ", NamedTextColor.GRAY))
                            .append(event.message());
                    missedGlobalChat
                            .computeIfAbsent(viewingPlayer.getUniqueId(), k -> new ArrayList<>())
                            .add(captured);
                    return true;
                }
            }
            return false;
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();

        List<Component> pending = manager.getPendingNotifications(uid);
        if (!pending.isEmpty()) {
            player.sendMessage(config.getPrefix()
                    .append(Component.text("--- " + pending.size() + " notification(s) while you were offline ---", NamedTextColor.GOLD)));
            for (Component notification : pending) {
                player.sendMessage(notification);
            }
            manager.clearPendingNotifications(uid);
            player.playSound(player.getLocation(), config.getNotifySound(), 1f, 1f);
        }

        replaymissedGlobalChat(player);
    }

    private void replaymissedGlobalChat(Player player) {
        List<Component> missed = missedGlobalChat.remove(player.getUniqueId());
        if (missed != null && !missed.isEmpty()) {
            player.sendMessage(config.getPrefix()
                    .append(Component.text("--- " + missed.size() + " global message(s) you missed ---", NamedTextColor.DARK_GRAY)));
            for (Component line : missed) {
                player.sendMessage(line);
            }
        }
    }
}
