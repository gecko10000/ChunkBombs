package io.github.levtey.ChunkBombs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import com.palmergames.bukkit.towny.TownyAPI;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;

public class ChunkBombs extends JavaPlugin implements Listener {
	FileConfiguration config;
	File langFile;
	FileConfiguration lang;
	NamespacedKey chunkBombKey = new NamespacedKey(this, "chunkbomb");
	ItemStack confirmItem = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
	ItemStack cancelItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
	ItemStack fillerItem = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
	List<Inventory> openConfirmations = new ArrayList<>();
	Map<Player, Location> confirmationInventories = new HashMap<>();
	List<Player> confirmedPlayers = new ArrayList<>();
	List<String> blacklist = new ArrayList<>();
	int layerDelay;
	String prefix;
	boolean coreProtectEnabled;
	CoreProtectAPI coreProtect;

	@Override
	public void onEnable() {
		this.saveDefaultConfig();
		config = this.getConfig();
		createLangFile();
		Bukkit.getPluginManager().registerEvents(this, this);
		ItemMeta confirmItemMeta = confirmItem.getItemMeta();
		confirmItemMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&a&lCONFIRM"));
		confirmItem.setItemMeta(confirmItemMeta);
		ItemMeta cancelItemMeta = cancelItem.getItemMeta();
		cancelItemMeta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&c&lCANCEL"));
		cancelItem.setItemMeta(cancelItemMeta);
		ItemMeta fillerItemMeta = fillerItem.getItemMeta();
		fillerItemMeta.setDisplayName("");
		fillerItem.setItemMeta(fillerItemMeta);
		blacklist = config.getStringList("blacklist");
		layerDelay = config.getInt("layerDelay");
		prefix = lang.getString("prefix");
		coreProtectEnabled = Bukkit.getPluginManager().isPluginEnabled("CoreProtect");
		if (coreProtectEnabled) {
			coreProtect = ((CoreProtect) Bukkit.getServer().getPluginManager().getPlugin("CoreProtect")).getAPI();
		}
	}

	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("chunkbombs")) {
			if (args.length > 0) {
				if (args[0].equalsIgnoreCase("give")) {
					if (sender.hasPermission("chunkbombs.give")) {
						if (args.length > 1) {
							Player targetPlayer = Bukkit.getPlayer(args[1]);
							if (targetPlayer != null) {
								Map<Integer, ItemStack> extra = targetPlayer.getInventory().addItem(createChunkBomb());
								if (!extra.isEmpty()) {
									targetPlayer.getWorld().dropItemNaturally(targetPlayer.getLocation(), extra.get(0));
								}
								sender.sendMessage(parseLang(lang.getString("successfulGive"), args[1]));
								if (!sender.getName().equals(targetPlayer.getName())) {
									targetPlayer.sendMessage(parseLang(lang.getString("successfulGiveOtherPlayer"),
											sender instanceof Player ? sender.getName() : "Console"));
								}
							} else {
								sender.sendMessage(parseLang(lang.getString("invalidPlayer"), args[1]));
							}
						} else {
							if (sender instanceof Player) {
								Map<Integer, ItemStack> extra = ((Player) sender).getInventory().addItem(createChunkBomb());
								if (!extra.isEmpty()) {
									((Player) sender).getWorld().dropItemNaturally(((Player) sender).getLocation(), extra.get(0));
								}
								sender.sendMessage(parseLang(lang.getString("successfulGive"), sender.getName()));
							} else {
								sender.sendMessage(parseLang(lang.getString("cannotGiveToConsole"), sender.getName()));
							}
						}
					} else {
						sender.sendMessage(parseLang(lang.getString("noPerms"), null));
					}
					return true;
				} else if (args[0].equalsIgnoreCase("reload")) {
					if (sender.hasPermission("chunkbombs.reload")) {
						for (Player player : confirmationInventories.keySet()) {
							player.closeInventory();
						}
						confirmationInventories.clear();
						this.saveDefaultConfig();
						this.reloadConfig();
						config = this.getConfig();
						createLangFile();
						blacklist = config.getStringList("blacklist");
						layerDelay = config.getInt("layerDelay");
						prefix = lang.getString("prefix");
						sender.sendMessage(parseLang(lang.getString("reload"), null));
					} else {
						sender.sendMessage(parseLang(lang.getString("noPerms"), null));
					}
					return true;
				}
			}
		}
		for (String helpLine : lang.getStringList("helpText")) {
			sender.sendMessage(parseLang(helpLine, sender.getName()));
		}
		return true;
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	public void onPlace(BlockPlaceEvent evt) {
		ItemStack itemInHand = evt.getItemInHand();
		if (itemInHand.getType().equals(Material.valueOf(config.getString("item.material").toUpperCase()))
				&& itemInHand.getItemMeta().getPersistentDataContainer().has(chunkBombKey, PersistentDataType.BYTE)) {
			evt.setCancelled(true);
			if (evt.getPlayer().hasPermission("chunkbombs.use")) {
				if (!Bukkit.getPluginManager().isPluginEnabled("Towny") || !config.getBoolean("inTownsOnly")
						|| (TownyAPI.getInstance().getTownName(evt.getBlock().getLocation()) != null)) {
					if (config.getBoolean("confirmation.enabled")) {
						Inventory confirmInventory = Bukkit.createInventory(evt.getPlayer(),
								config.getInt("confirmation.size") * 9,
								ChatColor.translateAlternateColorCodes('&',
										config.getString("confirmation.inventoryName").replaceAll("%y%",
												"" + evt.getBlock().getY())));
						for (int i = 0; i < config.getInt("confirmation.size"); i++) {
							for (int j = 0; j < 4; j++) {
								confirmInventory.setItem(i * 9 + j, confirmItem);
							}
							confirmInventory.setItem(i * 9 + 4, fillerItem);
							for (int j = 5; j < 9; j++) {
								confirmInventory.setItem(i * 9 + j, cancelItem);
							}
						}
						confirmationInventories.put(evt.getPlayer(), evt.getBlock().getLocation());
						evt.getPlayer().openInventory(confirmInventory);
					} else {
						clearChunk(evt.getBlock().getLocation(), evt.getPlayer());
					}
				} else {
					evt.getPlayer().sendMessage(parseLang(lang.getString("cannotUseHere"), null));
				}
			} else {
				evt.getPlayer().sendMessage(parseLang(lang.getString("noUsePerms"), null));
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onClick(InventoryClickEvent evt) {
		if (evt.getView().getTitle()
				.equals(ChatColor.translateAlternateColorCodes('&',
						config.getString("confirmation.inventoryName").replaceAll("%y%",
								"" + (confirmationInventories.containsKey((Player) evt.getWhoClicked()) ? confirmationInventories.get((Player) evt.getWhoClicked()).getBlockY() : null))))
				&& evt.getCurrentItem() != null
				&& !evt.getClickedInventory().equals(evt.getWhoClicked().getInventory())) {
			evt.setCancelled(true);
			if (evt.getCurrentItem().isSimilar(confirmItem)) {
				clearChunk(confirmationInventories.get((Player) evt.getWhoClicked()), (Player) evt.getWhoClicked());
				confirmedPlayers.add((Player) evt.getWhoClicked());
				evt.getWhoClicked().closeInventory();
				confirmationInventories.remove(evt.getWhoClicked());
			} else if (evt.getCurrentItem().isSimilar(cancelItem)) {
				evt.getWhoClicked().closeInventory();
				confirmationInventories.remove(evt.getWhoClicked());
			}
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent evt) {
		if (evt.getView().getTitle()
				.equals(ChatColor.translateAlternateColorCodes('&',
						config.getString("confirmation.inventoryName").replaceAll("%y%",
								"" + (confirmationInventories.containsKey((Player) evt.getPlayer()) ? confirmationInventories.get((Player) evt.getPlayer()).getBlockY() : null))))
				&& !evt.getPlayer().getGameMode().equals(GameMode.CREATIVE)
				&& !evt.getInventory().equals(evt.getPlayer().getInventory())
				&& !confirmedPlayers.contains((Player) evt.getPlayer())) {
			evt.getPlayer().getInventory().addItem(createChunkBomb());
		} else if (confirmedPlayers.contains((Player) evt.getPlayer())) {
			confirmedPlayers.remove((Player) evt.getPlayer());
		}
	}

	public void clearChunk(final Location location, Player player) {
		final String playerName = player.getName();
		final int finalX = location.getChunk().getX() * 16;
		final int finalZ = location.getChunk().getZ() * 16;
		for (int y = location.getBlockY(); y >= location.getWorld().getMinHeight(); y--) {
			final int finalY = y;
			Bukkit.getScheduler().runTaskLater(this, new Runnable() {
				public void run() {
					for (int x = finalX; x < finalX + 16; x++) {
						for (int z = finalZ; z < finalZ + 16; z++) {
							Block blockToRemove = location.getWorld().getBlockAt(x, finalY, z);
							if (!blacklist.contains(blockToRemove.getType().toString())) {
								if (coreProtectEnabled) {
									coreProtect.logRemoval(playerName + "#chunkbomb", blockToRemove.getLocation(),
											blockToRemove.getType(), blockToRemove.getBlockData());
								}
								blockToRemove.setType(Material.AIR);
							}
						}
					}
					location.getWorld().playSound(
							new Location(location.getWorld(), location.getX(), finalY, location.getZ()),
							Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
				}
			}, (location.getBlockY() - y) * layerDelay);
		}
	}

	public ItemStack createChunkBomb() {
		ItemStack chunkBomb = new ItemStack(Material.valueOf(config.getString("item.material")));
		ItemMeta chunkBombMeta = chunkBomb.getItemMeta();
		chunkBombMeta.setDisplayName(parseLang(config.getString("item.name"), null));
		List<String> lore = config.getStringList("item.lore");
		for (int i = 0; i < lore.size(); i++) {
			lore.set(i, parseLang(lore.get(i), null));
		}
		chunkBombMeta.setLore(lore);
		if (config.getBoolean("item.enchanted")) {
			chunkBombMeta.addEnchant(Enchantment.DURABILITY, 1, true);
			chunkBombMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
		}
		chunkBombMeta.getPersistentDataContainer().set(chunkBombKey, PersistentDataType.BYTE, (byte) 0);
		chunkBomb.setItemMeta(chunkBombMeta);
		return chunkBomb;
	}

	public String parseLang(String input, String playerName) {
		return ChatColor.translateAlternateColorCodes('&',
				input.replaceAll("%prefix%", prefix).replaceAll("%player%", playerName));
	}

	public void createLangFile() {
		langFile = new File(getDataFolder(), "lang.yml");
		if (!langFile.exists()) {
			langFile.getParentFile().mkdirs();
			saveResource("lang.yml", false);
		}
		lang = new YamlConfiguration();
		try {
			lang.load(langFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}
	}
}
