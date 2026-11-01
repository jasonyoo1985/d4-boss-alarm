// D4 Boss HUD - small always-on-top timer for Windows (worlds boss / helltide / legion).
// Plain WinForms, C# 5, no install, no network, no game access (separate window only).
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Media;
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
        const int GWL_EXSTYLE = -20;
        const int WM_HOTKEY = 0x0312, WM_NCHITTEST = 0x84, HTCLIENT = 1, HTCAPTION = 2;
        [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr h, int id, int mod, int vk);
        [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr h, int id);
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
        [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h, int i, int v);

        const long MIN = 60000;
        // Base layout at 1080p (scaled by screen height / 1080 and the size setting)
        const int BaseW = 300, BaseH = 112, HeaderH = 22, SideW = 58;

        static readonly string[] HkNames = { "F7", "F8", "F9", "F11", "F12", "Pause", "ScrollLock" };
        static readonly int[] HkVk = { 0x76, 0x77, 0x78, 0x7A, 0x7B, 0x13, 0x91 };
        int leadMin = 1;            // minutes before the event for the alarm
        int volPct = 60;
        bool ringing;
        DateTime ringEnd;
        SoundPlayer player;
        byte[] ringWav, startWav;
        int hkIndex = 1;            // default F8 toggles lock/edit
        EventDef[] events;
        bool locked;                // locked = game mode: display only, mouse clicks pass through
        bool soundOn = true;
        int scalePct = 100;
        int opacityPct = 90;
        double S = 1.0;             // current pixel scale
        NotifyIcon tray;
        System.Windows.Forms.Timer timer;
        HashSet<string> fired = new HashSet<string>();
        List<ToolStripMenuItem> leadItems = new List<ToolStripMenuItem>();
        List<ToolStripMenuItem> volItems = new List<ToolStripMenuItem>();
        List<ToolStripMenuItem> hkItems = new List<ToolStripMenuItem>();
        Dictionary<string, ToolStripMenuItem> items = new Dictionary<string, ToolStripMenuItem>();
        Font fName, fNameBig, fDigit, fDigitBig, fSmall, fBold;
        Rectangle rLock, rClose;
        Rectangle[] rToggle = new Rectangle[3];

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
            leadMin = Settings.GetInt("lead", 1);
            volPct = Settings.GetInt("vol", 60);
            BuildWavs();
            hkIndex = Math.Max(0, Math.Min(HkVk.Length - 1, Settings.GetInt("hk", 1)));
            locked = Settings.GetBool("locked", false);   // first run: editable until the user locks it

            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            BackColor = Color.FromArgb(16, 18, 20);
            DoubleBuffered = true;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);

            ApplyLayout(true);
            Opacity = locked ? Op(opacityPct) : Op(100);

            BuildTray();
            timer = new System.Windows.Forms.Timer();
            timer.Interval = 250;
            timer.Tick += delegate { try { Tick(); } catch { } };
            timer.Start();
            try
            {
                tray.ShowBalloonTip(5000, "디아4 보스 HUD", locked ? "실행 중이에요. 끄려면 작업 표시줄 오른쪽 ^ 안의 D4 아이콘을 우클릭하세요." : "끌어서 위치를 옮기고, 끝나면 '고정'을 체크하세요.", ToolTipIcon.Info);
            }
            catch { }
        }

        protected override bool ShowWithoutActivation { get { return true; } }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                cp.ExStyle |= WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_LAYERED;
                if (locked) cp.ExStyle |= WS_EX_TRANSPARENT;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try { RegisterHotKey(Handle, 1, 0x2 | 0x4, 0x48); } catch { } // Ctrl+Shift+H
            RegisterLockKey();
        }

        protected override void OnHandleDestroyed(EventArgs e)
        {
            try { UnregisterHotKey(Handle, 1); UnregisterHotKey(Handle, 2); } catch { }
            base.OnHandleDestroyed(e);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_HOTKEY)
            {
                if (m.WParam.ToInt32() == 2) { if (ringing) StopRing(); else SetLocked(!locked); } else ToggleVisible();
                return;
            }
            if (m.Msg == WM_NCHITTEST && !locked)
            {
                long l = m.LParam.ToInt64();
                int x = unchecked((short)(l & 0xFFFF)), y = unchecked((short)((l >> 16) & 0xFFFF));
                Point c = PointToClient(new Point(x, y));
                bool onButton = rLock.Contains(c) || rClose.Contains(c);
                for (int i = 0; i < 3; i++) if (rToggle[i].Contains(c)) onButton = true;
                m.Result = (IntPtr)(onButton ? HTCLIENT : HTCAPTION);   // drag by any other part
                return;
            }
            base.WndProc(ref m);
        }

        void RegisterLockKey()
        {
            bool ok = false;
            try { UnregisterHotKey(Handle, 2); ok = RegisterHotKey(Handle, 2, 0, HkVk[hkIndex]); } catch { }
            if (!ok)
            {
                try { tray.ShowBalloonTip(5000, "디아4 보스 HUD", HkNames[hkIndex] + " 키를 다른 프로그램이 쓰고 있어요. 트레이 메뉴의 '고정 단축키'에서 다른 키를 고르세요.", ToolTipIcon.Warning); } catch { }
            }
        }

        // Never exactly 1.0: WinForms then drops the layered style and a forced layered window stays invisible
        static double Op(int pct) { return Math.Min(0.99, pct / 100.0); }

        void ToggleVisible() { Visible = !Visible; if (Visible) TopMost = true; }

        int ExtraH { get { return locked ? 0 : (int)Math.Round(HeaderH * S); } }
        int ExtraW { get { return locked ? 0 : (int)Math.Round(SideW * S); } }

        // Left edge and bottom edge of the form stay fixed when switching between edit and locked mode.
        void ApplyLayout(bool first)
        {
            Rectangle b = Screen.PrimaryScreen.Bounds;
            S = (b.Height / 1080.0) * (scalePct / 100.0);
            int w = (int)(BaseW * S), h = (int)(BaseH * S);
            DisposeFonts();
            float px = (float)S;
            fName = new Font("Malgun Gothic", 11f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fNameBig = new Font("Malgun Gothic", 14f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fDigit = new Font("Consolas", 22f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fDigitBig = new Font("Consolas", 34f * px, FontStyle.Bold, GraphicsUnit.Pixel);
            fSmall = new Font("Malgun Gothic", 10f * px, FontStyle.Regular, GraphicsUnit.Pixel);
            fBold = new Font("Malgun Gothic", 11f * px, FontStyle.Bold, GraphicsUnit.Pixel);

            int left, bottom;
            if (first)
            {
                // Default: right next to the orange resource orb, about as high as the orb itself
                left = b.Left + (int)(b.Width * 0.73);
                bottom = b.Bottom - (int)(50 * S);
                if (Settings.GetBool("has_pos", false))
                {
                    int sx = Settings.GetInt("px", left), sb = Settings.GetInt("pb", bottom);
                    Point p = new Point(sx + w / 2, sb - h / 2);
                    foreach (Screen sc in Screen.AllScreens) if (sc.Bounds.Contains(p)) { left = sx; bottom = sb; }
                }
            }
            else { left = Left; bottom = Bottom; }
            int fw = w + ExtraW, fh = h + ExtraH;
            SetBounds(left, bottom - fh, fw, fh);
            Invalidate();
        }

        void DisposeFonts()
        {
            if (fName == null) return;
            fName.Dispose(); fNameBig.Dispose(); fDigit.Dispose(); fDigitBig.Dispose(); fSmall.Dispose(); fBold.Dispose();
        }

        void SavePos()
        {
            Settings.Set("px", Left); Settings.Set("pb", Bottom); Settings.Set("has_pos", true);
            Settings.Set("locked", locked);
            Settings.Save();
        }

        void SetLocked(bool v)
        {
            if (v == locked) return;
            if (!locked) { locked = true; SavePos(); locked = false; }   // remember where the user put it
            locked = v;
            try
            {
                int ex = GetWindowLong(Handle, GWL_EXSTYLE);
                SetWindowLong(Handle, GWL_EXSTYLE, locked ? (ex | WS_EX_TRANSPARENT) : (ex & ~WS_EX_TRANSPARENT));
            }
            catch { }
            Opacity = locked ? Op(opacityPct) : Op(100);
            ApplyLayout(false);
            TopMost = true;
            Settings.Set("locked", locked);
            Settings.Save();
            Sync();
        }

        void ToggleAlarm(int i)
        {
            events[i].Alarm = !events[i].Alarm;
            Settings.Set(i == 0 ? "alarm_boss" : i == 1 ? "alarm_tide" : "alarm_legion", events[i].Alarm);
            Settings.Save();
            Sync();
            Invalidate();
        }

        void QuitApp()
        {
            try { SavePos(); } catch { }
            try { tray.Visible = false; } catch { }
            Application.Exit();
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (locked || e.Button != MouseButtons.Left) return;
            if (rClose.Contains(e.Location)) { QuitApp(); return; }
            if (rLock.Contains(e.Location)) { SetLocked(true); return; }
            for (int i = 0; i < 3; i++) if (rToggle[i].Contains(e.Location)) { ToggleAlarm(i); return; }
        }

        void BuildTray()
        {
            tray = new NotifyIcon();
            tray.Text = "디아4 보스 HUD";
            tray.Icon = MakeIcon();
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add(Item("show", "보이기/숨기기  (Ctrl+Shift+H)", delegate { ToggleVisible(); }));
            menu.Items.Add(Item("lock", "고정 (게임용: 클릭·이동 안 됨)", delegate { SetLocked(!locked); }));
            ToolStripMenuItem hk = new ToolStripMenuItem("고정/해제 단축키");
            for (int i = 0; i < HkNames.Length; i++)
            {
                int ii = i;
                ToolStripMenuItem it = new ToolStripMenuItem(HkNames[i]);
                it.Click += delegate { hkIndex = ii; Settings.Set("hk", ii); Settings.Save(); RegisterLockKey(); Sync(); Invalidate(); };
                hk.DropDownItems.Add(it);
                hkItems.Add(it);
            }
            menu.Items.Add(hk);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(Item("a_boss", "월드보스 알림", delegate { ToggleAlarm(0); }));
            menu.Items.Add(Item("a_tide", "지옥물결 알림", delegate { ToggleAlarm(1); }));
            menu.Items.Add(Item("a_legion", "군단 알림", delegate { ToggleAlarm(2); }));
            menu.Items.Add(Item("sound", "알림 소리", delegate { soundOn = !soundOn; Settings.Set("sound", soundOn); Settings.Save(); Sync(); }));
            ToolStripMenuItem lead = new ToolStripMenuItem("알림 시각 (보스 몇 분 전)");
            foreach (int mn in new int[] { 1, 3, 5, 10 })
            {
                int mm = mn;
                ToolStripMenuItem it = new ToolStripMenuItem(mm + "분 전");
                it.Click += delegate { leadMin = mm; Settings.Set("lead", mm); Settings.Save(); Sync(); };
                lead.DropDownItems.Add(it);
                leadItems.Add(it);
            }
            menu.Items.Add(lead);
            ToolStripMenuItem vol = new ToolStripMenuItem("알림 음량");
            foreach (int v in new int[] { 30, 60, 100 })
            {
                int vv = v;
                ToolStripMenuItem it = new ToolStripMenuItem(vv + "%");
                it.Click += delegate { volPct = vv; Settings.Set("vol", vv); Settings.Save(); BuildWavs(); Sync(); };
                vol.DropDownItems.Add(it);
                volItems.Add(it);
            }
            menu.Items.Add(vol);
            menu.Items.Add(Item("test", "소리 테스트 (끄려면 단축키)", delegate { StartRing(); }));
            menu.Items.Add(new ToolStripSeparator());
            ToolStripMenuItem size = new ToolStripMenuItem("크기");
            foreach (int p in new int[] { 80, 100, 125, 150 })
            {
                int pp = p;
                ToolStripMenuItem it = new ToolStripMenuItem(pp + "%");
                it.Click += delegate { scalePct = pp; Settings.Set("scale", pp); Settings.Save(); ApplyLayout(false); };
                size.DropDownItems.Add(it);
            }
            menu.Items.Add(size);
            ToolStripMenuItem op = new ToolStripMenuItem("투명도 (고정했을 때)");
            foreach (int p in new int[] { 100, 90, 75, 60 })
            {
                int pp = p;
                ToolStripMenuItem it = new ToolStripMenuItem(pp + "%");
                it.Click += delegate { opacityPct = pp; Settings.Set("opacity", pp); Settings.Save(); if (locked) Opacity = Op(pp); };
                op.DropDownItems.Add(it);
            }
            menu.Items.Add(op);
            menu.Items.Add(Item("reset", "위치 기본값으로", delegate { Settings.Set("has_pos", false); Settings.Save(); bool l = locked; ApplyDefaultPos(); }));
            menu.Items.Add(Item("auto", "윈도우 시작 시 자동 실행", delegate { SetAutoStart(!IsAutoStart()); Sync(); }));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(Item("exit", "종료", delegate { QuitApp(); }));
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += delegate { ToggleVisible(); };
            tray.Visible = true;
            Sync();
        }

        void ApplyDefaultPos()
        {
            Rectangle b = Screen.PrimaryScreen.Bounds;
            Location = new Point(b.Left + (int)(b.Width * 0.73), b.Bottom - (int)(50 * S) - Height);
        }

        ToolStripMenuItem Item(string key, string text, EventHandler h)
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
            items["lock"].Checked = locked;
            for (int i = 0; i < hkItems.Count; i++) hkItems[i].Checked = i == hkIndex;
            int[] leads = { 1, 3, 5, 10 }, vols = { 30, 60, 100 };
            for (int i = 0; i < leadItems.Count; i++) leadItems[i].Checked = leads[i] == leadMin;
            for (int i = 0; i < volItems.Count; i++) volItems[i].Checked = vols[i] == volPct;
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

        // ---- alarms ----
        void Tick()
        {
            long now = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            foreach (EventDef e in events)
            {
                if (!e.Alarm) continue;
                long next = e.NextStart(now);
                long left = next - now;
                long lead = leadMin * MIN;
                if (left <= lead && left > lead - 10000 && fired.Add(e.Key + next + "L")) StartRing();
                long last = e.LastStart(now);
                if (now - last >= 0 && now - last < 10000 && fired.Add(e.Key + last + "S")) PlayStart();
            }
            if (fired.Count > 200) fired.Clear();
            if (ringing && DateTime.UtcNow > ringEnd) StopRing();
            Invalidate();
        }

        // Pre-alert: repeats until the user presses the key (or 60 s pass)
        void StartRing()
        {
            if (!soundOn) return;
            ringEnd = DateTime.UtcNow.AddSeconds(60);
            if (ringing) return;
            ringing = true;
            try
            {
                if (player != null) player.Dispose();
                player = new SoundPlayer(new MemoryStream(ringWav));
                player.PlayLooping();
            }
            catch { try { SystemSounds.Exclamation.Play(); } catch { } }
        }

        void StopRing()
        {
            ringing = false;
            try { if (player != null) player.Stop(); } catch { }
        }

        // At the exact start time: one short chime
        void PlayStart()
        {
            if (!soundOn) return;
            try
            {
                StopRing();
                if (player != null) player.Dispose();
                player = new SoundPlayer(new MemoryStream(startWav));
                player.Play();
            }
            catch { try { SystemSounds.Asterisk.Play(); } catch { } }
        }

        void BuildWavs()
        {
            double amp = 0.8 * volPct / 100.0;
            ringWav = Wav(new double[][] { new double[] { 880, 0.18 }, new double[] { 0, 0.08 }, new double[] { 880, 0.18 }, new double[] { 0, 0.08 }, new double[] { 1175, 0.30 }, new double[] { 0, 0.90 } }, amp);
            startWav = Wav(new double[][] { new double[] { 1047, 0.20 }, new double[] { 1319, 0.20 }, new double[] { 1568, 0.40 } }, amp);
        }

        // 16-bit mono 22.05 kHz WAV built in memory: list of {frequency Hz (0 = silence), seconds}
        static byte[] Wav(double[][] notes, double amp)
        {
            const int rate = 22050;
            MemoryStream ms = new MemoryStream();
            BinaryWriter w = new BinaryWriter(ms);
            int total = 0;
            foreach (double[] n in notes) total += (int)(n[1] * rate);
            w.Write(new char[] { 'R', 'I', 'F', 'F' }); w.Write(36 + total * 2);
            w.Write(new char[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' }); w.Write(16);
            w.Write((short)1); w.Write((short)1); w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16);
            w.Write(new char[] { 'd', 'a', 't', 'a' }); w.Write(total * 2);
            foreach (double[] n in notes)
            {
                int cnt = (int)(n[1] * rate);
                for (int i = 0; i < cnt; i++)
                {
                    double env = Math.Min(1.0, Math.Min(i, cnt - i) / (0.01 * rate));   // 10 ms fade in/out, no clicks
                    double v = n[0] <= 0 ? 0 : Math.Sin(2 * Math.PI * n[0] * i / rate) * amp * env;
                    w.Write((short)(v * 32767));
                }
            }
            w.Flush();
            return ms.ToArray();
        }

        // ---- drawing ----
        static GraphicsPath Pill(RectangleF r)
        {
            GraphicsPath p = new GraphicsPath();
            float d = r.Height;
            p.AddArc(r.X, r.Y, d, d, 90, 180);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 180);
            p.CloseFigure();
            return p;
        }

        protected override void OnPaint(PaintEventArgs pe)
        {
            Graphics g = pe.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            int w = ClientSize.Width, h = ClientSize.Height;
            float s = (float)S;
            int eh = ExtraH;
            g.Clear(Color.FromArgb(16, 18, 20));
            bool blink = ringing && (DateTime.UtcNow.Ticks / 5000000 % 2 == 0);
            using (Pen border = new Pen(blink ? Color.FromArgb(255, 70, 60) : locked ? Color.FromArgb(70, 120, 125) : Color.FromArgb(255, 200, 80), Math.Max(1.5f, (blink ? 3f : 1.5f) * s)))
                g.DrawRectangle(border, 0.5f, 0.5f, w - 2, h - 2);

            StringFormat left = new StringFormat { Alignment = StringAlignment.Near, LineAlignment = StringAlignment.Center, FormatFlags = StringFormatFlags.NoWrap };
            StringFormat right = new StringFormat { Alignment = StringAlignment.Far, LineAlignment = StringAlignment.Center, FormatFlags = StringFormatFlags.NoWrap };
            StringFormat mid = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center, FormatFlags = StringFormatFlags.NoWrap };

            // header (edit mode only): lock checkbox on the left, close on the right
            rLock = Rectangle.Empty; rClose = Rectangle.Empty;
            for (int i = 0; i < 3; i++) rToggle[i] = Rectangle.Empty;
            if (!locked)
            {
                float hh = eh;
                using (SolidBrush hb = new SolidBrush(Color.FromArgb(38, 42, 46))) g.FillRectangle(hb, 1, 1, w - 2, hh - 1);
                float cb = 13 * s;
                RectangleF box = new RectangleF(7 * s, (hh - cb) / 2, cb, cb);
                using (Pen bp = new Pen(Color.FromArgb(230, 220, 200), 1.5f * s)) g.DrawRectangle(bp, box.X, box.Y, box.Width, box.Height);
                g.DrawString("고정", fBold, Brushes.White, new RectangleF(box.Right + 5 * s, 0, 40 * s, hh), left);
                g.DrawString("끌어서 이동 · " + HkNames[hkIndex] + " 또는 □ 로 고정", fSmall, new SolidBrush(Color.FromArgb(170, 175, 180)), new RectangleF(70 * s, 0, w - 70 * s - hh, hh), left);
                rLock = new Rectangle(0, 0, (int)(66 * s), (int)hh);
                rClose = new Rectangle(w - (int)hh - 2, 1, (int)hh, (int)hh - 1);
                using (Pen xp = new Pen(Color.FromArgb(255, 110, 100), 2f * s))
                {
                    float cx = rClose.X + rClose.Width / 2f, cy = rClose.Y + rClose.Height / 2f, r = 4.5f * s;
                    g.DrawLine(xp, cx - r, cy - r, cx + r, cy + r);
                    g.DrawLine(xp, cx - r, cy + r, cx + r, cy - r);
                }
            }

            long now = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            float[] rowH = { 52 * s, 28 * s, 28 * s };
            float mainW = BaseW * s;
            float y = eh + 3 * s;
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
                RectangleF r = new RectangleF(8 * s, y, mainW - 16 * s, rowH[i]);
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

                if (!locked)
                {
                    // per-row alarm switch in the side column
                    RectangleF pr = new RectangleF(mainW + 6 * s, y + rowH[i] / 2 - 9 * s, 46 * s, 18 * s);
                    rToggle[i] = Rectangle.Round(pr);
                    using (GraphicsPath gp = Pill(pr))
                    using (SolidBrush pb = new SolidBrush(e.Alarm ? Color.FromArgb(46, 140, 80) : Color.FromArgb(70, 74, 78)))
                        g.FillPath(pb, gp);
                    g.DrawString(e.Alarm ? "알림" : "끔", fBold, Brushes.White, pr, mid);
                }
                if (i == 2)
                {
                    // always-visible small hint: which key locks/unlocks
                    using (SolidBrush hb2 = new SolidBrush(ringing ? Color.FromArgb(255, 210, 90) : Color.FromArgb(160, 170, 175)))
                        g.DrawString(HkNames[hkIndex] + (ringing ? " = 알람 끄기" : locked ? " = 편집" : " = 고정"), fSmall, hb2, new RectangleF(112 * s, y, 84 * s, rowH[i]), mid);
                }
                y += rowH[i];
                if (i < 2) using (Pen sep = new Pen(Color.FromArgb(48, 56, 58), 1f)) g.DrawLine(sep, 8 * s, y, mainW - 8 * s, y);
            }
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
