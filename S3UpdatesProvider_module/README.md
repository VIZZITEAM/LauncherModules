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
  "refreshIntervalSeconds": 0,
  "profiles": {
    "ZombiePlague": {
      "indexUrl": "https://cdn.zombieplague.net/public/clients/ZombiePlague/1.7.10/index.json",
      "downloadUrl": "https://cdn.zombieplague.net/public/clients/ZombiePlague/1.7.10/"
    }
  }
}
```

With this default config the module loads the S3 index on LaunchServer start and
does not poll the CDN every minute. GitHub Actions should call the
`s3UpdatesSync ZombiePlague` command after each deploy.

The module rejects deploy metadata and local helper files from the S3 index:
`.git`, `.github`, `.deploy`, `tests`, `.gitignore`, `.gitattributes`,
`.rawignore`, `README.md`, `upload-raw-client.bat`, `download-raw-client.bat`,
and `setup-s3-env.bat`.

The module registers the `s3UpdatesSync` LaunchServer command.
