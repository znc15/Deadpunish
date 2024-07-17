package com.littlesheep.deadpunish;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

public class Main extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private FileConfiguration config;
    private FileConfiguration langConfig;
    private Random random = new Random();

    @Override
    public void onEnable() {
        if (checkVault()) {
            getLogger().info("==========================================");
            getLogger().info(getDescription().getName());
            getLogger().info("版本: " + getDescription().getVersion());
            getLogger().info("作者: " + getDescription().getAuthors());
            getLogger().info("Github: https://github.com/znc15/deadpunish");
            getLogger().info("QQ群: 690216634");
            getLogger().info("Vault 插件已加载，继续启用插件。");
            setupConfig();
            createAndReleaseLangFiles(); // 确保释放所有语言文件
            setupLang();
            checkForUpdates();
            if (setupEconomy()) {
                getServer().getPluginManager().registerEvents(this, this);
                getLogger().info("Deadpunish 已启用！");
                getLogger().info("❛‿˂̵✧");
                getLogger().info("==========================================");
                // 注册命令处理器
                getCommand("dp").setExecutor(this);
                getCommand("deadpunish").setExecutor(this);

                // 启用 bStats
                if (config.getBoolean("enableMetrics", true)) {
                    int pluginId = 22675;
                    new Metrics(this, pluginId);
                }
            } else {
                getLogger().severe("未能设置 Vault 插件的经济服务提供者！禁用插件。");
                getLogger().info("❛‿˂̵✧");
                getLogger().info("==========================================");
                getServer().getPluginManager().disablePlugin(this);
            }
        } else {
            getLogger().severe("Vault 插件未找到！禁用插件。");
            getLogger().info("❛‿˂̵✧");
            getLogger().info("==========================================");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private boolean checkVault() {
        Plugin vaultPlugin = getServer().getPluginManager().getPlugin("Vault");
        return vaultPlugin != null && vaultPlugin.isEnabled();
    }

    private void setupConfig() {
        saveDefaultConfig();
        config = getConfig();
    }

    private void setupLang() {
        String lang = config.getString("language", "zh_CN");
        File langFile = new File(getDataFolder(), "lang" + File.separator + lang + ".yml");
        if (!langFile.exists()) {
            getLogger().severe("语言文件 " + lang + ".yml 未找到！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    private void createAndReleaseLangFiles() {
        File langDir = new File(getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        String[] languages = {"en_US.yml", "zh_CN.yml"};
        for (String lang : languages) {
            File langFile = new File(langDir, lang);
            if (!langFile.exists()) {
                saveResource("lang" + File.separator + lang, false);
            }
        }
    }

    private void checkForUpdates() {
        if (!config.getBoolean("checkForUpdates", true)) {
            return;
        }

        String currentVersion = getDescription().getVersion();
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://api.tcbmc.cc/update/Deadpunish/update.json").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            InputStream inputStream = connection.getInputStream();
            Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name());
            String jsonResponse = scanner.useDelimiter("\\A").next();
            scanner.close();

            JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
            String latestVersion = jsonObject.get("version").getAsString();

            if (!currentVersion.equals(latestVersion)) {
                getLogger().info(ChatColor.GREEN + "发现新版本 " + latestVersion + "，请前往 https://github.com/znc15/deadpunish 下载更新。");
            } else {
                getLogger().info("您用的是最新版本喵呜");
            }

        } catch (IOException e) {
            getLogger().warning("检查更新时出错：" + e.getMessage());
        }
    }

    private String getLangMessage(String key, Map<String, Object> placeholders) {
        String message = langConfig.getString("messages." + key, key);
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue().toString());
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        event.getDrops().clear(); // 清除掉落物品
        event.setKeepInventory(true); // 强制启用死亡不掉落

        // 使用 LuckPerms API 获取权限组
        String permissionGroup = getPermissionGroup(player);

        // 根据权限组扣除经验
        double expToDeduct = getExpDeduction(player, permissionGroup);
        player.setLevel(Math.max(0, player.getLevel() - (int) expToDeduct));

        // 根据配置文件的设置，决定扣除金币的方式
        double moneyToDeduct;
        int totalItems = 0;
        if (config.getBoolean("calculateBasedOnItems", false)) {
            totalItems = getTotalItems(player);
            moneyToDeduct = getMoneyDeductionBasedOnItems(player, permissionGroup);
        } else {
            moneyToDeduct = getMoneyDeduction(player, permissionGroup);
        }

        // 添加null检查，确保economy对象不为null
        if (economy != null) {
            economy.withdrawPlayer(player, moneyToDeduct);

            Map<String, Object> placeholders = new HashMap<>();
            placeholders.put("exp", Math.round(expToDeduct));
            placeholders.put("money", Math.round(moneyToDeduct));
            placeholders.put("items", totalItems);

            if (config.getBoolean("calculateBasedOnItems", false)) {
                player.sendMessage(getLangMessage("total_items", placeholders));
            }
            player.sendMessage(getLangMessage("death_punish", placeholders));

            // 添加检查，判断是否掉落物品
            double dropThreshold = config.getDouble("dropThreshold", 1000.0);
            if (economy.getBalance(player) < dropThreshold) {
                createDeathChest(player, placeholders);
                player.sendMessage(getLangMessage("low_balance", placeholders));
            } else {
                // 获取复活后的金币余额
                double balance = economy.getBalance(player);
                placeholders.put("balance", Math.round(balance));
                player.sendMessage(getLangMessage("balance_saved", placeholders));
            }
        }
    }

    private double getMoneyDeductionBasedOnItems(Player player, String permissionGroup) {
        ItemStack[] items = player.getInventory().getContents();
        double totalValue = 0.0;
        double itemValue = config.getDouble("itemValuePercentages." + permissionGroup, 1.0);
        for (ItemStack item : items) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                double randomMultiplier = 1.0 + (random.nextDouble() * (config.getDouble("itemValueVariance", 0.5) - 0.5));
                totalValue += item.getAmount() * itemValue * randomMultiplier;
            }
        }
        return totalValue;
    }

    private int getTotalItems(Player player) {
        ItemStack[] items = player.getInventory().getContents();
        int totalItems = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                totalItems += item.getAmount();
            }
        }
        return totalItems;
    }

    private void createDeathChest(Player player, Map<String, Object> placeholders) {
        Location location = player.getLocation();
        Block block = location.getBlock();
        block.setType(Material.CHEST);

        // 将物品放入箱子
        if (block.getState() instanceof org.bukkit.block.Chest) {
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) block.getState();
            ItemStack[] playerItems = player.getInventory().getContents();
            for (ItemStack item : playerItems) {
                if (item != null && !item.getType().equals(Material.AIR)) {
                    chest.getBlockInventory().addItem(item);
                }
            }
            player.getInventory().clear();
        }

        // 在箱子上方放置玩家的头颅
        Block headBlock = block.getRelative(BlockFace.UP);
        headBlock.setType(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) Bukkit.getItemFactory().getItemMeta(Material.PLAYER_HEAD);
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            headBlock.getState().update();
        }

        // 在箱子前面放置告示牌并设置方向
        BlockFace chestFacing = ((Directional) block.getBlockData()).getFacing();
        Block signBlock = block.getRelative(chestFacing);
        signBlock.setType(Material.OAK_WALL_SIGN);
        if (signBlock.getBlockData() instanceof WallSign) {
            WallSign signData = (WallSign) signBlock.getBlockData();
            signData.setFacing(chestFacing.getOppositeFace());
            signBlock.setBlockData(signData);
        }

        if (signBlock.getState() instanceof Sign) {
            Sign sign = (Sign) signBlock.getState();
            List<String> signLines = langConfig.getStringList("deathChestSignLines");
            for (int i = 0; i < Math.min(signLines.size(), 4); i++) {
                String line = signLines.get(i);
                line = line.replace("{player}", player.getName());
                sign.setLine(i, ChatColor.translateAlternateColorCodes('&', line));
            }
            sign.update();
        }
    }


    private String getPermissionGroup(Player player) {
        User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        if (user != null) {
            return user.getPrimaryGroup();
        }
        return "default";
    }

    private double getExpDeduction(Player player, String permissionGroup) {
        double percentage = config.getDouble("expDeductionPercentages." + permissionGroup, 2.0) / 100.0;
        return player.getLevel() * percentage;
    }

    private double getMoneyDeduction(Player player, String permissionGroup) {
        double percentage = config.getDouble("moneyDeductionPercentages." + permissionGroup, 5.0) / 100.0;
        return economy.getBalance(player) * percentage;
    }

    private void dropPlayerItems(Player player) {
        ItemStack[] playerItems = player.getInventory().getContents();
        for (ItemStack item : playerItems) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }
        player.getInventory().clear();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null || rsp.getProvider() == null) {
            return false;
        }

        economy = rsp.getProvider();
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("dp") || label.equalsIgnoreCase("deadpunish")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                // 重新加载配置文件
                reloadConfig();
                config = getConfig();
                setupLang(); // 重新加载语言文件
                getLogger().info("==========================================");
                sender.sendMessage(ChatColor.GREEN + "[Deadpunish] 插件配置文件已重新加载。");
                getLogger().info("==========================================");
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDisable() {
        getLogger().info("==========================================");
        getLogger().info("Goodbye! 插件已关闭。");
        getLogger().info("==========================================");
    }
}
