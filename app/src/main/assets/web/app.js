'use strict';

// Tile types: label, default size in cells, settings, and which entity domains suit them.
const TYPES = {
  clock: {
    label: 'Clock', size: [4, 2], fields: ['style', 'date', 'opacity', 'textSize'],
    overrides: { style: { label: 'Look', options: [['', 'Digital'], ['analog', 'Analog']] } },
  },
  entity: {
    label: 'Entity value', size: [3, 2], fields: ['entity', 'name', 'style', 'min', 'max', 'betterWhen', 'icon', 'unit', 'decimals', 'opacity', 'background', 'border', 'textSize'],
    overrides: { style: { label: 'Look', options: [['', 'Number'], ['trend', 'Trend (change and sparkline)'], ['gauge', 'Gauge'], ['progress', 'Progress bar']] } },
  },
  toggle: {
    label: 'Toggle / button', size: [2, 2], fields: ['entity', 'name', 'icon', 'measurement', 'unit', 'decimals', 'opacity', 'background', 'border', 'textSize'],
    domains: ['light', 'switch', 'fan', 'input_boolean', 'script', 'scene', 'button', 'input_button', 'cover', 'media_player', 'climate', 'lock'],
  },
  graph: {
    label: 'History graph', size: [6, 3], fields: ['entity', 'name', 'style', 'hours', 'unit', 'decimals', 'opacity', 'background', 'border', 'textSize'],
    overrides: { style: { label: 'Look', options: [['', 'Line'], ['bars', 'Bars (per hour)'], ['sparkline', 'Sparkline']] } },
    domains: ['sensor', 'input_number', 'number'], multi: 'line', labels: true,
  },
  weather: { label: 'Weather', size: [4, 3], fields: ['entity', 'name', 'opacity', 'background', 'border', 'textSize'], domains: ['weather'] },
  calendar: { label: 'Calendar', size: [4, 4], fields: ['entity', 'name', 'view', 'days', 'opacity', 'background', 'border', 'textSize'], domains: ['calendar'], multi: 'calendar' },
  camera: { label: 'Camera', size: [6, 4], fields: ['camera', 'name', 'fullStream', 'snapshot', 'fit', 'opacity', 'textSize'] },
  chips: { label: 'Chips (status row)', size: [12, 1], fields: ['entity', 'decimals'], multi: 'chip' },
  heading: {
    label: 'Heading', size: [12, 1], fields: ['name', 'subtitle', 'style'],
    overrides: { style: { label: 'Look', options: [['', 'Big title'], ['separator', 'Title and a line']] }, name: { label: 'Title', placeholder: 'Living room' } },
  },
  thermostat: {
    label: 'Thermostat', size: [3, 3], fields: ['entity', 'name', 'style', 'opacity', 'background', 'border', 'textSize'], domains: ['climate'],
    overrides: { style: { label: 'Look', options: [['', 'As the layout'], ['dial', 'Dial'], ['numbers', 'Numbers'], ['ring', 'Ring (glows)'], ['bar', 'Bar'], ['pill', 'Pill (one row)'], ['thermometer', 'Thermometer']] } },
  },
  slider: {
    label: 'Slider (dimmer, cover, volume)', size: [3, 1], fields: ['entity', 'name', 'icon', 'opacity', 'background', 'border', 'textSize'],
    domains: ['light', 'cover', 'fan', 'media_player', 'number', 'input_number'],
  },
  space: {
    label: 'Empty space', size: [1, 1], fields: ['picture', 'fit', 'opacity'],
    // The same props as others, with this tile's own meaning.
    overrides: {
      fit: { label: 'Picture', options: [['', 'Show all of it'], ['cover', 'Fill the tile'], ['stretch', 'Stretch to the tile']] },
      opacity: { label: 'Picture opacity (%)', type: 'number', placeholder: '100' },
    },
  },
};

const FIELDS = {
  entity: { label: 'Entity', placeholder: 'sensor.living_room_temperature' },
  name: { label: 'Name', placeholder: 'Name from Home Assistant' },
  measurement: { label: 'Measurement (optional)', placeholder: 'sensor.coffee_maker_power', list: 'allEntities' },
  unit: { label: 'Unit', placeholder: 'Unit from Home Assistant' },
  decimals: { label: 'Decimals', type: 'number', placeholder: 'As reported' },
  hours: { label: 'Hours shown', type: 'number', placeholder: '24' },
  days: { label: 'Days ahead', type: 'number', placeholder: '7' },
  date: { label: 'Date under the time', options: [['', 'Show'], ['off', 'Hide']] },
  camera: { label: 'Camera', placeholder: 'go2rtc stream name or rtsp:// URL', list: 'cameraList' },
  fullStream: { label: 'Full-screen stream (tap)', placeholder: 'Same as the tile', list: 'cameraList' },
  snapshot: { label: 'Snapshot URL', placeholder: 'From go2rtc' },
  opacity: { label: 'Opacity (%)', type: 'number', placeholder: 'As set for all tiles' },
  textSize: {
    label: 'Text size',
    options: [['', 'Auto (fits the tile)'], ['small', 'Small'], ['medium', 'Medium'], ['large', 'Large'], ['xl', 'Extra large']],
  },
  fit: { label: 'Picture', options: [['', 'Fill the tile'], ['contain', 'Show all of it']] },
  view: { label: 'View', options: [['', 'List of what\'s ahead'], ['week', 'Week: a column per day'], ['month', 'Month grid (6×5 or bigger)']] },
  min: { label: 'Min (gauge, bar)', type: 'number', placeholder: '0' },
  max: { label: 'Max (gauge, bar)', type: 'number', placeholder: '100' },
  betterWhen: { label: 'Trend: better when', options: [['', 'Rising'], ['down', 'Falling'], ['none', 'Neither (no colour)']] },
  subtitle: { label: 'Subtitle', placeholder: 'Optional' },
  style: { label: 'Look', options: [['', 'Default']] },
  border: { label: 'Border', options: [['', 'As the layout'], ['none', 'None']] },
  icon: { label: 'Icon (compact layout)', options: [['', 'For the entity'], ['none', 'None'], ...[["bed", "bed"], ["bolt", "bolt"], ["bulb", "bulb"], ["calendar", "calendar"], ["coffee", "coffee"], ["computer", "computer"], ["drop", "drop"], ["fan", "fan"], ["gauge", "gauge"], ["home", "home"], ["moon", "moon"], ["plug", "plug"], ["power", "power"], ["snowflake", "snowflake"], ["sun", "sun"], ["thermometer", "thermometer"], ["tv", "tv"]]] },
  background: { label: 'Background', options: [['', 'As the layout (Tiles)'], ['card', 'Card'], ['none', 'None (on the panel background)']] },
};

const $ = (selector) => document.querySelector(selector);
const board = $('#board');

let key = localStorage.getItem('biwurthKey') || '';

// A bookmarkable address: http://<tablet>:2323/#key=<key>&tab=<tab> logs in, opens that tab and
// drops the key from the address bar.
const hashParams = new URLSearchParams(location.hash.slice(1));
if (hashParams.get('key')) {
  key = hashParams.get('key');
  localStorage.setItem('biwurthKey', key);
  history.replaceState(null, '', location.pathname);
}
const startTab = hashParams.get('tab');
let config = null;   // last /api/config
let layout = null;   // the layout being edited
let pageIndex = 0;
let selectedId = null;
let dirty = false;
let entities = [];

