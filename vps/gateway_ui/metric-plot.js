function downloadJSON(filename, value) {
  const url = URL.createObjectURL(
      new Blob([JSON.stringify(value, null, 2)], { type: "application/json" }),
    ),
    a = el("a");
  a.href = url;
  a.download = filename;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function metricPlot(
  parent,
  {
    title,
    unit,
    points,
    description = "",
    context = {},
    binary = false,
    appendAction = action,
    allowExport = true,
  },
) {
  const card = el("div", undefined, "plot-card");
  parent.append(card);
  const heading = el("div", undefined, "plot-heading");
  heading.append(el("h4", title), el("small", unit || "請求結果"));
  card.append(heading);
  if (description) card.append(el("p", description, "section-description"));
  const finite = (v) =>
    v !== null && v !== undefined && v !== "" && Number.isFinite(Number(v)) && Number(v) >= 0;
  const rows = points
      .filter((p) => Number.isFinite(Number(p.at)) && Number(p.at) >= 0)
      .map((p, i) => ({ ...p, order: i }))
      .sort((a, b) => a.at - b.at || a.order - b.order),
    valid = rows.filter((p) => finite(p.value));
  const exportData = () =>
    downloadJSON("ModelAtlas-Chart.json", {
      version: 1,
      coverage: "LOADED_OBSERVATIONS_ONLY",
      title,
      unit,
      context,
      points: rows.map((p) => ({
        id: p.id || p.sample_id || null,
        at: Number(p.at),
        value: p.value ?? null,
        note: p.note || "",
        receipt_id: p.sample_id || null,
      })),
    });
  if (!valid.length) {
    card.append(el("p", "此範圍尚無可繪製的數值；缺資料不當成 0。", "section-description"));
    if (allowExport) appendAction(card, "匯出這張圖表 JSON", exportData);
    return;
  }
  const make = (name) => document.createElementNS("http://www.w3.org/2000/svg", name),
    svg = make("svg"),
    a = Number(rows[0].at),
    b = Number(rows.at(-1).at),
    bounds = valid.reduce(
      (r, p) => [Math.min(r[0], Number(p.value)), Math.max(r[1], Number(p.value))],
      [Infinity, -Infinity],
    ),
    low = bounds[0],
    high = bounds[1],
    span = high - low || Math.max(Math.abs(high) * 0.1, high === 0 ? 1 : Number.MIN_VALUE),
    min = binary ? 0 : Math.max(0, low - span * 0.1),
    max = binary ? 1 : high + span * 0.1;
  svg.setAttribute("viewBox", "0 0 640 200");
  svg.setAttribute("class", "metric-plot");
  svg.setAttribute("role", "group");
  svg.setAttribute(
    "aria-label",
    title + "。方向鍵選取資料點，Home / End 移至首尾，Escape 清除選取。",
  );
  svg.setAttribute("tabindex", "0");
  const x = (p) => (b === a ? 338 : 52 + ((Number(p.at) - a) / (b - a)) * 572),
    y = (p) => 164 - ((Number(p.value) - min) / (max - min)) * 146;
  for (let i = 0; i < (binary ? 2 : 4); i++) {
    const ratio = binary ? i : i / 3,
      v = min + (max - min) * ratio,
      line = make("line"),
      label = make("text");
    line.setAttribute("x1", "52");
    line.setAttribute("x2", "624");
    line.setAttribute("y1", String(164 - ratio * 146));
    line.setAttribute("y2", String(164 - ratio * 146));
    line.setAttribute("class", "plot-grid");
    label.setAttribute("x", "46");
    label.setAttribute("y", String(168 - ratio * 146));
    label.setAttribute("text-anchor", "end");
    label.textContent = binary ? (i ? "成功" : "失敗") : Number(v.toPrecision(3)).toString();
    svg.append(line, label);
  }
  let path = "",
    open = false;
  for (const p of rows) {
    if (!finite(p.value)) {
      open = false;
      continue;
    }
    path += (open ? " L" : " M") + x(p) + " " + y(p);
    open = true;
  }
  const line = make("path");
  line.setAttribute("d", path);
  line.setAttribute("class", "plot-line");
  svg.append(line);
  const dot = make("circle");
  dot.setAttribute("r", "4");
  dot.setAttribute("class", "plot-dot");
  svg.append(dot);
  card.append(svg);
  const dates = el("div", undefined, "plot-dates");
  dates.append(
    el("small", new Date(a).toLocaleString()),
    el("small", new Date(b).toLocaleString()),
  );
  card.append(dates);
  const chosen = el("div", undefined, "plot-selected"),
    actions = el("div", undefined, "actions plot-actions");
  chosen.setAttribute("aria-live", "polite");
  card.append(chosen, actions);
  let index = valid.length - 1,
    pinned = index;
  const previous = appendAction(actions, "上一筆", () => select((index ?? valid.length) - 1, true)),
    next = appendAction(actions, "下一筆", () => select((index ?? -1) + 1, true));
  if (allowExport) appendAction(actions, "匯出這張圖表 JSON", exportData);
  const select = (i, pin = false) => {
    index = i === null ? null : Math.max(0, Math.min(valid.length - 1, i));
    if (pin) pinned = index;
    chosen.replaceChildren();
    dot.style.display = index === null ? "none" : "";
    if (index === null) {
      chosen.append(el("small", "選擇圖點以查看精確值與來源。"));
      previous.disabled = next.disabled = false;
      return;
    }
    const p = valid[index];
    dot.setAttribute("cx", String(x(p)));
    dot.setAttribute("cy", String(y(p)));
    chosen.append(
      el(
        "strong",
        (binary ? (Number(p.value) === 1 ? "成功" : "失敗") : String(p.value)) +
          (unit ? " " + unit : ""),
      ),
      el(
        "small",
        new Date(p.at).toLocaleString() +
          " · " +
          (p.note || "") +
          " · " +
          (index + 1) +
          " / " +
          valid.length,
      ),
    );
    chosen.dataset.pointId = p.id || p.sample_id || "";
    previous.disabled = index === 0;
    next.disabled = index === valid.length - 1;
    if (p.sample_id) appendAction(chosen, "查看請求收據", () => requestDetail(p.sample_id));
  };
  const nearest = (e) => {
    const r = svg.getBoundingClientRect(),
      v = ((e.clientX - r.left) / r.width) * 640;
    return valid.reduce(
      (best, p, i) => (Math.abs(x(p) - v) < Math.abs(x(valid[best]) - v) ? i : best),
      0,
    );
  };
  svg.addEventListener("keydown", (e) => {
    if (
      !["ArrowLeft", "ArrowRight", "Home", "End", "Escape"].includes(e.key) ||
      e.isComposing ||
      e.keyCode === 229
    )
      return;
    e.preventDefault();
    if (e.key === "Escape") {
      e.stopPropagation();
      select(null, true);
      return;
    }
    select(
      e.key === "Home"
        ? 0
        : e.key === "End"
          ? valid.length - 1
          : (index ?? (e.key === "ArrowRight" ? -1 : valid.length)) +
            (e.key === "ArrowRight" ? 1 : -1),
      true,
    );
  });
  svg.addEventListener("click", (e) => select(nearest(e), true));
  svg.addEventListener("pointermove", (e) => {
    if (e.pointerType === "mouse") select(nearest(e));
  });
  svg.addEventListener("pointerleave", () => select(pinned));
  select(index);
}
