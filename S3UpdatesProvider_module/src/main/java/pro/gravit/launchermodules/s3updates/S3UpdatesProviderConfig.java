package pro.gravit.launchermodules.s3updates;

import java.util.LinkedHashMap;
import java.util.Map;

public class S3UpdatesProviderConfig {
    public boolean enabled = true;
    public boolean refreshOnStart = true;
    public long refreshIntervalSeconds = 0L;
    public Map<String, ProfileConfig> profiles = defaultProfiles();

    private static Map<String, ProfileConfig> defaultProfiles() {
        Map<String, ProfileConfig> result = new LinkedHashMap<>();
        ProfileConfig zombiePlague = new ProfileConfig();
        zombiePlague.indexUrl = "https://cdn.zombieplague.net/public/clients/ZombiePlague/1.7.10/index.json";
        zombiePlague.downloadUrl = "https://cdn.zombieplague.net/public/clients/ZombiePlague/1.7.10/";
        result.put("ZombiePlague", zombiePlague);
        return result;
    }

    public static class ProfileConfig {
        public String indexUrl;
        public String downloadUrl;
    }
}
