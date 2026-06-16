"use strict";

/* LightAnalytics dashboard — dependency-free. Fetches the JSON API and renders a
 * custom, zoomable/pannable canvas telemetry chart. No external libraries, so it
 * stays self-hosted and CSP-clean. */

// ---- tiny helpers ----------------------------------------------------------
const $ = (id) => document.getElementById(id);
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

// Build an element with optional class and text. Text is set via textContent, so
// names from the API (servers, usernames) can never inject markup.
function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
}

const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

const COLORS = {
    green: "#5be08a", amber: "#f2c14e", orange: "#e8884b",
    blue: "#6fb3ff", red: "#ff6b6b", muted: "#69745f",
};

const fmtInt = (n) => Math.round(n).toLocaleString("en-US");
const fmtPct = (frac, d = 0) => (frac * 100).toFixed(d) + "%";

function fmtBytes(bytes) {
    if (!isFinite(bytes) || bytes < 0) return "—";
    const units = ["B", "KiB", "MiB", "GiB", "TiB"];
    let v = bytes, i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return v.toFixed(v >= 100 || i === 0 ? 0 : 1) + " " + units[i];
}

function fmtDuration(ms) {
    if (!isFinite(ms) || ms <= 0) return "0m";
    const s = Math.floor(ms / 1000);
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    if (h > 0) return h + "h " + m + "m";
    if (m > 0) return m + "m " + (s % 60) + "s";
    return s + "s";
}

function fmtDateTime(ms) {
    return new Date(ms).toLocaleString([], {
        month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
    });
}

function fmtDate(ms) {
    return new Date(ms).toLocaleDateString([], { year: "numeric", month: "short", day: "numeric" });
}

function ago(ms) {
    const days = Math.floor((Date.now() - ms) / 86400000);
    if (days <= 0) return "today";
    if (days === 1) return "1 day ago";
    if (days < 60) return days + " days ago";
    return Math.floor(days / 30) + " months ago";
}

let toastTimer = null;
function toast(message) {
    const el = $("toast");
    el.textContent = message;
    el.hidden = false;
    requestAnimationFrame(() => el.classList.add("show"));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        el.classList.remove("show");
        setTimeout(() => { el.hidden = true; }, 220);
    }, 3600);
}

// ---- api -------------------------------------------------------------------
async function getJSON(url) {
    const res = await fetch(url, { headers: { Accept: "application/json" } });
    if (res.status === 401) { window.location = "/login"; throw new Error("unauthorized"); }
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
}

