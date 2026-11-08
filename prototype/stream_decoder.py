"""Streaming Morse decoder prototype (mirrors the Kotlin implementation).

Pipeline: PCM16 16 kHz mono -> tone tracker (Goertzel bank) -> complex demod + 15 ms boxcar
-> 5 ms envelope frames -> key-state detector (slope + level hysteresis)
-> run cleaner (blip/notch removal) -> adaptive dot/dash + gap classes -> characters.
"""
import math
import numpy as np

SR = 16000
HOP = 80                 # 5 ms
DT = HOP / SR * 1000.0   # ms per frame
BOX = 240                # 15 ms boxcar

RU = {".-": "А", "-...": "Б", ".--": "В", "--.": "Г", "-..": "Д", ".": "Е", "...-": "Ж", "--..": "З", "..": "И",
      ".---": "Й", "-.-": "К", ".-..": "Л", "--": "М", "-.": "Н", "---": "О", ".--.": "П", ".-.": "Р", "...": "С",
      "-": "Т", "..-": "У", "..-.": "Ф", "....": "Х", "-.-.": "Ц", "---.": "Ч", "----": "Ш", "--.-": "Щ",
      "--.--": "Ъ", "-.--": "Ы", "-..-": "Ь", "..-..": "Э", "..--": "Ю", ".-.-": "Я"}
EN = {".-": "A", "-...": "B", "-.-.": "C", "-..": "D", ".": "E", "..-.": "F", "--.": "G", "....": "H", "..": "I",
      ".---": "J", "-.-": "K", ".-..": "L", "--": "M", "-.": "N", "---": "O", ".--.": "P", "--.-": "Q", ".-.": "R",
      "...": "S", "-": "T", "..-": "U", "...-": "V", ".--": "W", "-..-": "X", "-.--": "Y", "--..": "Z"}
COMMON = {"-----": "0", ".----": "1", "..---": "2", "...--": "3", "....-": "4", ".....": "5", "-....": "6",
          "--...": "7", "---..": "8", "----.": "9", ".-.-.": "<AR>", "-...-": "=", "...-.-": "<SK>"}
PUNCT = {"..--..": "?", ".-.-.-": ".", "--..--": ",", "-..-.": "/"}


class ToneTracker:
    """Goertzel bank 300..1200 Hz step 10 Hz over 1024-sample blocks; EMA power; tonality gate."""
    FREQS = np.arange(300, 1201, 10.0)
    BLOCK = 1024

    def __init__(self):
        n = np.arange(self.BLOCK)
        w = np.hanning(self.BLOCK)
        self.cos = np.cos(2 * np.pi * np.outer(self.FREQS, n) / SR) * w
        self.sin = np.sin(2 * np.pi * np.outer(self.FREQS, n) / SR) * w
        self.ema = np.zeros(len(self.FREQS))
        self.buf = np.zeros(0)
        self.f0 = None
        self.cand = None
        self.cand_blocks = 0
        self.tonality_db = 0.0

    def feed(self, x):
        self.buf = np.concatenate([self.buf, x])
        while len(self.buf) >= self.BLOCK:
            b, self.buf = self.buf[:self.BLOCK], self.buf[self.BLOCK:]
            p = (self.cos @ b) ** 2 + (self.sin @ b) ** 2
            self.ema = 0.92 * self.ema + 0.08 * p if self.ema.any() else p
            self._update()

    def _update(self):
        e = self.ema
        i = int(np.argmax(e))
        med = float(np.median(e)) + 1e-18
        self.tonality_db = 10 * math.log10(e[i] / med + 1e-18)
        if self.tonality_db < 12 or i == 0 or i == len(e) - 1:
            return
        a, b, c = math.log(e[i - 1] + 1e-18), math.log(e[i] + 1e-18), math.log(e[i + 1] + 1e-18)
        den = a - 2 * b + c
        f = self.FREQS[i] + (0.5 * (a - c) / den * 10.0 if den != 0 else 0.0)
        if self.f0 is None:
            self.f0 = f
        elif abs(f - self.f0) < 25:
            self.f0 += 0.2 * (f - self.f0)
            self.cand = None
        else:
            if self.cand is not None and abs(f - self.cand) < 25:
                self.cand_blocks += 1
            else:
                self.cand, self.cand_blocks = f, 1
            if self.cand_blocks >= 24:      # ~1.5 s of a different stable tone
                self.f0, self.cand = f, None


