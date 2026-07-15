package pro.gravit.launchermodules.s3updates;

import java.util.List;

public class ClientIndex {
    public int schemaVersion;
    public String profile;
    public String version;
    public String baseUrl;
    public List<FileEntry> files;

    public static class FileEntry {
        public String path;
        public long size;
        public String md5;
        public String sha1;
        public String sha512;
        public String sha256;
    }
}