// ---- count-up --------------------------------------------------------------
function countUp(el, target, format) {
    const start = performance.now();
    const dur = 650;
    function step(now) {
        const t = clamp((now - start) / dur, 0, 1);
        const eased = 1 - Math.pow(1 - t, 3);
        el.textContent = format(target * eased);
        if (t < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
}

function setBar(el, frac, stops) {
    el.style.width = clamp(frac, 0, 1) * 100 + "%";
    let color = COLORS.green;
    if (frac >= stops[1]) color = COLORS.red;
    else if (frac >= stops[0]) color = COLORS.amber;
    el.style.background = color;
}

// ---- series definitions ----------------------------------------------------
const SERIES = [
    { key: "players", label: "Players", color: COLORS.green, axis: "left", fill: true },
    { key: "cpuProcess", label: "CPU proc", color: COLORS.amber, axis: "right" },
    { key: "cpuSystem", label: "CPU sys", color: COLORS.orange, axis: "right" },
    { key: "heap", label: "Heap", color: COLORS.blue, axis: "right" },
];

// Map a raw API point to a plottable value per series (null = gap).
function seriesValue(key, p) {
    switch (key) {
        case "players": return p.players;
        case "cpuProcess": return p.cpuProcess >= 0 ? p.cpuProcess * 100 : null;
        case "cpuSystem": return p.cpuSystem >= 0 ? p.cpuSystem * 100 : null;
        case "heap": return p.heapMax > 0 ? (p.heapUsed / p.heapMax) * 100 : null;
        default: return null;
    }
}

// ---- chart -----------------------------------------------------------------
class TelemetryChart {
    constructor(canvas, tooltipEl, onRangeChange) {
        this.canvas = canvas;
        this.ctx = canvas.getContext("2d");
        this.tooltip = tooltipEl;
        this.onRangeChange = onRangeChange;
        this.points = [];
        this.view = { from: Date.now() - 86400000, to: Date.now() };
        this.visible = new Set(SERIES.map((s) => s.key));
        this.hoverX = null;
        this.plot = { x: 0, y: 0, w: 0, h: 0 };
        this._raf = null;
        this._changeTimer = null;

        this._bindEvents();
        window.addEventListener("resize", () => this.scheduleDraw());
    }

    setData(points) {
        this.points = points || [];
        this.scheduleDraw();
    }

    setView(from, to) {
        this.view = { from, to };
        this.scheduleDraw();
    }

    toggle(key) {
        if (this.visible.has(key)) this.visible.delete(key); else this.visible.add(key);
        this.scheduleDraw();
    }

    scheduleDraw() {
        if (this._raf) return;
        this._raf = requestAnimationFrame(() => { this._raf = null; this.draw(); });
    }

    _emitRangeChange() {
        clearTimeout(this._changeTimer);
        this._changeTimer = setTimeout(() => {
            this.onRangeChange(Math.round(this.view.from), Math.round(this.view.to));
        }, 280);
    }

    _leftMax() {
        let max = 1;
        for (const p of this.points) {
            if (p.t < this.view.from || p.t > this.view.to) continue;
            if (p.players > max) max = p.players;
        }
        // Round up to a friendly number.
        const pow = Math.pow(10, Math.floor(Math.log10(max)));
        const n = max / pow;
        const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return step * pow;
    }

    draw() {
        const ctx = this.ctx;
        const dpr = window.devicePixelRatio || 1;
        const cssW = this.canvas.clientWidth;
        const cssH = this.canvas.clientHeight;
        this.canvas.width = Math.round(cssW * dpr);
        this.canvas.height = Math.round(cssH * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssW, cssH);

        const padL = 52, padR = 48, padT = 14, padB = 30;
        const plot = this.plot = { x: padL, y: padT, w: cssW - padL - padR, h: cssH - padT - padB };
        if (plot.w <= 0 || plot.h <= 0) return;

        const span = this.view.to - this.view.from || 1;
        const xOf = (t) => plot.x + ((t - this.view.from) / span) * plot.w;
        const leftMax = this._leftMax();
        const yLeft = (v) => plot.y + plot.h - (v / leftMax) * plot.h;
        const yRight = (v) => plot.y + plot.h - (v / 100) * plot.h;

        // Horizontal grid + left/right axis labels.
        ctx.font = "11px ui-monospace, monospace";
        ctx.textBaseline = "middle";
        const rows = 4;
        for (let i = 0; i <= rows; i++) {
            const y = plot.y + (plot.h / rows) * i;
            ctx.strokeStyle = "rgba(150,170,145,0.10)";
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(plot.x, y + 0.5);
            ctx.lineTo(plot.x + plot.w, y + 0.5);
            ctx.stroke();
            const leftVal = leftMax * (1 - i / rows);
            ctx.fillStyle = COLORS.muted;
            ctx.textAlign = "right";
            ctx.fillText(fmtInt(leftVal), plot.x - 9, y);
            const rightVal = 100 * (1 - i / rows);
            ctx.textAlign = "left";
            ctx.fillText(rightVal + "%", plot.x + plot.w + 9, y);
        }

        // Vertical time grid + labels.
        const ticks = this._timeTicks(span);
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        for (const t of ticks) {
            const x = xOf(t);
            if (x < plot.x - 1 || x > plot.x + plot.w + 1) continue;
            ctx.strokeStyle = "rgba(150,170,145,0.07)";
            ctx.beginPath();
            ctx.moveTo(x + 0.5, plot.y);
            ctx.lineTo(x + 0.5, plot.y + plot.h);
            ctx.stroke();
            ctx.fillStyle = COLORS.muted;
            ctx.fillText(this._timeLabel(t, span), x, plot.y + plot.h + 8);
        }

        // Series.
        ctx.save();
        ctx.beginPath();
        ctx.rect(plot.x, plot.y, plot.w, plot.h);
        ctx.clip();
        let drewAny = false;
        for (const s of SERIES) {
            if (!this.visible.has(s.key)) continue;
            const yOf = s.axis === "left" ? yLeft : yRight;
            const path = [];
            for (const p of this.points) {
                const v = seriesValue(s.key, p);
                path.push(v === null || v === undefined ? null : { x: xOf(p.t), y: yOf(v) });
            }
            if (s.fill) this._fillArea(ctx, path, plot, s.color);
            this._strokePath(ctx, path, s.color, 1.8);
            if (path.some((q) => q)) drewAny = true;
        }
        ctx.restore();

        // Hover crosshair + tooltip.
        this._drawHover(ctx, xOf, yLeft, yRight, plot);

        $("chartEmpty").hidden = this.points.length > 0 && drewAny;
    }

    _strokePath(ctx, path, color, width) {
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        ctx.lineJoin = "round";
        ctx.lineCap = "round";
        ctx.beginPath();
        let pen = false;
        for (const q of path) {
            if (!q) { pen = false; continue; }
            if (!pen) { ctx.moveTo(q.x, q.y); pen = true; }
            else ctx.lineTo(q.x, q.y);
        }
        ctx.stroke();
    }

    _fillArea(ctx, path, plot, color) {
        const bottom = plot.y + plot.h;
        ctx.fillStyle = this._fade(color, 0.14);
        let run = [];
        const flush = () => {
            if (run.length < 2) { run = []; return; }
            ctx.beginPath();
            ctx.moveTo(run[0].x, bottom);
            for (const q of run) ctx.lineTo(q.x, q.y);
            ctx.lineTo(run[run.length - 1].x, bottom);
            ctx.closePath();
            ctx.fill();
            run = [];
        };
        for (const q of path) { if (!q) flush(); else run.push(q); }
        flush();
    }

    _fade(hex, alpha) {
        const n = parseInt(hex.slice(1), 16);
        return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
    }

    _drawHover(ctx, xOf, yLeft, yRight, plot) {
        if (this.hoverX === null || !this.points.length) { this.tooltip.hidden = true; return; }
        // Nearest point by time.
        const t = this.view.from + ((this.hoverX - plot.x) / plot.w) * (this.view.to - this.view.from);
        let best = null, bestDist = Infinity;
        for (const p of this.points) {
            const d = Math.abs(p.t - t);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        if (!best) { this.tooltip.hidden = true; return; }
        const hx = xOf(best.t);
        if (hx < plot.x || hx > plot.x + plot.w) { this.tooltip.hidden = true; return; }

        ctx.strokeStyle = "rgba(217,226,212,0.25)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(hx + 0.5, plot.y);
        ctx.lineTo(hx + 0.5, plot.y + plot.h);
        ctx.stroke();

        let rows = "";
        for (const s of SERIES) {
            if (!this.visible.has(s.key)) continue;
            const v = seriesValue(s.key, best);
            if (v === null || v === undefined) continue;
            const yOf = s.axis === "left" ? yLeft : yRight;
            const py = yOf(v);
            ctx.fillStyle = s.color;
            ctx.beginPath();
            ctx.arc(hx, py, 3.2, 0, Math.PI * 2);
            ctx.fill();
            ctx.strokeStyle = "#0a0c0b";
            ctx.lineWidth = 1.5;
            ctx.stroke();
            rows += `<div class="tt-row"><span class="tt-key"><span class="tt-dot" style="background:${s.color}"></span>${s.label}</span><span class="tt-val">${this._tipValue(s.key, best)}</span></div>`;
        }
        this.tooltip.innerHTML = `<div class="tt-time">${fmtDateTime(best.t)}</div>${rows}`;
        this.tooltip.hidden = false;
        this.tooltip.style.left = hx + "px";
        this.tooltip.style.top = plot.y + plot.h * 0.18 + "px";
    }

    _tipValue(key, p) {
        if (key === "players") return fmtInt(p.players);
        if (key === "cpuProcess") return p.cpuProcess >= 0 ? fmtPct(p.cpuProcess, 1) : "n/a";
        if (key === "cpuSystem") return p.cpuSystem >= 0 ? fmtPct(p.cpuSystem, 1) : "n/a";
        if (key === "heap") return p.heapMax > 0 ? fmtBytes(p.heapUsed) : "n/a";
        return "";
    }

    _timeTicks(span) {
        const steps = [
            60e3, 300e3, 900e3, 1800e3, 3600e3, 3 * 3600e3, 6 * 3600e3,
            12 * 3600e3, 86400e3, 2 * 86400e3, 7 * 86400e3, 14 * 86400e3, 30 * 86400e3,
        ];
        let step = steps[steps.length - 1];
        for (const s of steps) { if (span / s <= 7) { step = s; break; } }
        const ticks = [];
        const start = Math.ceil(this.view.from / step) * step;
        for (let t = start; t <= this.view.to; t += step) ticks.push(t);
        return ticks;
    }

    _timeLabel(t, span) {
        const d = new Date(t);
        if (span <= 36 * 3600e3) {
            return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
        }
        return d.toLocaleDateString([], { month: "numeric", day: "numeric" });
    }

    // ---- interaction -------------------------------------------------------
    _bindEvents() {
        const c = this.canvas;
        c.addEventListener("wheel", (e) => {
            e.preventDefault();
            const rect = c.getBoundingClientRect();
            const frac = clamp((e.clientX - rect.left - this.plot.x) / this.plot.w, 0, 1);
            const span = this.view.to - this.view.from;
            const factor = e.deltaY < 0 ? 0.8 : 1.25;
            let newSpan = clamp(span * factor, 60e3, 400 * 86400e3);
            const anchor = this.view.from + frac * span;
            let from = anchor - frac * newSpan;
            let to = from + newSpan;
            const now = Date.now();
            if (to > now) { to = now; from = to - newSpan; }
            this.view = { from, to };
            this.scheduleDraw();
            this._emitRangeChange();
        }, { passive: false });

        let dragging = false, lastX = 0;
        c.addEventListener("pointerdown", (e) => {
            dragging = true; lastX = e.clientX; c.setPointerCapture(e.pointerId);
            c.style.cursor = "grabbing";
        });
        c.addEventListener("pointermove", (e) => {
            const rect = c.getBoundingClientRect();
            this.hoverX = e.clientX - rect.left;
            if (dragging) {
                const span = this.view.to - this.view.from;
                const dt = ((e.clientX - lastX) / this.plot.w) * span;
                lastX = e.clientX;
                let from = this.view.from - dt, to = this.view.to - dt;
                const now = Date.now();
                if (to > now) { to = now; from = to - span; }
                this.view = { from, to };
            }
            this.scheduleDraw();
        });
        const endDrag = () => {
            if (dragging) { dragging = false; c.style.cursor = "crosshair"; this._emitRangeChange(); }
        };
        c.addEventListener("pointerup", endDrag);
        c.addEventListener("pointercancel", endDrag);
        c.addEventListener("pointerleave", () => { this.hoverX = null; this.scheduleDraw(); });
        c.addEventListener("dblclick", () => { if (this.onReset) this.onReset(); });
    }
}

// ---- app state -------------------------------------------------------------
const WINDOWS = { "24h": 86400e3, "7d": 7 * 86400e3, "30d": 30 * 86400e3 };
let currentWindow = "24h";
let chart;

function maxPointsForWidth() {
    return clamp(Math.round($("chart").clientWidth * 1.5), 200, 2000);
}

async function loadSeries(from, to) {
    try {
        const data = await getJSON(`/api/series?from=${from}&to=${to}&maxPoints=${maxPointsForWidth()}`);
        chart.setData(data.points);
    } catch (e) { if (e.message !== "unauthorized") toast("Failed to load chart data"); }
}

async function loadSummary() {
    try {
        const s = await getJSON(`/api/summary?window=${currentWindow}`);
        $("rangeStamp").textContent = fmtDateTime(s.from) + "  →  " + fmtDateTime(s.to);

        $("sumCurrent").textContent = fmtInt(s.currentPopulation);
        $("sumPeak").textContent = fmtInt(s.peak.peak);
        $("sumPeakDate").textContent = s.peak.at > 0 ? "at " + fmtDateTime(s.peak.at) : "no data";

        $("sumAvg").textContent = s.population.currentAvg.toFixed(1);
        const delta = $("sumAvgDelta");
        const change = s.population.percentChange;
        if (s.population.previousAvg === 0 && s.population.currentAvg === 0) {
            delta.textContent = "—"; delta.className = "delta flat";
        } else {
            const up = s.population.absoluteChange >= 0;
            delta.textContent = (up ? "▲ " : "▼ ") + fmtPct(Math.abs(change), 0);
            delta.className = "delta " + (Math.abs(s.population.absoluteChange) < 0.05 ? "flat" : up ? "up" : "down");
        }

        $("sumNew").textContent = fmtInt(s.newPlayers);

        const pb = s.playerbase;
        $("pbUnique").textContent = fmtInt(pb.uniquePlayers);
        $("pbNew").textContent = fmtInt(pb.newPlayers);
        $("pbReturning").textContent = fmtInt(pb.returningPlayers);
        $("pbRegular").textContent = fmtInt(pb.regularPlayers);
        $("pbRegularSub").textContent = "≥ " + pb.regularThreshold
            + (pb.regularThreshold === 1 ? " session" : " sessions");
        $("pbJoins").textContent = fmtInt(pb.totalJoins);
        $("pbJoinsSub").textContent = "avg " + pb.avgJoinsPerPlayer.toFixed(1) + " / player";

        $("sumRetention").textContent = s.retention.cohortSize === 0 ? "—" : fmtPct(s.retention.retentionRate, 0);
        setBar($("retentionBar"), s.retention.retentionRate, [0.5, 0.999]);
        // Retention bar: more is better, so invert colour stops manually.
        $("retentionBar").style.background =
            s.retention.retentionRate >= 0.5 ? COLORS.green :
                s.retention.retentionRate >= 0.2 ? COLORS.amber : COLORS.red;
        $("sumRetentionSub").textContent = s.retention.retainedCount + " of " + s.retention.cohortSize + " returned";

        $("sumSessions").textContent = fmtInt(s.sessions.countedSessions);
        $("sumSessionsAvg").textContent = fmtDuration(s.sessions.averageDurationMillis);
        $("sumSessionsTotal").textContent = fmtDuration(s.sessions.totalPlaytimeMillis);

        $("sumCpu").textContent = s.resources.sampleCount === 0 ? "—" : fmtPct(s.resources.cpuProcessAvg, 1);
        $("sumCpuPeak").textContent = fmtPct(s.resources.cpuProcessPeak, 1);
        $("sumCpuSys").textContent = fmtPct(s.resources.cpuSystemAvg, 1);
        setBar($("cpuBar"), s.resources.cpuProcessAvg, [0.5, 0.85]);

        const heapFrac = s.resources.heapMax > 0 ? s.resources.heapUsedAvg / s.resources.heapMax : 0;
        $("sumHeap").textContent = fmtBytes(s.resources.heapUsedPeak);
        $("sumHeapMax").textContent = "/ " + fmtBytes(s.resources.heapMax);
        $("sumHeapAvg").textContent = fmtBytes(s.resources.heapUsedAvg);
        setBar($("heapBar"), heapFrac, [0.6, 0.85]);
    } catch (e) { if (e.message !== "unauthorized") toast("Failed to load summary"); }
}

async function loadAllTime() {
    try {
        const a = await getJSON("/api/alltime");
        countUp($("atPeak"), a.peakPlayers, (v) => fmtInt(v));
        $("atPeakDate").textContent = a.peakAt > 0 ? fmtDateTime(a.peakAt) : "no data yet";
        countUp($("atUnique"), a.uniquePlayers, (v) => fmtInt(v));
        countUp($("atSessions"), a.totalSessions, (v) => fmtInt(v));
        if (a.firstEverSeen > 0) {
            $("atFirst").textContent = fmtDate(a.firstEverSeen);
            $("atFirstAgo").textContent = ago(a.firstEverSeen);
        } else {
            $("atFirst").textContent = "—";
            $("atFirstAgo").textContent = "no players yet";
        }
        const live = a.currentPopulation > 0;
        $("statusCount").textContent = fmtInt(a.currentPopulation);
        $("statusDot").classList.toggle("is-live", live);
    } catch (e) { if (e.message !== "unauthorized") toast("Failed to load all-time stats"); }
}

// ---- servers tab -----------------------------------------------------------
function barRow(name, meta, valueMain, valueSub, frac) {
    const row = el("div", "row");
    const bar = el("div", "row-bar");
    bar.style.width = clamp(frac, 0, 1) * 100 + "%";
    row.appendChild(bar);

    const nm = el("div", "row-name");
    nm.appendChild(el("span", null, name));
    if (meta) nm.appendChild(el("span", "row-meta", meta));
    row.appendChild(nm);

    const val = el("div", "row-val", valueMain);
    if (valueSub != null) val.appendChild(el("span", "row-val-sub", valueSub));
    row.appendChild(val);
    return row;
}

function renderRows(host, items, emptyMsg, build) {
    host.innerHTML = "";
    if (!items.length) { host.appendChild(el("div", "rows-empty", emptyMsg)); return; }
    for (const item of items) host.appendChild(build(item));
}

function renderServers(data) {
    const maxOnline = Math.max(1, ...data.current.map((s) => s.online));
    renderRows($("serverPresence"), data.current, "no players connected", (s) =>
        barRow(s.server, null, fmtInt(s.online) + (s.online === 1 ? " player" : " players"),
            null, s.online / maxOnline));

    const maxPlay = Math.max(1, ...data.activity.map((s) => s.playtimeMillis));
    renderRows($("serverActivity"), data.activity, "no sessions in this window", (s) =>
        barRow(s.server, fmtInt(s.uniquePlayers) + (s.uniquePlayers === 1 ? " player" : " players"),
            fmtDuration(s.playtimeMillis),
            fmtInt(s.sessions) + (s.sessions === 1 ? " session" : " sessions"),
            s.playtimeMillis / maxPlay));
}

async function loadServers() {
    try { renderServers(await getJSON(`/api/servers?window=${currentWindow}`)); }
    catch (e) { if (e.message !== "unauthorized") toast("Failed to load servers"); }
}

// ---- players tab -----------------------------------------------------------
function curveCell(label, frac, color, sub) {
    const cell = el("div", "curve-cell");
    cell.appendChild(el("div", "curve-label", label));
    cell.appendChild(el("div", "curve-val", fmtPct(frac, 0)));
    const bar = el("div", "curve-bar");
    const span = el("span");
    span.style.width = clamp(frac, 0, 1) * 100 + "%";
    span.style.background = color;
    bar.appendChild(span);
    cell.appendChild(bar);
    cell.appendChild(el("div", "curve-sub", sub));
    return cell;
}

function renderPlayers(data) {
    const st = data.stickiness;
    $("stDau").textContent = fmtInt(st.dau);
    $("stWau").textContent = fmtInt(st.wau);
    $("stMau").textContent = fmtInt(st.mau);
    $("stRatio").textContent = st.mau === 0 ? "—" : fmtPct(st.stickiness, 0);

    const curve = $("retentionCurve");
    curve.innerHTML = "";
    const r = data.retention;
    if (r.cohortSize === 0) {
        curve.appendChild(el("div", "rows-empty", "no new players in this window"));
    } else {
        curve.appendChild(curveCell("returned · d1", r.d1, COLORS.green, "of cohort"));
        curve.appendChild(curveCell("returned · d7", r.d7, COLORS.green, "of cohort"));
        curve.appendChild(curveCell("returned · d30", r.d30, COLORS.green, "of cohort"));
        curve.appendChild(curveCell("bounced", r.bounceRate, COLORS.red,
            r.cohortSize + (r.cohortSize === 1 ? " new player" : " new players")));
    }

    const body = $("leaderboardBody");
    body.innerHTML = "";
    if (!data.leaderboard.length) {
        const tr = el("tr");
        const td = el("td", "rows-empty", "no sessions in this window");
        td.colSpan = 4;
        tr.appendChild(td);
        body.appendChild(tr);
        return;
    }
    data.leaderboard.forEach((p, i) => {
        const tr = el("tr");
        tr.appendChild(el("td", "board-rank", String(i + 1)));
        tr.appendChild(el("td", "board-name", p.username));
        tr.appendChild(el("td", "board-num", fmtInt(p.sessions)));
        tr.appendChild(el("td", "board-num", fmtDuration(p.playtimeMillis)));
        body.appendChild(tr);
    });
}

async function loadPlayers() {
    try { renderPlayers(await getJSON(`/api/players?window=${currentWindow}`)); }
    catch (e) { if (e.message !== "unauthorized") toast("Failed to load player stats"); }
}

// ---- activity tab ----------------------------------------------------------
function renderHeatmap(data) {
    const hm = $("heatmap");
    hm.innerHTML = "";
    const grid = data.grid, max = data.max || 1;
    hm.appendChild(el("div", "hm-corner"));
    for (let h = 0; h < 24; h++) hm.appendChild(el("div", "hm-hour", h % 3 === 0 ? String(h) : ""));
    for (let d = 0; d < 7; d++) {
        hm.appendChild(el("div", "hm-day", DOW[d]));
        for (let h = 0; h < 24; h++) {
            const c = (grid[d] && grid[d][h]) || 0;
            const cell = el("div", "hm-cell");
            if (c > 0) {
                const a = 0.12 + 0.88 * (c / max);
                cell.style.background = `rgba(91,224,138,${a.toFixed(3)})`;
            }
            cell.title = `${DOW[d]} ${String(h).padStart(2, "0")}:00 UTC · ${fmtInt(c)} login${c === 1 ? "" : "s"}`;
            hm.appendChild(cell);
        }
    }
}

function renderHistogram(dist) {
    const hist = $("histogram");
    hist.innerHTML = "";
    const total = dist.reduce((s, b) => s + b.count, 0);
    if (total === 0) { hist.appendChild(el("div", "rows-empty", "no sessions in this window")); return; }
    const max = Math.max(1, ...dist.map((b) => b.count));
    for (const b of dist) {
        const col = el("div", "histo-col");
        col.appendChild(el("div", "histo-count", b.count ? fmtInt(b.count) : ""));
        const track = el("div", "histo-track");
        const bar = el("div", "histo-bar");
        bar.style.height = (b.count / max * 100).toFixed(1) + "%";
        bar.title = fmtInt(b.count) + (b.count === 1 ? " session" : " sessions");
        track.appendChild(bar);
        col.appendChild(track);
        col.appendChild(el("div", "histo-label", b.label));
        hist.appendChild(col);
    }
}

async function loadActivity() {
    try {
        const data = await getJSON(`/api/activity?window=${currentWindow}`);
        renderHeatmap(data.heatmap);
        renderHistogram(data.distribution);
    } catch (e) { if (e.message !== "unauthorized") toast("Failed to load activity"); }
}

// ---- tab routing -----------------------------------------------------------
let currentTab = "overview";
const TAB_LOADERS = { servers: loadServers, players: loadPlayers, activity: loadActivity };
// Tracks which tabs hold data for the current window, so panels load lazily on
// first view and refresh when the window changes.
const tabLoaded = { overview: true };

function loadActiveTab() {
    const loader = TAB_LOADERS[currentTab];
    if (loader) loader();
}

function switchTab(tab) {
    if (tab === currentTab || (tab !== "overview" && !TAB_LOADERS[tab])) return;
    currentTab = tab;
    for (const btn of $("tabs").children) btn.classList.toggle("is-active", btn.dataset.tab === tab);
    for (const panel of document.querySelectorAll(".tab-panel")) panel.hidden = panel.id !== "tab-" + tab;
    if (!tabLoaded[tab]) { tabLoaded[tab] = true; loadActiveTab(); }
    // The canvas can't size itself while display:none, so redraw once visible.
    if (tab === "overview" && chart) chart.scheduleDraw();
}

// Window changed: insight panels are window-scoped, so invalidate them and refresh
// whichever one is currently visible.
function invalidateInsights() {
    for (const key of Object.keys(TAB_LOADERS)) tabLoaded[key] = false;
    if (currentTab !== "overview") { tabLoaded[currentTab] = true; loadActiveTab(); }
}

function resetToWindow() {
    const to = Date.now();
    const from = to - WINDOWS[currentWindow];
    chart.setView(from, to);
    loadSeries(from, to);
}

function buildLegend() {
    const host = $("chartLegend");
    host.innerHTML = "";
    for (const s of SERIES) {
        const el = document.createElement("button");
        el.type = "button";
        el.className = "legend-item";
        el.innerHTML = `<span class="swatch" style="background:${s.color}"></span>${s.label}`;
        el.addEventListener("click", () => {
            chart.toggle(s.key);
            el.classList.toggle("is-off");
        });
        host.appendChild(el);
    }
}

function init() {
    chart = new TelemetryChart($("chart"), $("chartTooltip"), (from, to) => loadSeries(from, to));
    chart.onReset = () => { resetToWindow(); $("resetZoom").hidden = true; };

    buildLegend();

    $("tabs").addEventListener("click", (e) => {
        const btn = e.target.closest(".tab");
        if (btn) switchTab(btn.dataset.tab);
    });

    $("windowToggle").addEventListener("click", (e) => {
        const btn = e.target.closest(".seg");
        if (!btn) return;
        for (const b of $("windowToggle").children) b.classList.remove("is-active");
        btn.classList.add("is-active");
        currentWindow = btn.dataset.window;
        $("resetZoom").hidden = true;
        resetToWindow();
        loadSummary();
        invalidateInsights();
    });

    const reset = $("resetZoom");
    reset.hidden = false;
    reset.addEventListener("click", () => { resetToWindow(); reset.hidden = true; });

    $("logoutBtn").addEventListener("click", async () => {
        try { await fetch("/api/logout", { method: "POST" }); } catch (_) { /* ignore */ }
        window.location = "/login";
    });

    loadAllTime();
    loadSummary();
    resetToWindow();

    // Gentle live refresh of the headline figures (leaves the chart view alone).
    setInterval(() => {
        loadAllTime();
        loadSummary();
        if (currentTab !== "overview") loadActiveTab();
    }, 30000);
}

document.addEventListener("DOMContentLoaded", init);