// ---- API ----------------------------------------------------------------------------------------

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${key}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 401) {
    showLogin();
    throw new Error('Wrong key');
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

// Background refreshes keep failing until a key is entered: open the box once and leave what's
// being typed alone (it used to be cleared every five seconds).
function showLogin() {
  if (!$('#login').hidden) return;
  $('#loginError').hidden = !key; // a key was tried and refused
  $('#login').hidden = false;
  $('#key').value = '';
  $('#key').focus();
}

$('#login form').addEventListener('submit', (event) => {
  event.preventDefault();
  key = $('#key').value.trim();
  localStorage.setItem('biwurthKey', key);
  $('#login').hidden = true;
  load();
});

async function load() {
  config = await api('GET', '/api/config');
  layout = structuredClone(config.layout);
  loadThemes();
  $('#version').textContent = config.version;
  pageIndex = Math.min(pageIndex, layout.pages.length - 1);
  setDirty(false);
  fillSettings();
  render();
  refreshEntities();
  refreshStatus();
}

async function refreshEntities() {
  try {
    entities = await api('GET', '/api/entities');
    renderBoard();
  } catch (e) { /* shown in the status line */ }
}

async function refreshStatus() {
  try {
    const s = await api('GET', '/api/status');
    $('#status').replaceChildren(
      chip('Screen', s.screen), ' · ', chip('Home Assistant', s.ha + (s.entities ? ` (${s.entities} entities)` : '')), ' · ', chip('MQTT', s.mqtt),
      ...(s.ownEntities ? [' · ', chip('Sources', `${s.ownEntities} values`)] : []),
      ...(s.memory ? [' · ', chip('Memory', `${s.memory.appMb} MB, ${gb(s.memory.deviceAvailableMb)} free`)] : []),
    );
    showMemory(s);
    $('#mqttStatus').textContent = `Status: ${s.mqtt}`;
  } catch (e) {
    $('#status').textContent = e.message;
  }
}

const gb = (mb) => (mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB`);

// Warns when the tablet is close to full with the layout it shows, and says what uses the most:
// live camera streams, set in the Cameras tab.
function showMemory(s) {
  const m = s.memory;
  const c = s.cameras;
  if (!m || !c) return;
  const box = $('#memoryWarning');
  box.hidden = !m.warning;
  if (m.warning) {
    const summary = `The tablet is close to full with this layout: ${gb(m.deviceAvailableMb)} free of ${gb(m.deviceTotalMb)}, the panel uses ${m.appMb} MB. `;
    if (c.live > 0) {
      const open = document.createElement('a');
      open.href = '#';
      open.textContent = 'Cameras tab';
      open.addEventListener('click', (e) => { e.preventDefault(); showTab('cameras'); });
      box.replaceChildren(
        summary,
        `${c.live} live camera stream${c.live === 1 ? '' : 's'} play now, each roughly 20–60 MB: lower Live streams at once in the `,
        open,
        m.suggestedMaxLive < c.maxLive ? ` (${m.suggestedMaxLive} suits this tablet).` : '.',
      );
    } else {
      box.replaceChildren(summary, 'Fewer pages, graphs or background pictures help.');
    }
  }
  const hint = $('#liveHint');
  hint.textContent = `${c.tiles ? `${c.tiles} camera tile${c.tiles === 1 ? '' : 's'} on the panel, ${c.live} live (the others show snapshots).` : 'No camera tiles on the panel.'} `
    + `This tablet has ${gb(m.deviceTotalMb)} of memory: up to ${m.suggestedMaxLive} live stream${m.suggestedMaxLive === 1 ? ' suits' : 's suit'} it.`;
  hint.classList.toggle('over', c.maxLive > m.suggestedMaxLive);
}

function chip(label, value) {
  const span = document.createElement('span');
  span.append(`${label}: `);
  const b = document.createElement('b');
  b.textContent = value;
  span.append(b);
  return span;
}

// ---- Layout state -------------------------------------------------------------------------------

const page = () => layout.pages[pageIndex];
const selectedTile = () => page().tiles.find((t) => t.id === selectedId);
const entity = (id) => entities.find((e) => e.id === id);
const entityIds = (tile) => (tile.props.entity || '').split(',').map((s) => s.trim()).filter(Boolean);
const uid = () => Math.random().toString(36).slice(2, 10);

function setDirty(value) {
  dirty = value;
  $('#saveBtn').disabled = !dirty;
  $('#dirty').hidden = !dirty;
}

function collides(candidate) {
  return page().tiles.some((o) => o.id !== candidate.id &&
    candidate.x < o.x + o.w && o.x < candidate.x + candidate.w &&
    candidate.y < o.y + o.h && o.y < candidate.y + candidate.h);
}

// The tablet compiles the layout (checks it, preloads its data) before switching to it.
$('#saveBtn').addEventListener('click', async () => {
  const button = $('#saveBtn');
  button.disabled = true;
  button.textContent = 'Compiling…';
  try {
    const result = await api('PUT', '/api/layout', layout);
    config.layout = result.layout;
    layout = structuredClone(config.layout);
    setDirty(false);
    render();
    showReport(result.report);
  } catch (e) {
    alert(`Not saved: ${e.message}`);
    button.disabled = false;
  } finally {
    button.textContent = 'Compile & save';
  }
});

function showReport(report) {
  const box = $('#report');
  const summary = document.createElement('div');
  summary.textContent = `Compiled in ${report.millis} ms, preloaded ${report.preloaded} tile${report.preloaded === 1 ? '' : 's'}` +
    (report.cost ? ` · drawing ${report.cost.graphPointsDrawn} of ${report.cost.graphPoints} graph readings, pictures ${Math.round(report.cost.pictureKb / 1024 * 10) / 10} MB, ${report.cost.liveStreams} live stream${report.cost.liveStreams === 1 ? '' : 's'}` : '') +
    (report.warnings.length ? ` · ${report.warnings.length} warning${report.warnings.length === 1 ? '' : 's'}:` : ' · no warnings');
  const list = document.createElement('ul');
  list.replaceChildren(...report.warnings.map((w) => {
    const li = document.createElement('li');
    li.textContent = w;
    return li;
  }));
  box.replaceChildren(summary, list);
  box.classList.toggle('warn', report.warnings.length > 0);
  box.hidden = false;
}

window.addEventListener('beforeunload', (event) => {
  if (dirty) event.preventDefault();
});

// ---- Rendering ----------------------------------------------------------------------------------

function render() {
  renderPageTabs();
  renderBoard();
  renderTileForm();
  renderLayoutForm();
}

function renderPageTabs() {
  $('#pageTabs').replaceChildren(...layout.pages.map((p, i) => {
    const button = document.createElement('button');
    button.textContent = p.name;
    button.classList.toggle('active', i === pageIndex);
    button.addEventListener('click', () => {
      pageIndex = i;
      selectedId = null;
      render();
    });
    if (i === pageIndex && layout.pages.length > 1) {
      const close = document.createElement('span');
      close.className = 'close';
      close.textContent = '×';
      close.title = 'Delete this page';
      close.addEventListener('click', (event) => {
        event.stopPropagation();
        deletePage();
      });
      button.append(close);
    }
    return button;
  }));
}

// The board has the tablet's proportions in the chosen orientation.
function boardAspect() {
  const { width, height } = config.display;
  const long = Math.max(width, height);
  const short = Math.min(width, height);
  return layout.orientation.includes('portrait') ? short / long : long / short;
}

function renderBoard() {
  if (!layout) return;
  const aspect = boardAspect();
  board.style.aspectRatio = `${aspect}`;
  board.style.width = `min(100%, calc((100vh - 190px) * ${aspect}))`;
  const grid = 'linear-gradient(to right, #ffffff10 1px, transparent 1px), linear-gradient(to bottom, #ffffff10 1px, transparent 1px)';
  const cell = `calc(100% / ${layout.columns}) calc(100% / ${layout.rows})`;
  const bg = boardBackground();
  board.style.backgroundColor = bg.color;
  board.style.backgroundImage = [grid, ...bg.layers.map((l) => l.image)].join(', ');
  board.style.backgroundSize = [cell, cell, ...bg.layers.map((l) => l.size)].join(', ');
  board.style.backgroundRepeat = ['repeat', 'repeat', ...bg.layers.map((l) => l.repeat)].join(', ');
  board.style.backgroundPosition = ['top left', 'top left', ...bg.layers.map((l) => l.position)].join(', ');
  board.replaceChildren(...page().tiles.map(tileElement));
}

function tileElement(tile) {
  const el = document.createElement('div');
  el.className = 'tile' + (tile.id === selectedId ? ' selected' : '');
  const opacity = (tile.props.opacity !== undefined && tile.props.opacity !== '' ? Number(tile.props.opacity) : (layout.theme.tileOpacity ?? 100)) / 100;
  el.style.backgroundColor = tile.props.background === 'none' ? 'transparent' : `rgba(26, 28, 31, ${opacity})`;
  const cardless = tile.props.background === 'none' || (tile.props.background !== 'card' && layout.theme.cards === false);
  if (cardless) el.style.backgroundColor = 'transparent';
  if (cardless || tile.type === 'space') el.style.border = '1px dashed #ffffff55';
  if (tile.type === 'space') {
    el.style.backgroundColor = 'transparent';
    if (tile.props.picture) {
      const name = tile.props.picture;
      pictureUrl(name).then((url) => {
        if (!url) return;
        el.style.backgroundImage = `url("${url}")`;
        el.style.backgroundRepeat = 'no-repeat';
        el.style.backgroundPosition = 'center';
        el.style.backgroundSize = { cover: 'cover', stretch: '100% 100%' }[tile.props.fit] || 'contain';
      });
    }
  }
  place(el, tile);
  const ids = entityIds(tile);
  const e = ids.length === 1 ? entity(ids[0]) : null;
  const measured = tile.props.measurement ? entity(tile.props.measurement) : null;
  const values = [...(measured ? [measured] : []), ...ids.map(entity).filter(Boolean)].map((x) => `${x.state} ${x.unit}`.trim());
  for (const [cls, text] of [
    ['type', TYPES[tile.type]?.label ?? tile.type],
    ['name', tile.props.name || e?.name || (ids.length > 1 ? `${ids.length} ${TYPES[tile.type].multi}s` : ids[0] || '')],
    ['value', values.join(' · ')],
  ]) {
    const div = document.createElement('div');
    div.className = cls;
    div.textContent = text;
    el.append(div);
  }
  const handle = document.createElement('div');
  handle.className = 'handle';
  el.append(handle);
  el.addEventListener('pointerdown', (event) => startDrag(event, tile, el, event.target === handle ? 'resize' : 'move'));
  return el;
}

// The gap as the panel shows it, scaled to the board: half of it around each tile.
function place(el, tile) {
  const half = boardGap() / 2;
  el.style.left = `calc(${(tile.x / layout.columns) * 100}% + ${half}px)`;
  el.style.top = `calc(${(tile.y / layout.rows) * 100}% + ${half}px)`;
  el.style.width = `calc(${(tile.w / layout.columns) * 100}% - ${2 * half}px)`;
  el.style.height = `calc(${(tile.h / layout.rows) * 100}% - ${2 * half}px)`;
}

// The panel's gap in board pixels: the panel is laid out on a 960 × 600 canvas (600 × 960 in
// portrait) scaled to its screen, and the board is that canvas shrunk to fit.
function boardGap() {
  const across = layout.orientation.includes('portrait') ? 600 : 960;
  return (layout.theme.gap ?? 8) * (board.clientWidth || across) / across;
}

// Moves and resizes snap to whole cells and never overlap another tile.
function startDrag(event, tile, el, mode) {
  event.preventDefault();
  if (selectedId !== tile.id) {
    selectedId = tile.id;
    board.querySelectorAll('.tile').forEach((t) => t.classList.toggle('selected', t === el));
    renderTileForm();
    showTab('tile');
  }
  const rect = board.getBoundingClientRect();
  const cellW = rect.width / layout.columns;
  const cellH = rect.height / layout.rows;
  const start = { x: event.clientX, y: event.clientY, tile: { ...tile } };
  el.setPointerCapture(event.pointerId);

  const move = (e) => {
    const dx = Math.round((e.clientX - start.x) / cellW);
    const dy = Math.round((e.clientY - start.y) / cellH);
    const c = { ...tile };
    if (mode === 'move') {
      c.x = clamp(start.tile.x + dx, 0, layout.columns - tile.w);
      c.y = clamp(start.tile.y + dy, 0, layout.rows - tile.h);
    } else {
      c.w = clamp(start.tile.w + dx, 1, layout.columns - tile.x);
      c.h = clamp(start.tile.h + dy, 1, layout.rows - tile.y);
    }
    if ((c.x !== tile.x || c.y !== tile.y || c.w !== tile.w || c.h !== tile.h) && !collides(c)) {
      Object.assign(tile, { x: c.x, y: c.y, w: c.w, h: c.h });
      place(el, tile);
      setDirty(true);
    }
  };
  const end = () => {
    el.removeEventListener('pointermove', move);
    el.removeEventListener('pointerup', end);
    el.removeEventListener('pointercancel', end);
  };
  el.addEventListener('pointermove', move);
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
}

const clamp = (v, min, max) => Math.max(min, Math.min(max, v));

// ---- Tile form ----------------------------------------------------------------------------------

function renderTileForm() {
  const form = $('#tab-tile');
  const tile = selectedTile();
  if (!tile) {
    form.innerHTML = '<p class="muted">Click a tile to edit it, or add one with “Add tile”.</p>';
    return;
  }
  form.replaceChildren();
  const type = TYPES[tile.type];

  const typeSelect = select(Object.entries(TYPES).map(([k, t]) => [k, t.label]), tile.type);
  typeSelect.addEventListener('change', () => {
    tile.type = typeSelect.value;
    const keep = TYPES[tile.type].fields;
    tile.props = Object.fromEntries(Object.entries(tile.props).filter(([k]) => keep.includes(k)));
    setDirty(true);
    renderBoard();
    renderTileForm();
  });
  form.append(labelled('Type', typeSelect));

  for (const name of type.fields) {
    const field = type.overrides?.[name] || FIELDS[name];
    if (name === 'entity' && type.multi) {
      form.append(multiEntityEditor(tile, type));
      continue;
    }
    if (name === 'picture') {
      form.append(pictureEditor(tile));
      continue;
    }
    let input;
    if (field.options) {
      input = select(field.options, tile.props[name] ?? '');
    } else {
      input = document.createElement('input');
      input.type = field.type || 'text';
      input.placeholder = field.placeholder || '';
      input.value = tile.props[name] ?? '';
      if (name === 'entity') {
        fillEntityList(type.domains);
        input.setAttribute('list', 'entityList');
        input.autocomplete = 'off';
      }
      if (field.list === 'allEntities') fillAllEntities();
      if (field.list) {
        input.setAttribute('list', field.list);
        input.autocomplete = 'off';
      }
    }
    input.addEventListener(field.options ? 'change' : 'input', () => {
      if (input.value.trim()) tile.props[name] = input.value.trim();
      else delete tile.props[name];
      setDirty(true);
      renderBoard();
    });
    form.append(labelled(field.label, input));
  }
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'danger';
  remove.textContent = 'Delete tile';
  remove.addEventListener('click', () => {
    page().tiles = page().tiles.filter((t) => t.id !== tile.id);
    selectedId = null;
    setDirty(true);
    renderBoard();
    renderTileForm();
  });
  form.append(remove);
}

// ---- Pictures (Empty space tiles) --------------------------------------------------------------

// blob: URLs of pictures on the tablet, for previews: they need the key, so an <img src> can't load them.
const pictureUrls = new Map();

async function pictureUrl(name) {
  if (!pictureUrls.has(name)) {
    pictureUrls.set(name, fetch(`/api/picture/${encodeURIComponent(name)}`, { headers: { Authorization: `Bearer ${key}` } })
      .then((r) => (r.ok ? r.blob() : null)).then((b) => (b ? URL.createObjectURL(b) : null)));
  }
  return pictureUrls.get(name);
}

function pictureEditor(tile) {
  const wrap = document.createElement('div');
  wrap.className = 'picture-field';
  const title = document.createElement('div');
  title.className = 'muted';
  title.textContent = 'Picture (JPEG, PNG, WebP or SVG)';
  const preview = document.createElement('img');
  preview.className = 'picture-preview';
  preview.hidden = true;
  const state = document.createElement('span');
  state.className = 'muted';
  const choose = document.createElement('button');
  choose.type = 'button';
  choose.textContent = 'Choose picture…';
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.textContent = 'Remove';
  const file = document.createElement('input');
  file.type = 'file';
  file.accept = 'image/jpeg,image/png,image/webp,image/svg+xml,.svg';
  file.hidden = true;

  const show = () => {
    const name = tile.props.picture;
    clear.hidden = !name;
    state.textContent = name ? '' : 'None: the space stays empty.';
    preview.hidden = !name;
    if (name) pictureUrl(name).then((url) => { if (url && tile.props.picture === name) preview.src = url; });
  };
  choose.addEventListener('click', () => file.click());
  clear.addEventListener('click', () => {
    delete tile.props.picture;
    setDirty(true);
    renderBoard();
    show();
  });
  file.addEventListener('change', async () => {
    const picked = file.files[0];
    if (!picked) return;
    state.textContent = 'Uploading…';
    try {
      const response = await fetch('/api/picture', { method: 'PUT', headers: { Authorization: `Bearer ${key}` }, body: picked });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || response.statusText);
      tile.props.picture = result.picture;
      setDirty(true);
      renderBoard();
      show();
    } catch (e) {
      state.textContent = `Upload failed: ${e.message}`;
    }
    file.value = '';
  });
  const row = document.createElement('div');
  row.className = 'row';
  row.append(choose, clear, state);
  wrap.append(title, preview, row, file);
  show();
  return wrap;
}

// One row per entity (a graph line or a calendar), stored comma separated in props.entity and,
// for graphs, the matching labels in props.labels.
function multiEntityEditor(tile, type) {
  const wrap = document.createElement('div');
  const title = document.createElement('div');
  title.className = 'muted';
  title.textContent = { line: 'Lines', chip: 'Chips' }[type.multi] || 'Calendars';
  wrap.append(title);
  fillEntityList(type.domains);

  const sync = () => {
    const rows = [...wrap.querySelectorAll('.line')].map((row) => ({
      id: row.querySelector('.ent').value.trim(),
      label: row.querySelector('.lab')?.value.replaceAll(',', ' ').trim() ?? '',
    })).filter((r) => r.id);
    if (rows.length) tile.props.entity = rows.map((r) => r.id).join(',');
    else delete tile.props.entity;
    if (rows.some((r) => r.label)) tile.props.labels = rows.map((r) => r.label).join(',');
    else delete tile.props.labels;
    setDirty(true);
    renderBoard();
  };

  const add = document.createElement('button');
  add.type = 'button';
  add.textContent = { line: '+ Add line', chip: '+ Add chip' }[type.multi] || '+ Add calendar';

  const row = (id, label) => {
    const line = document.createElement('div');
    line.className = 'line';
    const ent = document.createElement('input');
    ent.className = 'ent';
    ent.value = id;
    ent.placeholder = FIELDS.entity.placeholder;
    ent.setAttribute('list', 'entityList');
    ent.autocomplete = 'off';
    ent.addEventListener('input', sync);
    line.append(ent);
    if (type.labels) {
      const lab = document.createElement('input');
      lab.className = 'lab';
      lab.value = label;
      lab.placeholder = 'Label';
      lab.addEventListener('input', sync);
      line.append(lab);
    }
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '×';
    remove.title = 'Remove';
    remove.addEventListener('click', () => {
      line.remove();
      if (!wrap.querySelector('.line')) wrap.insertBefore(row('', ''), add);
      sync();
    });
    line.append(remove);
    return line;
  };

  const ids = entityIds(tile);
  const labels = (tile.props.labels || '').split(',');
  (ids.length ? ids : ['']).forEach((id, i) => wrap.append(row(id, (labels[i] || '').trim())));
  add.addEventListener('click', () => wrap.insertBefore(row('', ''), add).querySelector('.ent').focus());
  wrap.append(add);
  return wrap;
}

function fillAllEntities() {
  $('#allEntities').replaceChildren(...entities.map((e) => {
    const option = document.createElement('option');
    option.value = e.id;
    option.label = `${e.name} (${e.state}${e.unit ? ' ' + e.unit : ''})`;
    return option;
  }));
}

function fillEntityList(domains) {
  const list = entities.filter((e) => !domains || domains.includes(e.id.split('.')[0]));
  $('#entityList').replaceChildren(...list.map((e) => {
    const option = document.createElement('option');
    option.value = e.id;
    option.label = `${e.name} (${e.state}${e.unit ? ' ' + e.unit : ''})`;
    return option;
  }));
}

function select(options, value) {
  const el = document.createElement('select');
  for (const [v, label] of options) {
    const option = document.createElement('option');
    option.value = v;
    option.textContent = label;
    el.append(option);
  }
  el.value = value;
  return el;
}

function labelled(text, input) {
  const label = document.createElement('label');
  label.append(text, input);
  return label;
}

$('#addType').replaceChildren(...Object.entries(TYPES).map(([k, t]) => new Option(t.label, k)));

$('#addTile').addEventListener('click', () => {
  const type = $('#addType').value;
  let [w, h] = TYPES[type].size;
  w = Math.min(w, layout.columns);
  h = Math.min(h, layout.rows);
  for (let y = 0; y + h <= layout.rows; y++) {
    for (let x = 0; x + w <= layout.columns; x++) {
      const tile = { id: uid(), type, x, y, w, h, props: {} };
      if (!collides(tile)) {
        page().tiles.push(tile);
        selectedId = tile.id;
        setDirty(true);
        renderBoard();
        renderTileForm();
        showTab('tile');
        return;
      }
    }
  }
  alert('No free space on this page for that tile. Make room or add a page.');
});

// ---- Pages and grid -----------------------------------------------------------------------------

$('#addPage').addEventListener('click', () => {
  layout.pages.push({ id: uid(), name: `Page ${layout.pages.length + 1}`, tiles: [] });
  pageIndex = layout.pages.length - 1;
  selectedId = null;
  setDirty(true);
  render();
  showTab('layout');
  $('#pageName').select();
});

function renderLayoutForm() {
  $('#pageName').value = page().name;
  $('#pageLeft').disabled = pageIndex === 0;
  $('#pageRight').disabled = pageIndex === layout.pages.length - 1;
  $('#pageDelete').disabled = layout.pages.length === 1;
  $('#orientation').value = layout.orientation;
  $('#theme').value = layout.theme.preset;
  $('#font').value = layout.theme.font || 'inter';
  $('#textScale').value = String(layout.theme.textScale ?? 1);
  $('#tileOpacity').value = layout.theme.tileOpacity ?? 100;
  $('#gap').value = String(layout.theme.gap ?? 8);
  $('#animations').checked = layout.theme.animations ?? true;
  const tiles = layout.theme.tiles || {};
  $('#cards').checked = layout.theme.cards ?? true;
  $('#radius').value = tiles.radius ?? 16;
  $('#cardPadding').value = tiles.padding ?? 12;
  $('#borderWidth').value = String(Math.min(tiles.borderWidth ?? 0, 3));
  $('#valueWeight').value = tiles.valueWeight || 'light';
  $('#shadow').value = String([0, 1, 4, 8].reduce((best, v) => (Math.abs(v - (tiles.shadow ?? 0)) < Math.abs(best - (tiles.shadow ?? 0)) ? v : best), 0));
  $('#titleWeight').value = tiles.titleWeight || 'medium';
  $('#toggleStyle').value = tiles.toggleStyle || 'fill';
  $('#clockWeight').value = tiles.clockWeight || 'thin';
  $('#titleDivider').checked = !!tiles.titleDivider;
  $('#graphGrid').checked = !!tiles.graphGrid;
  $('#listDividers').checked = !!tiles.listDividers;
  $('#calendarColors').checked = tiles.calendarColors ?? true;
  $('#tileLayout').value = tiles.tileLayout || 'stacked';
  $('#iconStyle').value = tiles.icons || 'none';
  $('#buttonShape').value = tiles.buttonShape || 'card';
  $('#thermostatStyle').value = tiles.thermostatStyle || 'dial';
  $('#sliderStyle').value = tiles.sliderStyle || 'bar';
  renderBackgroundForm();
  $('#accentDefault').checked = !layout.theme.accent;
  $('#accent').value = layout.theme.accent || THEME_ACCENTS[layout.theme.preset] || '#e0a030';
  $('#accent').disabled = !layout.theme.accent;
  $('#columns').value = layout.columns;
  $('#rows').value = layout.rows;
}

$('#pageName').addEventListener('input', () => {
  page().name = $('#pageName').value;
  setDirty(true);
  renderPageTabs();
});

function movePage(by) {
  const [p] = layout.pages.splice(pageIndex, 1);
  pageIndex += by;
  layout.pages.splice(pageIndex, 0, p);
  setDirty(true);
  render();
}
$('#pageLeft').addEventListener('click', () => movePage(-1));
$('#pageRight').addEventListener('click', () => movePage(1));

function deletePage() {
  if (!confirm(`Delete the page “${page().name}” and its tiles? (Nothing changes on the tablet until you save.)`)) return;
  layout.pages.splice(pageIndex, 1);
  pageIndex = Math.max(0, pageIndex - 1);
  selectedId = null;
  setDirty(true);
  render();
}
$('#pageDelete').addEventListener('click', deletePage);

$('#orientation').addEventListener('change', () => {
  layout.orientation = $('#orientation').value;
  setDirty(true);
  renderBoard();
});

// The panel's own accents, so the colour picker starts from what the tablet shows.
const THEME_ACCENTS = { dark: '#e0a030', graphite: '#5aa9e6', nord: '#88c0d0', forest: '#7bc67e', light: '#d48a12' };

function updateTheme() {
  layout.theme = {
    // Keep what the form doesn't show (a theme's graph colours, border colour).
    ...layout.theme,
    preset: $('#theme').value,
    accent: $('#accentDefault').checked ? '' : $('#accent').value,
    font: $('#font').value,
    textScale: parseFloat($('#textScale').value),
    tileOpacity: parseInt($('#tileOpacity').value, 10),
    gap: parseInt($('#gap').value, 10),
    animations: $('#animations').checked,
    cards: $('#cards').checked,
    tiles: {
      ...(layout.theme.tiles || {}),
      radius: parseInt($('#radius').value, 10),
      padding: parseInt($('#cardPadding').value, 10),
      borderWidth: parseInt($('#borderWidth').value, 10),
      valueWeight: $('#valueWeight').value,
      shadow: parseInt($('#shadow').value, 10),
      titleWeight: $('#titleWeight').value,
      toggleStyle: $('#toggleStyle').value,
      clockWeight: $('#clockWeight').value,
      titleDivider: $('#titleDivider').checked,
      graphGrid: $('#graphGrid').checked,
      listDividers: $('#listDividers').checked,
      calendarColors: $('#calendarColors').checked,
      tileLayout: $('#tileLayout').value,
      icons: $('#iconStyle').value,
      buttonShape: $('#buttonShape').value,
      thermostatStyle: $('#thermostatStyle').value,
      sliderStyle: $('#sliderStyle').value,
    },
  };
  setDirty(true);
  renderLayoutForm();
}
for (const id of ['#cards', '#borderWidth', '#valueWeight', '#shadow', '#titleWeight', '#toggleStyle', '#clockWeight', '#titleDivider', '#graphGrid', '#listDividers', '#calendarColors', '#tileLayout', '#iconStyle', '#buttonShape', '#thermostatStyle', '#sliderStyle']) $(id).addEventListener('change', () => { updateTheme(); renderBoard(); });
for (const id of ['#radius', '#cardPadding']) $(id).addEventListener('change', updateTheme);

// Picking an installed theme puts its whole look on the layout; a built-in preset only its colours.
$('#theme').addEventListener('change', () => {
  const theme = themes.find((t) => t.id === $('#theme').value);
  if (theme) applyTheme(theme);
  else updateTheme();
});
$('#font').addEventListener('change', updateTheme);
$('#textScale').addEventListener('change', updateTheme);
$('#tileOpacity').addEventListener('input', () => { updateTheme(); renderBoard(); });
$('#gap').addEventListener('change', () => { updateTheme(); renderBoard(); });
$('#animations').addEventListener('change', updateTheme);
$('#accent').addEventListener('input', updateTheme);
$('#accentDefault').addEventListener('change', () => {
  if (!$('#accentDefault').checked) $('#accent').value = THEME_ACCENTS[$('#theme').value];
  updateTheme();
});

// ---- Themes and templates -----------------------------------------------------------------------

let themes = [];
let templates = [];

async function loadThemes() {
  try {
    themes = (await api('GET', '/api/themes')).themes;
    templates = (await api('GET', '/api/templates')).templates;
  } catch (e) {
    $('#themeStatus').textContent = e.message;
    return;
  }
  $('#themeGroup').replaceChildren(...themes.map((t) => new Option(t.name, t.id)));
  if (layout) $('#theme').value = layout.theme.preset;
  $('#themeList').replaceChildren(...themes.map(themeRow));
  const bySource = Map.groupBy ? Map.groupBy(templates, (t) => t.source) : new Map([['', templates]]);
  $('#addTemplate').replaceChildren(new Option('+ Page from template…', ''), ...[...bySource].map(([source, list]) => {
    const group = document.createElement('optgroup');
    group.label = source;
    group.append(...list.map((t) => new Option(t.name, t.id)));
    return group;
  }));
}

function themeRow(theme) {
  const row = document.createElement('div');
  row.className = 'theme-row';
  const swatches = document.createElement('div');
  swatches.className = 'swatches';
  for (const c of ['background', 'card', 'accent', 'text']) {
    const s = document.createElement('span');
    s.style.background = theme.colors[c];
    swatches.append(s);
  }
  const text = document.createElement('div');
  text.className = 'grow';
  const name = document.createElement('b');
  name.textContent = theme.name + (theme.builtIn ? ' (built in)' : '');
  const about = document.createElement('div');
  about.className = 'muted';
  about.textContent = [theme.description, `${theme.pictures.length} pictures, ${theme.templates.length} templates`].filter(Boolean).join(' · ');
  text.append(name, about);
  const apply = button('Use', () => { applyTheme(theme); $('#theme').value = theme.id; });
  const get = button('Download', () => downloadFile(`/api/themes/${theme.id}.zip`, `${theme.id}.zip`));
  row.append(swatches, text, apply, get);
  if (!theme.builtIn) {
    row.append(button('Delete', async () => {
      if (!confirm(`Uninstall the theme “${theme.name}”? Tiles using its pictures will show nothing.`)) return;
      await api('DELETE', `/api/themes/${theme.id}`).catch((e) => { $('#themeStatus').textContent = e.message; });
      loadThemes();
    }, 'danger'));
  }
  return row;
}

function button(label, onClick, cls) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = label;
  if (cls) b.className = cls;
  b.addEventListener('click', onClick);
  return b;
}

function applyTheme(theme) {
  layout.theme = structuredClone(theme.settings);
  if (theme.background) layout.background = structuredClone(theme.background);
  setDirty(true);
  render();
  $('#themeStatus').textContent = `${theme.name} is on the layout. Compile & save to show it on the panel.`;
}

async function downloadFile(path, name) {
  const response = await fetch(path, { headers: { Authorization: `Bearer ${key}` } });
  if (!response.ok) {
    $('#themeStatus').textContent = (await response.json().catch(() => ({}))).error || response.statusText;
    return;
  }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

$('#themeImport').addEventListener('click', () => $('#themeFile').click());
$('#themeFile').addEventListener('change', async () => {
  const file = $('#themeFile').files[0];
  if (!file) return;
  $('#themeStatus').textContent = 'Installing…';
  try {
    const response = await fetch('/api/themes', { method: 'POST', headers: { Authorization: `Bearer ${key}` }, body: file });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || response.statusText);
    $('#themeStatus').textContent = `Installed ${result.name}: ${result.pictures.length} pictures, ${result.templates.length} templates. Pick it under Theme to use it.`;
    loadThemes();
  } catch (e) {
    $('#themeStatus').textContent = `Couldn't install: ${e.message}`;
  }
  $('#themeFile').value = '';
});

