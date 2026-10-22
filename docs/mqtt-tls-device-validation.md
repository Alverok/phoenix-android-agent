# Device validation: Android node-agent over MQTT/TLS (Phoenix issue #23)

This runbook validates the Android agent end to end on a **physical phone**
against the **real Phoenix Core** and a **real Mosquitto broker** over TLS.
It covers the 13 scenarios of the issue's test matrix and says what evidence
to record for the PR.

The scenarios were rehearsed beforehand with the real Core, the shipped
broker configuration and the agent's own classes on a JVM, and the expected
lines below are taken from that run. The phone run is what counts for #23.

## Setup

**Control node** (laptop or server on the same LAN as the phone):

1. Mosquitto from Phoenix Core's `deploy/mosquitto` (TLS listener on 8883,
   loopback listener for Core on 1883, shipped ACL, `allow_anonymous false`).
   Certificates must include the IP the phone uses; see
   [mqtt-tls.md](mqtt-tls.md).
2. Phoenix Core with MQTT and an enrollment token, for example:

   ```bash
   java -Dphoenix.mqtt.url=tcp://127.0.0.1:1883 \
        -Dphoenix.mqtt.username=phoenix-core \
        -Dphoenix.mqtt.passwordFile=$HOME/.phoenix/core-broker-password \
        -Dphoenix.mqtt.enrollmentTokenFile=$HOME/.phoenix/enrollment-token \
        -jar phoenix.jar            # or: ./gradlew run with the same -D options
   ```

   Keep a copy of the console output: `script -f core-session.log` before
   starting Core records everything shown.

**Phone:** install this build, select **MQTT/TLS**, choose `ca.pem`, enter the
broker address, the device's broker password and the enrollment token
([mqtt-tls.md](mqtt-tls.md)). Keep **Validation hold** at `0` unless a step
says otherwise.

Useful on the control node (replace the device id):

```bash
DEV=android-7fbacc0c-…                         # shown in the app
CORE_PW=$(cat $HOME/.phoenix/core-broker-password)
watch_device() { mosquitto_sub -h 127.0.0.1 -p 1883 -u phoenix-core -P "$CORE_PW" \
                   -t "phoenix/v1/nodes/$DEV/#" -v; }
```

`watch_device` in a second terminal shows every message to and from the
phone (session tokens included; do not paste them into the PR).

## Scenarios

