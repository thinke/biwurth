'use strict';

// The diagram editor for heating plant tiles (props.diagram): parts placed on a grid, pipes drawn
// between them point by point. Keep in step with config/Diagram.kt (the format) and
// ui/PlantTile.kt (how the panel draws it).

const HEAT_SOURCES = [['oil', 'Oil boiler'], ['gas', 'Gas boiler'], ['pellet', 'Pellet boiler'], ['wood', 'Wood stove'],
  ['electric', 'Electric heater'], ['heatpump', 'Heat pump'], ['district', 'District heat'], ['solar', 'Solar collector']];

// Each part kind: its name, size in grid units, and its props as [key, label, 'entity' | 'text' | options].
const PART_KINDS = {
  source: {
    label: 'Heat source', size: [8, 10],
    fields: [['type', 'Type', HEAT_SOURCES], ['name', 'Name', 'text'], ['state', 'Burner / heater (on/off or %)', 'entity'], ['temp', 'Temperature', 'entity']],
  },
  airheater: {
    label: 'Wood air heater', size: [11, 8],
    fields: [['name', 'Name', 'text'], ['state', 'Fire (on/off; default: core over 40°)', 'entity'], ['fan', 'Fan (on/off or speed)', 'entity'],
      ['core', 'Heat exchanger temperature', 'entity'], ['in', 'Air in', 'entity'], ['out', 'Air out', 'entity'], ['flue', 'Flue gas temperature', 'entity']],
  },
  radiator: { label: 'Radiator', size: [8, 5], fields: [['name', 'Name', 'text'], ['temp', 'Temperature', 'entity'], ['active', 'Warm while (default always)', 'entity']] },
  floor: { label: 'Floor heating', size: [8, 5], fields: [['name', 'Name', 'text'], ['temp', 'Temperature', 'entity'], ['active', 'Warm while (default always)', 'entity']] },
  tank: { label: 'Hot-water tank', size: [6, 9], fields: [['name', 'Name', 'text'], ['temp', 'Temperature', 'entity'], ['active', 'Charging (outlined while on)', 'entity']] },
  pump: { label: 'Pump', size: [3, 3], fields: [['state', 'Running (on/off or speed)', 'entity'], ['dir', 'Points', [['', 'Right'], ['left', 'Left'], ['up', 'Up'], ['down', 'Down']]]] },
  valve: { label: 'Mixing valve', size: [3, 3], fields: [['position', 'Position (%)', 'entity'], ['dir', 'Pipe', [['', 'Horizontal'], ['vertical', 'Vertical']]]] },
  outdoor: {
    label: 'AC outdoor unit', size: [7, 8],
    fields: [['name', 'Name', 'text'], ['ac', 'AC (climate, or heating/cooling/idle state)', 'entity'], ['in', 'Air in', 'entity'], ['out', 'Air out', 'entity']],
  },
  indoor: {
    label: 'AC indoor unit', size: [9, 4],
    fields: [['name', 'Name', 'text'], ['ac', 'AC (climate, or heating/cooling/idle state)', 'entity'], ['in', 'Air in', 'entity'], ['out', 'Air out', 'entity'], ['room', 'Room temperature (if not climate)', 'entity']],
  },
  sensor: {
    label: 'Sensor value', size: [7, 2],
    fields: [['label', 'Label', 'text'], ['entity', 'Entity', 'entity'], ['from', 'Minus (shows the difference, Δ)', 'entity'],
      ['point', 'Measuring point x,y (a probe dot, e.g. on a duct)', 'text'], ['unit', 'Unit (default the entity\'s)', 'text']],
  },
  text: { label: 'Text', size: [8, 2], fields: [['text', 'Text', 'text']] },
};

const PIPE_KINDS = [['hot', 'Hot (supply)'], ['cold', 'Cold (return)'], ['warm', 'Warm'], ['refrigerant', 'Refrigerant'], ['air', 'Air duct'], ['flue', 'Flue']];
const PIPE_COLORS = { hot: '#ff6b3d', cold: '#3b9cf5', warm: '#e8a33d', refrigerant: '#b07cf0', air: '#ffb07088', flue: '#8a8f98' };
const SVG_NS = 'http://www.w3.org/2000/svg';

