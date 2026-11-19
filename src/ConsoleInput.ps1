Set-StrictMode -Version Latest

if (-not ("Procomp.Win32Console" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Procomp {
    public class ConsoleInputEvent {
        public string Kind;
        public int X;
        public int Y;
        public int VirtualKeyCode;
        public char UnicodeChar;
        public bool CtrlPressed;
    }

    public static class Win32Console {
        private const int STD_INPUT_HANDLE = -10;
        private const uint ENABLE_PROCESSED_INPUT = 0x0001;
        private const uint ENABLE_MOUSE_INPUT = 0x0010;
        private const uint ENABLE_QUICK_EDIT_MODE = 0x0040;
        private const uint ENABLE_EXTENDED_FLAGS = 0x0080;

        private const ushort KEY_EVENT = 0x0001;
        private const ushort MOUSE_EVENT = 0x0002;
        private const uint DOUBLE_CLICK = 0x0002;
        private const uint MOUSE_WHEELED = 0x0004;
        private const uint LEFT_CTRL_PRESSED = 0x0008;
        private const uint RIGHT_CTRL_PRESSED = 0x0004;

        private static uint _savedMode = 0;
        private static bool _hasSavedMode = false;

        [StructLayout(LayoutKind.Explicit)]
        private struct INPUT_RECORD {
            [FieldOffset(0)] public ushort EventType;
            [FieldOffset(4)] public KEY_EVENT_RECORD KeyEvent;
            [FieldOffset(4)] public MOUSE_EVENT_RECORD MouseEvent;
        }

        [StructLayout(LayoutKind.Explicit, CharSet = CharSet.Unicode)]
        private struct KEY_EVENT_RECORD {
            [FieldOffset(0)] public int bKeyDown;
            [FieldOffset(4)] public ushort wRepeatCount;
            [FieldOffset(6)] public ushort wVirtualKeyCode;
            [FieldOffset(8)] public ushort wVirtualScanCode;
            [FieldOffset(10)] public char UnicodeChar;
            [FieldOffset(12)] public uint dwControlKeyState;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct COORD {
            public short X;
            public short Y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSE_EVENT_RECORD {
            public COORD dwMousePosition;
            public uint dwButtonState;
            public uint dwControlKeyState;
            public uint dwEventFlags;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr GetStdHandle(int nStdHandle);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetNumberOfConsoleInputEvents(IntPtr hConsoleInput, out uint lpcNumberOfEvents);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool ReadConsoleInputW(IntPtr hConsoleInput, [Out] INPUT_RECORD[] lpBuffer, uint nLength, out uint lpNumberOfEventsRead);

        public static bool EnableMouse() {
            IntPtr hIn = GetStdHandle(STD_INPUT_HANDLE);
            uint mode;
            if (!GetConsoleMode(hIn, out mode)) return false;
            _savedMode = mode;
            _hasSavedMode = true;
            uint newMode = (mode & ~ENABLE_QUICK_EDIT_MODE & ~ENABLE_PROCESSED_INPUT) | ENABLE_MOUSE_INPUT | ENABLE_EXTENDED_FLAGS;
            return SetConsoleMode(hIn, newMode);
        }

        public static void RestoreMode() {
            if (!_hasSavedMode) return;
            IntPtr hIn = GetStdHandle(STD_INPUT_HANDLE);
            SetConsoleMode(hIn, _savedMode);
            _hasSavedMode = false;
        }

        public static ConsoleInputEvent[] ReadPendingEvents() {
            IntPtr hIn = GetStdHandle(STD_INPUT_HANDLE);
            uint count;
            if (!GetNumberOfConsoleInputEvents(hIn, out count) || count == 0) {
                return new ConsoleInputEvent[0];
            }

            uint toRead = Math.Min(count, 64u);
            INPUT_RECORD[] buf = new INPUT_RECORD[toRead];
            uint read;
            if (!ReadConsoleInputW(hIn, buf, toRead, out read) || read == 0) {
                return new ConsoleInputEvent[0];
            }

            var list = new List<ConsoleInputEvent>();
            for (int i = 0; i < read; i++) {
                if (buf[i].EventType == KEY_EVENT) {
                    var ke = buf[i].KeyEvent;
                    if (ke.bKeyDown != 0) {
                        bool ctrl = (ke.dwControlKeyState & (LEFT_CTRL_PRESSED | RIGHT_CTRL_PRESSED)) != 0;
                        list.Add(new ConsoleInputEvent {
                            Kind = "Key",
                            VirtualKeyCode = ke.wVirtualKeyCode,
                            UnicodeChar = ke.UnicodeChar,
                            CtrlPressed = ctrl
                        });
                    }
                } else if (buf[i].EventType == MOUSE_EVENT) {
                    var me = buf[i].MouseEvent;
                    if (me.dwEventFlags == MOUSE_WHEELED) {
                        int delta = (short)((me.dwButtonState >> 16) & 0xFFFF);
                        list.Add(new ConsoleInputEvent {
                            Kind = delta > 0 ? "WheelUp" : "WheelDown",
                            X = me.dwMousePosition.X,
                            Y = me.dwMousePosition.Y
                        });
                    } else if (me.dwEventFlags == DOUBLE_CLICK && (me.dwButtonState & 0x0001) != 0) {
                        list.Add(new ConsoleInputEvent {
                            Kind = "MouseDoubleClick",
                            X = me.dwMousePosition.X,
                            Y = me.dwMousePosition.Y
                        });
                    } else if (me.dwEventFlags == 0 && (me.dwButtonState & 0x0001) != 0) {
                        list.Add(new ConsoleInputEvent {
                            Kind = "MouseClick",
                            X = me.dwMousePosition.X,
                            Y = me.dwMousePosition.Y
                        });
                    }
                }
            }
            return list.ToArray();
        }
    }
}
"@
}

function Enable-ConsoleMouseInput {
    try { return [Procomp.Win32Console]::EnableMouse() } catch { return $false }
}

function Restore-ConsoleInputMode {
    try { [Procomp.Win32Console]::RestoreMode() } catch { }
}

function Read-ConsoleEvents {
    try { return @([Procomp.Win32Console]::ReadPendingEvents()) } catch { return @() }
}
