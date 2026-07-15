package pro.gravit.launchermodules.s3updates;

import pro.gravit.launcher.Launcher;
import pro.gravit.launcher.config.SimpleConfigurable;
import pro.gravit.launcher.hasher.HashedDir;
import pro.gravit.launcher.hasher.HashedFile;
import pro.gravit.launcher.modules.LauncherInitContext;
import pro.gravit.launcher.modules.LauncherModule;
import pro.gravit.launcher.modules.LauncherModuleInfo;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.config.LaunchServerConfig;
import pro.gravit.launchserver.modules.events.LaunchServerFullInitEvent;
import pro.gravit.launchserver.modules.impl.LaunchServerInitContext;
import pro.gravit.utils.Version;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class S3UpdatesProviderModule extends LauncherModule {
    public static final Version version = new Version(1, 0, 0, 1, Version.Type.BETA);
    private static final String MODULE_NAME = "S3UpdatesProvider";
    private LaunchServer server;
    private S3UpdatesProviderConfig config;
    private ScheduledExecutorService refreshExecutor;

    public S3UpdatesProviderModule() {
        super(new LauncherModuleInfo(MODULE_NAME, version, new String[]{"LaunchServerCore"}));
    }

    @Override
    public void init(LauncherInitContext initContext) {
        if (initContext instanceof LaunchServerInitContext) {
            onFullInit(new LaunchServerFullInitEvent(((LaunchServerInitContext) initContext).server));
        } else {
            registerEvent(this::onFullInit, LaunchServerFullInitEvent.class);
        }
    }

    private void onFullInit(LaunchServerFullInitEvent event) {
        this.server = event.server;
        this.config = loadConfig();
        this.server.commandHandler.registerCommand("s3UpdatesSync", new S3UpdatesSyncCommand(server, this));
        if (config.enabled && config.refreshOnStart) {
            syncQuietly();
        }
        if (config.enabled && config.refreshIntervalSeconds > 0) {
            refreshExecutor = Executors.newSingleThreadScheduledExecutor((task) -> {
                Thread thread = new Thread(task, "S3UpdatesProvider refresh");
                thread.setDaemon(true);
                return thread;
            });
            refreshExecutor.scheduleWithFixedDelay(this::syncQuietly,
                    config.refreshIntervalSeconds,
                    config.refreshIntervalSeconds,
                    TimeUnit.SECONDS);
        }
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (IOException e) {
            LogHelper.error(e);
        }
    }

    private S3UpdatesProviderConfig loadConfig() {
        SimpleConfigurable<S3UpdatesProviderConfig> configurable = modulesConfigManager.getConfigurable(S3UpdatesProviderConfig.class, MODULE_NAME);
        try {
            configurable.loadConfig();
        } catch (IOException e) {
            LogHelper.error(e);
            return new S3UpdatesProviderConfig();
        }
        S3UpdatesProviderConfig loaded = configurable.getConfig();
        return loaded == null ? new S3UpdatesProviderConfig() : loaded;
    }

    public synchronized void sync(String... requestedProfiles) throws IOException {
        if (server == null) {
            throw new IOException("LaunchServer is not initialized");
        }
        if (config == null) {
            config = loadConfig();
        }
        if (!config.enabled) {
            LogHelper.info("S3UpdatesProvider is disabled");
            return;
        }
        if (config.profiles == null || config.profiles.isEmpty()) {
            LogHelper.warning("S3UpdatesProvider has no configured profiles");
            return;
        }

        Set<String> profiles = selectProfiles(requestedProfiles);
        Map<String, HashedDir> next = new HashMap<>(server.updatesDirMap);
        for (String profile : profiles) {
            S3UpdatesProviderConfig.ProfileConfig profileConfig = config.profiles.get(profile);
            if (profileConfig == null) {
                throw new IOException("Unknown S3 updates profile: " + profile);
            }
            ClientIndex index = loadIndex(profileConfig.indexUrl);
            validateIndex(profile, index);
            HashedDir dir = buildHashedDir(index);
            next.put(profile, dir);
            applyDownloadBinding(profile, profileConfig);
            LogHelper.info("Loaded S3 update index profile=%s version=%s files=%d", profile, index.version, index.files.size());
        }
        server.updatesDirMap = Collections.unmodifiableMap(next);
    }

    private Set<String> selectProfiles(String... requestedProfiles) {
        if (requestedProfiles == null || requestedProfiles.length == 0) {
            return new LinkedHashSet<>(config.profiles.keySet());
        }
        return new LinkedHashSet<>(Arrays.asList(requestedProfiles));
    }

    private ClientIndex loadIndex(String indexUrl) throws IOException {
        if (indexUrl == null || indexUrl.trim().isEmpty()) {
            throw new IOException("indexUrl is empty");
        }
        URL url = new URL(indexUrl);
        try (Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            return Launcher.gsonManager.configGson.fromJson(reader, ClientIndex.class);
        }
    }

    private void validateIndex(String profile, ClientIndex index) throws IOException {
        if (index == null) {
            throw new IOException("Index is empty for profile " + profile);
        }
        if (index.schemaVersion != 1) {
            throw new IOException("Unsupported index schemaVersion " + index.schemaVersion + " for profile " + profile);
        }
        if (!profile.equals(index.profile)) {
            throw new IOException("Index profile mismatch: expected " + profile + ", got " + index.profile);
        }
        if (index.files == null || index.files.isEmpty()) {
            throw new IOException("Index files are empty for profile " + profile);
        }
        for (ClientIndex.FileEntry file : index.files) {
            validateFileEntry(file);
        }
    }

    private void validateFileEntry(ClientIndex.FileEntry file) throws IOException {
        if (file == null) {
            throw new IOException("Index contains null file entry");
        }
        if (file.path == null || file.path.isEmpty()) {
            throw new IOException("Index contains empty file path");
        }
        if (file.path.startsWith("/") || file.path.contains("..") || file.path.contains("//")) {
            throw new IOException("Unsafe index file path: " + file.path);
        }
        if (!(file.path.startsWith("mods/") || file.path.startsWith("data/"))) {
            throw new IOException("Unsupported index file path: " + file.path);
        }
        if (file.size < 0) {
            throw new IOException("Negative file size for " + file.path);
        }
        if (file.md5 == null || !file.md5.matches("^[0-9a-fA-F]{32}$")) {
            throw new IOException("Invalid MD5 for " + file.path);
        }
    }

    private HashedDir buildHashedDir(ClientIndex index) {
        HashedDir root = new HashedDir();
        for (ClientIndex.FileEntry file : index.files) {
            addFile(root, file.path, file.size, hexToBytes(file.md5));
        }
        return root;
    }

    private void addFile(HashedDir root, String path, long size, byte[] md5) {
        String[] parts = path.split("/");
        HashedDir current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.getOrCreateDir(parts[i]);
        }
        current.putEntry(parts[parts.length - 1], new HashedFile(size, md5));
    }

    private byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int offset = i * 2;
            result[i] = (byte) Integer.parseInt(hex.substring(offset, offset + 2), 16);
        }
        return result;
    }

    private void applyDownloadBinding(String profile, S3UpdatesProviderConfig.ProfileConfig profileConfig) {
        if (profileConfig.downloadUrl == null || profileConfig.downloadUrl.trim().isEmpty()) {
            return;
        }
        LaunchServerConfig.NettyUpdatesBind bind = server.config.netty.bindings.get(profile);
        if (bind == null) {
            bind = new LaunchServerConfig.NettyUpdatesBind();
            server.config.netty.bindings.put(profile, bind);
        }
        bind.url = profileConfig.downloadUrl;
        bind.zip = false;
    }
}
