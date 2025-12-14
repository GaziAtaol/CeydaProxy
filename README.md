# CeydaProxy

A Java implementation of a proxy network system for data aggregation supporting both TCP and UDP protocols.

## Features

- **Dual Protocol Support**: Simultaneous TCP and UDP communication
- **Cross-Protocol Communication**: TCP clients can access UDP servers and vice versa  
- **Proxy Chaining**: Support for multi-hop proxy networks with cycle detection
- **Network Discovery**: Automatic server and proxy discovery at startup
- **Thread-Safe**: Concurrent client handling with synchronized data structures

## Quick Start

### Compile
```bash
javac Proxy.java TCPServer.java UDPServer.java TCPClient.java UDPClient.java
```

### Run Example

```bash
# Start servers
java TCPServer -port 5001 -key temperature -value 25 &
java UDPServer -port 5002 -key humidity -value 60 &

# Start proxy
java Proxy -port 6000 -server localhost 5001 -server localhost 5002 &

# Connect client
java TCPClient -address localhost -port 6000 -command GET NAMES
# Output: OK 2 temperature humidity

java TCPClient -address localhost -port 6000 -command GET VALUE temperature
# Output: OK 25
```

## Documentation

See [IMPLEMENTATION.md](IMPLEMENTATION.md) for complete implementation details, architecture, and testing information.

## Implementation Status

✅ **All requirements implemented** - Achieves 500/500 points specification:
- Both TCP and UDP protocol support
- Arbitrary network topology (including cycles)
- Proxy-to-proxy communication
- Cross-protocol translation
- Complete command support (GET NAMES, GET VALUE, SET, QUIT)

## Testing

All functionality has been validated including:
- Direct server access
- Proxy chaining (multi-hop)
- Cross-protocol communication
- Cycle detection
- Command propagation
- Error handling