$('#themeExport').addEventListener('click', () => {
  if (dirty && !confirm('The export is of the saved layout; unsaved changes aren\'t in it. Export anyway?')) return;
  const name = prompt('Name for the theme', 'My theme');
  if (!name) return;
  const id = name.toLowerCase().normalize('NFD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'my-theme';
  downloadFile(`/api/themes/current.zip?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}`, `${id}.zip`);
});

// A page from a template: the tablet places tiles that have no position (the layout check does it).
$('#addTemplate').addEventListener('change', async () => {
  const id = $('#addTemplate').value;
  $('#addTemplate').value = '';
  if (!id) return;
  try {
    const template = await api('GET', `/api/templates/${id}`);
    const page = { id: uid(), name: template.name, tiles: template.tiles.map((t) => ({ ...t, id: uid(), props: { ...(t.props || {}) } })) };
    const checked = await api('POST', '/api/layout/check', { ...layout, pages: [...layout.pages, page] });
    layout.pages.push(checked.layout.pages[checked.layout.pages.length - 1]);
    pageIndex = layout.pages.length - 1;
    selectedId = null;
    setDirty(true);
    render();
  } catch (e) {
    alert(`Couldn't add the template: ${e.message}`);
  }
});

// ---- Background ---------------------------------------------------------------------------------

const DEFAULT_BACKGROUND = { type: 'theme', color: '#202020', colors: ['#1d2b3a', '#3a1d2e'], angle: 135, animate: false, image: '', mode: 'fill', dim: 0 };
const background = () => (layout.background = { ...DEFAULT_BACKGROUND, ...(layout.background || {}) });
let bgImageUrl = null; // a blob: URL for the board preview; the picture needs the key to load
let bgImageFor = null;

function renderBackgroundForm() {
  const b = background();
  $('#bgType').value = b.type;
  $('#bgColor').value = b.color;
  $('#bgColor1').value = b.colors[0];
  $('#bgColor2').value = b.colors[1];
  $('#bgAngle').value = String(b.angle);
  $('#bgAnimate').checked = b.animate;
  $('#bgMode').value = b.mode;
  $('#bgDim').value = b.dim;
  $('#bgImageName').textContent = b.image ? 'Picture on the tablet' : 'No picture yet';
  $('#bgColorRow').hidden = b.type !== 'color';
  $('#bgGradientRow').hidden = b.type !== 'gradient';
  $('#bgImageRow').hidden = b.type !== 'image';
}

function updateBackground() {
  const b = background();
  Object.assign(b, {
    type: $('#bgType').value,
    color: $('#bgColor').value,
    colors: [$('#bgColor1').value, $('#bgColor2').value],
    angle: parseInt($('#bgAngle').value, 10),
    animate: $('#bgAnimate').checked,
    mode: $('#bgMode').value,
    dim: parseInt($('#bgDim').value, 10),
  });
  setDirty(true);
  renderBackgroundForm();
  renderBoard();
}
for (const id of ['#bgType', '#bgAngle', '#bgMode', '#bgAnimate']) $(id).addEventListener('change', updateBackground);
for (const id of ['#bgColor', '#bgColor1', '#bgColor2', '#bgDim']) $(id).addEventListener('input', updateBackground);

$('#bgUpload').addEventListener('click', () => $('#bgFile').click());
$('#bgFile').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  $('#bgImageName').textContent = 'Uploading…';
  try {
    const response = await fetch('/api/background/image', { method: 'PUT', headers: { Authorization: `Bearer ${key}` }, body: file });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || response.statusText);
    background().image = data.image;
    background().type = 'image';
    setDirty(true);
    renderBackgroundForm();
    renderBoard();
  } catch (e) {
    alert(`Upload failed: ${e.message}`);
    renderBackgroundForm();
  }
});

