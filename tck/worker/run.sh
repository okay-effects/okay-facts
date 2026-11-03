#!/bin/bash
# THE TCK (okay-facts specs/design.md §11): the passthrough Worker on `wrangler dev` (the account's Workers AI, by
# wrangler's own login — not a token: CLOUDFLARE_API_TOKEN is unset for it), then the runner against it.
#   tck/worker/run.sh <out dir> <level L0|L2|L3> <model> [<model> …]
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p "$1"; out=$(cd "$1" && pwd); shift  # absolute: wrangler runs in tck/worker
sbt -batch tckWorker/fastLinkJS
port=8897
(cd tck/worker && ([ -d node_modules ] || npm install --silent) && env -u CLOUDFLARE_API_TOKEN npx wrangler dev --port $port > "$out/wrangler.log" 2>&1) &
pid=$!
trap 'pkill -P $pid 2>/dev/null; kill $pid 2>/dev/null' EXIT
until curl -s -o /dev/null "http://127.0.0.1:$port/"; do sleep 1; done
sbt -batch "tck/run http://127.0.0.1:$port $out $*"
