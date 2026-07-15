# S3UpdatesProvider Module

LaunchServer module that loads an update hash tree from a CDN/S3 `index.json`
instead of requiring a full local `updates/<profile>` mirror.

Default config path:

```text
config/S3UpdatesProvider/Config.json
```

Default ZombiePlague config:

```json
{
  "enabled": true,
  "refreshOnStart": true,
  "refreshIntervalSeconds": 60,
  "profiles": {
    "ZombiePlague": {
      "indexUrl": "https://cdn.zombieplague.net/public/clients/ZombiePlague/1.7.10/index.json",
      "downloadUrl": "https://cdn.zombieplague.net/public/clients/ZombiePlague/1.7.10/"
    }
  }
}
```

The module registers the `s3UpdatesSync` LaunchServer command.
