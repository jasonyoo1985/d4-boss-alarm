// D4 Boss HUD - small always-on-top timer for Windows (worlds boss / helltide / legion).
// Plain WinForms, C# 5, no install, no network, no game access (separate window only).
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("D4 Boss HUD")]
[assembly: AssemblyVersion("1.0.0.0")]

namespace D4BossHud
{
    class EventDef
    {
        public string Key, Name;
        public long Anchor, Period, Duration; // ms (epoch)
        public Color Accent;
        public bool Alarm;
        public long NextStart(long now) { return Anchor + ((long)Math.Floor((double)(now - Anchor) / Period) + 1) * Period; }
        public long LastStart(long now) { return NextStart(now) - Period; }
    }

    static class Settings
    {
        static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "D4BossHud");
        static readonly string FilePath = Path.Combine(Dir, "settings.ini");
        static Dictionary<string, string> d = new Dictionary<string, string>();
        public static void Load()
        {
            try
            {
                if (!File.Exists(FilePath)) return;
                foreach (string line in File.ReadAllLines(FilePath))
                {
                    int i = line.IndexOf('=');
                    if (i > 0) d[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
                }
            }
            catch { }
        }
        public static void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                List<string> l = new List<string>();
                foreach (KeyValuePair<string, string> kv in d) l.Add(kv.Key + "=" + kv.Value);
                File.WriteAllLines(FilePath, l.ToArray());
            }
            catch { }
        }
        public static int GetInt(string k, int def)
        {
            string v; int r;
            return d.TryGetValue(k, out v) && int.TryParse(v, out r) ? r : def;
        }
        public static bool GetBool(string k, bool def) { return GetInt(k, def ? 1 : 0) != 0; }
        public static void Set(string k, int v) { d[k] = v.ToString(); }
        public static void Set(string k, bool v) { d[k] = v ? "1" : "0"; }
    }

    class HudForm : Form
    {
        const int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_NOACTIVATE = 0x08000000, WS_EX_LAYERED = 0x80000;
        const int WM_HOTKEY = 0x0312, WM_NCHITTEST = 0x84, HTCAPTION = 2;
        [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr h, int id, int mod, int vk);
        [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr h, int id);
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
        [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h, int i, int v);

        const long MIN = 60000;
        EventDef[] events;
        bool clickThrough = true;   // default: mouse clicks go to the game
        bool soundOn = true;
        int scalePct = 100;
        int opacityPct = 90;
        NotifyIcon tray;
        System.Windows.Forms.Timer timer;
        HashSet<string> fired = new HashSet<string>();
        Dictionary<string, ToolStripMenuItem> items = new Dictionary<string, ToolStripMenuItem>();
        Font fName, fNameBig, fDigit, fDigitBig, fSmall;
        bool hasPos;

        // Base layout at 1080p (scaled by screen height / 1080 and the size setting)
        const int BaseW = 300, BaseH = 112;

        static long Utc(int y, int mo, int d, int h, int mi)
        {
            return (long)(new DateTime(y, mo, d, h, mi, 0, DateTimeKind.Utc) - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        public HudForm()
        {
            events = new EventDef[] {
                new EventDef { Key = "boss", Name = "월드보스", Anchor = 1792587600000L, Period = 210 * MIN, Duration = 15 * MIN, Accent = Color.FromArgb(226, 72, 63) },
                new EventDef { Key = "tide", Name = "지옥물결", Anchor = 1792540800000L, Period = 60 * MIN, Duration = 55 * MIN, Accent = Color.FromArgb(240, 138, 28) },
                new EventDef { Key = "legion", Name = "군단", Anchor = 1792589700000L, Period = 25 * MIN, Duration = 4 * MIN, Accent = Color.FromArgb(139, 108, 240) },
            };
            Settings.Load();
            events[0].Alarm = Settings.GetBool("alarm_boss", true);
            events[1].Alarm = Settings.GetBool("alarm_tide", false);
            events[2].Alarm = Settings.GetBool("alarm_legion", false);
            soundOn = Settings.GetBool("sound", true);
            scalePct = Settings.GetInt("scale", 100);
            opacityPct = Settings.GetInt("opacity", 90);

            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            BackColor = Color.FromArgb(16, 18, 20);
            DoubleBuffered = true;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);

            ApplySizeAndPosition();
            Opacity = opacityPct / 100.0;

            BuildTray();
            timer = new System.Windows.Forms.Timer();
            timer.Interval = 250;
            timer.Tick += delegate { try { Tick(); } catch { } };
            timer.Start();
        }

        protected override bool ShowWithoutActivation { get { return true; } }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                cp.ExStyle |= WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_LAYERED;
                if (clickThrough) cp.ExStyle |= WS_EX_TRANSPARENT;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try { RegisterHotKey(Handle, 1, 0x2 | 0x4, 0x48); } catch { } // Ctrl+Shift+H
        }

        protected override void OnHandleDestroyed(EventArgs e)
        {
            try { UnregisterHotKey(Handle, 1); } catch { }
            base.OnHandleDestroyed(e);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_HOTKEY) { ToggleVisible(); return; }
            if (m.Msg == WM_NCHITTEST && !clickThrough) { m.Result = (IntPtr)HTCAPTION; return; } // drag by any part
            base.WndProc(ref m);
        }

        void ToggleVisible() { Visible = !Visible; if (Visible) TopMost = true; }

        void ApplySizeAndPosition()
        {
            Rectangle b = Screen.PrimaryScreen.Bounds;
            double s = (b.Height / 1080.0) * (scalePct / 100.0);
            int w = (int)(BaseW * s), h = (int)(BaseH * s);
            Size = new Size(w, h);
            if (fName != null) { fName.Dispose(); fNameBig.Dispose(); fDigit.Dispose(); fDigitBig.Dispose(); fSmall.Dispose(); }
            float px = (float)s;
            fName = new Font("Malgun Gothic", 11f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fNameBig = new Font("Malgun Gothic", 14f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fDigit = new Font("Consolas", 22f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fDigitBig = new Font("Consolas", 34f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fSmall = new Font("Malgun Gothic", 10f * px, FontStyle.Regular, GraphicsUnit.Pixel);
            int x, y;
            if (Settings.GetInt("x", int.MinValue) != int.MinValue && Settings.GetBool("pos", false))
            {
                x = Settings.GetInt("x", 0); y = Settings.GetInt("y", 0);
                Point p = new Point(x + w / 2, y + h / 2);
                bool onScreen = false;
                foreach (Screen sc in Screen.AllScreens) if (sc.Bounds.Contains(p)) onScreen = true;
                if (!onScreen) { x = DefaultX(b); y = b.Bottom - h; }
            }
            else { x = DefaultX(b); y = b.Bottom - h; }
            Location = new Point(x, y);
            Invalidate();
        }

        // Right next to the orange resource orb of the default D4 layout (about 73% of the width)
        int DefaultX(Rectangle b) { return b.Left + (int)(b.Width * 0.73); }

        void BuildTray()
        {
            tray = new NotifyIcon();
            tray.Text = "디아4 보스 HUD";
            tray.Icon = MakeIcon();
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add(Item("show", "보이기/숨기기  (Ctrl+Shift+H)", delegate { ToggleVisible(); }, false));
            menu.Items.Add(Item("move", "위치 옮기기 (끝나면 다시 클릭)", delegate { SetMoveMode(clickThrough); }, false));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(Item("a_boss", "월드보스 알림", delegate { events[0].Alarm = !events[0].Alarm; Settings.Set("alarm_boss", events[0].Alarm); Settings.Save(); Sync(); }, true));
            menu.Items.Add(Item("a_tide", "지옥물결 알림", delegate { events[1].Alarm = !events[1].Alarm; Settings.Set("alarm_tide", events[1].Alarm); Settings.Save(); Sync(); }, true));
            menu.Items.Add(Item("a_legion", "군단 알림", delegate { events[2].Alarm = !events[2].Alarm; Settings.Set("alarm_legion", events[2].Alarm); Settings.Save(); Sync(); }, true));
            menu.Items.Add(Item("sound", "알림 소리", delegate { soundOn = !soundOn; Settings.Set("sound", soundOn); Settings.Save(); Sync(); }, true));
            menu.Items.Add(Item("test", "소리 테스트", delegate { PlayAlarm(false); }, false));
            menu.Items.Add(new ToolStripSeparator());
            ToolStripMenuItem size = new ToolStripMenuItem("크기");
            foreach (int p in new int[] { 80, 100, 125, 150 })
            {
                int pp = p;
                ToolStripMenuItem it = new ToolStripMenuItem(pp + "%");
                it.Click += delegate { scalePct = pp; Settings.Set("scale", pp); Settings.Save(); ApplySizeAndPosition(); };
                size.DropDownItems.Add(it);
            }
            menu.Items.Add(size);
            ToolStripMenuItem op = new ToolStripMenuItem("투명도");
            foreach (int p in new int[] { 100, 90, 75, 60 })
            {
                int pp = p;
                ToolStripMenuItem it = new ToolStripMenuItem(pp + "%");
                it.Click += delegate { opacityPct = pp; Settings.Set("opacity", pp); Settings.Save(); Opacity = pp / 100.0; };
                op.DropDownItems.Add(it);
            }
            menu.Items.Add(op);
            menu.Items.Add(Item("reset", "위치 기본값으로", delegate { Settings.Set("pos", false); Settings.Save(); ApplySizeAndPosition(); }, false));
            menu.Items.Add(Item("auto", "윈도우 시작 시 자동 실행", delegate { SetAutoStart(!IsAutoStart()); Sync(); }, true));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(Item("exit", "종료", delegate { tray.Visible = false; Application.Exit(); }, false));
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += delegate { ToggleVisible(); };
            tray.Visible = true;
            Sync();
        }

        ToolStripMenuItem Item(string key, string text, EventHandler h, bool check)
        {
            ToolStripMenuItem it = new ToolStripMenuItem(text);
            it.Click += h;
            items[key] = it;
            return it;
        }

        void Sync()
        {
            items["a_boss"].Checked = events[0].Alarm;
            items["a_tide"].Checked = events[1].Alarm;
            items["a_legion"].Checked = events[2].Alarm;
            items["sound"].Checked = soundOn;
            items["auto"].Checked = IsAutoStart();
            items["move"].Checked = !clickThrough;
        }

        static Icon MakeIcon()
        {
            Bitmap bm = new Bitmap(32, 32);
            using (Graphics g = Graphics.FromImage(bm))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                g.FillEllipse(new SolidBrush(Color.FromArgb(200, 40, 40)), 2, 2, 28, 28);
                g.DrawEllipse(new Pen(Color.FromArgb(255, 210, 120), 2), 3, 3, 26, 26);
                using (Font f = new Font("Consolas", 14f, FontStyle.Bold, GraphicsUnit.Pixel))
                    g.DrawString("D4", f, Brushes.White, 4, 8);
            }
            return Icon.FromHandle(bm.GetHicon());
        }

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        static bool IsAutoStart()
        {
            try { using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue("D4BossHud") != null; }
            catch { return false; }
        }
        static void SetAutoStart(bool on)
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (k == null) return;
                    if (on) k.SetValue("D4BossHud", "\"" + Application.ExecutablePath + "\"");
                    else k.DeleteValue("D4BossHud", false);
                }
            }
            catch { }
        }

        void SetMoveMode(bool move)
        {
            if (move)
            {
                clickThrough = false;
                RecreateHandle();
                Opacity = 1.0;
                MessageBeep();
            }
            else
            {
                clickThrough = true;
                Settings.Set("x", Left); Settings.Set("y", Top); Settings.Set("pos", true); Settings.Save();
                RecreateHandle();
                Opacity = opacityPct / 100.0;
            }
            Sync();
            Invalidate();
        }

        static void MessageBeep() { try { System.Media.SystemSounds.Asterisk.Play(); } catch { } }

        // ---- alarms ----
        void Tick()
        {
            long now = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            foreach (EventDef e in events)
            {
                if (!e.Alarm) continue;
                long next = e.NextStart(now);
                long left = next - now;
                string id5 = e.Key + next + "L5";
                if (left <= 5 * MIN && left > 5 * MIN - 10000 && fired.Add(id5)) PlayAlarm(false);
                long last = e.LastStart(now);
                if (now - last >= 0 && now - last < 10000 && fired.Add(e.Key + last + "S")) PlayAlarm(true);
            }
            if (fired.Count > 200) fired.Clear();
            Invalidate();
        }

        void PlayAlarm(bool start)
        {
            if (!soundOn) return;
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    if (start) { Console.Beep(1047, 220); Console.Beep(1319, 220); Console.Beep(1568, 380); Console.Beep(1319, 220); Console.Beep(1568, 500); }
                    else { Console.Beep(880, 250); Thread.Sleep(80); Console.Beep(880, 250); }
                }
                catch { try { System.Media.SystemSounds.Exclamation.Play(); } catch { } }
            });
        }

        // ---- drawing ----
        protected override void OnPaint(PaintEventArgs pe)
        {
            Graphics g = pe.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            int w = ClientSize.Width, h = ClientSize.Height;
            g.Clear(Color.FromArgb(16, 18, 20));
            float s = (float)w / BaseW;
            using (Pen border = new Pen(clickThrough ? Color.FromArgb(70, 120, 125) : Color.FromArgb(255, 200, 80), Math.Max(1.5f, 1.5f * s)))
                g.DrawRectangle(border, 0.5f, 0.5f, w - 2, h - 2);

            long now = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            float[] rowH = { 52 * s, 28 * s, 28 * s };
            float y = 3 * s;
            StringFormat left = new StringFormat { Alignment = StringAlignment.Near, LineAlignment = StringAlignment.Center, FormatFlags = StringFormatFlags.NoWrap };
            StringFormat right = new StringFormat { Alignment = StringAlignment.Far, LineAlignment = StringAlignment.Center, FormatFlags = StringFormatFlags.NoWrap };
            for (int i = 0; i < 3; i++)
            {
                EventDef e = events[i];
                bool big = i == 0;
                long last = e.LastStart(now);
                bool live = now < last + e.Duration;
                long ms = live ? last + e.Duration - now : e.NextStart(now) - now;
                long sec = Math.Max(0, ms / 1000);
                string txt = string.Format("{0:00}:{1:00}:{2:00}", sec / 3600, sec % 3600 / 60, sec % 60);
                Color c;
                if (live) c = Color.FromArgb(110, 230, 140);
                else if (ms <= 1 * MIN) c = (now / 500 % 2 == 0) ? Color.FromArgb(255, 70, 60) : Color.FromArgb(255, 140, 120);
                else if (ms <= 5 * MIN) c = Color.FromArgb(255, 205, 70);
                else c = Color.FromArgb(240, 236, 228);
                RectangleF r = new RectangleF(8 * s, y, w - 16 * s, rowH[i]);
                using (SolidBrush dot = new SolidBrush(e.Accent))
                    g.FillEllipse(dot, 8 * s, y + rowH[i] / 2 - 4 * s, 8 * s, 8 * s);
                RectangleF nameR = new RectangleF(22 * s, y, 110 * s, rowH[i]);
                using (SolidBrush nb = new SolidBrush(Color.FromArgb(215, 205, 195)))
                {
                    g.DrawString(e.Name, big ? fNameBig : fName, nb, nameR, left);
                    if (live) g.DrawString("진행 중", fSmall, new SolidBrush(Color.FromArgb(110, 230, 140)),
                        new RectangleF(22 * s + (big ? 68 : 56) * s, y, 70 * s, rowH[i]), left);
                }
                using (SolidBrush db = new SolidBrush(c))
                    g.DrawString(txt, big ? fDigitBig : fDigit, db, r, right);
                y += rowH[i];
                if (i < 2) using (Pen sep = new Pen(Color.FromArgb(48, 56, 58), 1f)) g.DrawLine(sep, 8 * s, y, w - 8 * s, y);
            }
            if (!clickThrough)
                g.DrawString("드래그해서 옮기세요 · 끝나면 트레이에서 다시 클릭", fSmall, Brushes.Gold, new RectangleF(0, 0, w, 14 * s), new StringFormat { Alignment = StringAlignment.Center });
        }
    }

    static class Program
    {
        [STAThread]
        static void Main()
        {
            bool created;
            using (Mutex m = new Mutex(true, "D4BossHud-single", out created))
            {
                if (!created) return;
                try { SetProcessDPIAware(); } catch { }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                try { Application.Run(new HudForm()); }
                catch (Exception ex)
                {
                    try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "D4BossHud-error.txt"), ex.ToString()); } catch { }
                }
            }
        }
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    }
}
