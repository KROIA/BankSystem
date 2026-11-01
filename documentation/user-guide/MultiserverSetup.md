# Multi-Server Setup

BankSystem allows multiple Minecraft servers to connect and share the banking system, including bank accounts. When activated, it uses a master-slave architecture.

<div align="center">
    <img src="../images/MasterSlaveArchitecture.png" style="width:80%"> <br>
    <b>Master-Slave Architecture</b>
</div>

---
<table>
<tr>
<td>
<b>Master</b><br>
The master manages and stores the bank accounts of each player, including players that are online on the slave servers.
</td>
<td>
<b>Slave</b><br>
The slave server needs to be connected to a master in order to use the bank system. 
A slave always forwards bank system requests to the master for processing.
No bank data is stored on a slave server.
</td>
</tr>
</table>

---
## Setting Up a Master

Edit the configuration file at: `<ServerFolder>/config/MultiServerConfig.json`

```json
{
  "enable": true,                       
  "isMaster": true,
  "sharedSecret": "change-me-please",
  "slaveID": "",
  "masterHost": "",
  "masterTcpPort": 25575
}
```

| Setting | Description |
|---------|-------------|
| `enable` | Enables/disables the multi-server feature. If disabled, the server manages bank accounts on its own and slaves cannot connect. |
| `isMaster` | Set to `true` for the master server. |
| `sharedSecret` | A shared string that slaves must match to connect. **Change this** to ensure only trusted slaves can connect. |
| `slaveID` | Ignored for a master. |
| `masterHost` | Ignored for a master. |
| `masterTcpPort` | The TCP port on which the master listens for slave connections. |

---
## Setting Up a Slave

Edit the configuration file at: `<ServerFolder>/config/MultiServerConfig.json`

```json
{
  "enable": true,
  "isMaster": false,
  "sharedSecret": "change-me-please",
  "slaveID": "slave_server_1",
  "masterHost": "127.0.0.1",
  "masterTcpPort": 25575
}
```

| Setting | Description |
|---------|-------------|
| `enable` | Enables/disables the multi-server feature. |
| `isMaster` | Set to `false` for a slave server. |
| `sharedSecret` | Must match the master's `sharedSecret` to establish a connection. |
| `slaveID` | A unique identifier for this slave server. Each slave must have a different ID. |
| `masterHost` | The IP address or hostname of the master server. |
| `masterTcpPort` | The TCP port the master is listening on. |

---
## Trusted Slave Servers

By default, slave servers are treated as **untrusted** and can only forward read-only operations (balance queries, account lookups, etc.). Untrusted slaves cannot perform mutations like deposits, withdrawals, or transfers.

To allow a slave server to perform all operations, it must be explicitly added to the trusted list on the master server.

### Adding a Trusted Slave

Run the following command **on the master server**:
```
/banksystem trust <slaveID>
```
The `slaveID` must match the `slaveID` value configured in the slave's `MultiServerConfig.json`. The command provides auto-suggestions for currently connected slave servers.

### Removing a Trusted Slave

```
/banksystem untrust <slaveID>
```

### Viewing Trust Status

Use the server network info command to see all connected servers and their trust status:
```
/banksystem serverNetworkInfo
```

### How It Works

The trusted slave list is stored as part of the world data and persists across server restarts. When a slave forwards a request to the master, the master checks:

1. **Is the function allowed for untrusted slaves?** Read-only operations are always allowed.
2. **If not, is the slave in the trusted list?** If yes, the request proceeds. If no, the request is rejected.

This two-tier security model means:
- **Untrusted slaves** can let players check balances and view account info, but all mutations must originate from server-side mod code (e.g. the StockMarket mod running on the slave).
- **Trusted slaves** can forward any operation, including deposits and withdrawals initiated by other mods.

> [!IMPORTANT]
> Only trust slave servers that you fully control. A compromised trusted slave could manipulate bank data (e.g. deposit items for free).
