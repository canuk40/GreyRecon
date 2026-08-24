#!/system/bin/sh
# ai <question> -- ask GreyRecon's on-device AI agent, right from the terminal.
#
# Talks to AgentBridgeServer over a loopback socket (same pattern greyrecon-pkg uses for fetches):
# the agent runs the real recon tools (scan_network, check_snmp, lookup_cve, check_kev, whois, ...)
# using the AI provider + API key configured in GreyRecon's Settings, and streams its progress and
# answer back here. Each call is a one-shot question; the AI Assistant screen is where a back-and-forth
# conversation with memory lives.

if [ -z "$*" ]; then
  echo "usage: ai <question>"
  echo "example: ai what's on my network and is anything exposed?"
  exit 1
fi

port="${GREYRECON_AGENT_PORT:-47824}"

# -w 170: the agent can run several scans before answering, so allow a long read window (the server's
# own request timeout is 180s). printf feeds the prompt as one line; nc prints whatever the server
# streams back until the server closes the connection.
printf '%s\n' "$*" | nc -w 170 127.0.0.1 "$port"
