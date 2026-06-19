/* Renders the Earshot benchmarks page from benchmarks/results.json.
   Word error rate is treated as trustworthy (accuracy is device-independent). Speed and memory
   are shown with a provenance badge and, when not measured on a real device, are visibly flagged
   as not representative rather than quietly presented as on-device truth. */

document.body.classList.remove('preload');

const $ = (tag, cls, html) => {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  if (html != null) el.innerHTML = html;
  return el;
};
const pct = (x) => `${x.toFixed(2)}%`;
const rtf = (x) => `${x.toFixed(2)}×`;
const mb = (b) => `${Math.round(b / 1048576)} MB`;
const secs = (ms) => (ms >= 1000 ? `${(ms / 1000).toFixed(1)} s` : `${ms} ms`);
const platKey = (r) => (r.platform.toLowerCase().includes('android') ? 'android' : 'ios');
const isReal = (r) => r.provenance === 'real-device';
// A short model tag for the bar-chart labels, so two sherpa-onnx rows on the same platform
// (Parakeet offline vs Nemotron streaming) stay distinguishable. The full model name is in
// the comparison table.
const modelTag = (r) => {
  const m = r.model.toLowerCase();
  if (m.includes('nemotron')) return 'Nemotron';
  if (m.includes('parakeet')) return 'Parakeet';
  return null;
};

const PROV_LABEL = { 'real-device': 'real device', simulator: 'simulator', emulator: 'emulator' };
function provBadge(p) {
  const cls = p === 'real-device' ? 'real' : p;
  return `<span class="prov ${cls}"><span class="dot"></span>${PROV_LABEL[p] || p}</span>`;
}

async function main() {
  const root = document.getElementById('bench-root');
  let data;
  try {
    const res = await fetch(root.dataset.src, { cache: 'no-store' });
    if (!res.ok) throw new Error(res.status);
    data = await res.json();
  } catch (e) {
    root.innerHTML = `<p class="bench-loading">Could not load results.json (${e}). Generate it with the :benchmark scorer.</p>`;
    return;
  }
  root.innerHTML = '';
  const runtimes = data.runtimes || [];
  const android = runtimes.find((r) => platKey(r) === 'android' && !r.accuracyOnly);
  const ios = runtimes.find((r) => platKey(r) === 'ios' && !r.accuracyOnly);
  const allReal = runtimes.every(isReal);

  root.appendChild(deltaCard(android, ios, allReal));
  root.appendChild(comparisonBlock(runtimes));
  root.appendChild(werChart(runtimes));
  root.appendChild(perfChart(runtimes, 'speed'));
  root.appendChild(perfChart(runtimes, 'memory'));
  if (android && ios) root.appendChild(clipBlock(data, android, ios));
  root.appendChild(methodBlock(data));

  // Animate bars in once laid out.
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      document.querySelectorAll('.bar-fill').forEach((b) => {
        b.style.transform = `scaleX(${b.dataset.scale})`;
      });
    });
  });
}

function deltaCard(android, ios, allReal) {
  const card = $('div', 'delta-card');
  const side = (r, side) => {
    if (!r) return `<div class="delta-side ${side}"><div class="who">—</div><div class="delta-num">—</div></div>`;
    return `<div class="delta-side ${side}">
      <div class="who"><b>${r.platform}</b> · ${r.runtime}</div>
      <div class="delta-num">${r.werPercent.toFixed(2)}<small>% WER</small></div>
      <div class="sub">${r.model}</div>
    </div>`;
  };
  let mid = '<div class="delta-mid"><div class="gap">vs</div></div>';
  if (android && ios) {
    const gap = Math.abs(android.werPercent - ios.werPercent);
    const better = ios.werPercent < android.werPercent ? 'iOS / CoreML' : 'Android / ONNX';
    mid = `<div class="delta-mid">
      <div class="gap">${gap.toFixed(2)} pts</div>
      <div class="gap-cap">${better} more accurate, same model family</div>
    </div>`;
  }
  card.innerHTML = `
    <p class="delta-eyebrow">Cross-runtime accuracy · word error rate, lower is better</p>
    <div class="delta-grid">
      ${side(android, 'android')}
      ${mid}
      ${side(ios, 'ios')}
    </div>
    <p class="delta-foot">
      Both numbers reflect how the model actually runs on each platform: the iOS figure is from the
      iPad's <b>Neural Engine</b>, the Android figure from the same Olive int8 graph a phone runs.
      The gap is the runtime and its precision, <b>float16 on CoreML versus int8 on ONNX</b>, not
      the model. Both are Whisper <code style="color:var(--amber)">tiny.en</code>.
    </p>`;
  return card;
}

