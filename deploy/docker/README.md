# Forvum on Docker

`docker-compose.yml` here runs Forvum together with an Ollama model server, both as containers. It is a
convenience path for a VPS or a home server; the full deployment guide — including the recommended
`systemd` + native-binary path, Telegram setup, security, and reverse-proxy/TLS — is in
[`../../docs/DEPLOY.md`](../../docs/DEPLOY.md).

```bash
# 1. Scaffold the config tree (./forvum-home, mounted into the container at /forvum):
docker compose -f deploy/docker/docker-compose.yml run --rm forvum init

# 2. Configure: edit ./forvum-home/agents/main.json, then enable a channel by adding either
#    ./forvum-home/channels/telegram.json  (phone access, no inbound port — recommended) or
#    ./forvum-home/channels/web.json        (browser UI on :8080 — front it with an auth proxy).

# 3. Start, and pull the default model into the Ollama container:
docker compose -f deploy/docker/docker-compose.yml up -d
docker compose -f deploy/docker/docker-compose.yml exec ollama ollama pull qwen3:1.7b
```

The `ghcr.io/eldermoraes/forvum:<version>-native` image is published by the release pipeline
(`.github/workflows/release.yml`). Pin an explicit tag in production instead of `latest-native`. State
(the SQLite ledger) persists in `./forvum-home/state/` — back it up.
