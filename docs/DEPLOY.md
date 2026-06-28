# Deploying Forvum on a server (VPS)

This guide takes you from a fresh Linux VPS to a Forvum instance running 24/7, reachable from your
phone, surviving reboots, with persistent memory and a sane security posture. No Kubernetes required —
for the k8s/Helm path, see [`deploy/helm/README.md`](../deploy/helm/README.md) instead.

Forvum ships as a **single native binary**. It has two run modes:

- **Command mode** — `forvum init`, `forvum doctor`, `forvum ask "..."`, etc. run once and exit.
- **Server mode** — when a *server channel* is enabled (a file under `$FORVUM_HOME/channels/`), the
  binary stays alive and serves that channel. This is what a VPS deployment runs.

The two channels that suit a VPS:

| Channel | Reach it from | Inbound port? | Built-in auth? | Best for |
|---|---|---|---|---|
| **Telegram** | the Telegram app on your phone | **no** (outbound long-poll) | yes (`allowedUserIds`) | the simplest, safest remote access |
| **Web** | a browser (chat UI + WebSocket) | yes (`:8080`) | **no** — needs a reverse proxy | a private dashboard behind nginx/Caddy |

Telegram is the recommended default: it needs no open port, no TLS, and no reverse proxy, and it has a
built-in allow-list.

---

## Quick start (systemd + Telegram, the recommended path)

Run these as a sudo-capable user on an Ubuntu/Debian VPS (the binary is `linux-x64`).

### 1. Install the binary system-wide

```bash
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh \
  | FORVUM_INSTALL_DIR=/usr/local/bin sh
/usr/local/bin/forvum --version
```

### 2. Create a dedicated service user and state directory

```bash
sudo useradd -r -s /usr/sbin/nologin -d /var/lib/forvum forvum
sudo install -d -o forvum -g forvum -m 0700 /var/lib/forvum
sudo -u forvum env FORVUM_HOME=/var/lib/forvum forvum init
```

`forvum init` scaffolds `/var/lib/forvum` with a starter `agents/main.json` (owner-only `0700`/`0600`
permissions). State — the SQLite ledger with conversations, memory, and metrics — lives at
`/var/lib/forvum/state/forvum.sqlite`.

### 3. Configure a model provider

A real conversation needs a model. Two common choices:

- **Cloud provider (no GPU on the VPS).** Put the key in an `0600` env file (read by the service):

  ```bash
  sudo install -d -m 0750 -o root -g forvum /etc/forvum
  echo 'QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY=sk-ant-...' | sudo tee /etc/forvum/forvum.env >/dev/null
  sudo chmod 0640 /etc/forvum/forvum.env && sudo chgrp forvum /etc/forvum/forvum.env
  ```

  Then point the default agent at that provider:

  ```bash
  sudo -u forvum sed -i 's#"ollama:gemma4:31b-cloud"#"anthropic:claude-sonnet-4-5"#' /var/lib/forvum/agents/main.json
  ```

  Supported cloud providers: `anthropic`, `openai`, `google`, and GitHub `copilot` (device-code login).
  Each reads `QUARKUS_LANGCHAIN4J_<PROVIDER>_API_KEY`.

- **Local Ollama on the VPS (needs enough RAM/CPU; a GPU helps).**

  ```bash
  curl -fsSL https://ollama.com/install.sh | sh
  ollama pull gemma4:31b-cloud  # the model the default agent is pinned to
  ```

  The default `agents/main.json` already points at `ollama:gemma4:31b-cloud`, so no edit is needed.

Validate the config any time with `sudo -u forvum env FORVUM_HOME=/var/lib/forvum forvum doctor`.

### 4. Enable the Telegram channel

