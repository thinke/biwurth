'use strict';

// Tile types: label, default size in cells, settings, and which entity domains suit them.
const TYPES = {
  clock: { label: 'Clock', size: [4, 2], fields: ['date', 'textSize'] },
  entity: { label: 'Entity value', size: [3, 2], fields: ['entity', 'name', 'unit', 'decimals', 'textSize'] },
  toggle: {
    label: 'Toggle / button', size: [2, 2], fields: ['entity', 'name', 'textSize'],
    domains: ['light', 'switch', 'fan', 'input_boolean', 'script', 'scene', 'button', 'input_button', 'cover', 'media_player', 'climate', 'lock'],
  },
  graph: {
    label: 'History graph', size: [6, 3], fields: ['entity', 'name', 'hours', 'unit', 'decimals', 'textSize'],
    domains: ['sensor', 'input_number', 'number'], multi: 'line', labels: true,
  },
  weather: { label: 'Weather', size: [4, 3], fields: ['entity', 'name', 'textSize'], domains: ['weather'] },
  calendar: { label: 'Calendar', size: [4, 4], fields: ['entity', 'name', 'days', 'textSize'], domains: ['calendar'], multi: 'calendar' },
  camera: { label: 'Camera', size: [6, 4], fields: ['camera', 'name', 'fullStream', 'snapshot', 'fit', 'textSize'] },
};

const FIELDS = {
  entity: { label: 'Entity', placeholder: 'sensor.living_room_temperature' },
  name: { label: 'Name', placeholder: 'Name from Home Assistant' },
  unit: { label: 'Unit', placeholder: 'Unit from Home Assistant' },
  decimals: { label: 'Decimals', type: 'number', placeholder: 'As reported' },
  hours: { label: 'Hours shown', type: 'number', placeholder: '24' },
  days: { label: 'Days ahead', type: 'number', placeholder: '7' },
  date: { label: 'Date under the time', options: [['', 'Show'], ['off', 'Hide']] },
  camera: { label: 'Camera', placeholder: 'go2rtc stream name or rtsp:// URL', list: 'cameraList' },
  fullStream: { label: 'Full-screen stream (tap)', placeholder: 'Same as the tile', list: 'cameraList' },
  snapshot: { label: 'Snapshot URL', placeholder: 'From go2rtc' },
  textSize: {
    label: 'Text size',
    options: [['', 'Auto (fits the tile)'], ['small', 'Small'], ['medium', 'Medium'], ['large', 'Large'], ['xl', 'Extra large']],
  },
  fit: { label: 'Picture', options: [['', 'Fill the tile'], ['contain', 'Show all of it']] },
};

const $ = (selector) => document.querySelector(selector);
const board = $('#board');

let key = localStorage.getItem('biwurthKey') || '';

// A bookmarkable address: http://<tablet>:2323/#key=<key> logs in and drops the key from the address bar.
if (location.hash.startsWith('#key=')) {
  key = decodeURIComponent(location.hash.slice(5));
  localStorage.setItem('biwurthKey', key);
  history.replaceState(null, '', location.pathname);
}
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

function showLogin() {
  $('#login').hidden = false;
  $('#key').value = key;
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
    );
    $('#haStatus').textContent = `Status: ${s.ha}`;
    $('#mqttStatus').textContent = `Status: ${s.mqtt}`;
  } catch (e) {
    $('#status').textContent = e.message;
  }
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
  board.style.backgroundImage =
    'linear-gradient(to right, #ffffff10 1px, transparent 1px), linear-gradient(to bottom, #ffffff10 1px, transparent 1px)';
  board.style.backgroundSize = `calc(100% / ${layout.columns}) calc(100% / ${layout.rows})`;
  board.replaceChildren(...page().tiles.map(tileElement));
}

function tileElement(tile) {
  const el = document.createElement('div');
  el.className = 'tile' + (tile.id === selectedId ? ' selected' : '');
  place(el, tile);
  const ids = entityIds(tile);
  const e = ids.length === 1 ? entity(ids[0]) : null;
  const values = ids.map(entity).filter(Boolean).map((x) => `${x.state} ${x.unit}`.trim());
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

function place(el, tile) {
  el.style.left = `calc(${(tile.x / layout.columns) * 100}% + 2px)`;
  el.style.top = `calc(${(tile.y / layout.rows) * 100}% + 2px)`;
  el.style.width = `calc(${(tile.w / layout.columns) * 100}% - 4px)`;
  el.style.height = `calc(${(tile.h / layout.rows) * 100}% - 4px)`;
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
    const field = FIELDS[name];
    if (name === 'entity' && type.multi) {
      form.append(multiEntityEditor(tile, type));
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

// One row per entity (a graph line or a calendar), stored comma separated in props.entity and,
// for graphs, the matching labels in props.labels.
function multiEntityEditor(tile, type) {
  const wrap = document.createElement('div');
  const title = document.createElement('div');
  title.className = 'muted';
  title.textContent = type.multi === 'line' ? 'Lines' : 'Calendars';
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
  add.textContent = type.multi === 'line' ? '+ Add line' : '+ Add calendar';

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
    preset: $('#theme').value,
    accent: $('#accentDefault').checked ? '' : $('#accent').value,
    font: $('#font').value,
    textScale: parseFloat($('#textScale').value),
  };
  setDirty(true);
  renderLayoutForm();
}
$('#theme').addEventListener('change', updateTheme);
$('#font').addEventListener('change', updateTheme);
$('#textScale').addEventListener('change', updateTheme);
$('#accent').addEventListener('input', updateTheme);
$('#accentDefault').addEventListener('change', () => {
  if (!$('#accentDefault').checked) $('#accent').value = THEME_ACCENTS[$('#theme').value];
  updateTheme();
});

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

// ---- Settings tabs ------------------------------------------------------------------------------

function showTab(name) {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('.tab').forEach((t) => { t.hidden = t.id !== `tab-${name}`; });
}
document.querySelectorAll('#tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

function fillSettings() {
  $('#haUrl').value = config.ha.url;
  $('#haToken').value = '';
  $('#haToken').placeholder = config.ha.tokenSet ? 'Saved (leave empty to keep it)' : 'Paste the token';

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
  $('#brightnessSystem').checked = s.brightness < 0;
  $('#brightness').value = s.brightness < 0 ? 80 : s.brightness;
  $('#brightness').disabled = s.brightness < 0;
}

async function saveSettings(form, path, body) {
  const button = form.querySelector('button.primary');
  button.disabled = true;
  try {
    const fresh = await api('PUT', path, body);
    config.ha = fresh.ha;
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

$('#tab-ha').addEventListener('submit', (event) => {
  event.preventDefault();
  saveSettings(event.target, '/api/ha', { url: $('#haUrl').value.trim(), token: $('#haToken').value.trim() });
});

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
  });
});

$('#wakeBtn').addEventListener('click', () => api('POST', '/api/wake').then(refreshStatus));
$('#sleepBtn').addEventListener('click', () => api('POST', '/api/sleep').then(refreshStatus));

// ---- Start --------------------------------------------------------------------------------------

setInterval(refreshStatus, 5000);
setInterval(refreshEntities, 30000);
if (key) load().catch(() => {}); else showLogin();