function comparisonBlock(runtimes) {
  const block = $('section', 'bench-block');
  block.innerHTML = `<h2>Every number, with where it came from</h2>
    <p class="block-sub">One row per on-device runtime. Word error rate is comparable across rows.
    Cold load, speed and peak memory are only representative when the provenance reads
    <em>real device</em>.</p>`;
  const wrap = $('div', 'rt-table-wrap');
  const head = `<thead><tr>
      <th>Runtime</th><th>Model</th><th>WER</th><th>Median RTF</th>
      <th>Peak memory</th><th>Cold load</th><th>Device</th><th>Provenance</th>
    </tr></thead>`;
  const na = '<span style="color:var(--fg-faint)">n/a</span>';
  const rows = runtimes.map((r) => `
    <tr class="row-${platKey(r)}">
      <th scope="row">${r.runtime}<span class="rt-plat">${r.platform}</span></th>
      <td>${r.model}</td>
      <td><span class="big">${pct(r.werPercent)}</span></td>
      <td>${r.accuracyOnly ? na : rtf(r.medianRtf)}</td>
      <td>${r.accuracyOnly ? na : mb(r.peakMemoryBytes)}</td>
      <td>${r.accuracyOnly ? na : secs(r.loadMs)}</td>
      <td>${r.device}<br><span style="color:var(--fg-faint);font-size:0.8em">${r.osVersion}</span></td>
      <td>${provBadge(r.provenance)}</td>
    </tr>`).join('');
  const hasAccuracyOnly = runtimes.some((r) => r.accuracyOnly);
  const foot = hasAccuracyOnly
    ? `<p class="chart-foot">n/a: Apple's recognizer runs in a system process, so its speed, memory and load are not measured the same way as the in-process runtimes. It is compared on accuracy only.</p>`
    : '';
  wrap.innerHTML = `<table class="rt-table">${head}<tbody>${rows}</tbody></table>`;
  block.appendChild(wrap);
  block.insertAdjacentHTML('beforeend', foot);
  return block;
}

function barLine(r, value, label, scale) {
  const k = r.accuracyOnly ? 'apple' : platKey(r);
  const unrep = (label !== 'wer' && !isReal(r)) ? ' unrep' : '';
  const tag = modelTag(r);
  const suffix = tag ? ` · ${tag}` : '';
  return `<div class="bar-item">
      <div class="blabel"><b>${r.platform}</b> · ${r.runtime}${suffix}</div>
      <div class="bar-line">
        <span class="bar-track"><span class="bar-fill ${k}${unrep}" data-scale="${scale.toFixed(3)}"></span></span>
        <span class="bval">${value}</span>
      </div>
    </div>`;
}

function werChart(runtimes) {
  const block = $('section', 'bench-block');
  block.innerHTML = `<h2>Word error rate</h2>
    <p class="block-sub">Scored offline by one algorithm over identical references, so the bars are
    directly comparable. Lower is better.</p>`;
  const card = $('div', 'chart-card');
  const max = Math.max(...runtimes.map((r) => r.werPercent), 0.001);
  const lines = runtimes.map((r) => barLine(r, pct(r.werPercent), 'wer', r.werPercent / max)).join('');
  const foot = `Three NVIDIA model variants on the same clips alongside Whisper <code style="color:var(--amber)">tiny.en</code> (39M): <code style="color:var(--amber)">Parakeet-TDT v3</code> (600M, offline) and <code style="color:var(--amber)">Nemotron Speech Streaming</code> (600M, cache-aware streaming, 1120ms chunk), both via sherpa-onnx. Apple's built-in recognizer is shown for reference. The streaming Nemotron trades accuracy for its 1120ms-chunk latency; Parakeet is the most accurate. Speed and memory show up in the charts below. Bars scaled to the highest value shown.`;
  card.innerHTML = `<div class="chart-head"><h3>Accuracy</h3><span class="hint">lower is better</span></div>${lines}
    <p class="chart-foot">${foot}</p>`;
  block.appendChild(card);
  return block;
}