async function loadBackgroundPreview(name) {
  if (bgImageFor === name) return;
  bgImageFor = name;
  const response = await fetch(`/api/background/image/${name}`, { headers: { Authorization: `Bearer ${key}` } });
  if (!response.ok || bgImageFor !== name) return;
  if (bgImageUrl) URL.revokeObjectURL(bgImageUrl);
  bgImageUrl = URL.createObjectURL(await response.blob());
  renderBoard();
}

// The board shows the background as the tablet draws it (CSS), under the grid lines.
function boardBackground() {
  const b = background();
  const dim = b.dim ? `linear-gradient(rgba(0,0,0,${b.dim / 100}), rgba(0,0,0,${b.dim / 100}))` : null;
  const layers = [];
  if (dim) layers.push({ image: dim, size: '100% 100%', repeat: 'no-repeat', position: 'center' });
  if (b.type === 'gradient') {
    layers.push({ image: `linear-gradient(${b.angle + 90}deg, ${b.colors[0]}, ${b.colors[1]})`, size: '100% 100%', repeat: 'no-repeat', position: 'center' });
  } else if (b.type === 'image' && b.image) {
    loadBackgroundPreview(b.image);
    if (bgImageUrl && bgImageFor === b.image) {
      const size = { fill: 'cover', fit: 'contain', stretch: '100% 100%', tile: 'auto', center: 'auto' }[b.mode];
      layers.push({ image: `url(${bgImageUrl})`, size, repeat: b.mode === 'tile' ? 'repeat' : 'no-repeat', position: b.mode === 'tile' ? 'top left' : 'center' });
    }
  }
  const color = b.type === 'color' ? b.color : (THEME_BACKGROUNDS[layout.theme.preset] || '#000');
  return { layers, color };
}