class Frontend:
    """Complex demodulation at f0 + 15 ms boxcar, one envelope value per 5 ms."""

    def __init__(self):
        self.phase = 0.0
        self.ring_re = np.zeros(BOX)
        self.ring_im = np.zeros(BOX)
        self.pos = 0
        self.sum_re = 0.0
        self.sum_im = 0.0
        self.count = 0

    def feed(self, x, f0):
        out = []
        w = 2 * math.pi * f0 / SR
        for s in x:
            re, im = s * math.cos(self.phase), -s * math.sin(self.phase)
            self.phase += w
            if self.phase > 1e6:
                self.phase = math.fmod(self.phase, 2 * math.pi)
            self.sum_re += re - self.ring_re[self.pos]
            self.sum_im += im - self.ring_im[self.pos]
            self.ring_re[self.pos], self.ring_im[self.pos] = re, im
            self.pos = (self.pos + 1) % BOX
            self.count += 1
            if self.count % HOP == 0:
                out.append(math.hypot(self.sum_re, self.sum_im) / BOX)
        return out


class KeyDecoder:
    """Envelope frames (5 ms) -> characters. All durations in ms.

    Phase 1 (bootstrap): speed unknown. Key-down/up runs are only recorded (narrow slope window); once enough
    runs exist a robust fit finds the dot length T, the runs are cleaned with it and laid out into letters.
    Phase 2 (live): thresholds scale with the running dot estimate, which follows the signal."""
    SLOPE_UP, SLOPE_DOWN = 35.0, -35.0   # dB/s
    WMAX = 8                               # delay line half-window (frames)
    H_BOOT = 8                             # slope half-window (frames) before calibration
    PEAK_DECAY_DB_S = 2.0
    FLOOR_RISE_DB_S = 3.0
    MIN_SNR_DB = 12.0
    ON_LEVEL_DB = -12.0                    # a new key-down must be within 12 dB of the recent peak
    MIN_ABS_DB = -66.0
    BOOT_ON_RUNS = 14                      # key-down runs (>= 20 ms) needed to calibrate
    BOOT_SETTLE_MS = 150.0                 # ... and the key must have been up this long

    def __init__(self, table):
        self.table = table
        self.peak = -200.0
        self.floor = None
        self.dl = []
        self.state = False
        self.run_len = 0.0                 # length of the current key run (bootstrap)
        self.boot = []                     # [(is_on, ms)] finished runs while uncalibrated
        self.on_run = 0.0
        self.off_run = 0.0
        self.in_elem = False
        self.on_acc = 0.0
        self.gap_acc = 0.0
        self.pre_gap = 0.0
        self.letter = []
        self.letter_open = False
        self.word_pending = False
        self.recent = []
        self.long_gaps = []
        self.dot = 120.0
        self.dash = 360.0
        self.calibrated = False
        self.out = []
        self.debug = []
        self.t_ms = 0.0
        self.elem_log = []
        self.stats = {"elements": 0, "blips": 0, "notches": 0}

    # ---- thresholds -------------------------------------------------
    def min_on(self):  return max(25.0, 0.45 * self.dot)
    def min_off(self): return max(20.0, 0.40 * self.dot)
    def letter_thr(self): return 2.2 * self.dot
    def letter_gap(self):
        if len(self.long_gaps) < 3: return None
        return float(np.median(self.long_gaps))
    def word_thr(self):
        lg = self.letter_gap()
        w = 1.7 * lg if lg else 6.2 * self.dot
        return max(w, 4.5 * self.dot)
    def dash_thr(self): return math.sqrt(self.dot * self.dash) if self.dash > 1.8 * self.dot else 1.8 * self.dot
    def half_win(self):
        if not self.calibrated:
            return self.H_BOOT
        return int(min(self.WMAX, max(2, round(self.dot / 15.0))))

    # ---- main entry -------------------------------------------------
    def frame(self, env):
        dt = DT / 1000.0
        self.t_ms += DT
        L = 20 * math.log10(env + 1e-9)
        self.peak = max(L, self.peak - self.PEAK_DECAY_DB_S * dt)
        self.floor = L if self.floor is None else min(L, self.floor + self.FLOOR_RISE_DB_S * dt)
        self.dl.append((L, self.peak, self.floor))
        W = self.WMAX
        if len(self.dl) < 2 * W + 1:
            return
        if len(self.dl) > 2 * W + 1:
            self.dl.pop(0)
        Lc, pk, fl = self.dl[W]
        h = self.half_win()
        s = (self.dl[W + h][0] - self.dl[W - h][0]) / (2 * h * dt)
        rel = Lc - pk
        ok = (pk - fl) >= self.MIN_SNR_DB and pk >= self.MIN_ABS_DB
        off_level = max(-30.0, (fl - pk) + 8.0)
        if not ok or rel < off_level:
            new = False
        elif s > self.SLOPE_UP:
            new = True if (self.state or rel >= max(self.ON_LEVEL_DB, off_level)) else False
        elif s < self.SLOPE_DOWN:
            new = False
        else:
            new = self.state
        if self.calibrated:
            self._runs(new)
        else:
            self._boot_runs(new)

    # ---- phase 1: bootstrap ----------------------------------------
    def _boot_runs(self, new):
        if new != self.state:
            if self.run_len > 0:
                self.boot.append((self.state, self.run_len))
            self.run_len = 0.0
        self.run_len += DT
        self.state = new
        if not new and self.run_len >= self.BOOT_SETTLE_MS:
            n_on = sum(1 for on, d in self.boot if on and d >= 20.0)
            if n_on >= self.BOOT_ON_RUNS:
                self._calibrate_boot()

    @staticmethod
    def fit_dot(runs):
        """Robust scale fit by reward. A key-down run earns up to 1 if it is ~1T or ~3T, a short silence earns up
        to 0.5 if it is ~1T. Everything else (ripple, noise, long pauses) earns nothing and cannot distort the fit."""
        ons = [d for on, d in runs if on and d >= 20.0]
        offs = [d for on, d in runs[1:] if not on and d >= 20.0]
        l3, sig2 = math.log(3.0), 0.28 ** 2
        best_r, best_T = -1.0, None
        for T in np.geomspace(20.0, 450.0, 110):
            r_tot = 0.0
            for d in ons:
                r = math.log(d / T)
                r_tot += max(0.0, 1.0 - min(r * r, (r - l3) ** 2) / sig2)
            for d in offs:
                r = math.log(d / T)
                r_tot += 0.5 * max(0.0, 1.0 - r * r / sig2)
            if r_tot > best_r + 1e-9:
                best_r, best_T = r_tot, T
        return float(best_T)

    @staticmethod
    def clean_runs(runs, T):
        """Blips (< 0.45 T key-down) become silence; notches (< 0.4 T silence) inside a key-down disappear."""
        out = []
        for on, d in runs:
            if on and d < 0.45 * T:
                on = False
            if out and out[-1][0] == on:
                out[-1][1] += d
            else:
                out.append([on, d])
        res = []
        for i, (on, d) in enumerate(out):
            if (not on) and 0 < i < len(out) - 1 and d < 0.4 * T and res and res[-1][0]:
                res[-1][1] += d            # glue: previous key-down absorbs the notch; next one is merged below
                res[-1].append("glue")
            elif on and res and len(res[-1]) == 3:
                res[-1][1] += d
                res[-1].pop()
            else:
                res.append([on, d])
        return [(on, d) for on, d, *_ in res]

    def _calibrate_boot(self):
        runs = list(self.boot)
        open_off = self.run_len
        while runs and not runs[0][0]:
            runs.pop(0)
        T = self.fit_dot(runs)
        runs = self.clean_runs(runs, T)
        raw, gap = [], 0.0
        for on, d in runs:
            if on:
                raw.append((gap, d)); gap = 0.0
            else:
                gap += d
        self.stats["elements"] += len(raw)
        if len(raw) < 4:
            self.boot = []            # nothing usable yet: keep waiting, start over
            return
        self.recent = [d for _, d in raw][-48:]
        self.dot, self.dash = T, 3 * T
        self._recluster(force=True)
        self.calibrated = True
        self._parse_raw(raw)
        # hand over to the live state machine: we are in the middle of a silence
        self.boot = []
        self.state = False
        self.off_run = open_off
        self.gap_acc = open_off + 0.0
        self.in_elem = False

    def _parse_raw(self, raw):
        """Lay the bootstrap elements out into letters/words. The letter boundary comes from the gaps themselves
        (2-means in the log domain), not from a speed prior."""
        gaps = [g for i, (g, _) in enumerate(raw) if i > 0 and g > 0]
        thr = self.letter_thr()
        if len(gaps) >= 4:
            v = np.log(np.array(gaps))
            c = np.array([v.min(), v.max()])
            for _ in range(20):
                a = np.abs(v[:, None] - c[None]).argmin(axis=1)
                nc = np.array([v[a == j].mean() if (a == j).any() else c[j] for j in range(2)])
                if np.allclose(nc, c):
                    break
                c = nc
            if math.exp(c[1] - c[0]) >= 2.0:
                thr = math.exp((c[0] + c[1]) / 2)
        self.long_gaps = [g for g in gaps if g >= thr][-32:]
        cur = []
        for i, (g, dur) in enumerate(raw):
            if i > 0 and g >= thr:
                self._emit_char(self._to_char(cur)); cur = []
                if g >= self.word_thr():
                    self._emit_char(" ")
            cur.append(dur)
        self.letter = cur
        self.letter_open = bool(cur)

    # ---- phase 2: live run cleaner ----------------------------------
    def _runs(self, new):
        d = DT
        if new:
            if not self.state:                       # OFF -> ON
                if self.in_elem and self.off_run < self.min_off():
                    self.on_acc += self.off_run
                    self.stats["notches"] += 1
                self.on_run = 0.0
            self.on_run += d
            self.off_run = 0.0
        else:
            if self.state:                           # ON -> OFF
                run = self.on_run
                if not self.in_elem and run < self.min_on():
                    self.gap_acc += run
                    self.stats["blips"] += 1
                else:
                    if not self.in_elem:
                        self.pre_gap = self.gap_acc
                    self.on_acc = (self.on_acc if self.in_elem else 0.0) + run
                    self.in_elem = True
                self.on_run = 0.0
                self.off_run = 0.0
            self.off_run += d
            if self.in_elem:
                if self.off_run >= self.min_off():
                    self._element(self.pre_gap, self.on_acc)
                    self.in_elem = False
                    self.gap_acc = self.off_run
            else:
                self.gap_acc += d
                self._gap_checks()
        self.state = new

    def _element(self, pre_gap, dur):
        self.elem_log.append((round(self.t_ms - self.off_run - dur), round(dur)))
        self.stats["elements"] += 1
        self.recent.append(dur)
        if len(self.recent) > 48:
            self.recent.pop(0)
        self._recluster()
        if pre_gap >= self.letter_thr():
            self.long_gaps.append(pre_gap)
            if len(self.long_gaps) > 32:
                self.long_gaps.pop(0)
        self.letter.append(dur)
        self.letter_open = True

    def _recluster(self, force=False):
        v = np.log(np.array(self.recent))
        if len(v) < (2 if force else 6):
            return
        lo, hi = np.quantile(v, [0.15, 0.85])
        c = np.array([lo, hi])
        for _ in range(12):
            a = np.abs(v[:, None] - c[None]).argmin(axis=1)
            nc = np.array([v[a == j].mean() if (a == j).any() else c[j] for j in range(2)])
            if np.allclose(nc, c):
                break
            c = nc
        d0, d1 = math.exp(c[0]), math.exp(c[1])
        if d1 / d0 > 4.5:
            keep = [x for x in self.recent if x > math.sqrt(d0 * d1)]
            if len(keep) >= 3:
                self.recent = keep
                self._recluster(force=force)
            return
        if d1 / d0 >= 1.8:
            self.dot, self.dash = d0, d1
        else:                                        # one length class only
            m = math.exp(v.mean())
            if m < 1.7 * self.dot:
                self.dot, self.dash = m, 3 * m
            elif m > 2.2 * self.dot:
                self.dot, self.dash = m / 3, m

    def _gap_checks(self):
        if self.letter_open and self.gap_acc >= self.letter_thr():
            self._emit_letter()
            self.word_pending = True
        if self.word_pending and self.gap_acc >= self.word_thr():
            self._emit_char(" ")
            self.word_pending = False

    def _emit_letter(self):
        self.letter_open = False
        pat = self.letter
        self.letter = []
        self.debug.append((list(map(round, pat)), round(self.dot), round(self.dash), round(self.dash_thr())))
        self._emit_char(self._to_char(pat))

    def _to_char(self, durs):
        thr = self.dash_thr()
        code = "".join("-" if x > thr else "." for x in durs)
        return self.table.get(code, "?")

    def _emit_char(self, ch):
        self.out.append(ch)

    def finish(self):
        if not self.calibrated:
            if self.state or self.run_len:
                self.boot.append((self.state, self.run_len))
            self.run_len = 1e9 if False else self.run_len
            n_on = sum(1 for on, d in self.boot if on and d >= 20.0)
            if n_on >= 4:
                self._calibrate_boot()
        elif self.in_elem:
            self._element(self.pre_gap, self.on_acc)
            self.in_elem = False
        if self.calibrated and self.letter_open:
            self.gap_acc = 1e9
            self._gap_checks()


def decode(samples, table, chunk=1024):
    """samples: float32 array in [-1,1]. Returns (text, info)."""
    tt, fe, kd = ToneTracker(), Frontend(), KeyDecoder(table)
    for i in range(0, len(samples), chunk):
        x = samples[i:i + chunk]
        tt.feed(x)
        f0 = tt.f0 if tt.f0 else 600.0
        for e in fe.feed(x, f0):
            if tt.f0 is not None:
                kd.frame(e)
    kd.finish()
    return "".join(kd.out), {"elem_log": kd.elem_log, "env_dbg": None, "debug": kd.debug, "f0": tt.f0, "dot": kd.dot, "dash": kd.dash, "stats": kd.stats, "tonality": tt.tonality_db}
