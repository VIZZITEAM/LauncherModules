package pro.gravit.launchermodules.s3updates;

import pro.gravit.launcher.base.Launcher;
import pro.gravit.launcher.base.config.SimpleConfigurable;
import pro.gravit.launcher.base.modules.LauncherInitContext;
import pro.gravit.launcher.base.modules.LauncherModule;
import pro.gravit.launcher.base.modules.LauncherModuleInfo;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.core.hasher.HashedDir;
import pro.gravit.launcher.core.hasher.HashedFile;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.profiles.ProfilesProvider;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class S3UpdatesProviderModule extends LauncherModule {
    public static final Version version = new Version(1, 0, 0, 1, Version.Type.BETA);
    private static final String MODULE_NAME = "S3UpdatesProvider";
    private LaunchServer server;
    private S3UpdatesProviderConfig config;
    private ScheduledExecutorService refreshExecutor;
    private volatile Map<String, HashedDir> cachedDirs = Collections.emptyMap();

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
        if (!(this.server.config.profilesProvider instanceof S3ProfilesProvider)) {
            this.server.config.profilesProvider = new S3ProfilesProvider(this.server.config.profilesProvider, this);
        }
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
        } catch (Exception e) {
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
        Map<String, HashedDir> next = new HashMap<>(cachedDirs);
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
        cachedDirs = Collections.unmodifiableMap(next);
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
        if (file.sha1 == null || !file.sha1.matches("^[0-9a-fA-F]{40}$")) {
            throw new IOException("Invalid SHA-1 for " + file.path);
        }
    }

    private HashedDir buildHashedDir(ClientIndex index) {
        HashedDir root = new HashedDir();
        for (ClientIndex.FileEntry file : index.files) {
            addFile(root, file.path, file.size, hexToBytes(file.sha1));
        }
        return root;
    }

    private void addFile(HashedDir root, String path, long size, byte[] md5) {
        HashedDir.FindRecursiveResult parent = root.createParentDirectories(path);
        parent.parent.put(parent.name, new HashedFile(size, md5));
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
        if (server.config.netty.bindings == null) {
            server.config.netty.bindings = new HashMap<>();
        }
        LaunchServerConfig.NettyUpdatesBind bind = server.config.netty.bindings.get(profile);
        if (bind == null) {
            bind = new LaunchServerConfig.NettyUpdatesBind();
            server.config.netty.bindings.put(profile, bind);
        }
        bind.url = profileConfig.downloadUrl;
        bind.zip = false;
    }

    private HashedDir getCachedClientDir(ClientProfile profile) {
        if (profile == null) {
            return null;
        }
        HashedDir dir = cachedDirs.get(profile.getDir());
        if (dir != null) {
            return dir;
        }
        return cachedDirs.get(profile.getTitle());
    }

    private static class WrappedCompletedProfile implements ProfilesProvider.CompletedProfile {
        private final ProfilesProvider.CompletedProfile delegate;
        private final HashedDir clientDir;

        private WrappedCompletedProfile(ProfilesProvider.CompletedProfile delegate, HashedDir clientDir) {
            this.delegate = delegate;
            this.clientDir = clientDir;
        }

        @Override
        public String getTag() {
            return delegate.getTag();
        }

        @Override
        public HashedDir getClientDir() {
            return clientDir;
        }

        @Override
        public HashedDir getAssetDir() {
            return delegate.getAssetDir();
        }

        @Override
        public ClientProfile getProfile() {
            return delegate.getProfile();
        }

        @Override
        public UUID getUuid() {
            return delegate.getUuid();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public String getDefaultTag() {
            return delegate.getDefaultTag();
        }
    }

    private static class S3ProfilesProvider extends ProfilesProvider {
        private final ProfilesProvider delegate;
        private final S3UpdatesProviderModule module;

        private S3ProfilesProvider(ProfilesProvider delegate, S3UpdatesProviderModule module) {
            this.delegate = delegate;
            this.module = module;
        }

        private CompletedProfile wrap(CompletedProfile profile) {
            if (profile == null) {
                return null;
            }
            HashedDir cached = module.getCachedClientDir(profile.getProfile());
            return cached == null ? profile : new WrappedCompletedProfile(profile, cached);
        }

        @Override
        public void init(LaunchServer server) {
            super.init(server);
            delegate.init(server);
        }

        @Override
        public UncompletedProfile create(String name, String description, CompletedProfile defaultProfile) {
            return delegate.create(name, description, defaultProfile);
        }

        @Override
        public void delete(UncompletedProfile profile) {
            delegate.delete(profile);
        }

        @Override
        public Set<UncompletedProfile> getProfiles(pro.gravit.launchserver.socket.Client client) {
            return delegate.getProfiles(client);
        }

        @Override
        public CompletedProfile pushUpdate(UncompletedProfile profile, String tag, ClientProfile clientProfile, List<ProfileAction> update, List<ProfileAction> assetUpdate, List<UpdateFlag> flags) throws IOException {
            return wrap(delegate.pushUpdate(profile, tag, clientProfile, update, assetUpdate, flags));
        }

        @Override
        public void download(CompletedProfile profile, Map<String, java.nio.file.Path> files, boolean assets) throws IOException {
            delegate.download(profile, files, assets);
        }

        @Override
        public HashedDir getUnconnectedDirectory(String name) {
            HashedDir cached = module.cachedDirs.get(name);
            return cached == null ? delegate.getUnconnectedDirectory(name) : cached;
        }

        @Override
        public CompletedProfile get(UUID uuid, String tag) {
            return wrap(delegate.get(uuid, tag));
        }

        @Override
        public CompletedProfile get(String name, String tag) {
            return wrap(delegate.get(name, tag));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
