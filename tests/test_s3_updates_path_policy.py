from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "S3UpdatesProvider_module/src/main/java/pro/gravit/launchermodules/s3updates/S3UpdatesProviderModule.java"


class S3UpdatesPathPolicyTest(unittest.TestCase):
    def test_s3_updates_uses_denylist_not_client_folder_whitelist(self):
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("isDeniedClientPath", source)
        self.assertNotIn("isSupportedClientPath", source)
        self.assertNotIn('path.startsWith("config/")', source)
        self.assertNotIn('path.startsWith("mods/")', source)
        self.assertNotIn('path.startsWith("data/")', source)

    def test_s3_updates_blocks_only_dangerous_or_deploy_metadata_paths(self):
        source = SOURCE.read_text(encoding="utf-8")

        for denied in [
            '".git"',
            '".github"',
            '".deploy"',
            '"tests"',
            '".gitignore"',
            '".gitattributes"',
            '".rawignore"',
            '"README.md"',
            '"upload-raw-client.bat"',
            '"download-raw-client.bat"',
            '"setup-s3-env.bat"',
        ]:
            self.assertIn(denied, source)

    def test_s3_updates_does_not_poll_by_default(self):
        config = (ROOT / "S3UpdatesProvider_module/src/main/java/pro/gravit/launchermodules/s3updates/S3UpdatesProviderConfig.java").read_text(encoding="utf-8")
        readme = (ROOT / "S3UpdatesProvider_module/README.md").read_text(encoding="utf-8")

        self.assertIn("refreshOnStart = true", config)
        self.assertIn("refreshIntervalSeconds = 0L", config)
        self.assertNotIn("refreshIntervalSeconds = 60L", config)
        self.assertIn('"refreshIntervalSeconds": 0', readme)
        self.assertIn("after each deploy", readme)


if __name__ == "__main__":
    unittest.main()