const THEME_BACKGROUNDS = { dark: '#000000', graphite: '#16181c', nord: '#2e3440', forest: '#0f1a14', light: '#f2f2f0' };

// A new grid scales every tile to it, keeping the layout's shape.
function regrid() {
  const columns = clamp(parseInt($('#columns').value, 10) || layout.columns, 1, 48);
  const rows = clamp(parseInt($('#rows').value, 10) || layout.rows, 1, 48);
  if (columns === layout.columns && rows === layout.rows) return;
  const sx = columns / layout.columns;
  const sy = rows / layout.rows;
  for (const p of layout.pages) {
    for (const t of p.tiles) {
      t.w = clamp(Math.round(t.w * sx), 1, columns);
      t.h = clamp(Math.round(t.h * sy), 1, rows);
      t.x = clamp(Math.round(t.x * sx), 0, columns - t.w);
      t.y = clamp(Math.round(t.y * sy), 0, rows - t.h);
    }
  }
  layout.columns = columns;
  layout.rows = rows;
  setDirty(true);
  renderBoard();
}
$('#columns').addEventListener('change', regrid);
$('#rows').addEventListener('change', regrid);

// ---- Import and export -------------------------------------------------------------------------

function download(name, data) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

const fileSlug = (text) => text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'page';

$('#pageExport').addEventListener('click', () => {
  download(`biwurth-page-${fileSlug(page().name)}.json`, {
    type: 'biwurth-page', version: 1, columns: layout.columns, rows: layout.rows, page: page(),
  });
});

$('#pageImport').addEventListener('click', () => $('#pageFile').click());

// An imported page becomes a new page, scaled to this grid, with fresh ids.
$('#pageFile').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    // 'panel-page': files from before the app was named biwurth.
    if (!['biwurth-page', 'panel-page'].includes(data.type) || !data.page) throw new Error('Not a biwurth page file');
    const sx = layout.columns / (data.columns || layout.columns);
    const sy = layout.rows / (data.rows || layout.rows);
    const tiles = (data.page.tiles || []).filter((t) => TYPES[t.type]).map((t) => {
      const w = clamp(Math.round(t.w * sx), 1, layout.columns);
      const h = clamp(Math.round(t.h * sy), 1, layout.rows);
      return {
        ...t, id: uid(), w, h,
        x: clamp(Math.round(t.x * sx), 0, layout.columns - w),
        y: clamp(Math.round(t.y * sy), 0, layout.rows - h),
        props: { ...(t.props || {}) },
      };
    });
    layout.pages.push({ id: uid(), name: data.page.name || 'Imported', tiles });
    pageIndex = layout.pages.length - 1;
    selectedId = null;
    setDirty(true);
    render();
  } catch (e) {
    alert(`Can't import: ${e.message}`);
  }
});

