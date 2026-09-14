/* =====================================================================
   scheduling.js — client for the appointment calendar (S-5).

   Renders the day/week grid, provider availability shading and the now-line,
   and drives the New/Edit appointment modal, the recurring-series editor and
   the availability editor. All data is read and written through the JSON API
   that S-4 exposes (/api/appointments, /api/availability, /api/appointment-
   series); the booking guard stays server-side and surfaces here as HTTP 409,
   shown inline and recoverable — never swallowed.

   No framework, no calendar library: the grid is CSS-grid positioned by time,
   consistent with the server-rendered Thymeleaf app.
   ===================================================================== */
(function () {
  "use strict";

  // --- Grid geometry (mirrors the tokens in scheduling.css) ---
  var HOUR_H = 48;          // --sch-grid-hour-h
  var DAY_START = 7;        // first hour shown (07:00)
  var DAY_END = 20;         // last hour shown (20:00, exclusive of the 20 row label)
  var HOURS = DAY_END - DAY_START;

  var DOW_SHORT = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  var DOW_JAVA = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"];

  // --- State ---
  var state = {
    view: "week",           // "day" | "week"
    anchor: startOfDay(new Date()),
    providerId: null,
    rules: [],              // AvailabilityRule[]
    exceptions: [],         // AvailabilityException[]
    appts: [],              // Appointment[]
    editing: null           // appointment being edited, or null for new
  };

  // --- Elements ---
  var el = {};
  var apptModal, availModal, scopeModal, toast;

  document.addEventListener("DOMContentLoaded", init);

  function init() {
    cache();
    if (!el.calendar) return; // not on the calendar page

    bindToolbar();
    bindModal();
    bindAvailability();

    // Pick the sensible default view for the viewport: day on a phone, week on desktop.
    if (window.matchMedia("(max-width: 767.98px)").matches) {
      setView("day", false);
    } else {
      setView("week", false);
    }

    var first = el.provider && el.provider.value;
    if (first) {
      state.providerId = first;
      refresh();
    } else {
      showEmpty("Select a provider", "Choose a provider to see their calendar.");
    }
  }

  function cache() {
    el.calendar = document.getElementById("schCalendar");
    el.loading = document.getElementById("schLoading");
    el.empty = document.getElementById("schEmpty");
    el.emptyTitle = document.getElementById("schEmptyTitle");
    el.emptyBody = document.getElementById("schEmptyBody");
    el.rangeTitle = document.getElementById("rangeTitle");
    el.provider = document.getElementById("providerSelect");
    el.error = document.getElementById("schError");
    el.errorText = document.getElementById("schErrorText");
    el.viewDay = document.getElementById("viewDay");
    el.viewWeek = document.getElementById("viewWeek");
    el.toastText = document.getElementById("schToastText");

    var m;
    m = document.getElementById("apptModal"); if (m) apptModal = new bootstrap.Modal(m);
    m = document.getElementById("availModal"); if (m) availModal = new bootstrap.Modal(m);
    m = document.getElementById("scopeModal"); if (m) scopeModal = new bootstrap.Modal(m);
    m = document.getElementById("schToast"); if (m) toast = new bootstrap.Toast(m, { delay: 3000 });
  }

  // ------------------------------------------------------------------ toolbar
  function bindToolbar() {
    el.provider.addEventListener("change", function () {
      state.providerId = el.provider.value;
      refresh();
    });
    document.getElementById("btnPrev").addEventListener("click", function () { step(-1); });
    document.getElementById("btnNext").addEventListener("click", function () { step(1); });
    document.getElementById("btnToday").addEventListener("click", function () {
      state.anchor = startOfDay(new Date()); refresh();
    });
    el.viewDay.addEventListener("click", function () { setView("day", true); });
    el.viewWeek.addEventListener("click", function () { setView("week", true); });
    document.getElementById("btnNew").addEventListener("click", function () { openNew(); });
    document.getElementById("btnManageAvail").addEventListener("click", openAvailability);
    document.querySelectorAll("[data-sch-new]").forEach(function (b) {
      b.addEventListener("click", function () { openNew(); });
    });
    // Keyboard nav on the focused grid: left/right = step, t = today.
    el.calendar.addEventListener("keydown", function (e) {
      if (e.key === "ArrowLeft") { step(-1); e.preventDefault(); }
      else if (e.key === "ArrowRight") { step(1); e.preventDefault(); }
      else if (e.key === "t" || e.key === "T") { state.anchor = startOfDay(new Date()); refresh(); }
    });
  }

  function setView(view, doRefresh) {
    state.view = view;
    el.viewDay.setAttribute("aria-pressed", String(view === "day"));
    el.viewWeek.setAttribute("aria-pressed", String(view === "week"));
    if (doRefresh) refresh();
  }

  function step(dir) {
    var days = state.view === "day" ? 1 : 7;
    state.anchor = addDays(state.anchor, dir * days);
    refresh();
  }

  // ------------------------------------------------------------------ range
  function rangeFor() {
    if (state.view === "day") {
      return { from: startOfDay(state.anchor), to: addDays(startOfDay(state.anchor), 1) };
    }
    var from = startOfWeek(state.anchor);
    return { from: from, to: addDays(from, 7) };
  }

  function rangeLabel(from, to) {
    var opt = { day: "numeric", month: "short", year: "numeric" };
    if (state.view === "day") {
      return from.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
    }
    var last = addDays(from, 6);
    return from.toLocaleDateString(undefined, { day: "numeric", month: "short" }) +
      " – " + last.toLocaleDateString(undefined, opt);
  }

  // ------------------------------------------------------------------ fetch
  function refresh() {
    if (!state.providerId) {
      showEmpty("Select a provider", "Choose a provider to see their calendar.");
      return;
    }
    var r = rangeFor();
    el.rangeTitle.textContent = rangeLabel(r.from, r.to);
    showLoading();

    var pid = state.providerId;
    Promise.all([
      api("/api/appointments/calendar?providerId=" + pid +
        "&from=" + isoLocal(r.from) + "&to=" + isoLocal(r.to)),
      api("/api/availability/rules?providerId=" + pid),
      api("/api/availability/exceptions?providerId=" + pid)
    ]).then(function (res) {
      state.appts = res[0] || [];
      state.rules = res[1] || [];
      state.exceptions = res[2] || [];
      render(r.from, r.to);
    }).catch(function (err) {
      showError("Could not load the calendar. " + (err.message || ""));
      hideLoading();
      el.calendar.classList.remove("d-none");
    });
  }

  // ------------------------------------------------------------------ render
  function render(from, to) {
    hideLoading();
    hideEmptyOnly();

    var timed = state.appts.filter(function (a) { return a.type === "APPOINTMENT"; });
    if (timed.length === 0 && state.appts.length === 0) {
      // Grid still renders so the person can book into an empty day; but a clear
      // hint helps. We keep the grid visible and show an inline note above it.
    }

    var days = state.view === "day" ? 1 : 7;
    var grid = document.createElement("div");
    grid.className = "sch-grid " + (state.view === "day" ? "sch-grid--day" : "sch-grid--week");
    grid.setAttribute("data-testid", "calendar-grid");
    grid.setAttribute("data-view", state.view);

    // corner + day headers
    grid.appendChild(cell("sch-grid__corner"));
    var today = startOfDay(new Date());
    for (var d = 0; d < days; d++) {
      var date = addDays(from, d);
      var head = cell("sch-grid__dayhead");
      if (sameDay(date, today)) head.classList.add("is-today");
      head.innerHTML = '<div class="dow">' + DOW_SHORT[date.getDay()] + '</div>' +
        '<div class="dom">' + date.getDate() + '</div>';
      grid.appendChild(head);
    }

    // time gutter
    var gutter = cell("sch-grid__gutter");
    for (var h = DAY_START; h < DAY_END; h++) {
      var hr = cell("sch-grid__hour");
      hr.textContent = fmtHour(h);
      gutter.appendChild(hr);
    }
    grid.appendChild(gutter);

    // day columns
    for (var c = 0; c < days; c++) {
      var colDate = addDays(from, c);
      var col = cell("sch-grid__col");
      col.setAttribute("data-testid", "calendar-day-column");
      col.setAttribute("data-date", ymd(colDate));
      col.style.position = "relative";
      if (sameDay(colDate, today)) col.classList.add("is-today");

      // empty hour slots (for visual rows + click-to-create)
      for (var s = 0; s < HOURS; s++) {
        var slot = cell("sch-grid__slot");
        slot.dataset.hour = String(DAY_START + s);
        slot.dataset.date = ymd(colDate);
        slot.addEventListener("click", onSlotClick);
        col.appendChild(slot);
      }

      paintAvailability(col, colDate);
      paintEvents(col, colDate);
      if (sameDay(colDate, today)) paintNow(col);

      grid.appendChild(col);
    }

    el.calendar.innerHTML = "";
    el.calendar.appendChild(grid);
    el.calendar.classList.remove("d-none");

    if (state.appts.length === 0) {
      showEmpty(
        state.view === "day" ? "No appointments this day" : "No appointments this week",
        "This provider has no bookings in this period."
      );
    }
  }

  function paintAvailability(col, date) {
    var dow = DOW_JAVA[date.getDay()];
    // Whole-day off exception → hatch the entire column.
    var dayOff = state.exceptions.some(function (x) {
      return x.date === ymd(date) && !x.available && !x.startTime;
    });
    if (dayOff) {
      col.appendChild(band("sch-unavail", 0, HOURS * HOUR_H));
      return;
    }
    // Base: hatch everything, then reveal bookable windows from the weekly rules.
    col.appendChild(band("sch-unavail", 0, HOURS * HOUR_H));
    state.rules.filter(function (r) { return r.dayOfWeek === dow; }).forEach(function (r) {
      var top = minutesTop(r.startTime), bot = minutesTop(r.endTime);
      col.appendChild(band("sch-avail", top, bot - top));
    });
    // Dated exceptions: extra hours (green) or a blocked window (gold dashed).
    state.exceptions.filter(function (x) { return x.date === ymd(date) && x.startTime; }).forEach(function (x) {
      var top = minutesTop(x.startTime), bot = minutesTop(x.endTime);
      col.appendChild(band(x.available ? "sch-avail" : "sch-exception", top, bot - top));
    });
  }

  function paintEvents(col, date) {
    state.appts.filter(function (a) { return sameYmd(a.startTime, date); }).forEach(function (a) {
      if (a.type === "DEADLINE") { col.appendChild(deadlineMarker(a)); return; }
      var top = minutesTop(a.startTime);
      var h = Math.max(24, minutesTop(a.endTime) - top);
      var ev = document.createElement("button");
      ev.type = "button";
      ev.className = "sch-event " + statusClass(a.status);
      ev.setAttribute("data-testid", "appointment-block");
      ev.setAttribute("data-appointment-id", String(a.id));
      ev.setAttribute("data-status", a.status || "");
      ev.style.top = top + "px";
      ev.style.height = h + "px";
      ev.setAttribute("aria-label", eventAria(a));
      var flags = "";
      if (a.series) flags += '<i class="bi bi-arrow-repeat" title="Part of a series"></i>';
      if (a.status === "Conflict") flags += '<i class="bi bi-exclamation-triangle-fill" title="Conflict"></i>';
      ev.innerHTML =
        (flags ? '<span class="sch-event__flags">' + flags + "</span>" : "") +
        '<div class="sch-event__title">' + esc(a.title) + "</div>" +
        '<div class="sch-event__time">' + hm(a.startTime) + "–" + hm(a.endTime) + "</div>";
      ev.addEventListener("click", function (e) { e.stopPropagation(); openEdit(a); });
      col.appendChild(ev);
    });
  }

  function deadlineMarker(a) {
    var top = minutesTop(a.startTime);
    var wrap = document.createElement("button");
    wrap.type = "button";
    wrap.className = "sch-event";
    wrap.style.top = Math.max(0, top - 10) + "px";
    wrap.style.height = "22px";
    wrap.style.background = "transparent";
    wrap.style.borderLeft = "0";
    wrap.setAttribute("aria-label", "Deadline: " + a.title + " at " + hm(a.startTime));
    wrap.innerHTML = '<span class="sch-deadline">' + esc(a.title) + "</span>";
    wrap.addEventListener("click", function (e) { e.stopPropagation(); openEdit(a); });
    return wrap;
  }

  function paintNow(col) {
    var now = new Date();
    var mins = (now.getHours() - DAY_START) * 60 + now.getMinutes();
    if (mins < 0 || mins > HOURS * 60) return;
    var line = document.createElement("div");
    line.className = "sch-nowline";
    line.style.top = (mins / 60 * HOUR_H) + "px";
    line.setAttribute("aria-hidden", "true");
    col.appendChild(line);
  }

  // ------------------------------------------------------------------ appt modal
  function bindModal() {
    el.form = document.getElementById("apptForm");
    el.form.addEventListener("submit", onSaveAppt);

    // Type toggle: a deadline has no end/status/recurrence.
    document.querySelectorAll('input[name="apptType"]').forEach(function (r) {
      r.addEventListener("change", syncTypeUi);
    });

    // Recurrence toggle + editor.
    document.getElementById("apptRepeat").addEventListener("change", function () {
      document.getElementById("recurEditor").classList.toggle("d-none", !this.checked);
      updateRecurSummary();
    });
    document.querySelectorAll(".sch-recur__day").forEach(function (b) {
      b.addEventListener("click", function () {
        var on = b.getAttribute("aria-pressed") === "true";
        b.setAttribute("aria-pressed", String(!on));
        updateRecurSummary();
      });
    });
    ["recurFreq", "recurInterval", "recurUntil", "recurCount"].forEach(function (id) {
      var n = document.getElementById(id);
      if (n) n.addEventListener("input", updateRecurSummary);
    });
    document.querySelectorAll('input[name="recurEnd"]').forEach(function (r) {
      r.addEventListener("change", function () {
        var byDate = document.getElementById("endOnDate").checked;
        document.querySelector("[data-recur-until-wrap]").classList.toggle("d-none", !byDate);
        document.querySelector("[data-recur-count-wrap]").classList.toggle("d-none", byDate);
        updateRecurSummary();
      });
    });
    document.getElementById("recurFreq").addEventListener("change", function () {
      var freq = this.value;
      document.getElementById("recurIntervalUnit").textContent =
        freq === "DAILY" ? "days" : freq === "MONTHLY" ? "months" : "weeks";
      document.querySelector("[data-recur-weekdays-wrap]").classList.toggle("d-none", freq !== "WEEKLY");
      updateRecurSummary();
    });
  }

  function syncTypeUi() {
    var isDeadline = document.getElementById("typeDeadline").checked;
    document.querySelector("[data-appt-end-wrap]").classList.toggle("d-none", isDeadline);
    document.getElementById("apptEnd").required = !isDeadline;
    document.querySelector("[data-recur-toggle-wrap]").classList.toggle("d-none", isDeadline);
    if (isDeadline) {
      document.getElementById("apptRepeat").checked = false;
      document.getElementById("recurEditor").classList.add("d-none");
    }
  }

  function openNew(preset) {
    state.editing = null;
    el.form.reset();
    document.getElementById("apptId").value = "";
    document.getElementById("apptModalTitle").textContent = "New appointment";
    document.getElementById("typeAppt").checked = true;
    hide(document.getElementById("apptError"));
    if (state.providerId) document.getElementById("apptProvider").value = state.providerId;
    var start = preset && preset.start ? preset.start : defaultStart();
    document.getElementById("apptStart").value = start;
    document.getElementById("apptEnd").value = plusMinutes(start, 30);
    document.querySelector("[data-appt-status-wrap]").classList.remove("d-none");
    document.getElementById("recurEditor").classList.add("d-none");
    document.getElementById("apptRepeat").checked = false;
    syncTypeUi();
    updateRecurSummary();
    apptModal.show();
  }

  function openEdit(a) {
    // A series occurrence asks for the edit scope first (this / following / all).
    if (a.series) {
      showScopePicker(function (scope) { fillEdit(a, scope); });
      return;
    }
    fillEdit(a, "one");
  }

  function fillEdit(a, scope) {
    state.editing = { appt: a, scope: scope };
    el.form.reset();
    document.getElementById("apptId").value = a.id;
    document.getElementById("apptModalTitle").textContent = "Edit appointment";
    hide(document.getElementById("apptError"));
    (a.type === "DEADLINE" ? document.getElementById("typeDeadline") : document.getElementById("typeAppt")).checked = true;
    document.getElementById("apptTitle").value = a.title || "";
    if (a.customer) document.getElementById("apptCustomer").value = a.customer.id;
    if (a.provider) document.getElementById("apptProvider").value = a.provider.id;
    document.getElementById("apptStart").value = (a.startTime || "").slice(0, 16);
    document.getElementById("apptEnd").value = (a.endTime || "").slice(0, 16);
    document.getElementById("apptPriority").value = a.priority || "Medium";
    document.getElementById("apptStatus").value = a.status || "Pending";
    document.getElementById("apptDesc").value = a.description || "";
    // Editing does not re-open recurrence (series structure is edited via scope).
    document.querySelector("[data-recur-toggle-wrap]").classList.add("d-none");
    document.getElementById("recurEditor").classList.add("d-none");
    syncTypeUi();
    document.querySelector("[data-recur-toggle-wrap]").classList.toggle(
      "d-none", a.type === "DEADLINE" || !!a.series || true); // hidden on edit
    apptModal.show();
  }

  function onSaveAppt(e) {
    e.preventDefault();
    if (!el.form.checkValidity()) { el.form.reportValidity(); return; }
    var repeat = document.getElementById("apptRepeat").checked && !state.editing;
    if (repeat) { return saveSeries(); }
    return saveSingle();
  }

  function saveSingle() {
    var isDeadline = document.getElementById("typeDeadline").checked;
    var start = document.getElementById("apptStart").value;
    var end = isDeadline ? start : document.getElementById("apptEnd").value;
    var body = {
      title: val("apptTitle"),
      type: isDeadline ? "DEADLINE" : "APPOINTMENT",
      customer: { id: Number(val("apptCustomer")) },
      provider: { id: Number(val("apptProvider")) },
      startTime: start,
      endTime: end,
      priority: val("apptPriority"),
      status: val("apptStatus"),
      description: val("apptDesc")
    };
    var editing = state.editing;
    var url = editing ? "/api/appointments/" + editing.appt.id : "/api/appointments";
    var method = editing ? "PUT" : "POST";
    saving(true);
    api(url, method, body).then(function () {
      saving(false);
      apptModal.hide();
      showToast(editing ? "Appointment updated." : "Appointment booked.");
      refresh();
    }).catch(function (err) { saving(false); apptError(err); });
  }

  function saveSeries() {
    var freq = val("recurFreq");
    var start = document.getElementById("apptStart").value; // yyyy-MM-ddTHH:mm
    var body = {
      title: val("apptTitle"),
      customer: { id: Number(val("apptCustomer")) },
      provider: { id: Number(val("apptProvider")) },
      frequency: freq,
      intervalCount: Number(val("recurInterval")) || 1,
      startDate: start.slice(0, 10),
      startTime: start.slice(11, 16),
      durationMin: durationMinutes(),
      priority: val("apptPriority"),
      description: val("apptDesc")
    };
    if (document.getElementById("endOnDate").checked) body.untilDate = val("recurUntil");
    else body.occurrenceCount = Number(val("recurCount")) || 1;
    if (!body.untilDate && !body.occurrenceCount) {
      return apptError(new Error("Set an end date or a number of occurrences."));
    }
    saving(true);
    api("/api/appointment-series", "POST", body).then(function (generated) {
      saving(false);
      apptModal.hide();
      var conflicts = (generated || []).filter(function (a) { return a.status === "Conflict"; }).length;
      showToast("Series created — " + (generated || []).length + " appointments" +
        (conflicts ? " (" + conflicts + " flagged as conflicts)" : "") + ".");
      refresh();
    }).catch(function (err) { saving(false); apptError(err); });
  }

  // ------------------------------------------------------------------ recurrence summary
  var DOW_FULL = { 7: "Sun", 1: "Mon", 2: "Tue", 3: "Wed", 4: "Thu", 5: "Fri", 6: "Sat" };

  function updateRecurSummary() {
    var out = document.getElementById("recurSummary");
    if (!out) return;
    if (!document.getElementById("apptRepeat").checked) {
      out.innerHTML = '<i class="bi bi-arrow-repeat me-1"></i> Pick the days and an end to preview the series.';
      return;
    }
    var freq = val("recurFreq");
    var n = Number(val("recurInterval")) || 1;
    var unit = freq === "DAILY" ? "day" : freq === "MONTHLY" ? "month" : "week";
    var text = "Repeats " + (n === 1 ? "every " + unit : "every " + n + " " + unit + "s");

    if (freq === "WEEKLY") {
      var picked = [].slice.call(document.querySelectorAll('.sch-recur__day[aria-pressed="true"]'))
        .map(function (b) { return DOW_FULL[Number(b.dataset.dow)]; });
      if (picked.length) text += " on " + picked.join(", ");
    }
    if (document.getElementById("endOnDate").checked) {
      var until = val("recurUntil");
      text += until ? ", until " + until : ", until a chosen date";
    } else {
      var cnt = Number(val("recurCount")) || 0;
      text += cnt ? ", for " + cnt + " occurrence" + (cnt === 1 ? "" : "s") : ", for a number of occurrences";
    }
    out.innerHTML = '<i class="bi bi-arrow-repeat me-1"></i> ' + esc(text) + ".";
  }

  // ------------------------------------------------------------------ series edit scope
  function showScopePicker(onConfirm) {
    var picker = document.querySelector('input[name="scope"][value="one"]');
    if (picker) picker.checked = true;
    var btn = document.getElementById("scopeConfirm");
    var handler = function () {
      var scope = (document.querySelector('input[name="scope"]:checked') || {}).value || "one";
      scopeModal.hide();
      btn.removeEventListener("click", handler);
      onConfirm(scope);
    };
    btn.addEventListener("click", handler);
    scopeModal.show();
  }

  // ------------------------------------------------------------------ availability editor
  var DAY_LABEL = {
    MONDAY: "Monday", TUESDAY: "Tuesday", WEDNESDAY: "Wednesday", THURSDAY: "Thursday",
    FRIDAY: "Friday", SATURDAY: "Saturday", SUNDAY: "Sunday"
  };
  var DAY_ORDER = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

  function bindAvailability() {
    var ruleForm = document.getElementById("ruleForm");
    if (ruleForm) ruleForm.addEventListener("submit", onAddRule);
    var excForm = document.getElementById("exceptionForm");
    if (excForm) excForm.addEventListener("submit", onAddException);
    var excKind = document.getElementById("excKind");
    if (excKind) excKind.addEventListener("change", function () {
      var extra = this.value === "extra";
      document.querySelectorAll("[data-exc-times]").forEach(function (n) {
        n.classList.toggle("d-none", !extra);
      });
    });
  }

  function openAvailability() {
    if (!state.providerId) {
      showError("Choose a provider before editing availability.");
      return;
    }
    hide(document.getElementById("availError"));
    renderAvailability();
    availModal.show();
  }

  function renderAvailability() {
    var rulesBox = document.getElementById("availRules");
    var excBox = document.getElementById("availExceptions");
    if (state.rules.length === 0) {
      rulesBox.innerHTML = '<p class="form-text mb-0">No weekly hours yet — the provider has no bookable time.</p>';
    } else {
      var sorted = state.rules.slice().sort(function (a, b) {
        return DAY_ORDER.indexOf(a.dayOfWeek) - DAY_ORDER.indexOf(b.dayOfWeek) ||
          (a.startTime || "").localeCompare(b.startTime || "");
      });
      rulesBox.innerHTML = sorted.map(function (r) {
        return '<div class="sch-availrow">' +
          '<span class="sch-availrow__day">' + esc(DAY_LABEL[r.dayOfWeek] || r.dayOfWeek) + "</span>" +
          '<span>' + hhmm(r.startTime) + " – " + hhmm(r.endTime) + "</span>" +
          '<button type="button" class="btn btn-sm btn-outline-danger" data-del-rule="' + r.id +
          '" aria-label="Remove ' + esc(DAY_LABEL[r.dayOfWeek]) + ' hours"><i class="bi bi-trash"></i></button>' +
          "</div>";
      }).join("");
      rulesBox.querySelectorAll("[data-del-rule]").forEach(function (b) {
        b.addEventListener("click", function () { delRule(b.dataset.delRule); });
      });
    }

    if (state.exceptions.length === 0) {
      excBox.innerHTML = '<p class="form-text mb-0">No dated exceptions.</p>';
    } else {
      var xs = state.exceptions.slice().sort(function (a, b) { return (a.date || "").localeCompare(b.date || ""); });
      excBox.innerHTML = xs.map(function (x) {
        var label = x.available
          ? "Extra hours " + hhmm(x.startTime) + " – " + hhmm(x.endTime)
          : (x.startTime ? "Blocked " + hhmm(x.startTime) + " – " + hhmm(x.endTime) : "Time off (whole day)");
        return '<div class="sch-availrow">' +
          '<span class="sch-availrow__day">' + esc(x.date) + "</span>" +
          "<span>" + esc(label) + "</span>" +
          '<button type="button" class="btn btn-sm btn-outline-danger" data-del-exc="' + x.id +
          '" aria-label="Remove exception on ' + esc(x.date) + '"><i class="bi bi-trash"></i></button>' +
          "</div>";
      }).join("");
      excBox.querySelectorAll("[data-del-exc]").forEach(function (b) {
        b.addEventListener("click", function () { delException(b.dataset.delExc); });
      });
    }
  }

  function onAddRule(e) {
    e.preventDefault();
    hide(document.getElementById("availError"));
    var body = {
      provider: { id: Number(state.providerId) },
      dayOfWeek: val("ruleDay"),
      startTime: val("ruleStart"),
      endTime: val("ruleEnd")
    };
    if (body.startTime >= body.endTime) { return availError(new Error("From must be before To.")); }
    api("/api/availability/rules", "POST", body).then(function (saved) {
      state.rules.push(saved);
      renderAvailability();
      showToast("Weekly hours added.");
      refresh();
    }).catch(availError);
  }

  function delRule(id) {
    api("/api/availability/rules/" + id, "DELETE").then(function () {
      state.rules = state.rules.filter(function (r) { return String(r.id) !== String(id); });
      renderAvailability();
      showToast("Weekly hours removed.");
      refresh();
    }).catch(availError);
  }

  function onAddException(e) {
    e.preventDefault();
    hide(document.getElementById("availError"));
    var extra = val("excKind") === "extra";
    var date = val("excDate");
    if (!date) { return availError(new Error("Pick a date for the exception.")); }
    var body = { provider: { id: Number(state.providerId) }, date: date, available: extra };
    if (extra) {
      body.startTime = val("excStart");
      body.endTime = val("excEnd");
      if (!body.startTime || !body.endTime) { return availError(new Error("Extra hours need a start and end time.")); }
      if (body.startTime >= body.endTime) { return availError(new Error("From must be before To.")); }
    }
    api("/api/availability/exceptions", "POST", body).then(function (saved) {
      state.exceptions.push(saved);
      renderAvailability();
      showToast("Exception added.");
      refresh();
    }).catch(availError);
  }

  function delException(id) {
    api("/api/availability/exceptions/" + id, "DELETE").then(function () {
      state.exceptions = state.exceptions.filter(function (x) { return String(x.id) !== String(id); });
      renderAvailability();
      showToast("Exception removed.");
      refresh();
    }).catch(availError);
  }

  // ------------------------------------------------------------------ slot click → new
  function onSlotClick(e) {
    var slot = e.currentTarget;
    var start = slot.dataset.date + "T" + pad2(Number(slot.dataset.hour)) + ":00";
    openNew({ start: start });
  }

  // ------------------------------------------------------------------ HTTP
  function api(url, method, body) {
    var opts = {
      method: method || "GET",
      headers: { "Accept": "application/json" },
      credentials: "same-origin"
    };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    var csrf = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    if (csrf && csrfHeader && method && method !== "GET") {
      opts.headers[csrfHeader.content] = csrf.content;
    }
    return fetch(url, opts).then(function (res) {
      if (res.status === 204) return null;
      return res.text().then(function (text) {
        var data = null;
        if (text) { try { data = JSON.parse(text); } catch (_) { data = text; } }
        if (!res.ok) {
          var msg = (data && data.message) ? data.message
            : (typeof data === "string" && data) ? data
            : res.status === 409 ? "That time is unavailable or already booked."
            : "Request failed (" + res.status + ")";
          var err = new Error(msg);
          err.status = res.status;
          throw err;
        }
        return data;
      });
    });
  }

  // ------------------------------------------------------------------ state banners
  function showLoading() {
    el.loading.classList.remove("d-none");
    el.empty.classList.add("d-none");
    el.calendar.classList.add("d-none");
  }
  function hideLoading() { el.loading.classList.add("d-none"); }
  function showEmpty(title, bodyText) {
    el.emptyTitle.textContent = title;
    el.emptyBody.textContent = bodyText;
    el.empty.classList.remove("d-none");
    el.loading.classList.add("d-none");
    el.calendar.classList.add("d-none");
  }
  function hideEmptyOnly() { el.empty.classList.add("d-none"); }
  function showError(msg) {
    el.errorText.textContent = msg;
    el.error.classList.remove("d-none");
  }
  function apptError(err) {
    var box = document.getElementById("apptError");
    document.getElementById("apptErrorText").textContent = err.message || "Could not save.";
    box.classList.remove("d-none");
    box.scrollIntoView({ block: "nearest" });
  }
  function availError(err) {
    var box = document.getElementById("availError");
    document.getElementById("availErrorText").textContent = err.message || "Could not save.";
    box.classList.remove("d-none");
  }
  function saving(on) {
    var btn = document.getElementById("apptSave");
    var spin = document.getElementById("apptSaveSpin");
    btn.disabled = on;
    spin.classList.toggle("d-none", !on);
  }
  function showToast(msg) {
    el.toastText.textContent = msg;
    if (toast) toast.show();
  }
  function hide(node) { if (node) node.classList.add("d-none"); }

  // ------------------------------------------------------------------ DOM helpers
  function cell(cls) { var d = document.createElement("div"); d.className = cls; return d; }
  function band(cls, top, height) {
    var d = document.createElement("div");
    d.className = cls;
    d.style.top = top + "px";
    d.style.height = Math.max(0, height) + "px";
    d.setAttribute("aria-hidden", "true");
    d.setAttribute("data-testid", "availability-band");
    d.setAttribute("data-kind",
      cls === "sch-avail" ? "available" : cls === "sch-exception" ? "exception" : "unavailable");
    return d;
  }
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  function val(id) { var n = document.getElementById(id); return n ? n.value : ""; }

  // ------------------------------------------------------------------ status → class + a11y label
  function statusClass(status) {
    switch (status) {
      case "In Progress": return "sch-event--progress";
      case "Completed":   return "sch-event--completed";
      case "Cancelled":   return "sch-event--cancelled";
      case "Conflict":    return "sch-event--conflict";
      default:            return "";  // Pending / Scheduled use the base .sch-event style
    }
  }
  function eventAria(a) {
    return (a.status || "Scheduled") + " appointment: " + a.title +
      ", " + hm(a.startTime) + " to " + hm(a.endTime) +
      (a.series ? ", part of a repeating series" : "") +
      (a.status === "Conflict" ? ", flagged as a conflict" : "");
  }

  // ------------------------------------------------------------------ geometry / time
  function minutesTop(t) {
    // t is "HH:mm[:ss]" (LocalTime) or "yyyy-MM-ddTHH:mm[:ss]" (LocalDateTime)
    var hh, mm;
    if (t.indexOf("T") >= 0) { var p = t.split("T")[1].split(":"); hh = +p[0]; mm = +p[1]; }
    else { var q = t.split(":"); hh = +q[0]; mm = +q[1]; }
    return ((hh - DAY_START) * 60 + mm) / 60 * HOUR_H;
  }
  function durationMinutes() {
    var s = document.getElementById("apptStart").value;
    var e = document.getElementById("apptEnd").value;
    if (!s || !e) return 30;
    var d = (new Date(e) - new Date(s)) / 60000;
    return d > 0 ? d : 30;
  }

  // ------------------------------------------------------------------ formatting
  function fmtHour(h) {
    var ampm = h < 12 ? "am" : "pm";
    var hr = h % 12; if (hr === 0) hr = 12;
    return hr + " " + ampm;
  }
  function hm(t) {
    if (!t) return "";
    var time = t.indexOf("T") >= 0 ? t.split("T")[1] : t;
    var p = time.split(":");
    return fmtClock(+p[0], +p[1]);
  }
  function hhmm(t) { if (!t) return ""; var p = t.split(":"); return fmtClock(+p[0], +p[1]); }
  function fmtClock(h, m) {
    var ampm = h < 12 ? "am" : "pm";
    var hr = h % 12; if (hr === 0) hr = 12;
    return hr + (m ? ":" + pad2(m) : "") + ampm;
  }

  // ------------------------------------------------------------------ date utils
  function startOfDay(d) { var x = new Date(d); x.setHours(0, 0, 0, 0); return x; }
  function addDays(d, n) { var x = new Date(d); x.setDate(x.getDate() + n); return x; }
  function startOfWeek(d) {
    // Week starts Sunday, matching the day-of-week header order.
    var x = startOfDay(d);
    x.setDate(x.getDate() - x.getDay());
    return x;
  }
  function sameDay(a, b) {
    return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  }
  function sameYmd(iso, date) { return iso && iso.slice(0, 10) === ymd(date); }
  function ymd(d) { return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()); }
  function isoLocal(d) { return ymd(d) + "T" + pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":00"; }
  function defaultStart() {
    var d = new Date();
    d.setMinutes(d.getMinutes() < 30 ? 30 : 0);
    if (d.getMinutes() === 0) d.setHours(d.getHours() + 1);
    d.setSeconds(0, 0);
    return ymd(d) + "T" + pad2(d.getHours()) + ":" + pad2(d.getMinutes());
  }
  function plusMinutes(local, mins) {
    var d = new Date(local);
    d.setMinutes(d.getMinutes() + mins);
    return ymd(d) + "T" + pad2(d.getHours()) + ":" + pad2(d.getMinutes());
  }
  function pad2(n) { return (n < 10 ? "0" : "") + n; }

})();
