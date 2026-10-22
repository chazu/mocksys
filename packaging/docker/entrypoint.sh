#!/bin/sh
# Serve mocksys scenarios from a container.
#
#   MOCKSYS_SERVE  required: space-separated SCENARIO=PUBLIC_PORT pairs, such as
#                  "helpdesk/helpdesk=8080 okta/okta=8081"
#   MOCKSYS_KIT    optional: arguments for `mocksys kit`, run before serving, so a
#                  container can regenerate its twin with other flags, such as
#                  "helpdesk --rate-limit 100 --port 9400"
#
# The daemon serves every mock on loopback only, so each one is published with socat
# on 0.0.0.0:PUBLIC_PORT. PUBLIC_PORT must differ from the port the scenario runs on.
# The container lives as long as the daemon does.
set -eu

: "${MOCKSYS_SERVE:?set MOCKSYS_SERVE to SCENARIO=PUBLIC_PORT pairs, such as helpdesk/helpdesk=8080}"

if [ -n "${MOCKSYS_KIT:-}" ]; then
  # shellcheck disable=SC2086 # the flags are meant to split
  mocksys kit $MOCKSYS_KIT
fi

# A token left by an earlier run of this container would make the wait below pass
# before the new daemon is listening.
token="$MOCKSYS_HOME/daemon-$MOCKSYS_ADMIN_PORT.token"
rm -f "$token"
mocksys daemon &
daemon=$!
until [ -s "$token" ]; do
  kill -0 "$daemon" 2>/dev/null || { echo "mocksys-entrypoint: the daemon exited" >&2; exit 1; }
  sleep 0.1
done

for pair in $MOCKSYS_SERVE; do
  scenario=${pair%=*}
  public=${pair##*=}
  if [ "$scenario" = "$pair" ] || [ -z "$public" ]; then
    echo "mocksys-entrypoint: '$pair' needs a public port: SCENARIO=PORT" >&2
    exit 1
  fi
  mocksys run "$scenario"
  port=$(mocksys env "$scenario" --json | node -pe 'JSON.parse(require("fs").readFileSync(0, "utf8")).port')
  if [ "$port" = "$public" ]; then
    echo "mocksys-entrypoint: $scenario runs on $port, so publish it on another port" >&2
    exit 1
  fi
  socat "TCP-LISTEN:$public,fork,reuseaddr" "TCP:127.0.0.1:$port" &
  echo "mocksys-entrypoint: $scenario on 0.0.0.0:$public (-> 127.0.0.1:$port)"
done

wait "$daemon"