$('#backupDownload').addEventListener('click', async () => {
  try {
    const secrets = $('#backupSecrets').checked;
    const data = await api('GET', `/api/backup${secrets ? '?secrets=1' : ''}`);
    download(`biwurth-backup-${new Date().toISOString().slice(0, 10)}.json`, data);
  } catch (e) {
    alert(`Backup failed: ${e.message}`);
  }
});

$('#backupRestore').addEventListener('click', () => {
  if (dirty && !confirm('Restoring replaces the layout, including your unsaved changes. Continue?')) return;
  $('#backupFile').click();
});

$('#backupFile').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    if (!['biwurth-backup', 'panel-backup'].includes(data.type)) throw new Error('Not a biwurth backup file');
    if (!confirm(`Restore the backup from ${data.created?.slice(0, 10) ?? 'this file'}? It replaces the current settings.`)) return;
    const result = await api('PUT', '/api/backup', data);
    await load();
    if (result.report) showReport(result.report);
  } catch (e) {
    alert(`Restore failed: ${e.message}`);
  }
});

// ---- Updates -------------------------------------------------------------------------------------

async function refreshUpdate(action) {
  try {
    const u = action ? await api('POST', `/api/update/${action}`) : await api('GET', '/api/update');
    renderUpdate(u);
  } catch (e) {
    $('#updateInfo').textContent = e.message;
  }
}

function renderUpdate(u) {
  const last = u.lastCheck ? new Date(u.lastCheck).toLocaleString() : 'never';
  $('#updateVersion').textContent = `Installed ${u.installed}${u.canUpdate ? '' : ' (debug build: updates need a release build)'} · last check ${last}`;
  const info = $('#updateInfo');
  info.replaceChildren();
  const line = (text) => { const p = document.createElement('p'); p.textContent = text; info.append(p); };
  const r = u.release;
  ({
    idle: () => line('Not checked yet.'),
    checking: () => line('Checking…'),
    upToDate: () => line(`Up to date${u.latest ? ` (latest release ${u.latest})` : ''}.`),
    available: () => line(`biwurth ${r.version} is available${u.skipped ? ' (skipped)' : ''}.`),
    downloading: () => line(`Downloading ${r.version}: ${Math.round(u.fraction * 100)} %`),
    installing: () => line(`Installing ${r.version}… ${u.deviceOwner ? '' : 'Confirm on the tablet.'}`),
    needsPermission: () => line('Android needs permission for biwurth to install apps: allow it on the tablet, then Install again.'),
    failed: () => line(u.message),
  }[u.state] || (() => {}))();
  if (r && r.changes?.length) {
    for (const change of r.changes) {
      const h = document.createElement('div');
      h.className = 'muted';
      h.textContent = `What's new in ${change.version}`;
      const ul = document.createElement('ul');
      ul.replaceChildren(...change.lines.map((t) => { const li = document.createElement('li'); li.textContent = t; return li; }));
      info.append(h, ul);
    }
  }
  const actionable = ['available', 'failed', 'needsPermission'].includes(u.state) && r;
  $('#updateInstall').hidden = !actionable || !u.canUpdate;
  $('#updateSkip').hidden = !(u.state === 'available' && !u.skipped);
  $('#updatePermission').hidden = u.state !== 'needsPermission';
  $('#updateAuto').checked = u.check;
  $('#updateAutoInstall').checked = u.autoInstall;
  $('#updateAutoInstallNote').hidden = u.silentInstall !== false;
  $('#updatePre').checked = u.prerelease;
  if (['checking', 'downloading', 'installing'].includes(u.state)) setTimeout(() => refreshUpdate(), 1500);
}

$('#updateCheck').addEventListener('click', () => refreshUpdate('check'));
$('#updateInstall').addEventListener('click', () => refreshUpdate('install'));
$('#updateSkip').addEventListener('click', () => refreshUpdate('skip'));
$('#updatePermission').addEventListener('click', () => refreshUpdate('permission'));
for (const [id, field] of [['#updateAuto', 'check'], ['#updateAutoInstall', 'autoInstall'], ['#updatePre', 'prerelease']]) {
  $(id).addEventListener('change', async () => renderUpdate(await api('PUT', '/api/update/settings', { [field]: $(id).checked })));
}

// ---- New tablet setup (QR provisioning) ---------------------------------------------------------

$('#provQrBtn').addEventListener('click', async () => {
  try {
    const result = await api('POST', '/api/provisioning', {
      wifiSsid: $('#provSsid').value.trim(),
      wifiPassword: $('#provPassword').value,
    });
    // The SVG comes from the tablet itself (ZXing), so this page needs no internet.
    $('#provQr').innerHTML = result.svg;
    $('#provInfo').textContent = result.debugBuild
      ? `This panel runs a debug build: its key matches no GitHub release, so a tablet can't use this code.`
      : `Installs biwurth ${result.version} from ${result.apk}, signed with key ${result.checksum}.`;
  } catch (e) {
    $('#provInfo').textContent = e.message;
  }
});

// ---- Data sources ---------------------------------------------------------------------------------

// Per source type: its settings, and the columns of its field rows.
const SOURCE_TYPES = {
  homeassistant: {
    label: 'Home Assistant',
    settings: [['url', 'URL', 'http://homeassistant.local:8123'], ['token', 'Long-lived access token', '', 'password'],
      ['primary', "Primary: its entities keep their own ids (sensor.x)", '', 'checkbox']],
    columns: [],
  },
  http: {
    label: 'HTTP / JSON',
    settings: [['url', 'URL', 'https://api.example.com/data'], ['interval', 'Every (s)', '60'], ['headers', 'Headers (Name: value per line)', 'Authorization: Bearer …', 'textarea']],
    columns: [['name', 'Name'], ['path', 'JSON path', 'current.temperature_2m'], ['unit', 'Unit']],
  },
  mqtt: {
    label: 'MQTT topics (broker from the MQTT tab)',
    settings: [],
    columns: [['name', 'Name'], ['topic', 'Topic', 'zigbee2mqtt/sauna'], ['path', 'JSON path', 'temperature'], ['unit', 'Unit'],
      ['commandTopic', 'Command topic (button)'], ['payloadOn', 'On', 'ON'], ['payloadOff', 'Off', 'OFF']],
  },
  status: {
    label: 'Status checks',
    settings: [['interval', 'Every (s)', '60']],
    columns: [['name', 'Name'], ['target', 'URL or host:port', 'http://nas.local or 192.168.1.1:22']],
  },
  modbus: {
    label: 'Modbus TCP',
    settings: [['host', 'Host', '192.168.1.50'], ['port', 'Port', '502'], ['unit', 'Unit id', '1'], ['interval', 'Every (s)', '10']],
    columns: [['name', 'Name'], ['kind', 'Kind', '', ['holding', 'input', 'coil', 'discrete']], ['address', 'Address', '0'],
      ['type', 'Type', '', ['int16', 'uint16', 'int32', 'uint32', 'float32']], ['order', 'Words', '', ['big', 'little']], ['scale', 'Scale', '1'], ['unit', 'Unit']],
  },
};

let sources = [];
const slug = (text) => text.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');

async function loadSources() {
  try {
    const data = await api('GET', '/api/sources');
    sources = data.sources;
    renderSources();
  } catch (e) {
    $('#sourceList').textContent = e.message;
  }
  if (!$('#tab-sources').hidden) setTimeout(() => !$('#tab-sources').hidden && $('#sourceModal').hidden && loadSources(), 5000);
}

// Numbers where the tablet expects numbers, ids from names.
function normalized(list) {
  return list.map((source) => {
    const s = structuredClone(source);
    for (const key of ['interval', 'port', 'unit', 'keepDays']) if (s.settings[key] !== undefined && s.settings[key] !== '') s.settings[key] = Number(s.settings[key]);
    for (const f of s.fields) for (const key of ['address', 'scale']) if (f[key] !== undefined && f[key] !== '') f[key] = Number(f[key]);
    s.id = slug(s.id || s.name);
    return s;
  });
}

async function saveSources(list) {
  const data = await api('PUT', '/api/sources', normalized(list));
  sources = data.sources;
  renderSources();
  setTimeout(() => { loadSources(); refreshEntities(); }, 3000);
}

function renderSources() {
  $('#sourceList').replaceChildren(...sources.map(sourceRow));
}

// One compact row per source: on/off, name, type and status, Edit; × for all but Home Assistant.
function row(enabled, onToggle, name, detail, onEdit, onDelete) {
  const el = document.createElement('div');
  el.className = 'source-row';
  const toggle = document.createElement('input');
  toggle.type = 'checkbox';
  toggle.checked = enabled;
  toggle.title = 'On';
  toggle.addEventListener('change', () => onToggle(toggle.checked));
  const what = document.createElement('div');
  what.className = 'what';
  const title = document.createElement('b');
  title.textContent = name;
  const status = document.createElement('div');
  status.className = 'status';
  status.textContent = detail;
  status.title = detail;
  what.append(title, status);
  const edit = document.createElement('button');
  edit.type = 'button';
  edit.textContent = 'Edit';
  edit.addEventListener('click', onEdit);
  el.append(toggle, what, edit);
  if (onDelete) {
    const del = document.createElement('button');
    del.type = 'button';
    del.textContent = '×';
    del.title = 'Delete source';
    del.addEventListener('click', onDelete);
    el.append(del);
  }
  return el;
}

function sourceRow(source, index) {
  return row(
    source.enabled !== false,
    (on) => saveSources(sources.map((s, i) => (i === index ? { ...s, enabled: on } : s))),
    source.name,
    `${SOURCE_TYPES[source.type].label}${source.primary ? ' (primary)' : ''} · ${source.status || 'not saved yet'}`,
    () => openSourceModal(index),
    () => {
      if (confirm(`Delete the source “${source.name}”? Tiles showing its values will show “–”.`)) {
        saveSources(sources.filter((_, i) => i !== index));
      }
    },
  );
}

