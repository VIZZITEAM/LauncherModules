package pro.gravit.launchermodules.s3updates;

import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.command.Command;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;

public class S3UpdatesSyncCommand extends Command {
    private final S3UpdatesProviderModule module;

    public S3UpdatesSyncCommand(LaunchServer server, S3UpdatesProviderModule module) {
        super(server);
        this.module = module;
    }

    @Override
    public String getArgsDescription() {
        return "[profile...]";
    }

    @Override
    public String getUsageDescription() {
        return "Load update hash trees from S3/CDN index files";
    }

    @Override
    public void invoke(String... args) throws IOException {
        module.sync(args);
        LogHelper.subInfo("S3 update indexes successfully synced");
    }
}
