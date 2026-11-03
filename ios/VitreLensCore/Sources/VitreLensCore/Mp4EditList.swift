import Foundation

/// 从 MP4 里读视频轨 edit list 的起始偏移（微秒）。
///
/// 动态照片的视频段常带一条 elst：媒体时间 0 之前还有一段「预卷」，
/// 真正的第 0 帧在 media_time 处。不处理这段预卷，成片视频会相对音频整体提前。
///
/// 只做最小解析：moov → trak(hdlr=vide) → edts/elst 第一条 + mdia/mdhd 的 timescale。
/// 任何一步不符合预期都返回 0，不影响主流程。逐行移植自 Android `Mp4EditList`。
public enum Mp4EditList {

    /// 超过这个大小就不解析，避免把大文件整个读进内存
    public static let maxBytes = 96 << 20

    /// 视频轨要跳过的媒体时间（微秒）；读不到返回 0
    public static func videoStartUs(url: URL) -> Int64 {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              data.count >= 32, data.count <= maxBytes else { return 0 }
        return videoStartUs(data: [UInt8](data))
    }

    public static func videoStartUs(data d: [UInt8]) -> Int64 {
        guard d.count >= 32 else { return 0 }
        let moov = findBox(d, 0, d.count, "moov")
        guard moov >= 0 else { return 0 }
        let moovEnd = min(d.count, moov + boxSize(d, moov))

        var p = moov + headerSize(d, moov)
        while p + 8 <= moovEnd {
            if boxType(d, p) == "trak" {
                let us = parseTrak(d, p, moovEnd)
                if us >= 0 { return us }
            }
            let next = min(moovEnd, p + boxSize(d, p))
            if next <= p { break }
            p = next
        }
        return 0
    }

    /// 返回 >=0 表示找到了视频轨的 edit 偏移；-1 表示这条 trak 不是目标
    private static func parseTrak(_ d: [UInt8], _ trak: Int, _ limit: Int) -> Int64 {
        let end = min(limit, trak + boxSize(d, trak))
        var p = trak + headerSize(d, trak)

        var isVideo = false
        var timescale = 0
        var mediaTime: Int64 = -1
        var hasElst = false

        while p + 8 <= end {
            let t = boxType(d, p)
            let bEnd = min(end, p + boxSize(d, p))
            if t == "mdia" {
                var q = p + headerSize(d, p)
                while q + 8 <= bEnd {
                    let t2 = boxType(d, q)
                    let qEnd = min(bEnd, q + boxSize(d, q))
                    if t2 == "hdlr" {
                        // version(1) flags(3) pre_defined(4) handler_type(4)
                        // 注意 handler_type 不是盒头，直接从 h 读 4 字节
                        let h = q + headerSize(d, q) + 8
                        if h + 4 <= qEnd {
                            isVideo = String(bytes: d[h..<(h + 4)], encoding: .isoLatin1) == "vide"
                        }
                    } else if t2 == "mdhd" {
                        let m = q + headerSize(d, q)
                        if m + 24 <= qEnd {
                            let ver = Int(d[m])
                            timescale = readInt(d, m + (ver == 1 ? 20 : 12))
                        }
                    }
                    if qEnd <= q { break }
                    q = qEnd
                }
            } else if t == "edts" {
                var q = p + headerSize(d, p)
                while q + 8 <= bEnd {
                    let t2 = boxType(d, q)
                    let qEnd = min(bEnd, q + boxSize(d, q))
                    if t2 == "elst" {
                        let m = q + headerSize(d, q)
                        if m + 8 <= qEnd {
                            let ver = Int(d[m])
                            let count = Int64(readInt(d, m + 4) & 0xFFFF_FFFF)
                            let e = m + 8
                            // 只看第一条：它是正片起始位置，后面的是后续剪辑段
                            if count > 0 {
                                mediaTime = ver == 1
                                    ? readLong(d, e + 8)      // segment_duration(8) media_time(8)
                                    : Int64(readInt(d, e + 4)) // segment_duration(4) media_time(4)
                                hasElst = true
                            }
                        }
                    }
                    if qEnd <= q { break }
                    q = qEnd
                }
            }
            if bEnd <= p { break }
            p = bEnd
        }

        // media_time = -1 是「空编辑」，不是我们要的偏移
        if !isVideo || !hasElst || mediaTime <= 0 || timescale <= 0 { return -1 }
        let us = mediaTime * 1_000_000 / Int64(timescale)
        // 超过 10 秒的偏移几乎不可能是预卷，当作解析错了
        return (us > 0 && us < 10_000_000) ? us : -1
    }

    // MARK: - box 基础读取

    private static func boxType(_ d: [UInt8], _ o: Int) -> String {
        guard o + 8 <= d.count else { return "" }
        return String(bytes: d[(o + 4)..<(o + 8)], encoding: .isoLatin1) ?? ""
    }

    private static func boxSize(_ d: [UInt8], _ o: Int) -> Int {
        var sz = Int64(readInt(d, o) & 0xFFFF_FFFF)
        if sz == 1 {
            sz = readLong(d, o + 8)
        } else if sz == 0 {
            sz = Int64(d.count - o)   // 延伸到文件末尾
        }
        return Int(sz)
    }

    private static func headerSize(_ d: [UInt8], _ o: Int) -> Int {
        (readInt(d, o) & 0xFFFF_FFFF) == 1 ? 16 : 8
    }

    private static func readInt(_ d: [UInt8], _ o: Int) -> Int {
        guard o >= 0, o + 4 <= d.count else { return 0 }
        return (Int(d[o]) << 24) | (Int(d[o + 1]) << 16) | (Int(d[o + 2]) << 8) | Int(d[o + 3])
    }

    private static func readLong(_ d: [UInt8], _ o: Int) -> Int64 {
        guard o >= 0, o + 8 <= d.count else { return 0 }
        var v: Int64 = 0
        for i in 0..<8 { v = (v << 8) | Int64(d[o + i]) }
        return v
    }

    private static func findBox(_ d: [UInt8], _ from: Int, _ to: Int, _ type: String) -> Int {
        var p = from
        while p + 8 <= to {
            let sz = boxSize(d, p)
            if sz < 8 { return -1 }
            if boxType(d, p) == type { return p }
            let next = min(to, p + sz)
            if next <= p { return -1 }
            p = next
        }
        return -1
    }
}