// ---- Source settings window

// Edits a copy; nothing changes until Save.
function openSourceModal(index) {
  const isNew = index === null;
  const draft = isNew
    ? (() => {
      const type = $('#sourceType').value;
      const name = type === 'homeassistant' ? 'Home Assistant' : `${SOURCE_TYPES[type].label.split(' ')[0]} ${sources.length + 1}`;
      return { type, name, enabled: true, settings: {}, fields: SOURCE_TYPES[type].columns.length ? [{}] : [] };
    })()
    : structuredClone(sources[index]);
  const box = $('#sourceModalBox');
  const render = () => {
    const title = document.createElement('h2');
    title.textContent = `${isNew ? 'New source' : draft.name}: ${SOURCE_TYPES[draft.type].label}`;
    const actions = document.createElement('div');
    actions.className = 'row';
    const save = document.createElement('button');
    save.type = 'button';
    save.className = 'primary';
    save.textContent = 'Save';
    save.addEventListener('click', async () => {
      try {
        await saveSources(isNew ? [...sources, draft] : sources.map((s, i) => (i === index ? draft : s)));
        $('#sourceModal').hidden = true;
      } catch (e) {
        alert(`Not saved: ${e.message}`);
      }
    });
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.textContent = 'Cancel';
    cancel.addEventListener('click', () => { $('#sourceModal').hidden = true; });
    actions.append(save, cancel);
    box.replaceChildren(title, sourceForm(draft, render), actions);
  };
  render();
  $('#sourceModal').hidden = false;
}

function input(value, placeholder, onChange, options) {
  let el;
  if (options) {
    el = select(options.map((o) => [o, o]), value || options[0]);
  } else {
    el = document.createElement('input');
    el.value = value ?? '';
    el.placeholder = placeholder || '';
  }
  el.addEventListener(options ? 'change' : 'input', () => onChange(el.value));
  return el;
}

// A source's settings, telemetry and fields; [rerender] after adding or removing a field.
function sourceForm(source, rerender) {
  const type = SOURCE_TYPES[source.type];
  const form = document.createElement('div');
  form.append(labelled('Name', input(source.name, 'Name', (v) => { source.name = v; updateIds(); })));
  if (source.status) {
    const status = document.createElement('p');
    status.className = 'muted';
    status.textContent = `Status: ${source.status}`;
    form.append(status);
  }

  source.settings = source.settings || {};
  for (const [key, label, placeholder, kind] of type.settings) {
    let el;
    if (kind === 'checkbox') {
      const check = document.createElement('label');
      check.className = 'check';
      const box = document.createElement('input');
      box.type = 'checkbox';
      box.checked = !!source.settings[key];
      box.addEventListener('change', () => { source.settings[key] = box.checked; });
      check.append(box, label);
      form.append(check);
      continue;
    }
    if (kind === 'password') {
      // Tokens never come back from the tablet: empty keeps the saved one.
      el = input('', source.settings.tokenSet ? 'Saved (leave empty to keep it)' : 'Paste the token', (v) => { source.settings[key] = v; });
      el.type = 'password';
      el.autocomplete = 'off';
    } else if (kind === 'textarea') {
      el = document.createElement('textarea');
      el.rows = 2;
      el.value = source.settings[key] ?? '';
      el.placeholder = placeholder;
      el.addEventListener('input', () => { source.settings[key] = el.value; });
    } else {
      el = input(source.settings[key], placeholder, (v) => { source.settings[key] = v; });
    }
    form.append(labelled(label, el));
  }

  // Telemetry: every source can record its readings and what happened, as CSV per day.
  const telemetryRow = document.createElement('div');
  telemetryRow.className = 'row';
  const mode = select([['off', 'Off'], ['changes', 'Changes'], ['every', 'Every reading']], source.settings.telemetry || 'off');
  mode.addEventListener('change', () => { source.settings.telemetry = mode.value; });
  const keep = input(source.settings.keepDays ?? 30, '30', (v) => { source.settings.keepDays = v; });
  keep.type = 'number';
  telemetryRow.append(labelled('Record telemetry', mode), labelled('Keep (days)', keep));
  form.append(telemetryRow);
  if (source.telemetryDays) {
    const files = document.createElement('div');
    files.className = 'row';
    const info = document.createElement('span');
    info.className = 'muted';
    info.textContent = `${source.telemetryDays} day${source.telemetryDays === 1 ? '' : 's'}, ${(source.telemetryBytes / 1024).toFixed(0)} KB · Download CSV:`;
    files.append(info);
    for (const [days, label] of [[1, 'today'], [7, '7 days'], [3650, 'all']]) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.addEventListener('click', () => downloadTelemetry(source, days));
      files.append(b);
    }
    form.append(files);
  }

  const table = document.createElement('table');
  const header = document.createElement('tr');
  for (const [, label] of type.columns) {
    const th = document.createElement('td');
    th.className = 'muted';
    th.textContent = label;
    header.append(th);
  }
  header.append(document.createElement('td'));
  table.append(header);
  source.fields = source.fields || [];
  source.fields.forEach((field, row) => {
    const tr = document.createElement('tr');
    for (const [key, , placeholder, options] of type.columns) {
      const td = document.createElement('td');
      td.append(input(field[key], placeholder, (v) => { field[key] = v; updateIds(); }, options));
      if (options && !field[key]) field[key] = options[0];
      tr.append(td);
    }
    const td = document.createElement('td');
    const del = document.createElement('button');
    del.type = 'button';
    del.textContent = '×';
    del.addEventListener('click', () => { source.fields.splice(row, 1); rerender(); });
    td.append(del);
    tr.append(td);
    table.append(tr);
  });
  const add = document.createElement('button');
  add.type = 'button';
  add.textContent = '+ Field';
  add.addEventListener('click', () => { source.fields.push({}); rerender(); });

  const ids = document.createElement('div');
  ids.className = 'ids';
  function updateIds() {
    const id = slug(source.id || source.name || '');
    ids.textContent = source.fields.filter((f) => slug(f.name || '')).map((f) => `src.${id}.${slug(f.name)}`).join('  ');
  }
  updateIds();
  if (type.columns.length) form.append(table, add, ids);
  else if (source.type === 'homeassistant') {
    const hint = document.createElement('p');
    hint.className = 'muted';
    hint.textContent = `Create the token in Home Assistant: your profile → Security → Long-lived access tokens. It stays on the tablet. ${
      source.primary ? 'This is the primary Home Assistant: its entities keep their own ids.' : `Its entities are src.${slug(source.id || source.name || '')}.<entity id>.`}`;
    form.append(hint);
  }
  return form;
}

