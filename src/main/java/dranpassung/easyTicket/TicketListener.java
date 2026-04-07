package dranpassung.easyTicket;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class TicketListener implements Listener {
    private final TicketManager manager;
    private final ConfigManager config;

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
            // FIX 2: Inform the player that global chat is now hidden
            player.sendMessage(Component.text("(Global chat is hidden while in this ticket.)", NamedTextColor.DARK_GRAY));
            for (String line : t.getTranscript()) player.sendMessage(Component.text(line, NamedTextColor.WHITE));
            player.playSound(player.getLocation(), config.getSuccessSound(), 1f, 1f);

        } else if (event.isRightClick() && player.hasPermission("easyticket.staff")) {
            manager.closeTicket(tid, player.getName());
            player.sendMessage(config.getPrefix().append(Component.text("Ticket Closed.", NamedTextColor.RED)));
            player.playSound(player.getLocation(), config.getUiClickSound(), 1f, 0.5f);
            TicketGUI.openDashboard(player, manager, true, 0);

        } else if (event.getClick().toString().contains("MIDDLE") && player.hasPermission("easyticket.staff")) {
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

            // 1: Auto-enter the creator into the ticket chat immediately.
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
            return;
        }
        // 2: Remove any player currently inside a ticket chat from the viewer set
        event.viewers().removeIf(viewer -> {
            if (viewer instanceof Player viewingPlayer) {
                return manager.getActiveTicketForPlayer(viewingPlayer.getUniqueId()) != null;
            }
            return false;
        });
    }
}
