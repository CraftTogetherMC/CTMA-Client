package de.crafttogether.ctmaclient;

import com.froobworld.viewdistancetweaks.ViewDistanceTweaks;
import org.bukkit.plugin.java.JavaPlugin;

public final class CTMAClientPlugin extends JavaPlugin {
    private static CTMAClientPlugin plugin;
    private static CTMAClient client;

    public static CTMAClientPlugin getInstance() {
        return plugin;
    }

    private ViewDistanceTweaks vdt;

    @Override
    public void onEnable() {
        plugin = this;

        if (getConfig() != null)
            saveDefaultConfig();

        vdt = (ViewDistanceTweaks) getServer().getPluginManager().getPlugin("ViewDistanceTweaks");

        if (vdt == null)
            getServer().getLogger().warning("Couldn't find plugin 'ViewDistanceTweaks'");

        String serverName = getConfig().getString("serverName");
        String authKey = getConfig().getString("authKey");
        String host = getConfig().getString("host");
        int port = getConfig().getInt("port");

        if (isSet(serverName) && isSet(host) && isSet(authKey) && port != 0) {
            client = new CTMAClient(host, port, serverName, authKey);
            client.connect();
        }

        else {
            getLogger().info("Check your config!");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private boolean isSet(String val) {
        if (val != null || val.length() > 0) return true;
        return false;
    }

    @Override
    public void onDisable() {
        if (client != null)
            client.close();

        getServer().getScheduler().cancelTasks(this);
    }

    public ViewDistanceTweaks getVdt() {
        return vdt;
    }
}