// Roughly how wide the panel draws [text] in grid units: small text is 1.2 units high.
const textWidth = (text, size = 1.2) => String(text || '').length * size * 0.56;

function partName(part) {
  if (part.kind === 'source') return part.props.name || HEAT_SOURCES.find(([k]) => k === (part.props.type || 'oil'))?.[1];
  return part.props.name || { radiator: 'Radiators', floor: 'Floor heating', tank: 'Hot water', airheater: 'Wood air heater' }[part.kind] || '';
}

// Where a part draws on the panel (ui/PlantTile.kt): its shape, and the text it puts round it
// (names above, readings below and beside). Each is { x, y, w, h, what }.
function footprints(part) {
  const { x, y, w, h, props } = part;
  const has = (k) => !!props[k];
  const out = [{ x, y, w, h, what: part.kind === 'sensor' || part.kind === 'text' ? 'text' : 'shape' }];
  const above = (text, width) => out.push({ x, y: y - 1.6, w: Math.min(width ?? textWidth(text), Math.max(w * 2, 12)), h: 1.6, what: 'name' });
  const below = (width) => out.push({ x, y: y + h, w: width, h: 1.6, what: 'reading' });
  const beside = (rows) => rows && out.push({ x: x + w + 0.6, y: y + h / 2 - rows * 0.8, w: 6.5, h: rows * 1.6, what: 'readings' });
  const airRows = (has('in') ? 1 : 0) + (has('out') ? 1 : 0) + (has('in') && has('out') ? 1 : 0);
  switch (part.kind) {
    case 'radiator': case 'floor':
      above(partName(part));
      if (has('temp')) below(4);
      break;
    case 'tank': above(partName(part)); break;
    case 'outdoor':
      if (has('name')) above(props.name);
      if (has('in') && has('out')) below(5);
      break;
    case 'indoor':
      if (has('name') || has('ac')) above(props.name || 'Air conditioner');
      if (has('room') || has('ac')) below(8);
      beside(airRows);
      break;
    case 'airheater':
      above(partName(part), w * 0.88 - 1.2);
      // The flue's reading, right of its stub over the top right.
      if (has('flue')) out.push({ x: x + w * 0.88 + 0.6, y: y - 1.6, w: 8, h: 1.6, what: 'flue' });
      beside(airRows);
      break;
    case 'valve':
      if (has('position')) {
        if (props.dir === 'vertical') out.push({ x: x + w + 0.4, y: y + h / 2 - 0.8, w: 6, h: 1.6, what: 'reading' });
        else out.push({ x: x + w / 2 - 3, y: y - 1.6, w: 6, h: 1.6, what: 'reading' });
      }
      break;
    default:
  }
  return out;
}

// Text needs a little clear space round it to read; shapes may touch.
const intersects = (a, b) => {
  const gap = a.what === 'shape' && b.what === 'shape' ? -0.05 : 0.3;
  return a.x < b.x + b.w + gap && b.x < a.x + a.w + gap && a.y < b.y + b.h + gap && b.y < a.y + a.h + gap;
};

// A pipe segment through a box (Liang–Barsky clipping).
function segmentHits([x1, y1], [x2, y2], r) {
  let t0 = 0;
  let t1 = 1;
  const dx = x2 - x1;
  const dy = y2 - y1;
  for (const [p, q] of [[-dx, x1 - r.x], [dx, r.x + r.w - x1], [-dy, y1 - r.y], [dy, r.y + r.h - y1]]) {
    if (p === 0) { if (q < 0) return false; continue; }
    const t = q / p;
    if (p < 0) t0 = Math.max(t0, t); else t1 = Math.min(t1, t);
    if (t0 > t1) return false;
  }
  return true;
}

