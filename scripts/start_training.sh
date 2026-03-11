#!/bin/bash
# Start the Java bridge server, run PPO training, and clean up on exit.
#
# Usage:
#   ./scripts/start_training.sh
#   ./scripts/start_training.sh --timesteps 100000

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_DIR"

# Build the Java server
echo "Building Java bridge server..."
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")
./gradlew :gym-bridge:classes --quiet

# Start the Java server in the background
echo "Starting bridge server on port 9876..."
./gradlew :gym-bridge:run --quiet &
SERVER_PID=$!

# Clean up server on exit
cleanup() {
    echo ""
    echo "Stopping bridge server (PID $SERVER_PID)..."
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    echo "Done."
}
trap cleanup EXIT INT TERM

# Wait for server to start accepting connections
echo "Waiting for server to start..."
for i in $(seq 1 30); do
    if python3 -c "
import zmq, json, sys
ctx = zmq.Context()
s = ctx.socket(zmq.PAIR)
s.setsockopt(zmq.RCVTIMEO, 1000)
s.setsockopt(zmq.SNDTIMEO, 1000)
s.connect('tcp://localhost:9876')
try:
    s.send_string(json.dumps({'type':'init','data':{'blueDeck':['knight','archer','fireball','arrows','giant','musketeer','minions','valkyrie'],'redDeck':['knight','archer','fireball','arrows','giant','musketeer','minions','valkyrie'],'level':11,'ticksPerStep':6}}))
    r = s.recv_string()
    s.send_string(json.dumps({'type':'close'}))
    s.recv_string()
except: pass
s.close()
ctx.term()
sys.exit(0 if 'init_ok' in r else 1)
" 2>/dev/null; then
        echo "Server is ready."
        break
    fi
    if [ $i -eq 30 ]; then
        echo "Error: Server failed to start after 30 seconds"
        exit 1
    fi
    sleep 1
done

# Run training
echo ""
echo "Starting PPO training..."
python3 python/examples/train_ppo.py "$@"
