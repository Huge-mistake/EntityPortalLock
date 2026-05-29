package com.EntityPortalLock;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EntityPortalLock extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

	private final Set<EntityType> targetEntities = ConcurrentHashMap.newKeySet();
	private volatile boolean isBlacklistMode = true;
	private FileConfiguration defaultConfig;
	private BukkitAudiences adventure;
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private volatile String PREFIX = "";

	private static final List<String> ALL_ENTITY_TYPES = Arrays.stream(EntityType.values())
			.map(EntityType::name)
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.collect(Collectors.toList());

	private List<String> filterCompletions(List<String> candidates, String input) {
		String lowerInput = input.toLowerCase(Locale.ROOT);
		return candidates.stream()
				.filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lowerInput))
				.collect(Collectors.toList());
	}

	private void sendMessage(CommandSender sender, String configKey, String... replacements) {
		FileConfiguration config = getConfig();
		String message = config.getString(configKey);
		if (message == null || message.trim().isEmpty()) {
			if (defaultConfig != null) {
				message = defaultConfig.getString(configKey, "");
			} else {
				message = "";
			}
		}
		message = message.replace("%Prefix%", PREFIX);
		if (replacements != null) {
			for (int i = 0; i < replacements.length - 1; i += 2) {
				message = message.replace(replacements[i], replacements[i + 1]);
			}
		}
		Component component = MINI_MESSAGE.deserialize(message);
		this.adventure().sender(sender).sendMessage(component);
	}

	public BukkitAudiences adventure() {
		if (this.adventure == null) {
			throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
		}
		return this.adventure;
	}

	@Override
	public void onEnable() {
		new Metrics(this, 27946);
		this.adventure = BukkitAudiences.create(this);
		try {
			defaultConfig = YamlConfiguration.loadConfiguration(
					new InputStreamReader(Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)
			);
		} catch (Exception e) {
			sendMessage(Bukkit.getConsoleSender(), "Messages.config_load_fail");
		}
		getServer().getPluginManager().registerEvents(this, this);
		saveDefaultConfig();
		loadConfigSettings();
		PluginCommand cmd = this.getCommand("eplock");
		if (cmd != null) {
			cmd.setExecutor(this);
			cmd.setTabCompleter(this);
		} else {
			sendMessage(Bukkit.getConsoleSender(), "Messages.command_register_fail");
		}
		sendMessage(Bukkit.getConsoleSender(), "Messages.enable_plugin");
		getLogger().info("Issue_Feedback: https://github.com/CatTeaA/EntityPortalLock/issues");
	}

	@Override
	public void onDisable() {
		sendMessage(Bukkit.getConsoleSender(), "Messages.disable_plugin");
		if (this.adventure != null) {
			this.adventure.close();
			this.adventure = null;
		}
	}

	@Override
	public boolean onCommand(CommandSender sender, @NotNull Command cmd,
	                         @NotNull String label, String @NotNull [] args) {
		if (!sender.hasPermission("entityportallock.admin")) {
			sendMessage(sender, "Messages.no_permission");
			return true;
		}
		if (args.length == 0) {
			sendMessage(sender, "Messages.invalid_command");
			return true;
		}
		// EntityPortalLock add
		if (args[0].equalsIgnoreCase("add")) {
			if (args.length < 2) {
				sendMessage(sender, "Messages.usage_add");
				return true;
			}
			try {
				EntityType type = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
				if (targetEntities.add(type)) {
					saveEntityListToConfig();
					sendMessage(sender, "Messages.type_add", "%entity%", type.name());
				} else {
					sendMessage(sender, "Messages.type_already_in_list", "%entity%", type.name());
				}
			} catch (IllegalArgumentException e) {
				sendMessage(sender, "Messages.invalid_type", "%entity%", args[1]);
			}
			return true;
		}
		// EntityPortalLock remove
		if (args[0].equalsIgnoreCase("remove")) {
			if (args.length < 2) {
				sendMessage(sender, "Messages.usage_remove");
				return true;
			}
			try {
				EntityType type = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
				if (targetEntities.remove(type)) {
					saveEntityListToConfig();
					sendMessage(sender, "Messages.type_remove", "%entity%", type.name());
				} else {
					sendMessage(sender, "Messages.type_not_in_list", "%entity%", type.name());
				}
			} catch (IllegalArgumentException e) {
				sendMessage(sender, "Messages.invalid_type", "%entity%", args[1]);
			}
			return true;
		}
		// EntityPortalLock reload
		if (args[0].equalsIgnoreCase("reload")) {
			reloadConfigSettings();
			sendMessage(sender, "Messages.reload_success");
			return true;
		}
		// EntityPortalLock list
		if (args[0].equalsIgnoreCase("list")) {
			if (targetEntities.isEmpty()) {
				sendMessage(sender, "Messages.list_empty");
				return true;
			}
			String entityList = targetEntities.stream()
					.map(EntityType::name)
					.sorted()
					.collect(Collectors.joining(", "));
			sendMessage(sender, "Messages.entity_list", "%list%", entityList);
			return true;
		}
		// invalid command
		sendMessage(sender, "Messages.invalid_command");
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, @NotNull Command cmd,
	                                  @NotNull String label, String @NotNull [] args) {
		List<String> completions = new ArrayList<>();
		if (!sender.hasPermission("entityportallock.admin")) {
			return completions;
		}
		switch (args.length) {
			case 1:
				completions.add("add");
				completions.add("remove");
				completions.add("reload");
				completions.add("list");
				return filterCompletions(completions, args[0]);
			case 2:
				String subCommand = args[0].toLowerCase(Locale.ROOT);
				if (subCommand.equals("add") || subCommand.equals("remove")) {
					boolean isAdd = subCommand.equals("add");
					List<String> allEntityTypes = ALL_ENTITY_TYPES.stream()
							.filter(type -> isAdd != targetEntities.contains(EntityType.valueOf(type)))
							.collect(Collectors.toList());
					return filterCompletions(allEntityTypes, args[1]);
				}
				break;
		}
		return completions;
	}

	private void loadConfigSettings() {
		FileConfiguration config = getConfig();
		isBlacklistMode = config.getBoolean("ListMode.Blacklist", true);
		PREFIX = config.getString("Prefix");
		if (PREFIX == null || PREFIX.trim().isEmpty()) {
			if (defaultConfig != null) {
				PREFIX = defaultConfig.getString("Prefix");
			}
			if (PREFIX == null || PREFIX.trim().isEmpty()) {
				PREFIX = "<gold>[<white>EntityPortalLock<gold>]<reset>";
			}
		}
		List<String> entityNames = config.getStringList("EntityTypeList");
		List<EntityType> tempEntities = new ArrayList<>();
		List<String> loadedEntityNames = new ArrayList<>();
		for (String name : entityNames) {
			try {
				EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
				tempEntities.add(type);
				loadedEntityNames.add(type.name());
			} catch (IllegalArgumentException e) {
				sendMessage(Bukkit.getConsoleSender(), "Messages.type_load_warning", "%entity%", name);
			}
		}
		if (loadedEntityNames.isEmpty()) {
			sendMessage(Bukkit.getConsoleSender(), "Messages.list_empty");
		} else {
			String entityList = String.join(", ", loadedEntityNames);
			sendMessage(Bukkit.getConsoleSender(), "Messages.type_load_success", "%entity%", entityList);
		}
		tempEntities.sort(Comparator.comparing(EntityType::name));
		targetEntities.clear();
		targetEntities.addAll(tempEntities);
	}

	private void reloadConfigSettings() {
		File configFile = new File(getDataFolder(), "config.yml");
		if (!configFile.exists()) {
			saveDefaultConfig();
		}
		reloadConfig();
		loadConfigSettings();
		//sendMessage(Bukkit.getConsoleSender(), "Messages.reload_success");
	}

	private void saveEntityListToConfig() {
		FileConfiguration config = getConfig();
		List<String> entityNames = new ArrayList<>();
		for (EntityType type : targetEntities) {
			entityNames.add(type.name());
		}
		entityNames.sort(Comparator.naturalOrder());
		config.set("EntityTypeList", entityNames);
		saveConfig();
	}

	private boolean shouldDeny(EntityType type) {
		return isBlacklistMode == targetEntities.contains(type);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityPortal(EntityPortalEvent event) {
		if (shouldDeny(event.getEntityType())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerPortal(PlayerPortalEvent event) {
		if (shouldDeny(event.getPlayer().getType())) {
			event.setCancelled(true);
		}
	}
}