1. In Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot`, and copy the bot token.
2. Get your numeric user id: message [@userinfobot](https://t.me/userinfobot) (it replies with your id).
3. Write the channel file:

   ```bash
   sudo -u forvum tee /var/lib/forvum/channels/telegram.json >/dev/null <<'JSON'
   { "botToken": "123456:ABC-your-bot-token", "allowedUserIds": [111111111] }
   JSON
   sudo chmod 0600 /var/lib/forvum/channels/telegram.json
   ```

   `allowedUserIds` restricts the bot to those ids. **An empty list allows anyone** — only do that on a
   throwaway bot.

### 5. Install and start the systemd service

```bash
sudo cp deploy/systemd/forvum.service /etc/systemd/system/forvum.service
sudo systemctl daemon-reload
sudo systemctl enable --now forvum
systemctl status forvum            # should be "active (running)"
journalctl -u forvum -f            # follow the logs
```

(The unit is in this repo at [`deploy/systemd/forvum.service`](../deploy/systemd/forvum.service); if you
installed only the binary, download that one file from the repo.)

Now message your bot from your phone — it should reply. `Restart=always` plus `systemctl enable` means
it comes back after a crash or a reboot.

> The service stays alive only because a **server channel** (Telegram) is enabled. With no server
> channel the binary exits 0 immediately and systemd would restart it in a loop — always configure a
> channel before enabling the service.

---

## The Web channel behind a reverse proxy

The Web channel's operator endpoints — the approval dashboard (`/q/dashboard/approvals`), the CAPR
dashboard (`/q/dashboard/capr`), and the chat socket (`/ws/chat`) — require an **operator token** (#165):
set `forvum.operator.token` (env `FORVUM_OPERATOR_TOKEN`, a Kubernetes Secret) or write
`$FORVUM_HOME/state/credentials/operator` (`0600`). A server channel **fails closed at startup** without
one. Clients authenticate with `Authorization: Bearer <token>` (HTTP) or `?access_token=<token>` (the
WebSocket handshake); a missing/wrong token is `401`, an authenticated non-operator is `403`. The static
UI page itself is unauthenticated (it carries no data); only the data/control paths are gated. TLS is
still your responsibility — bind to loopback and front Forvum with a TLS-terminating reverse proxy for
public access (the proxy can add a second auth layer).

Enable it:

```bash
echo '{}' | sudo -u forvum tee /var/lib/forvum/channels/web.json >/dev/null
sudo systemctl restart forvum
```

The shipped systemd unit already sets `QUARKUS_HTTP_HOST=127.0.0.1` and `QUARKUS_HTTP_PORT=8080`, so
Forvum listens only on loopback. Example Caddy config (automatic HTTPS + basic auth):

```caddy
forvum.example.com {
    basic_auth {
        you JDJhJDE0... # `caddy hash-password` output
    }
    reverse_proxy 127.0.0.1:8080
}
```

Or nginx (add TLS with certbot, and an `auth_basic` / OAuth layer) proxying to `127.0.0.1:8080`,
forwarding the WebSocket upgrade headers (`Upgrade`/`Connection`) for `/ws/chat`.

---

## Alternative: Docker Compose

If you prefer containers, [`deploy/docker/`](../deploy/docker/) has a Compose file that runs Forvum +
Ollama. It pulls `ghcr.io/eldermoraes/forvum:<version>-native`, published by the release pipeline. See
[`deploy/docker/README.md`](../deploy/docker/README.md). The systemd + binary path above works today
with the existing binary release and needs no container runtime.

---

## Persistence and backups

Everything operational is in one SQLite file: `$FORVUM_HOME/state/forvum.sqlite` (plus `-wal`/`-shm`
sidecars). Back up the whole `state/` directory. A simple cron backup:

```bash
sudo install -d -m 0700 /var/backups/forvum
0 3 * * *  sqlite3 /var/lib/forvum/state/forvum.sqlite ".backup '/var/backups/forvum/forvum-$(date +\%F).sqlite'"
```

Config (`agents/`, `channels/`, …) is plain text under `$FORVUM_HOME` — keep it in version control
(secrets excluded). Forvum hot-reloads config edits at runtime; for credentials in the env file, run
`sudo systemctl restart forvum`.

---

## Security checklist

- [ ] Run as a dedicated unprivileged user (`forvum`), never root — the systemd unit enforces this.
- [ ] Set `allowedUserIds` on Telegram (or any chat channel) — never leave it empty on a public bot.
- [ ] Never expose the Web channel directly; bind to `127.0.0.1` and use an authenticating reverse
      proxy with TLS.
- [ ] Keep provider API keys in `/etc/forvum/forvum.env` (`0640`, group `forvum`), not in the unit file
      or the config tree.
- [ ] Restrict any agent's tool belt (`allowedTools` in `agents/*.json`) and roles to what it needs;
      review before granting filesystem/shell tools.
- [ ] Firewall the box; for a Telegram-only deployment, no inbound ports need to be open at all.

---

## Updating

```bash
sudo systemctl stop forvum
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh \
  | FORVUM_INSTALL_DIR=/usr/local/bin sh    # fetches the newest release
sudo systemctl start forvum
forvum --version
```

State and config are untouched by an upgrade (they live in `$FORVUM_HOME`).

---

## Troubleshooting

- **The service restarts in a loop.** No server channel is enabled (the binary exits 0). Add
  `channels/telegram.json` (with a `botToken`) or `channels/web.json`, then `systemctl restart forvum`.
  (The interactive TUI channel `forvum init` scaffolds does *not* cause this under systemd: with no
  terminal attached, a configured server channel takes precedence, so a leftover `channels/tui.json` is
  harmless. To run the local TUI instead, start `forvum` in a terminal.)
- **A turn fails with "Is the model provider running?"** The model provider is unreachable — start
  Ollama / fix `QUARKUS_LANGCHAIN4J_OLLAMA_BASE_URL`, or check the cloud key. The error names the model.
- **No reply on Telegram.** Confirm your numeric id is in `allowedUserIds`, the token is correct, and
  `journalctl -u forvum` shows the long-poll loop started.
- **Want verbose logs.** The shipped binary logs at WARN by default; set
  `QUARKUS_LOG_CONSOLE_LEVEL=INFO` (e.g. in `/etc/forvum/forvum.env`) and restart. A turn failure is
  always surfaced as a one-line cause; to also see the underlying framework stack traces (suppressed by
  default), re-enable their category, e.g.
  `QUARKUS_LOG_CATEGORY__ORG_BSC_LANGGRAPH4J__LEVEL=ALL`.