Record the outcome of each step in the [evidence table](#evidence-for-the-pr).

### 1. MQTT/TLS connection

Tap **Connect to Phoenix**.

- Phone: `Connecting to ssl://<ip>:8883 as android-… (TLS)...` then
  `Connected to the broker.`
- Broker log: `New client connected from <phone ip>:<port> as android-… (p2, c0, k30, u'android-…').`
  (`c0`: persistent session; `u'…'`: the broker user is the device id.)

### 2. Authentication: an unauthorized connection is rejected

Run each, then restore the correct setting:

| Change                                   | Expected event in the app                                                       |
| ---------------------------------------- | ------------------------------------------------------------------------------- |
| wrong broker password                    | `Could not connect to the broker: the broker refused this device's user name or password` |
| a different CA file (any other `ca.pem`) | `… TLS failed: the broker certificate is not trusted (check the CA certificate)` |
| an address not in the certificate*      | `… the broker certificate does not match the host name in ssl://…`             |
| wrong enrollment token                   | `Phoenix Core refused the registration: enrollment_rejected`                    |

\* For example the control node's host name if only its IP is in the
certificate. Core logs `[MQTT] Rejected registration from android-… refused:
enrollment_rejected`, and the Core console shows **no** node for the phone.

### 3. Registration: one stable Phoenix node is created

- Phone: `Registered with Phoenix.` and `Phoenix node id <nodeId> (device
  android-…, …)`; the device card shows both ids.
- Core: `[MQTT] Device registered: <manufacturer-model> nodeId=<nodeId> (ANDROID, android)`.
- Core console: exactly **one** entry for the phone.

### 4. Telemetry reaches Phoenix

Core console node panel: the phone with its real core count, memory and
available RAM; CPU shown as unknown (`-1`, Android does not expose it), energy
`6.0`, latency = the registration round trip. `watch_device` shows
`up/heartbeat` bodies every 5 s.

### 5. The node stays healthy while heartbeats arrive

Leave it connected for 2 minutes. Core keeps the node `ONLINE`, and no
`[NodeHealthMonitor]` line mentions it.

### 6, 7, 8. Task delivery, execution, correlation by taskId

Press **D** in the Core console three times.

- Phone, per task: `Received task task-00N, executing Monte-Carlo (samples=2000000, attempt 1, id …)...`
  then `Task task-00N done: Computed Monte-Carlo pi ~= 3.14… (…ms)`.
- Core: `[MQTT] Result received: task=task-00N status=SUCCESS from android-…`
  and `[TUI] task-00N -> SUCCESS on <phone> | output=Computed Monte-Carlo pi ~= …`.
- `watch_device`: `down/task`, then `up/ack`, `up/started`, `up/result`, all with
  the same `taskId` and `attemptId`.

### 12, 13. Duplicate task, duplicate result

Capture the next task as Core sends it, then deliver it a second time through
the broker (Core's account may publish on every downlink):

```bash
mosquitto_sub -h 127.0.0.1 -p 1883 -u phoenix-core -P "$CORE_PW" \
  -t "phoenix/v1/nodes/$DEV/down/task" -C 1 -q 1 > task.json &
# press D in the Core console, wait for the result
mosquitto_pub -h 127.0.0.1 -p 1883 -u phoenix-core -P "$CORE_PW" \
  -t "phoenix/v1/nodes/$DEV/down/task" -q 1 -f task.json
```

- Phone: `Duplicate delivery of task task-00N (attempt …): result sent again, not executed twice.`
  There is only **one** `Received task task-00N,` line.
- Core: `[MQTT] Dropping ack for task-00N: attempt … is not pending` and
  `[MQTT] Dropping result for task-00N: attempt … is not pending`. The task is
  completed once (one `[TUI] task-00N -> SUCCESS`).

The re-sent result is the duplicate result of scenario 13: Core ignores it.

### 9. Disconnect: the lifecycle follows the shared health policy

Do **a** (graceful), then repeat with **b** (realistic network loss).

- **a.** Tap **Disconnect**. Phone: `Agent stopped.`
- **b.** Turn on airplane mode while connected. Phone: `Broker connection lost: …; reconnecting...`

In both cases Core decides from heartbeats, not from the connection:

- `[NodeHealthMonitor] No heartbeat from <phone> in ~2xs -> DEGRADED`
- `[NodeHealthMonitor] No heartbeat from <phone> in ~6xs -> OFFLINE`
- `[MQTT] Device android-… disconnected from the broker` (from the status
  message or last will) is logged but changes **no** node state by itself.

### 10. Reconnect: the existing node identity is restored

- After **9a**: tap **Connect to Phoenix**.
- After **9b**: turn airplane mode off. Phone: `Reconnected to the broker.`,
  heartbeats resume without a new registration.

Expected:

- Phone: the **same** `Phoenix node id` as in scenario 3 (case a).
- Core: `[MQTT] Device android-… registered again as node <same nodeId>; new session`
  (case a), then `[Registry] Node <nodeId> -> ONLINE`.
- Core console: still exactly **one** entry for the phone; press **D** and the
  task completes on it.

### 11. Task interruption: the existing recovery path is invoked

1. Disconnect, set **Validation hold** to `30`, connect again. The app logs
   `Validation hold: every task waits 30 s before it runs.`
2. Press **D**. Phone: `Received task task-00N, …` then
   `Holding task task-00N for 30 s before executing (validation hold)...`
3. While it holds, **force-stop the app** (Settings → Apps → Phoenix Agent →
   Force stop). The task dies mid-execution, after `ack` and `started`.
4. Core, about 12 s after the start (`phoenix.taskTimeoutSeconds`):
   - `[Lifecycle] task-00N STARTED -> FAILED`
   - `[Recovery] Task task-00N failed on <phone> reason=EXECUTION_TIMEOUT`
   - `[Recovery] Node <phone> failed task task-00N -> DEGRADED`
   - with no other eligible node: `[Recovery] task-00N WAITING: no eligible alternative after 1 attempt(s)`
5. Open the app again (the hold is back at `0`), connect. Core re-places the
   pending task as **attempt 2**:
   - `[Lifecycle] task-00N ACCEPTED (attempt 2, …)` … `STARTED -> COMPLETED`
   - `[Recovery] task-00N recovered -> SUCCESS on <phone>`
   - Phone: `Received task task-00N, … attempt 2 …` then `Task task-00N done: …`

With a second node registered (for example the Linux reference agent), step 4
re-places the task on that node instead. Either way the decision is Core's
recovery manager, not the phone's.

Optional variant: keep the app open in step 3. At the timeout Core cancels the
attempt (`Phoenix Core cancelled task task-00N (…, execution_timeout).` then
`Task task-00N stopped: cancelled by Phoenix Core; no result sent.`). Set the
hold back to `0` before the retry reaches the phone, or the retry times out
too.

## Evidence for the PR

Fill in and paste into the PR description (remove session tokens and
passwords from any log you attach).

```markdown
### Device validation (issue #23)

| | |
|---|---|
| Phone | <manufacturer model>, Android <version> (API <level>), <abi> |
| Device id | android-… |
| Phoenix node id | … (unchanged across reconnects: yes/no) |
| Agent | phoenix-android-agent @ <commit> |
| Core | phoenix @ <commit>, enrollment token: on |
| Broker | Mosquitto <version>, TLS 1.2 on :8883, shipped ACL |

| # | Scenario | Expected | Result | Evidence |
|---|---|---|---|---|
| 1 | MQTT/TLS connection | Android connects | | |
| 2 | Authentication | Unauthorized connection rejected (password, CA, host name, enrollment) | | |
| 3 | Registration | One stable Phoenix node | | |
| 4 | Telemetry | Resource state reaches Phoenix | | |
| 5 | Heartbeat | Node stays healthy | | |
| 6 | Task delivery | Task reaches Android | | |
| 7 | Execution | Monte-Carlo-Pi completes on the phone | | |
| 8 | Result | Correlated by taskId | | |
| 9 | Disconnect | DEGRADED, then OFFLINE, by Core's health policy | | |
| 10 | Reconnect | Same node identity, one NodeRecord | | |
| 11 | Task interruption | EXECUTION_TIMEOUT, recovery, completed as attempt 2 | | |
| 12 | Duplicate task | One execution | | |
| 13 | Duplicate result | One completion | | |

Known limitations:
- CPU usage is reported as unknown (-1): Android does not expose it to apps.
- Network latency is the registration round trip; energy is the fixed
  Android heuristic (6.0).
- The agent runs while the app is in the foreground (screen kept on); there
  is no background service yet.
- Handled attempt ids are remembered in memory only, so duplicate
  suppression does not survive an app restart.
```
