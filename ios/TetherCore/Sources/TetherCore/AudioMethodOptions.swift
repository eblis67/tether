/// The Call audio section's method pickers (docs/CALL-AUDIO.md): which choices one direction offers,
/// from the methods the Android phone reported in its status.
public enum AudioMethodOptions {
    /// "auto" plus the method ids of one direction ("capture" / "inject") the phone reported.
    public static func options(_ helper: HelperInfo, dir: String) -> [String] {
        ["auto"] + (helper.methods ?? []).filter { $0.dir == dir }.map(\.id)
    }

    /// The pick to show for one direction: the phone's reported pick, kept only while it is still offered.
    public static func selection(_ helper: HelperInfo, dir: String) -> String {
        let pick = dir == "inject" ? helper.pick?.inject : helper.pick?.capture
        guard let pick, options(helper, dir: dir).contains(pick) else { return "auto" }
        return pick
    }
}
