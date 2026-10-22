# Connecting the Android agent over MQTT/TLS

The app speaks two transports. **HTTP (v0.2)** is the original pull baseline
and is unchanged. **MQTT/TLS** implements the Phoenix MQTT node-agent
protocol, version 1 (Phoenix Core: `docs/mqtt-agent-protocol.md`). The phone
opens one outbound TLS connection to the broker, and Phoenix Core pushes work
to it through the broker.

```text
Phoenix Core ── loopback ──> Mosquitto ── TLS :8883 <── Android agent
(control node)               (control node)            (phone, same LAN)
```

## 1. Prepare the broker (on the control node)

Use the files in Phoenix Core's `deploy/mosquitto/` (see its README). Two
details matter for a phone:

1. **Certificates must name the address the phone uses.** If the phone
   connects to `ssl://192.168.1.20:8883`, include that IP:

   ```bash
   ./gen-certs.sh /etc/mosquitto/phoenix/certs broker.lan 192.168.1.20
   ```

   The agent verifies the broker's host name on every Android version. A
   name or IP missing from the certificate is refused.

2. **The broker user name must be the phone's device id.** The app shows it
   under *Broker user name* once **MQTT/TLS** is selected; the text can be
   selected and copied. Create the account with that exact id:

   ```bash
   ./add-device.sh /etc/mosquitto/phoenix/passwd android-7fbacc0c-…   # prints "<id> <password>"
   sudo systemctl reload mosquitto
   ```

## 2. Put the CA on the phone

Copy `ca.pem` (only the CA certificate, **never** `ca.key`) to the phone, for
example to *Downloads*. In the app, tap **Choose broker CA certificate
(ca.pem)** and pick it. The app then trusts exactly that CA for the broker
connection; no device-wide certificate is installed. Long-press the button
to forget the CA and trust the platform's CA store instead.

## 3. Connect

| Field            | Value                                                        |
| ---------------- | ------------------------------------------------------------ |
| Transport        | **MQTT/TLS**                                                 |
| Broker           | `ssl://<control node IP>:8883` (`host` or `host:port` work too) |
| Broker password  | the password printed by `add-device.sh`                      |
| Enrollment token | Core's enrollment token, if Core is started with one         |
| Validation hold  | `0` (see the validation runbook; reset to 0 on every app start) |

Tap **Connect to Phoenix**. The events list shows *Connected to the broker.*,
then *Registered with Phoenix.* and the Phoenix node id. Heartbeats follow
every 5 s, and tasks dispatched from the Core console run on the phone.

While connected, the screen stays on so that Android does not suspend the
agent. The status badge shows **this device's connection** (connected,
connection issue, disconnected). Whether the node is `ONLINE`, `DEGRADED` or
`OFFLINE` is decided by Phoenix Core from heartbeats, and shown there.

## Troubleshooting

| Event in the app                                                  | Cause                                                                       |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------- |
| *the broker refused this device's user name or password*          | wrong password, or no broker account for this device id                     |
| *TLS failed: the broker certificate is not trusted*               | wrong or missing `ca.pem`                                                   |
| *the broker certificate does not match the host name in ssl://…* | the address you entered is not in the certificate; regenerate it with that name/IP |
| *Phoenix Core refused the registration: enrollment_rejected*      | missing or wrong enrollment token                                           |
| *Broker not reachable (…); retrying...*                           | wrong address, broker down, or firewall; the agent keeps retrying          |
| *No answer from Phoenix Core yet; sending the registration again.* | the broker is reachable but Core is not running or not connected to it     |

## Security notes

- TLS 1.2+ with host-name verification, against the CA you chose or the
  platform store. Plain `tcp://` is refused unless the broker is on the phone
  itself.
- The password and the enrollment token are kept in app-private storage and
  excluded from backups and device transfers. They are not encrypted at rest
  beyond Android's app sandbox.
- The device id doubles as broker user name and MQTT client id, so the broker
  ACL keeps every phone on its own topics.