async function downloadTelemetry(source, days) {
  const response = await fetch(`/api/telemetry/${source.id}?days=${days}`, { headers: { Authorization: `Bearer ${key}` } });
  if (!response.ok) { alert('Download failed'); return; }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = `biwurth-${source.id}-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

$('#sourceAdd').addEventListener('click', () => openSourceModal(null));

// ---- Log ----------------------------------------------------------------------------------------

async function logText(path) {
  const response = await fetch(path, { headers: { Authorization: `Bearer ${key}` } });
  if (response.status === 401) { showLogin(); throw new Error('Wrong key'); }
  return response.text();
}

async function loadLog() {
  try {
    const text = await logText('/api/logs');
    const pre = $('#logText');
    pre.textContent = text || 'Empty.';
    pre.scrollTop = pre.scrollHeight;
  } catch (e) {
    $('#logText').textContent = e.message;
  }
}

$('#logRefresh').addEventListener('click', loadLog);
$('#logDownload').addEventListener('click', async () => {
  const url = URL.createObjectURL(new Blob([await logText('/api/logs/all')], { type: 'text/plain' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `biwurth-log-${new Date().toISOString().slice(0, 16).replace(':', '')}.txt`;
  a.click();
  URL.revokeObjectURL(url);
});
$('#logClear').addEventListener('click', async () => {
  if (!confirm('Clear the log on the tablet?')) return;
  await api('DELETE', '/api/logs');
  loadLog();
});

// ---- Settings tabs ------------------------------------------------------------------------------

function showTab(name) {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('.tab').forEach((t) => { t.hidden = t.id !== `tab-${name}`; });
  if (name === 'updates') refreshUpdate();
  if (name === 'sources') loadSources();
  if (name === 'cameras') loadCameraTiles();
  document.body.classList.toggle('wide-aside-log', name === 'log');
  if (name === 'log') loadLog();
}
document.querySelectorAll('#tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

function fillSettings() {

  const m = config.mqtt;
  $('#mqttName').value = m.deviceName;
  $('#mqttHost').value = m.host;
  $('#mqttPort').value = m.port;
  $('#mqttUser').value = m.username;
  $('#mqttPassword').value = '';
  $('#mqttPassword').placeholder = m.passwordSet ? 'Saved (leave empty to keep it)' : '';
  $('#mqttTls').checked = m.tls;

  const c = config.cameras;
  $('#go2rtcUrl').value = c.go2rtcUrl;
  $('#rtspPort').value = c.rtspPort;
  $('#maxLive').value = String(c.maxLive);
  $('#keepLiveAsleep').checked = c.keepLiveAsleep;
  loadCameraList();

  const s = config.screen;
  $('#idle').value = String(s.idleSeconds);
  $('#sleepMode').value = s.sleepMode;
  $('#sleepMode option[value=SCREEN_OFF]').disabled = !s.adminActive;
  $('#adminRequest').hidden = s.adminActive;
  $('#brightnessSystem').checked = s.brightness < 0;
  $('#brightness').value = s.brightness < 0 ? 80 : s.brightness;
  $('#brightness').disabled = s.brightness < 0;
  $('#showFps').checked = !!s.showFps;
}

async function saveSettings(form, path, body) {
  const button = form.querySelector('button.primary');
  button.disabled = true;
  try {
    const fresh = await api('PUT', path, body);
    config.mqtt = fresh.mqtt;
    config.screen = fresh.screen;
    config.cameras = fresh.cameras;
    fillSettings();
    setTimeout(() => { refreshStatus(); refreshEntities(); }, 2000);
  } catch (e) {
    alert(`Not saved: ${e.message}`);
  } finally {
    button.disabled = false;
  }
}


$('#tab-mqtt').addEventListener('submit', (event) => {
  event.preventDefault();
  saveSettings(event.target, '/api/mqtt', {
    deviceName: $('#mqttName').value.trim(),
    host: $('#mqttHost').value.trim(),
    port: parseInt($('#mqttPort').value, 10) || 1883,
    username: $('#mqttUser').value,
    password: $('#mqttPassword').value,
    tls: $('#mqttTls').checked,
  });
});

async function loadCameraList() {
  if (!config.cameras.go2rtcUrl) {
    $('#cameraStatus').textContent = '';
    return;
  }
  try {
    const streams = await api('GET', '/api/cameras');
    $('#cameraList').replaceChildren(...streams.map((name) => new Option(name, name)));
    $('#cameraStatus').textContent = `go2rtc: ${streams.length} streams`;
  } catch (e) {
    $('#cameraStatus').textContent = e.message;
  }
}

// Each camera tile's state (live, retrying, snapshot only…), refreshed while the tab is open.
async function loadCameraTiles() {
  try {
    const cameras = await api('GET', '/api/cameras/status');
    const box = $('#cameraTiles');
    if (!cameras.length) {
      box.textContent = 'No camera tiles.';
    } else {
      box.replaceChildren(...cameras.map((c) => {
        const row = document.createElement('div');
        row.className = 'source';
        const name = document.createElement('b');
        name.textContent = c.name;
        const status = document.createElement('div');
        status.textContent = c.status;
        status.style.color = c.status.startsWith('Error') ? 'var(--danger)' : 'var(--text)';
        const url = document.createElement('div');
        url.className = 'ids';
        url.textContent = c.url;
        row.append(name, status, url);
        return row;
      }));
    }
  } catch (e) { /* the status line shows connection problems */ }
  if (!$('#tab-cameras').hidden) setTimeout(loadCameraTiles, 3000);
}

$('#tab-cameras').addEventListener('submit', (event) => {
  event.preventDefault();
  saveSettings(event.target, '/api/cameras', {
    go2rtcUrl: $('#go2rtcUrl').value.trim(),
    rtspPort: parseInt($('#rtspPort').value, 10) || 8554,
    maxLive: parseInt($('#maxLive').value, 10),
    keepLiveAsleep: $('#keepLiveAsleep').checked,
  });
});

$('#brightnessSystem').addEventListener('change', () => {
  $('#brightness').disabled = $('#brightnessSystem').checked;
});

$('#tab-screen').addEventListener('submit', (event) => {
  event.preventDefault();
  saveSettings(event.target, '/api/screen', {
    idleSeconds: parseInt($('#idle').value, 10),
    sleepMode: $('#sleepMode').value,
    brightness: $('#brightnessSystem').checked ? -1 : parseInt($('#brightness').value, 10),
    showFps: $('#showFps').checked,
  });
});

// ---- Presence ----------------------------------------------------------------------------------

let presence = null;

function renderPresence(p) {
  presence = p;
  $('#presenceOn').checked = p.plan.enabled;
  $('#presenceKeep').checked = p.plan.keepAwake;
  $('#presenceSensitivity').value = String(p.plan.sensitivity);
  const live = p.live;
  $('#presenceLive').textContent = (live.running ? `Watching · change ${live.change} %` : 'Not watching') +
    (live.brightness != null ? ` · room ${live.brightness} %` : '') +
    (live.motion ? ' · motion now' : '') +
    (live.lastMotion ? ` · last motion ${new Date(live.lastMotion).toLocaleTimeString()} (${live.lastSource})` : '') +
    (p.sleepMode === 'SCREEN_OFF' ? ' · needs the Backlight off sleep mode' : '') +
    (!live.cameraAllowed ? ' · camera not allowed' : '');
  const notes = p.plan.notes.length ? p.plan.notes : ['Not calibrated yet: it works with general settings until then.'];
  $('#presencePlan').replaceChildren(...notes.map((n) => Object.assign(document.createElement('li'), { textContent: n })));
  const inv = p.inventory;
  $('#presenceInventory').replaceChildren(
    ...inv.cameras.map((c) => Object.assign(document.createElement('li'), { textContent: `Camera ${c.id}: ${c.facing}, ${c.smallest ?? '?'} at ${c.slowestFps ?? '?'} fps` })),
    ...inv.sensors.map((x) => Object.assign(document.createElement('li'), { textContent: `${x.kind}: ${x.name} (${x.vendor})${x.wakeUp ? ', wake-up' : ''}` })),
    Object.assign(document.createElement('li'), { textContent: `Microphone: ${inv.microphone ? 'yes' : 'no'}` }),
  );
}

async function refreshPresence() {
  try { renderPresence(await api('GET', '/api/presence')); } catch (e) { $('#presenceStatus').textContent = e.message; }
}

async function savePresence() {
  renderPresence(await api('PUT', '/api/presence', {
    enabled: $('#presenceOn').checked, keepAwake: $('#presenceKeep').checked, sensitivity: parseInt($('#presenceSensitivity').value, 10),
  }));
}
$('#presenceOn').addEventListener('change', savePresence);
$('#presenceKeep').addEventListener('change', savePresence);
$('#presenceSensitivity').addEventListener('change', savePresence);

async function calibrate(step, prompt) {
  const buttons = [$('#calStill'), $('#calMoving')];
  buttons.forEach((b) => { b.disabled = true; });
  try {
    for (let s = 5; s > 0; s--) {
      $('#presenceStatus').textContent = `${prompt} in ${s}…`;
      await new Promise((r) => setTimeout(r, 1000));
    }
    $('#presenceStatus').textContent = step === 'still' ? 'Measuring: stay out of view (15 s)…' : 'Measuring: keep moving in front of the panel (15 s)…';
    const p = await api('POST', `/api/presence/calibrate?step=${step}&seconds=15`);
    renderPresence(p);
    $('#presenceStatus').textContent = step === 'still' ? 'Step 1 done: now step 2.' : 'Calibrated.';
  } catch (e) {
    $('#presenceStatus').textContent = `Couldn't calibrate: ${e.message}`;
  } finally {
    buttons.forEach((b) => { b.disabled = false; });
  }
}
$('#calStill').addEventListener('click', () => calibrate('still', 'Step out of view'));
$('#calMoving').addEventListener('click', () => calibrate('moving', 'Walk in front of the panel'));
// The live reading while the Screen tab is open, for tuning.
setInterval(() => { if (signedIn() && !$('#tab-screen').hidden) refreshPresence(); }, 2000);

// Benchmark: results stack up in a table, newest first, to compare runs.
$('#benchRun').addEventListener('click', async () => {
  const button = $('#benchRun');
  const seconds = Math.min(60, Math.max(5, parseInt($('#benchSeconds').value, 10) || 15));
  const query = `seconds=${seconds}&mode=${$('#benchMode').value}&warm=${$('#benchWarm').checked ? 1 : 0}`;
  button.disabled = true;
  button.textContent = `Running ${seconds + 2} s…`;
  try {
    const r = await api('POST', `/api/benchmark?${query}`);
    let table = $('#benchResults');
    if (!table) {
      table = document.createElement('table');
      table.id = 'benchResults';
      table.className = 'bench';
      table.innerHTML = '<thead><tr><th>Test</th><th>fps</th><th>Median</th><th>90 %</th><th>99 %</th><th>Slow</th><th>CPU</th></tr></thead><tbody></tbody>';
      button.parentElement.after(table);
    }
    const row = document.createElement('tr');
    for (const cell of [`${r.mode}${r.warm ? ' (warm)' : ''}`, r.fps, `${r.frameMs.p50} ms`, `${r.frameMs.p90} ms`, `${r.frameMs.p99} ms`,
      `${r.overBudgetPercent} %`, `${r.cpuPercentOfCore} %`]) {
      const td = document.createElement('td');
      td.textContent = cell;
      row.append(td);
    }
    row.title = `${r.frames} frames in ${r.seconds} s at ${r.refreshHz} Hz (budget ${r.budgetMs} ms); CPU as % of one of ${r.cores} cores; theme ${r.layout.theme}, background ${r.layout.background}`;
    table.tBodies[0].prepend(row);
  } catch (e) {
    alert(`Benchmark failed: ${e.message}`);
  } finally {
    button.disabled = false;
    button.textContent = 'Run';
  }
});

// Opens Android's "Activate device admin" dialog on the tablet; reload once it's confirmed there.
$('#adminRequestBtn').addEventListener('click', async () => {
  await api('POST', '/api/admin/request');
  $('#adminRequestBtn').textContent = 'Confirm on the tablet, then reload this page';
});

$('#wakeBtn').addEventListener('click', () => api('POST', '/api/wake').then(refreshStatus));
$('#sleepBtn').addEventListener('click', () => api('POST', '/api/sleep').then(refreshStatus));

// ---- Start --------------------------------------------------------------------------------------

const signedIn = () => $('#login').hidden;
setInterval(() => signedIn() && refreshStatus(), 5000);
setInterval(() => signedIn() && refreshEntities(), 30000);
if (key) load().then(() => startTab && showTab(startTab)).catch(() => {}); else showLogin();
