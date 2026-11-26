import ActivityKit
import SwiftUI
import TetherCore
import WidgetKit

/// The Live Activity's UI (docs/LIVE-ACTIVITY.md): everything the Lock Screen and the Dynamic
/// Island show of the Android phone. The app starts, updates and ends the activity
/// (`LiveActivityController`); this extension only renders `PhoneLiveState`, which both sides take
/// from TetherCore so the type can never drift.
@main
struct TetherLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        PhoneLiveActivity()
    }
}

struct PhoneLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: PhoneActivityAttributes.self) { context in
            LockScreenView(state: context.state)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.device).font(.headline).lineLimit(1)
                        if let sim = context.state.featuredSim { SimLine(sim: sim) }
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    BatteryView(state: context.state).font(.headline)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ExpandedBottom(state: context.state)
                }
            } compactLeading: {
                HStack(spacing: 4) {
                    Image(systemName: context.state.linked ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                    if let sim = context.state.featuredSim, sim.hasService {
                        SignalBars(level: sim.signal)
                        if let net = sim.net { Text(net).font(.caption2.weight(.semibold)) }
                    }
                }
            } compactTrailing: {
                BatteryView(state: context.state)
            } minimal: {
                Image(systemName: batteryIcon(context.state))
            }
            .keylineTint(.teal)
        }
    }
}

// MARK: Lock Screen

private struct LockScreenView: View {
    let state: PhoneLiveState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: state.linked ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                    .foregroundStyle(state.linked ? .teal : .secondary)
                Text(state.device).font(.headline).lineLimit(1)
                Spacer(minLength: 8)
                BatteryView(state: state)
            }
            if !state.linked {
                Text("Out of reach").font(.subheadline).foregroundStyle(.orange)
            }
            ForEach(state.sims, id: \.slot) { sim in
                HStack(spacing: 6) {
                    if state.sims.count > 1 {
                        Text(sim.name.isEmpty ? "SIM \(sim.slot + 1)" : sim.name)
                            .font(.subheadline.weight(.semibold)).lineLimit(1)
                    }
                    SimLine(sim: sim)
                    Spacer(minLength: 4)
                    Text(sim.carrier).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            if let call = state.call {
                Divider()
                CallLine(call: call)
            }
        }
        .padding(14)
    }
}

// MARK: Dynamic Island expanded bottom

private struct ExpandedBottom: View {
    let state: PhoneLiveState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let call = state.call {
                CallLine(call: call)
            } else if !state.linked {
                Text("Out of reach").font(.subheadline).foregroundStyle(.orange)
            } else {
                ForEach(state.sims, id: \.slot) { sim in
                    HStack(spacing: 6) {
                        if state.sims.count > 1 {
                            Text(sim.name.isEmpty ? "SIM \(sim.slot + 1)" : sim.name)
                                .font(.caption.weight(.semibold)).lineLimit(1)
                        }
                        SimLine(sim: sim)
                        Spacer(minLength: 4)
                        Text(sim.carrier).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: pieces

/// One call in the activity: who it is with, what state it is in, and the running timer once
/// connected. CallKit owns the call's own Lock Screen UI; this is the glanceable line inside ours.
private struct CallLine: View {
    let call: PhoneLiveState.Call

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: call.incoming ? "phone.arrow.down.left" : "phone.arrow.up.right")
                .foregroundStyle(.green)
            Text(call.label).font(.subheadline.weight(.semibold)).lineLimit(1)
            Spacer(minLength: 4)
            if call.elsewhere {
                Text("On the Android phone").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            } else if call.held {
                Text("On hold").font(.subheadline).foregroundStyle(.secondary)
            } else if call.connected, let since = call.since {
                Text(timerInterval: since...Date(timeIntervalSinceNow: 12 * 3600), countsDown: false)
                    .font(.subheadline.monospacedDigit())
                    .frame(maxWidth: 64, alignment: .trailing)
            } else {
                Text(call.incoming ? "Ringing…" : "Calling…").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

/// Bars, network label and roaming for one SIM — or "No service" / "SOS".
private struct SimLine: View {
    let sim: PhoneLiveState.Sim

    var body: some View {
        HStack(spacing: 3) {
            if sim.hasService {
                SignalBars(level: sim.signal)
                if let net = sim.net { Text(net).font(.caption.weight(.semibold)) }
                if sim.roaming { Text("R").font(.caption.weight(.bold)).foregroundStyle(.orange) }
            } else {
                Text(sim.service == "emergency" ? "SOS" : String(localized: "No service"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct SignalBars: View {
    let level: Int
    var body: some View {
        HStack(alignment: .bottom, spacing: 1) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < level ? Color.primary : Color.secondary.opacity(0.3))
                    .frame(width: 3, height: CGFloat(4 + i * 2))
            }
        }
        .accessibilityLabel(Text("Signal \(level) of 4"))
    }
}

private struct BatteryView: View {
    let state: PhoneLiveState

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: batteryIcon(state))
            Text("\(state.battery)%").font(.caption.weight(.semibold).monospacedDigit())
        }
        .foregroundStyle(state.battery <= 15 && !state.charging ? .red : .primary)
    }
}

private func batteryIcon(_ state: PhoneLiveState) -> String {
    if state.charging { return "battery.100.bolt" }
    switch state.battery {
    case ..<13: return "battery.0"
    case ..<38: return "battery.25"
    case ..<63: return "battery.50"
    case ..<88: return "battery.75"
    default: return "battery.100"
    }
}