const WHAT = { shape: '', text: '', name: '’s name', reading: '’s reading', readings: '’s readings', flue: '’s flue' };

// What overlaps in [diagram]: parts and their text on each other, and pipes across text.
function diagramOverlaps(diagram) {
  const label = (part) => partName(part) || part.props.label || part.props.text || PART_KINDS[part.kind]?.label || part.kind;
  const found = [];
  const prints = diagram.parts.map((part) => ({ part, prints: footprints(part) }));
  for (let i = 0; i < prints.length; i += 1) {
    for (let j = i + 1; j < prints.length; j += 1) {
      const hit = prints[i].prints.flatMap((a) => prints[j].prints.filter((b) => intersects(a, b)).map((b) => [a, b]))[0];
      if (hit) found.push({ parts: [prints[i].part.id, prints[j].part.id], text: `${label(prints[i].part)}${WHAT[hit[0].what]} and ${label(prints[j].part)}${WHAT[hit[1].what]} overlap` });
    }
  }
  for (const pipe of diagram.pipes) {
    for (const { part, prints: list } of prints) {
      // Pipes may run into shapes; only text matters. A measuring point's label sits by its pipe, not on it.
      const textBoxes = list.filter((f) => f.what !== 'shape');
      const crossed = textBoxes.find((f) => pipe.points.some((p, k) => k > 0 && segmentHits(pipe.points[k - 1], p, f)));
      if (crossed) found.push({ parts: [part.id], pipe: pipe.id, text: `A ${pipe.kind} pipe runs across ${label(part)}${WHAT[crossed.what] || '’s text'}` });
    }
  }
  return found;
}

// Two heat sources in one heating loop (an oil boiler and an electric heater), radiators, floor
// heating, a hot-water tank and a split AC: a start to edit, entities still to set.
function exampleDiagram() {
  const part = (id, kind, x, y, w, h, props = {}) => ({ id, kind, x, y, w, h, props });
  const pipe = (id, kind, points, flow = '') => ({ id, kind, points, flow });
  return {
    w: 48, h: 36,
    parts: [
      part('oil', 'source', 1, 2, 8, 10, { type: 'oil' }),
      part('electric', 'source', 1, 15, 8, 9, { type: 'electric' }),
      part('pump', 'pump', 15.5, 2.5, 3, 3),
      part('valve', 'valve', 20.5, 2.5, 3, 3),
      part('supplyT', 'sensor', 26, 1.5, 8, 2, { label: 'Supply' }),
      part('returnT', 'sensor', 13.5, 10.8, 8, 2, { label: 'Return' }),
      part('radiators', 'radiator', 35, 2.5, 9, 8.5),
      part('floor', 'floor', 35, 14, 9, 6),
      part('tank', 'tank', 19, 15.5, 6, 9),
      part('acOut', 'outdoor', 1, 27, 7, 8, { name: 'Outdoor unit' }),
      part('acIn', 'indoor', 20, 29, 10, 4),
    ],
    pipes: [
      pipe('supply', 'hot', [[9, 4], [35, 4]]),
      pipe('electricOut', 'hot', [[9, 17], [12, 17], [12, 4]]),
      pipe('bypass', 'cold', [[22, 4], [22, 10]]),
      pipe('return', 'cold', [[35, 10], [9, 10]]),
      pipe('electricBack', 'cold', [[11, 10], [11, 22], [9, 22]]),
      pipe('floorIn', 'hot', [[31, 4], [31, 15.5], [35, 15.5]]),
      pipe('floorBack', 'cold', [[35, 18.5], [29, 18.5], [29, 10]]),
      pipe('tankIn', 'hot', [[12, 16], [19, 16]]),
      pipe('tankBack', 'cold', [[19, 22], [11, 22]]),
      pipe('refrigerantLiquid', 'refrigerant', [[8, 30], [20, 30]]),
      pipe('refrigerantGas', 'refrigerant', [[20, 32], [8, 32]]),
    ],
  };
}

let diagramModal = null;

