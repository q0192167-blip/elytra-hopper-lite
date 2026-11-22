#!/usr/bin/env python3
"""Elytra Hopper 状态机模拟器：对比 v7(闩锁) 与 v8(落地电平) 在关键场景的行为。"""

COOLDOWN = 20

def sim_v7(ticks, jump_held=True):
    """v7: 边沿 + awaitingRecast 闩锁。ticks: list of (fallFlying, onGround)"""
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
    """v8: 落地电平 + onGround + 冷却，无闩锁。"""
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
    """正常 hopping：飞 flight tick -> 落地 ground tick -> 服务端认可起飞 -> 循环"""
    ticks = []
    while len(ticks) < n:
        ticks += [(True, False)] * flight
        ticks += [(False, True)] * ground
    return ticks[:n]

def scenario_server_reject(n=120):
    """落地跳被服务端拒绝：一直在地面 (fallFlying=false, onGround=true)"""
    return [(False, True)] * n

def scenario_airborne_flap(n=120):
    """v5 抽搐场景：空中滑翔，服务端反复切回 fallFlying=false，但 onGround=false"""
    ticks = []
    while len(ticks) < n:
        ticks += [(True, False)] * 2
        ticks += [(False, False)] * 2
    return ticks[:n]

def run(name, ticks, expect):
    r7 = sim_v7(ticks)
    r8 = sim_v8(ticks)
    print(f"\n=== {name} ===")
    print(f"  v7 重开次数={len(r7)}  刻度={r7[:12]}{'...' if len(r7)>12 else ''}")
    print(f"  v8 重开次数={len(r8)}  刻度={r8[:12]}{'...' if len(r8)>12 else ''}")
    print(f"  v7: {expect['v7']}")
    print(f"  v8: {expect['v8']}")

if __name__ == '__main__':
    run("正常 hopping（服务端认可起飞）",
        scenario_normal_hopping(),
        {"v7": "应正常循环", "v8": "应正常循环"})

    run("落地跳被服务端拒绝（只停地面）",
        scenario_server_reject(),
        {"v7": "死锁:只重开1次 -> 就是用户说的只跳一次", "v8": "每20tick稳定重试,不再卡死"})

    run("空中反复切回（v5 抽搐场景）",
        scenario_airborne_flap(),
        {"v7": "0次,不抽搐", "v8": "0次,不抽搐"})

    # 松开跳跃
    ticks = [(False, True)] * 40
    print("\n=== 松开跳跃 ===")
    print(f"  v7 重开次数={len(sim_v7(ticks, jump_held=False))} (应0)")
    print(f"  v8 重开次数={len(sim_v8(ticks, jump_held=False))} (应0)")