function perfChart(runtimes, kind) {
  const speed = kind === 'speed';
  const block = $('section', 'bench-block');
  block.innerHTML = speed
    ? `<h2>Speed</h2><p class="block-sub">Real-time factor: processing time divided by audio
       duration. <b>0.20× means a minute of audio in twelve seconds.</b> Lower is faster.</p>`
    : `<h2>Peak memory</h2><p class="block-sub">Highest process footprint observed across the run.</p>`;
  const card = $('div', 'chart-card');
  // Out-of-process engines (Apple) are not comparable on speed/memory; show only in-process ones.
  const shown = runtimes.filter((r) => !r.accuracyOnly);
  const vals = shown.map((r) => (speed ? r.medianRtf : r.peakMemoryBytes));
  const max = Math.max(...vals, speed ? 0.001 : 1);
  const lines = shown.map((r, i) =>
    barLine(r, speed ? rtf(r.medianRtf) : mb(r.peakMemoryBytes), kind, vals[i] / max)).join('');
  card.innerHTML = `<div class="chart-head"><h3>${speed ? 'Real-time factor' : 'Peak memory'}</h3>
    <span class="hint">${speed ? 'lower is faster' : 'lower is leaner'}</span></div>${lines}`;
  const nonReal = shown.filter((r) => !isReal(r));
  if (nonReal.length) {
    const where = [...new Set(nonReal.map((r) => PROV_LABEL[r.provenance]))].join(' / ');
    const plats = [...new Set(nonReal.map((r) => r.platform))].join(' & ');
    card.appendChild($('div', 'unrep-flag',
      `<span>⚠</span><span>The <b>${plats}</b> ${nonReal.length > 1 ? 'bars' : 'bar'} ${nonReal.length > 1 ? 'come' : 'comes'}
       from ${where === 'emulator' ? 'an' : 'a'} <b>${where}</b>, which does not use the phone's Neural Engine.
       ${nonReal.length > 1 ? 'They show' : 'It shows'} the pipeline runs, not real-hardware speed.
       <b>Real-device ${plats} numbers pending.</b></span>`));
  }
  block.appendChild(card);
  return block;
}

function clipBlock(data, android, ios) {
  const block = $('section', 'bench-block');
  block.innerHTML = `<h2>Clip by clip</h2>
    <p class="block-sub">All ${data.fixtureCount} fixtures, scored individually. Read it as a spot
    check on the aggregate, not a leaderboard.</p>`;
  const det = $('details', 'clips');
  const iosById = Object.fromEntries(ios.clips.map((c) => [c.id, c]));
  const rows = android.clips.map((a) => {
    const i = iosById[a.id] || {};
    return `<tr>
      <td>${a.id}</td>
      <td class="android">${pct(a.werPercent)}</td>
      <td class="ios">${i.werPercent != null ? pct(i.werPercent) : '—'}</td>
      <td class="android">${rtf(a.rtf)}</td>
      <td class="ios">${i.rtf != null ? rtf(i.rtf) : '—'}</td>
    </tr>`;
  }).join('');
  det.innerHTML = `<summary><span>Show all ${data.fixtureCount} clips</span><span>▾</span></summary>
    <div class="clip-scroll"><table class="clip-table">
      <thead><tr><th>Clip</th><th>WER · Android</th><th>WER · iOS</th><th>RTF · Android</th><th>RTF · iOS</th></tr></thead>
      <tbody>${rows}</tbody>
    </table></div>`;
  block.appendChild(det);
  return block;
}

function methodBlock(data) {
  const block = $('section', 'bench-block');
  block.innerHTML = `<h2>How this was measured</h2>`;
  const grid = $('div', 'method-grid');
  const cards = [
    ['Dataset', data.dataset],
    ['Fixtures', `<b>${data.fixtureCount}</b> clips, ${data.totalAudioSec.toFixed(0)}s of speech`],
    ['Scoring', 'One word-error-rate pass, normalized for case and punctuation, applied to both runtimes'],
    ['Model', 'Whisper <b>tiny.en</b> on both: ONNX Runtime (Olive int8) on Android, WhisperKit / CoreML on iOS'],
  ];
  cards.forEach(([k, v]) => {
    const c = $('div', 'method-card');
    c.innerHTML = `<p class="mk">${k}</p><div class="mv">${v}</div>`;
    grid.appendChild(c);
  });
  block.appendChild(grid);

  const repro = $('div', 'repro');
  repro.innerHTML = `<p class="rk">Reproduce</p><pre><code># Android (ONNX) on a connected device or emulator
adb install -r -g sample-android-debug.apk sample-android-debug-androidTest.apk
adb shell am instrument -w -e class dev.eknuth.earshot.sample.BenchmarkRunnerTest \\
  dev.eknuth.earshot.sample.test/androidx.test.runner.AndroidJUnitRunner

# iOS (WhisperKit/CoreML) on a connected device or simulator
xcrun simctl launch --console-pty booted dev.eknuth.earshot.sample --earshot-bench

# Score both raw runs against the references
./gradlew :benchmark:run --args="--manifest benchmark/fixtures/manifest.json \\
  --raw raw-android.json --raw raw-ios.json --out docs/benchmarks/results.json"</code></pre>`;
  block.appendChild(repro);

  const note = $('p', 'chart-foot', data.note);
  note.style.marginTop = 'var(--s-3)';
  block.appendChild(note);
  return block;
}

main();