function openDiagramEditor(tile) {
  let draft;
  try {
    draft = tile.props.diagram ? JSON.parse(tile.props.diagram) : { w: 48, h: 30, parts: [], pipes: [] };
  } catch {
    draft = { w: 48, h: 30, parts: [], pipes: [] };
  }
  draft.parts = draft.parts || [];
  draft.pipes = draft.pipes || [];
  let selected = null; // { type: 'part' | 'pipe', id }
  let drawing = null; // points of a pipe being drawn
  let pipeKind = 'hot';
  let hover = null;
  let drag = null;

  if (!diagramModal) {
    diagramModal = document.createElement('div');
    diagramModal.className = 'overlay';
    diagramModal.innerHTML = '<div class="modal diagram-modal"></div>';
    document.body.append(diagramModal);
  }
  const box = diagramModal.firstElementChild;
  box.replaceChildren();

  const head = document.createElement('div');
  head.className = 'diagram-head';
  const title = document.createElement('h2');
  title.textContent = 'Heating plant diagram';
  const done = button('Done', () => {
    if (drawing) finishPipe();
    if (draft.parts.length || draft.pipes.length) tile.props.diagram = JSON.stringify(draft);
    else delete tile.props.diagram;
    setDirty(true);
    renderBoard();
    renderTileForm();
    close();
  }, 'primary');
  const cancel = button('Cancel', () => close());
  head.append(title, button('Start from an example', () => {
    if ((draft.parts.length || draft.pipes.length) && !confirm('Replace this diagram with the example?')) return;
    draft = exampleDiagram();
    selected = null;
    render();
  }), button('Preview on tablet', preview), done, cancel);

  // Add parts, draw pipes.
  const tools = document.createElement('div');
  tools.className = 'diagram-tools';
  const addLabel = document.createElement('span');
  addLabel.className = 'muted';
  addLabel.textContent = 'Add:';
  tools.append(addLabel);
  for (const [kind, k] of Object.entries(PART_KINDS)) {
    tools.append(button(k.label, () => {
      const [w, h] = k.size;
      const id = uniqueId(kind);
      // A new part goes in the middle, nudged along while that spot is taken.
      let x = Math.round((draft.w - w) / 2);
      let y = Math.round((draft.h - h) / 2);
      while (draft.parts.some((p) => p.x === x && p.y === y)) { x += 2; y += 2; }
      draft.parts.push({ id, kind, x, y, w, h, props: kind === 'source' ? { type: 'oil' } : {} });
      selected = { type: 'part', id };
      render();
    }));
  }
  const pipeBar = document.createElement('div');
  pipeBar.className = 'diagram-tools';
  const kindSelect = select(PIPE_KINDS, pipeKind);
  kindSelect.addEventListener('change', () => { pipeKind = kindSelect.value; });
  const drawButton = button('Draw pipe', () => {
    if (drawing) finishPipe();
    else { drawing = []; selected = null; render(); }
  });
  const pipeHelp = document.createElement('span');
  pipeHelp.className = 'muted';
  pipeBar.append(kindSelect, drawButton, pipeHelp);

  const body = document.createElement('div');
  body.className = 'diagram-body';
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.classList.add('diagram-canvas');
  const side = document.createElement('div');
  side.className = 'diagram-side';
  body.append(svg, side);
  const previewBox = document.createElement('div');
  previewBox.className = 'diagram-preview';

  // What overlaps on the panel, kept up to date while parts move.
  const problems = document.createElement('div');
  problems.className = 'diagram-problems';
  box.append(head, tools, pipeBar, problems, body, previewBox);
  diagramModal.hidden = false;
  document.addEventListener('keydown', onKey);
  render();

  function close() {
    diagramModal.hidden = true;
    document.removeEventListener('keydown', onKey);
  }

  function uniqueId(base) {
    const ids = new Set([...draft.parts, ...draft.pipes].map((x) => x.id));
    let i = 1;
    while (ids.has(`${base}${i}`)) i += 1;
    return `${base}${i}`;
  }

  // Grid units under the pointer, to the nearest half unit.
  function point(event) {
    const p = svg.createSVGPoint();
    p.x = event.clientX;
    p.y = event.clientY;
    const q = p.matrixTransform(svg.getScreenCTM().inverse());
    return [Math.round(q.x * 2) / 2, Math.round(q.y * 2) / 2];
  }

  // Pipes go straight across or down from their last point, unless Shift is held.
  function aligned(p, event) {
    if (!drawing.length || event.shiftKey) return p;
    const [lx, ly] = drawing[drawing.length - 1];
    return Math.abs(p[0] - lx) >= Math.abs(p[1] - ly) ? [p[0], ly] : [lx, p[1]];
  }

  function finishPipe() {
    const points = drawing.filter((p, i) => i === 0 || p[0] !== drawing[i - 1][0] || p[1] !== drawing[i - 1][1]);
    drawing = null;
    if (points.length >= 2) {
      const id = uniqueId('pipe');
      draft.pipes.push({ id, kind: pipeKind, points, flow: '' });
      selected = { type: 'pipe', id };
    }
    render();
  }

  function onKey(event) {
    if (diagramModal.hidden) return;
    if (event.key === 'Escape' && drawing) { drawing = null; render(); return; }
    if (event.key === 'Enter' && drawing) { finishPipe(); return; }
    const typing = ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement?.tagName);
    if ((event.key === 'Delete' || event.key === 'Backspace') && selected && !typing) {
      event.preventDefault();
      removeSelected();
    }
  }

  function removeSelected() {
    if (!selected) return;
    if (selected.type === 'part') draft.parts = draft.parts.filter((p) => p.id !== selected.id);
    else draft.pipes = draft.pipes.filter((p) => p.id !== selected.id);
    selected = null;
    render();
  }

  svg.addEventListener('pointerdown', (event) => {
    const p = point(event);
    if (drawing) {
      drawing.push(aligned(p, event));
      render();
      return;
    }
    const vertex = event.target.closest('[data-vertex]');
    const handle = event.target.closest('[data-resize]');
    const partEl = event.target.closest('[data-part]');
    const pipeEl = event.target.closest('[data-pipe]');
    if (vertex) {
      drag = { kind: 'vertex', index: Number(vertex.dataset.vertex) };
    } else if (handle) {
      drag = { kind: 'resize', part: draft.parts.find((x) => x.id === handle.dataset.resize), start: p };
      drag.size = [drag.part.w, drag.part.h];
    } else if (partEl) {
      const part = draft.parts.find((x) => x.id === partEl.dataset.part);
      selected = { type: 'part', id: part.id };
      drag = { kind: 'move', part, offset: [p[0] - part.x, p[1] - part.y] };
    } else if (pipeEl) {
      selected = { type: 'pipe', id: pipeEl.dataset.pipe };
    } else {
      selected = null;
    }
    if (drag) svg.setPointerCapture(event.pointerId);
    render();
  });

  svg.addEventListener('pointermove', (event) => {
    const p = point(event);
    if (drawing) { hover = aligned(p, event); render(); return; }
    if (!drag) return;
    if (drag.kind === 'move') {
      drag.part.x = clamp(p[0] - drag.offset[0], 0, draft.w - drag.part.w);
      drag.part.y = clamp(p[1] - drag.offset[1], 0, draft.h - drag.part.h);
    } else if (drag.kind === 'resize') {
      drag.part.w = clamp(drag.size[0] + p[0] - drag.start[0], 1, draft.w - drag.part.x);
      drag.part.h = clamp(drag.size[1] + p[1] - drag.start[1], 1, draft.h - drag.part.y);
    } else if (drag.kind === 'vertex') {
      const pipe = selectedPipe();
      pipe.points[drag.index] = [clamp(p[0], 0, draft.w), clamp(p[1], 0, draft.h)];
    }
    render();
  });

  const endDrag = () => {
    if (!drag) return;
    drag = null;
    render();
  };
  svg.addEventListener('pointerup', endDrag);
  svg.addEventListener('pointercancel', endDrag);

  // Double-click: ends a pipe being drawn; on a selected pipe adds a bend there, or on a bend removes it.
  svg.addEventListener('dblclick', (event) => {
    if (drawing) { finishPipe(); return; }
    const pipe = selectedPipe();
    if (!pipe) return;
    const vertex = event.target.closest('[data-vertex]');
    if (vertex) {
      if (pipe.points.length > 2) pipe.points.splice(Number(vertex.dataset.vertex), 1);
    } else if (event.target.closest('[data-pipe]')) {
      const p = point(event);
      pipe.points.splice(nearestSegment(pipe.points, p) + 1, 0, p);
    }
    render();
  });

  function nearestSegment(points, [x, y]) {
    let best = 0;
    let bestD = Infinity;
    for (let i = 0; i < points.length - 1; i += 1) {
      const [ax, ay] = points[i];
      const [bx, by] = points[i + 1];
      const len2 = (bx - ax) ** 2 + (by - ay) ** 2 || 1;
      const t = clamp(((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / len2, 0, 1);
      const d = (x - ax - t * (bx - ax)) ** 2 + (y - ay - t * (by - ay)) ** 2;
      if (d < bestD) { bestD = d; best = i; }
    }
    return best;
  }

  function selectedPipe() {
    return selected?.type === 'pipe' ? draft.pipes.find((p) => p.id === selected.id) : null;
  }

  function el(name, attrs = {}, parent = null) {
    const e = document.createElementNS(SVG_NS, name);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (parent) parent.append(e);
    return e;
  }

  function render() {
    drawButton.textContent = drawing ? 'Finish pipe' : 'Draw pipe';
    drawButton.classList.toggle('primary', !!drawing);
    pipeHelp.textContent = drawing
      ? 'Click to add points (straight unless Shift is held); double-click, Enter or Finish to end; Esc cancels.'
      : 'Select a pipe to drag its bends; double-click it to add a bend, a bend to remove it.';
    svg.setAttribute('viewBox', `-1 -1 ${draft.w + 2} ${draft.h + 2}`);
    const overlaps = diagramOverlaps(draft);
    const overlapping = new Set(overlaps.flatMap((o) => o.parts));
    problems.replaceChildren(...overlaps.slice(0, 6).map((o) => Object.assign(document.createElement('div'), { textContent: o.text })));
    if (overlaps.length > 6) problems.append(Object.assign(document.createElement('div'), { textContent: `and ${overlaps.length - 6} more` }));
    problems.hidden = !overlaps.length;
    svg.replaceChildren();
    const defs = el('defs', {}, svg);
    const pattern = el('pattern', { id: 'diagramGrid', width: 1, height: 1, patternUnits: 'userSpaceOnUse' }, defs);
    el('circle', { cx: 0, cy: 0, r: 0.06, fill: '#ffffff30' }, pattern);
    el('rect', { x: 0, y: 0, width: draft.w, height: draft.h, fill: 'url(#diagramGrid)', stroke: '#ffffff20', 'stroke-width': 0.1 }, svg);

    for (const pipe of draft.pipes) {
      const g = el('g', { 'data-pipe': pipe.id }, svg);
      const points = pipe.points.map((p) => p.join(',')).join(' ');
      el('polyline', { points, fill: 'none', stroke: 'transparent', 'stroke-width': 1.6 }, g);
      el('polyline', {
        points, fill: 'none', stroke: PIPE_COLORS[pipe.kind] || PIPE_COLORS.hot, 'stroke-width': { air: 1.2, flue: 0.8 }[pipe.kind] || 0.5, 'stroke-linejoin': 'round',
        opacity: selected?.id === pipe.id ? 1 : 0.85,
      }, g);
      // Which way it flows: an arrow at its middle.
      const mid = Math.floor((pipe.points.length - 1) / 2);
      const [ax, ay] = pipe.points[mid];
      const [bx, by] = pipe.points[mid + 1];
      const angle = Math.atan2(by - ay, bx - ax) * 180 / Math.PI;
      el('path', { d: 'M -0.5 -0.45 L 0.5 0 L -0.5 0.45 Z', fill: '#ffffffaa', transform: `translate(${(ax + bx) / 2} ${(ay + by) / 2}) rotate(${angle})` }, g);
    }
    const pipe = selectedPipe();
    if (pipe) {
      pipe.points.forEach(([x, y], i) => el('circle', { cx: x, cy: y, r: 0.55, fill: '#111', stroke: '#e0a030', 'stroke-width': 0.18, 'data-vertex': i, class: 'grab' }, svg));
    }

    for (const part of draft.parts) {
      const kind = PART_KINDS[part.kind];
      const isSelected = selected?.type === 'part' && selected.id === part.id;
      const g = el('g', { 'data-part': part.id, class: 'grab' }, svg);
      const round = part.kind === 'pump' ? Math.min(part.w, part.h) / 2 : 0.5;
      el('rect', {
        x: part.x, y: part.y, width: part.w, height: part.h, rx: round,
        fill: '#1a1c1fdd', stroke: isSelected ? '#e0a030' : (overlapping.has(part.id) ? '#e06060' : '#9a9ea6'), 'stroke-width': isSelected || overlapping.has(part.id) ? 0.22 : 0.12,
      }, g);
      // Where its text goes on the panel, faintly, so there's room left for it.
      for (const f of footprints(part).filter((f) => f.what !== 'shape' && f.what !== 'text')) {
        el('rect', { x: f.x, y: f.y, width: f.w, height: f.h, fill: overlapping.has(part.id) ? '#e0606022' : '#ffffff0a', stroke: 'none', 'pointer-events': 'none' }, svg);
      }
      const name = part.props.name || part.props.label || part.props.text
        || (part.kind === 'source' ? (HEAT_SOURCES.find(([k]) => k === (part.props.type || 'oil'))?.[1]) : kind.label);
      // A part too small for its name (a pump, a valve) has it under it.
      const fits = part.w >= textWidth(name, 0.9);
      const fontSize = fits ? Math.max(0.7, Math.min(1.2, part.h * 0.3, (part.w * 1.6) / Math.max(4, name.length))) : 0.8;
      const label = el('text', {
        x: part.x + part.w / 2, y: fits ? part.y + part.h / 2 : part.y + part.h + 0.7, 'font-size': fontSize,
        'text-anchor': 'middle', 'dominant-baseline': 'middle', fill: fits ? '#e8e8e8' : '#9a9ea6', 'pointer-events': fits ? 'auto' : 'none',
      }, g);
      label.textContent = name;
      if (isSelected) el('rect', { x: part.x + part.w - 0.8, y: part.y + part.h - 0.8, width: 1, height: 1, fill: '#e0a030', 'data-resize': part.id, class: 'resize' }, svg);
    }

    if (drawing) {
      const points = [...drawing, ...(hover ? [hover] : [])];
      if (points.length) {
        el('polyline', { points: points.map((p) => p.join(',')).join(' '), fill: 'none', stroke: PIPE_COLORS[pipeKind], 'stroke-width': 0.5, 'stroke-dasharray': '1 0.5' }, svg);
      }
      if (hover) el('circle', { cx: hover[0], cy: hover[1], r: 0.35, fill: PIPE_COLORS[pipeKind] }, svg);
    }
    renderSide();
  }

  // The selected part's or pipe's settings, or the diagram's.
  function renderSide() {
    side.replaceChildren();
    fillAllEntities();
    const field = (labelText, value, onChange, kind) => {
      let input;
      if (Array.isArray(kind)) {
        input = select(kind, value ?? '');
        input.addEventListener('change', () => onChange(input.value));
      } else {
        input = document.createElement('input');
        input.value = value ?? '';
        if (kind === 'number') input.type = 'number';
        if (kind === 'entity') {
          input.setAttribute('list', 'allEntities');
          input.autocomplete = 'off';
          input.placeholder = 'sensor.…';
        }
        input.addEventListener(kind === 'number' ? 'change' : 'input', () => onChange(input.value.trim()));
      }
      side.append(labelled(labelText, input));
    };
    const heading = (text) => {
      const h = document.createElement('h3');
      h.textContent = text;
      side.append(h);
    };

    if (selected?.type === 'part') {
      const part = draft.parts.find((p) => p.id === selected.id);
      const kind = PART_KINDS[part.kind];
      heading(kind.label);
      for (const [key, labelText, type] of kind.fields) {
        field(labelText, part.props[key], (v) => {
          if (v) part.props[key] = v; else delete part.props[key];
          // Names and types show on the canvas: redraw it, but keep this form (and its focus).
          if (key !== 'state' && key !== 'temp') renderCanvasOnly();
        }, type);
      }
      const where = document.createElement('div');
      where.className = 'diagram-xywh';
      for (const key of ['x', 'y', 'w', 'h']) {
        const input = document.createElement('input');
        input.type = 'number';
        input.step = '0.5';
        input.value = part[key];
        input.title = { x: 'Left', y: 'Top', w: 'Width', h: 'Height' }[key];
        input.addEventListener('change', () => {
          const v = Number(input.value);
          if (!Number.isFinite(v)) return;
          part[key] = key === 'w' || key === 'h' ? Math.max(1, v) : Math.max(0, v);
          render();
        });
        where.append(labelled(key.toUpperCase(), input));
      }
      side.append(where);
      side.append(button('Delete part', removeSelected, 'danger'));
    } else if (selected?.type === 'pipe') {
      const p = selectedPipe();
      heading('Pipe');
      field('Kind', p.kind, (v) => { p.kind = v; renderCanvasOnly(); }, PIPE_KINDS);
      field('Flows while (a pump, burner, AC…)', p.flow, (v) => { p.flow = v; }, 'entity');
      side.append(button('Reverse direction', () => { p.points.reverse(); render(); }));
      side.append(button('Delete pipe', removeSelected, 'danger'));
    } else {
      heading('Diagram');
      const help = document.createElement('p');
      help.className = 'muted';
      help.textContent = 'Add parts with the buttons above and drag them into place; the orange corner of a selected part resizes it. '
        + 'Draw pipes between them: water moves along a pipe, first point to last, while its "Flows while" entity runs. '
        + 'Names go above parts and temperatures below, so leave a little room around them. Delete removes what\'s selected.';
      side.append(help);
      field('Grid width (units)', draft.w, (v) => { draft.w = clamp(Number(v) || 48, 8, 200); render(); }, 'number');
      field('Grid height (units)', draft.h, (v) => { draft.h = clamp(Number(v) || 30, 8, 200); render(); }, 'number');
    }
  }

  // Redraw the canvas without rebuilding the side form, so typing isn't interrupted.
  function renderCanvasOnly() {
    const keep = renderSide;
    renderSide = () => {};
    render();
    renderSide = keep;
  }

  async function preview() {
    if (drawing) finishPipe();
    previewBox.textContent = 'Drawing on the tablet…';
    const draftLayout = structuredClone(layout);
    const t = draftLayout.pages[pageIndex].tiles.find((x) => x.id === tile.id);
    if (t) t.props = { ...t.props, diagram: JSON.stringify(draft) };
    try {
      const response = await fetch('/api/screenshot?scale=0.6&format=jpeg&settle=1500', {
        method: 'POST',
        headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ layout: draftLayout, page: pageIndex }),
      });
      if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || response.statusText);
      const img = document.createElement('img');
      img.src = URL.createObjectURL(await response.blob());
      img.alt = 'The page as the tablet draws it';
      previewBox.replaceChildren(img);
    } catch (e) {
      previewBox.textContent = `No preview: ${e.message}`;
    }
  }
}
