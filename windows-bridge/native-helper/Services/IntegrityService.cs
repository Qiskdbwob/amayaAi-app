using System.Runtime.InteropServices;
using System.Text;
using AmayaBridgeHelper.Windows;

namespace AmayaBridgeHelper.Services;

/// <summary>
/// Windows integrity-level helpers.
///
/// UIPI blocks synthesized input (SendInput, PostMessage, SendMessage) from a
/// lower-integrity process to a higher-integrity window. The OS returns success
/// from SendInput but silently drops the event. The only way to detect this
/// before attempting input is to read the target window's process token
/// integrity and compare against our own. If our helper runs Medium integrity
/// and the target is High/System integrity, we refuse up front with
/// PERMISSION_DENIED and tell the user to relaunch the bridge elevated.
/// </summary>
internal static class IntegrityService
{
    // SYSTEM 0x4000+, HIGH 0x3000, MEDIUM 0x2000, LOW 0x1000, UNTRUSTED 0x0000.
    // Values are well known; see winnt.h SECURITY_MANDATORY_* constants.
    private const int SECURITY_MANDATORY_UNTRUSTED_RID = 0x00000000;
    private const int SECURITY_MANDATORY_LOW_RID = 0x00001000;
    private const int SECURITY_MANDATORY_MEDIUM_RID = 0x00002000;
    private const int SECURITY_MANDATORY_HIGH_RID = 0x00003000;
    private const int SECURITY_MANDATORY_SYSTEM_RID = 0x00004000;

    private const int TokenIntegrityLevel = 25;
    private const uint TOKEN_QUERY = 0x0008;
    private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private static readonly Lazy<string> SelfLabel = new(() => LabelFromRid(SelfRidInternal()));
    private static readonly Lazy<int> SelfRid = new(SelfRidInternal);

    public enum IntegrityLevel
    {
        Unknown,
        Untrusted,
        Low,
        Medium,
        High,
        System
    }

    public static string SelfIntegrity => SelfLabel.Value;
    public static int SelfIntegrityRid => SelfRid.Value;

    /// <summary>
    /// Returns the integrity label for the window handle's owning process.
    /// "unknown" when the token cannot be opened (e.g. protected process).
    /// </summary>
    public static IntegrityLevel LevelForWindow(IntPtr hWnd)
    {
        if (hWnd == IntPtr.Zero) return IntegrityLevel.Unknown;
        NativeMethods.GetWindowThreadProcessId(hWnd, out uint pid);
        if (pid == 0) return IntegrityLevel.Unknown;
        return LevelForProcess((int)pid);
    }

    public static IntegrityLevel LevelForProcess(int pid)
    {
        if (pid <= 0) return IntegrityLevel.Unknown;
        IntPtr proc = NativeMethods.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, (uint)pid);
        if (proc == IntPtr.Zero) return IntegrityLevel.Unknown;
        try
        {
            return RidToLevel(RidFromProcess(proc));
        }
        finally
        {
            NativeMethods.CloseHandle(proc);
        }
    }

    public static string LabelForWindow(IntPtr hWnd) => RidToLabel(LevelForWindow(hWnd));
    public static string LabelForProcess(int pid) => RidToLabel(LevelForProcess(pid));

    /// <summary>
    /// True when this helper cannot inject input into the window because the
    /// window runs at a higher integrity level than the helper.
    /// </summary>
    public static bool WouldBeBlockedByUipi(IntPtr hWnd)
    {
        var target = LevelForWindow(hWnd);
        if (target == IntegrityLevel.Unknown) return false; // conservative: try anyway
        return IntegrityRank(target) > IntegrityRank(RidToLevel(SelfRid.Value));
    }

    public static bool WouldBeBlockedByUipi(string? windowId)
    {
        if (string.IsNullOrWhiteSpace(windowId)) return false;
        if (!long.TryParse(windowId, out var handleValue)) return false;
        return WouldBeBlockedByUipi(new IntPtr(handleValue));
    }

    private static int RidFromProcess(IntPtr proc)
    {
        if (!NativeMethods.OpenProcessToken(proc, TOKEN_QUERY, out var token)) return -1;
        try
        {
            // First query with zero-length buffer to discover required size.
            uint size = 0;
            NativeMethods.GetTokenInformation(token, TokenIntegrityLevel, IntPtr.Zero, 0, out size);
            if (size == 0) return -1;
            IntPtr buffer = Marshal.AllocHGlobal((int)size);
            try
            {
                if (!NativeMethods.GetTokenInformation(token, TokenIntegrityLevel, buffer, size, out _))
                {
                    return -1;
                }
                // TOKEN_MANDATORY_LABEL { PSID Label; }; SID = { Revision, SubAuthorityCount, IdentifierAuthority, SubAuthority[SubAuthorityCount] }
                IntPtr sid = Marshal.ReadIntPtr(buffer); // first field of TOKEN_MANDATORY_LABEL
                if (sid == IntPtr.Zero) return -1;
                byte subCount = Marshal.ReadByte(sid, 1);
                if (subCount == 0) return -1;
                // SubAuthority[n] starts at offset 8 (revision + count + auth identifier = 8 bytes), each 4 bytes
                int lastIndex = subCount - 1;
                int offset = 8 + lastIndex * 4;
                return Marshal.ReadInt32(sid, offset);
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }
        finally
        {
            NativeMethods.CloseHandle(token);
        }
    }

    private static int SelfRidInternal()
    {
        IntPtr self = NativeMethods.GetCurrentProcess();
        return RidFromProcess(self);
    }

    private static IntegrityLevel RidToLevel(int rid)
    {
        if (rid < 0) return IntegrityLevel.Unknown;
        if (rid >= SECURITY_MANDATORY_SYSTEM_RID) return IntegrityLevel.System;
        if (rid >= SECURITY_MANDATORY_HIGH_RID) return IntegrityLevel.High;
        if (rid >= SECURITY_MANDATORY_MEDIUM_RID) return IntegrityLevel.Medium;
        if (rid >= SECURITY_MANDATORY_LOW_RID) return IntegrityLevel.Low;
        return IntegrityLevel.Untrusted;
    }

    private static string LabelFromRid(int rid) => RidToLabel(RidToLevel(rid));

    private static string RidToLabel(IntegrityLevel level) => level switch
    {
        IntegrityLevel.System => "system",
        IntegrityLevel.High => "high",
        IntegrityLevel.Medium => "medium",
        IntegrityLevel.Low => "low",
        IntegrityLevel.Untrusted => "untrusted",
        _ => "unknown"
    };

    private static int IntegrityRank(IntegrityLevel level) => level switch
    {
        IntegrityLevel.Untrusted => 0,
        IntegrityLevel.Low => 1,
        IntegrityLevel.Medium => 2,
        IntegrityLevel.High => 3,
        IntegrityLevel.System => 4,
        _ => -1
    };
}
