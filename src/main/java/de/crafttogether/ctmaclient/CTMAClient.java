package de.crafttogether.ctmaclient;

import com.froobworld.viewdistancetweaks.ViewDistanceTweaks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class CTMAClient implements Runnable {
    private static CTMAClient client;
    private static BukkitTask clientTask;

    private CTMAClientPlugin plugin;

    private String clientName;
    private String authKey;
    private String host;
    private int port;
    private int sendInterval;
    private int heartbeat;
    private int connectionAttempts;
    private boolean shutdown;
    private boolean isConnected;
    private boolean isRegistered;
    private boolean isReconnecting;

    private JSONObject serverInfo;
    private long lastHeartBeat;

    private boolean debug;

    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;

    public CTMAClient(String host, int port, String clientName, String authKey) {
        client = this;

        this.plugin = CTMAClientPlugin.getInstance();
        this.serverInfo = new JSONObject();
        this.lastHeartBeat = 0;

        this.clientName = clientName;
        this.authKey = authKey;
        this.host = host;
        this.port = port;

        this.connectionAttempts = 0;
        this.shutdown = false;
        this.isConnected = false;
        this.isRegistered = false;
        this.isReconnecting = false;

        this.heartbeat = plugin.getConfig().getInt("heartbeat");
        this.sendInterval = plugin.getConfig().getInt("sendInterval");
        this.debug = plugin.getConfig().getBoolean("debug");
    }

    @Override
    public void run() {
        shutdown = false;
        isConnected = false;
        isRegistered = false;

        try {
            socket = new Socket(host, port);
            writer = new PrintWriter(socket.getOutputStream(), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            String message = e.getMessage();

            if (e.getMessage().contains("Connection refused"))
                message = "Connection refused";

            if (!isReconnecting)
                plugin.getLogger().warning("Error: Could not connect to " + host + ":" + port + " (" + message + ")");

            if (message == e.getMessage())
                e.printStackTrace();

            closeConnection(true);
            return;
        }

        plugin.getLogger().info("Connection established.");

        isConnected = true;
        isReconnecting = false;
        connectionAttempts = 0;
        register(clientName, authKey);

        try {
            String inputLine;
            while ((inputLine = reader.readLine()) != null) {
                if (inputLine.strip().length() < 1)
                    continue;

                JSONObject packet = null;
                try {
                    packet = new JSONObject(inputLine);
                }catch (JSONException e){
                    e.printStackTrace();
                }

                if (packet != null && packet.has("error")) {
                    String err = packet.getString("error");

                    plugin.getLogger().warning("Error:");
                    plugin.getLogger().warning(err);
                    continue;
                }

                if (packet != null && packet.has("evt")) {
                    String evt = packet.getString("evt");
                    if (evt.equals("auth-success"))
                        onReady();
                    continue;
                }

                if (debug) {
                    plugin.getLogger().info("Received message from CTMA:");
                    plugin.getLogger().info(packet.toString());
                }
            }
        } catch (Exception e) {
            if (!e.getMessage().equalsIgnoreCase("socket closed") && !e.getMessage().equalsIgnoreCase("connection reset"))
                e.printStackTrace();
            else
                plugin.getLogger().warning(e.getMessage());
        }

        if (isConnected && !shutdown)
            plugin.getLogger().warning("lost connection to server");

        closeConnection(true);
    }

    private void onReady() {
        if (this.isRegistered)
            return;
        else
            isRegistered = true;

        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            this.lastHeartBeat = System.currentTimeMillis() / 1000L;
        }, 0L, 20L * heartbeat);

        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            // Get actual data
            getServerInfo();

            // Send data
            emit("server-heartbeat", lastHeartBeat, serverInfo);
        }, 20L, 20L * sendInterval);
    }

    private void getServerInfo() {
        double tps = 0, mspt = 0;
        JSONObject worlds = null;

        int globalChunkCount = 0;
        int globalEntityCount = 0;
        int globalTileEntityCount = 0;
        int globalTickableTileEntityCount = 0;

        int viewDistance = 0;
        int noTickViewDistance = 0;

        try {
            ViewDistanceTweaks vdt = plugin.getVdt();
            World defaultWorld = Bukkit.getWorlds().get(0);
            worlds = new JSONObject();

            if (vdt != null) {
                tps = vdt.getTaskManager().getTpsTracker().getTps();
                mspt = vdt.getTaskManager().getMsptTracker().getMspt();
                viewDistance = vdt.getHookManager().getViewDistanceHook().getViewDistance(defaultWorld);
                noTickViewDistance = vdt.getHookManager().getNoTickViewDistanceHook().getViewDistance(defaultWorld);
            }
            else {
                viewDistance = defaultWorld.getViewDistance();
                noTickViewDistance = defaultWorld.getNoTickViewDistance();
            }

            for (World world : Bukkit.getServer().getWorlds()) {
                JSONObject worldInfo = new JSONObject();

                globalChunkCount += world.getChunkCount();
                globalEntityCount += world.getEntityCount();
                globalTileEntityCount += world.getTileEntityCount();
                globalTickableTileEntityCount += world.getTickableTileEntityCount();

                worldInfo.put("environment", world.getEnvironment().name());
                worldInfo.put("chunks", world.getChunkCount());
                worldInfo.put("entities", world.getEntityCount());
                worldInfo.put("tileEntities", world.getTileEntityCount());
                worldInfo.put("tickableTileEntities", world.getTickableTileEntityCount());

                if (vdt != null) {
                    worldInfo.put("viewDistance", vdt.getHookManager().getViewDistanceHook().getViewDistance(world));
                    worldInfo.put("noTickViewDistance", vdt.getHookManager().getNoTickViewDistanceHook().getViewDistance(world));
                }
                else {
                    worldInfo.put("viewDistance", world.getViewDistance());
                    worldInfo.put("noTickViewDistance", world.getNoTickViewDistance());
                }

                worlds.put(world.getName(), worldInfo);
            }
        }
        catch(Exception ex) {
            plugin.getLogger().warning(ex.getMessage());
        }

        serverInfo.put("tps", tps);
        serverInfo.put("mspt", mspt);
        serverInfo.put("players", Bukkit.getServer().getOnlinePlayers().size());
        serverInfo.put("chunks", globalChunkCount);
        serverInfo.put("entities", globalEntityCount);
        serverInfo.put("tileEntities", globalTileEntityCount);
        serverInfo.put("tickableTileEntities", globalTickableTileEntityCount);
        serverInfo.put("viewDistance", viewDistance);
        serverInfo.put("noTickViewDistance", noTickViewDistance);
        serverInfo.put("worlds", worlds);
    }

    public void connect() {
        if (!isReconnecting)
            plugin.getLogger().info("Connecting to " + host + ":" + port + "...");

        clientTask = CTMAClientPlugin.getInstance().getServer().getScheduler().runTaskAsynchronously(CTMAClientPlugin.getInstance(), this);
    }

    private void retryConnect() {
        if (shutdown || isConnected)
            return;

        int delay = 1;

        connectionAttempts++;
        if (connectionAttempts > 10) delay = 3;
        if (connectionAttempts > 20) delay = 5;

        if (!isReconnecting)
            plugin.getLogger().info("Try to reconnect");

        isReconnecting = true;


        Bukkit.getScheduler().runTaskAsynchronously(CTMAClientPlugin.getInstance(), () -> {
            plugin.getServer().getScheduler().cancelTasks(plugin);
            client.connect();
        });

        if (debug)
            plugin.getLogger().info("Try to reconnect to " + host + ":" + port + " in " + delay + " Seconds");
    }

    public void close() {
        this.shutdown = true;
        this.closeConnection(false);
    }

    private void closeConnection(boolean retry) {
        isConnected = false;

        if (writer != null) {
            writer.flush();
            writer.close();
        }

        try {
            if (reader != null)
                reader.close();
            if (socket != null)
                socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (retry)
            retryConnect();
    }

    private void register(String name, String key) {
        emit("auth", "ctma-client", name, key);
    }

    public void emit(String event, Object ...args) {
        JSONArray argList = new JSONArray();
        for (Object arg : args) argList.put(arg);

        JSONObject packet = new JSONObject();
        packet.put("evt", event);
        packet.put("args", argList);
        sendPacket(packet);
    }

    private void sendPacket(JSONObject packet) {
        String strPacket = packet.toString();

        // TODO: Exception?
        if (strPacket == null)
            return;

        writer.println(strPacket);
        writer.flush();
    }

    public String getName() {
        return clientName;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isRegistered() {
        return isRegistered;
    }

    public static CTMAClient getInstance() {
        return client;
    }
}