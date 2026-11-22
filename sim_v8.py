#!/usr/bin/env python3
"""Elytra Hopper state machine simulator: compares v7 (latch) and v8 (landing level) behavior in key scenarios."""

COOLDOWN = 20

def sim_v7(ticks, jump_held=True):
    """v7: edge detection + awaitingRecast latch. ticks: list of (fallFlying, onGround)"""
    prev = False
    awaiting = False
    cooldown = 0
    recasts = []
    for i, (fall, ground) in enumerate(ticks):
        if not jump_held:
            prev = awaiting = False; cooldown = 0; continue
        if cooldown > 0: cooldown -= 1
        if awaiting:
            if fall: awaiting = False
        elif (not fall) and prev and ground and cooldown == 0:
            recasts.append(i); awaiting = True; cooldown = COOLDOWN
        prev = fall
    return recasts

def sim_v8(ticks, jump_held=True):
    """v8: landing level + onGround + cooldown, no latch."""
    cooldown = 0
    recasts = []
    for i, (fall, ground) in enumerate(ticks):
        if not jump_held:
            cooldown = 0; continue
        if cooldown > 0: cooldown -= 1
        if (not fall) and ground and cooldown == 0:
            recasts.append(i); cooldown = COOLDOWN
    return recasts

def scenario_normal_hopping(n=120, flight=25, ground=2):
    """Normal hopping: fly for `flight` ticks -> land for `ground` ticks -> server acknowledges takeoff -> loop"""
    ticks = []
    while len(ticks) < n:
        ticks += [(True, False)] * flight
        ticks += [(False, True)] * ground
    return ticks[:n]

def scenario_server_reject(n=120):
    """Landing hop rejected by server: stays on the ground (fallFlying=false, onGround=true)"""
    return [(False, True)] * n

def scenario_airborne_flap(n=120):
    """v5 flapping scenario: gliding in the air, server repeatedly flips fallFlying=false while onGround=false"""
    ticks = []
    while len(ticks) < n:
        ticks += [(True, False)] * 2
        ticks += [(False, False)] * 2
    return ticks[:n]

def run(name, ticks, expect):
    r7 = sim_v7(ticks)
    r8 = sim_v8(ticks)
    print(f"\n=== {name} ===")
    print(f"  v7 recasts={len(r7)}  ticks={r7[:12]}{'...' if len(r7)>12 else ''}")
    print(f"  v8 recasts={len(r8)}  ticks={r8[:12]}{'...' if len(r8)>12 else ''}")
    print(f"  v7: {expect['v7']}")
    print(f"  v8: {expect['v8']}")

if __name__ == '__main__':
    run("Normal hopping (server acknowledges takeoff)",
        scenario_normal_hopping(),
        {"v7": "should loop normally", "v8": "should loop normally"})

    run("Landing hop rejected by server (stays on ground)",
        scenario_server_reject(),
        {"v7": "deadlock: only 1 recast -> the reported 'jumps once'", "v8": "stable retry every 20 ticks, no deadlock"})

    run("Repeatedly flipped back in air (v5 flapping scenario)",
        scenario_airborne_flap(),
        {"v7": "0 recasts, no flapping", "v8": "0 recasts, no flapping"})

    # Jump released
    ticks = [(False, True)] * 40
    print("\n=== Jump released ===")
    print(f"  v7 recasts={len(sim_v7(ticks, jump_held=False))} (should be 0)")
    print(f"  v8 recasts={len(sim_v8(ticks, jump_held=False))} (should be 0)")
