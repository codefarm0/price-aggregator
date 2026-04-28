#!/bin/bash
# Circuit Breaker Testing Script
# Usage: ./cb-test.sh [scenario]
# Scenarios: fast-fail | slow | unstable | reset

HOST="${HOST:-localhost:8080}"
DELAY=0.5  # Delay between requests

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo_step() { echo -e "${BLUE}[STEP]${NC} $1"; }
echo_success() { echo -e "${GREEN}[OK]${NC} $1"; }
echo_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
echo_error() { echo -e "${RED}[ERROR]${NC} $1"; }

check_circuit() {
    curl -s "http://$HOST/actuator/health" 2>/dev/null | jq -r '
        .components.circuitBreakers.details // empty |
        to_entries[] |
        "\(.key): state=\(.value.details.state) buffered=\(.value.details.bufferedCalls) failed=\(.value.details.failedCalls) notPermitted=\(.value.details.notPermittedCalls)"
    ' 2>/dev/null || echo "Failed to check circuit status"
}

wait_for_app() {
    echo_step "Waiting for app to be ready..."
    for i in {1..30}; do
        if curl -s "http://$HOST/actuator/health" > /dev/null 2>&1; then
            echo_success "App is ready"
            return 0
        fi
        sleep 1
    done
    echo_error "App not responding"
    exit 1
}

scenario_reset() {
    echo_step "Resetting chaos mode and circuit state"
    curl -s "http://$HOST/mock-api/chaos/reset" && echo ""
    sleep 2
    echo_success "Reset complete"
}

scenario_fast_fail() {
    echo_step "Enabling FAST FAILURES scenario (100% failure rate)"
    curl -s "http://$HOST/mock-api/chaos/scenario/fast-failures" && echo ""
    
    echo_step "Making 12 rapid calls to trip the circuit..."
    for i in {1..12}; do
        echo -n "Call $i: "
        RESPONSE=$(curl -s -w "\nTime: %{time_total}s" "http://$HOST/api/prices/test-$i" 2>&1)
        if echo "$RESPONSE" | grep -q "error\|exception\|fail\|Timeout\|Connect"; then
            echo_error "FAILED (expected)"
        else
            echo_success "SUCCESS (unexpected - circuit may not be open yet)"
        fi
        sleep $DELAY
    done
    
    echo ""
    echo_step "Checking circuit breaker state..."
    check_circuit
}

scenario_slow() {
    echo_step "Enabling SLOW RESPONSES scenario (500ms delay)"
    curl -s "http://$HOST/mock-api/chaos/scenario/slow-responses" && echo ""
    
    echo_step "Making 12 slow calls (500ms each, exceeding 100ms threshold)"
    echo_step "Watch circuit state - after 6+ slow calls (50%+), it should OPEN"
    echo ""
    
    for i in {1..12}; do
        echo -n "Call $i: "
        RESPONSE=$(curl -s -m 5 "http://$HOST/api/prices/slow-$i" 2>&1 || echo "TIMEOUT")
        
        if echo "$RESPONSE" | grep -q "price"; then
            echo_success "Got price"
        else
            echo_warn "Slow/timeout"
        fi
    done
    
    echo ""
    echo_step "Checking circuit breaker state (slowCallRate should be high)..."
    check_circuit
}

scenario_unstable() {
    echo_step "Enabling UNSTABLE scenario (70% failures, 3s delay)"
    curl -s "http://$HOST/mock-api/chaos/scenario/unstable" && echo ""
    
    echo_step "Making 15 rapid calls..."
    for i in {1..15}; do
        echo -n "Call $i: "
        START=$(date +%s)
        RESPONSE=$(curl -s -m 8 "http://$HOST/api/prices/test-$i" 2>&1 || echo "FAILED")
        DURATION=$(( $(date +%s) - START ))
        
        if echo "$RESPONSE" | grep -q "price"; then
            echo_success "SUCCESS (${DURATION}s)"
        else
            echo_error "FAILED (${DURATION}s)"
        fi
        sleep 0.3
    done
    
    echo ""
    echo_step "Checking circuit breaker state..."
    check_circuit
}

scenario_observe() {
    echo_step "Normal mode - observing circuit behavior"
    echo_step "Making 5 normal calls..."
    
    for i in {1..5}; do
        echo -n "Call $i: "
        RESPONSE=$(curl -s "http://$HOST/api/prices/product-$i" 2>&1)
        if echo "$RESPONSE" | grep -q "prices"; then
            echo_success "OK"
        else
            echo_warn "Response received"
        fi
        sleep 1
    done
    
    echo ""
    check_circuit
}

scenario_watch() {
    echo_step "Watching circuit breaker state in real-time (Ctrl+C to stop)"
    while true; do
        clear
        echo "=== Circuit Breaker Status ==="
        echo "Time: $(date '+%H:%M:%S')"
        echo ""
        check_circuit
        echo ""
        echo "Refreshing every 2 seconds..."
        sleep 2
    done
}

# Main
case "${1:-}" in
    reset)
        wait_for_app
        scenario_reset
        ;;
    fast-fail)
        wait_for_app
        scenario_reset
        scenario_fast_fail
        ;;
    slow)
        wait_for_app
        scenario_reset
        scenario_slow
        ;;
    unstable)
        wait_for_app
        scenario_reset
        scenario_unstable
        ;;
    observe)
        wait_for_app
        scenario_observe
        ;;
    watch)
        wait_for_app
        scenario_watch
        ;;
    help|--help|-h)
        echo "Circuit Breaker Test Script"
        echo ""
        echo "Usage: $0 [scenario]"
        echo ""
        echo "Scenarios:"
        echo "  reset       - Reset chaos mode and circuit state"
        echo "  fast-fail   - Trip circuit with 100% failures"
        echo "  slow        - Test slow call threshold (5s delay)"
        echo "  unstable    - 70% failure rate + 3s delay"
        echo "  observe     - Normal calls to observe circuit"
        echo "  watch       - Real-time monitoring"
        echo ""
        echo "Chaos API endpoints:"
        echo "  GET /mock-api/chaos/scenario/fast-failures"
        echo "  GET /mock-api/chaos/scenario/slow-responses"  
        echo "  GET /mock-api/chaos/scenario/unstable"
        echo "  GET /mock-api/chaos/reset"
        echo ""
        echo "Monitor:"
        echo "  curl http://$HOST/actuator/health"
        echo ""
        ;;
    *)
        echo -e "${RED}Unknown scenario: $1${NC}"
        echo "Run '$0 help' for usage"
        exit 1
        ;;
esac