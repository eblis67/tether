import Foundation

/// Pure builders for the Share VPN setup payloads (PROTOCOL.md §5.3), mirroring `ProxyRequests` on the
/// Android side: the same inputs produce the same strings on both platforms, and the tests pin that down.
public enum ShareSetup {
    public static let maxBypass = 64

    /// Telegram's SOCKS5 deep link for a proxy address. Telegram also has an MTProto form (`tg://proxy`),
    /// but that needs an MTProto server; Tether's proxy speaks SOCKS5, so this is the working equivalent.
    public static func telegramLink(ip: String, port: Int) -> String {
        "tg://socks?server=\(ip)&port=\(port)"
    }

    /**
     * The split-tunnel bypass list, cleaned the same way the Android phone cleans it before storing: bare
     * lower-case host names only (a pasted scheme, path, port, leading "*." or trailing dot is stripped),
     * duplicates and implausible names dropped, at most `maxBypass` entries.
     */
    public static func sanitizedBypass(_ raw: [String]) -> [String] {
        var out: [String] = []
        for r in raw {
            var h = r.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if let range = h.range(of: "://") { h = String(h[range.upperBound...]) }
            h = String(h.split(separator: "/", maxSplits: 1).first ?? "")
            h = String(h.split(separator: ":", maxSplits: 1).first ?? "")
            if h.hasPrefix("*.") { h = String(h.dropFirst(2)) }
            while h.hasSuffix(".") { h = String(h.dropLast()) }
            guard (1...253).contains(h.count), h.contains("."),
                  h.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "." }),
                  !h.hasPrefix("."), !h.hasPrefix("-"), !h.contains(".."),
                  !out.contains(h) else { continue }
            out.append(h)
            if out.count >= maxBypass { break }
        }
        return out
    }
}

public extension ShareProxyInfo {
    /// The address a setup screen features: the hotspot one (what an iPhone joins), else the first.
    var setupAddr: Addr? { addrs.first { $0.kind == "hotspot" } ?? addrs.first }

    /// The QR payload for the setup: the featured address's payload from the phone, else its PAC URL.
    var qrPayload: String? { setupAddr.map { $0.qr ?? $0.pac } }

    /// The Telegram deep link to offer: the phone's preferred one, else built from the featured address.
    var telegramLink: String? {
        telegram ?? setupAddr.map { ShareSetup.telegramLink(ip: $0.ip, port: port) }
    }
}
