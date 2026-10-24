/*
 * Postmaster UI behaviour: the menu, toasts, dialogs, confirmations, tables and relative times.
 * Plain JavaScript with no dependency except HTMX. The behaviours and selectors are described in
 * docs/dev/contracts.md ("Phase 2: UI foundation"). Exposes window.Postmaster.
 */
(function () {
  "use strict";

  var TOAST_FADE_MS = 4000;
  var TOAST_REMOVE_MS = 4500;
  var TOAST_LEVELS = ["success", "info", "error"];
  var MIN_COLUMN_PX = 48;
  var RESIZE_STEP_PX = 16;
  var TIME_REFRESH_MS = 60000;

  function byId(id) {
    return document.getElementById(id);
  }

  function each(root, selector, fn) {
    if (root.matches && root.matches(selector)) {
      fn(root);
    }
    Array.prototype.forEach.call(root.querySelectorAll(selector), fn);
  }

  // -------------------------------------------------------------------------------------------
  // Menu
  // -------------------------------------------------------------------------------------------

  function initMenu() {
    var button = byId("pm-menu-button");
    var menu = byId("pm-menu");
    if (!button || !menu) {
      return;
    }
    var pointerInMenu = false;

    function items() {
      return Array.prototype.slice.call(menu.querySelectorAll("a[href]"));
    }

    function isOpen() {
      return !menu.hidden;
    }

    function setOpen(open, focusTarget) {
      menu.hidden = !open;
      button.setAttribute("aria-expanded", open ? "true" : "false");
      if (focusTarget) {
        focusTarget.focus();
      }
    }

    button.addEventListener("click", function () {
      if (isOpen()) {
        setOpen(false);
      } else {
        setOpen(true, items()[0]);
      }
    });

    menu.addEventListener("keydown", function (event) {
      var list = items();
      var index = list.indexOf(document.activeElement);
      var target = null;
      if (event.key === "ArrowDown") {
        target = list[(index + 1) % list.length];
      } else if (event.key === "ArrowUp") {
        target = list[(index - 1 + list.length) % list.length];
      } else if (event.key === "Home") {
        target = list[0];
      } else if (event.key === "End") {
        target = list[list.length - 1];
      }
      if (target) {
        event.preventDefault();
        target.focus();
      }
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && isOpen()) {
        event.preventDefault();
        setOpen(false, button);
      }
    });

    document.addEventListener("click", function (event) {
      if (isOpen() && !menu.contains(event.target) && !button.contains(event.target)) {
        setOpen(false);
      }
    });

    menu.addEventListener("pointerdown", function () {
      pointerInMenu = true;
    });
    document.addEventListener("pointerup", function () {
      pointerInMenu = false;
    });

    menu.addEventListener("focusout", function (event) {
      var next = event.relatedTarget;
      if (pointerInMenu || (next && (menu.contains(next) || next === button))) {
        return;
      }
      // Focus left the menu, e.g. by tabbing past the last item
      setOpen(false);
    });
  }

  // -------------------------------------------------------------------------------------------
  // Toasts
  // -------------------------------------------------------------------------------------------

  // A modal dialog makes the rest of the page inert and covers it with its backdrop, so while one
  // is open toasts live in a container inside it, and move back to the page when it closes
  function openModal() {
    var open = Array.prototype.filter.call(document.querySelectorAll("dialog[open]"), function (dialog) {
      try {
        return dialog.matches(":modal");
      } catch (e) {
        return true;
      }
    });
    return open[open.length - 1] || null;
  }

  function dialogToasts(dialog) {
    var container = dialog.querySelector(":scope > .pm-toasts");
    if (!container) {
      container = document.createElement("div");
      container.className = "pm-toasts";
      container.setAttribute("data-testid", "dialog-toasts");
      dialog.appendChild(container);
    }
    return container;
  }

  function moveToasts(from, to) {
    if (from && to && from !== to) {
      Array.prototype.slice.call(from.querySelectorAll(":scope > .pm-toast")).forEach(function (element) {
        to.appendChild(element);
      });
    }
  }

  function toastContainer() {
    var dialog = openModal();
    return dialog ? dialogToasts(dialog) : byId("pm-toasts");
  }

  function toast(level, message) {
    var container = toastContainer();
    if (!container) {
      return null;
    }
    if (TOAST_LEVELS.indexOf(level) < 0) {
      level = "info";
    }
    var element = document.createElement("div");
    element.className = "pm-toast pm-toast--" + level;
    element.setAttribute("data-testid", "toast");
    element.setAttribute("data-level", level);
    element.setAttribute("role", level === "error" ? "alert" : "status");

    var text = document.createElement("span");
    text.className = "pm-toast__message";
    text.setAttribute("data-testid", "toast-message");
    text.textContent = String(message);

    var close = document.createElement("button");
    close.type = "button";
    close.className = "pm-toast__close";
    close.setAttribute("data-testid", "toast-close");
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";

    element.appendChild(text);
    element.appendChild(close);
    container.appendChild(element);
    initToast(element);
    return element;
  }

  function initToast(element) {
    if (element.hasAttribute("data-pm-ready")) {
      return;
    }
    element.setAttribute("data-pm-ready", "");
    var close = element.querySelector(".pm-toast__close");
    if (close) {
      close.addEventListener("click", function () {
        element.remove();
      });
    }
    if (element.getAttribute("data-level") !== "error") {
      scheduleFade(element);
    }
  }

  function scheduleFade(element) {
    var hovered = false;
    var due = false;
    element.addEventListener("pointerenter", function () {
      hovered = true;
    });
    element.addEventListener("pointerleave", function () {
      hovered = false;
      if (due) {
        leave(element);
      }
    });
    setTimeout(function () {
      due = true;
      if (!hovered) {
        leave(element);
      }
    }, TOAST_FADE_MS);
  }

  function leave(element) {
    if (element.classList.contains("pm-toast--leaving")) {
      return;
    }
    element.classList.add("pm-toast--leaving");
    setTimeout(function () {
      element.remove();
    }, TOAST_REMOVE_MS - TOAST_FADE_MS);
  }

  function initToastEvents() {
    document.body.addEventListener("pm-toast", function (event) {
      var toasts = (event.detail && event.detail.toasts) || [];
      toasts.forEach(function (item) {
        toast(item.level, item.message);
      });
    });
    document.body.addEventListener("htmx:responseError", function (event) {
      var xhr = event.detail && event.detail.xhr;
      var trigger = xhr ? xhr.getResponseHeader("HX-Trigger") : null;
      if (trigger && trigger.indexOf("pm-toast") >= 0) {
        // The server already explained the failure
        return;
      }
      toast("error", "Request failed (" + (xhr ? xhr.status : "unknown") + ")");
    });
    document.body.addEventListener("htmx:sendError", function () {
      toast("error", "Network error: the server could not be reached");
    });
  }

  // -------------------------------------------------------------------------------------------
  // Dialogs and confirmations
  // -------------------------------------------------------------------------------------------

  var openers = new WeakMap();
  var confirmResolve = null;

  var FOCUSABLE = "a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), " +
      "select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])";

  function openDialog(id, opener) {
    var dialog = byId(id);
    if (!dialog || typeof dialog.showModal !== "function") {
      return;
    }
    initDialog(dialog);
    if (dialog.open) {
      return;
    }
    openers.set(dialog, opener || document.activeElement);
    dialog.showModal();
    moveToasts(byId("pm-toasts"), dialogToasts(dialog));
    var target = dialog.querySelector("[autofocus]");
    if (!target) {
      var body = dialog.querySelector(".pm-dialog__body");
      target = (body && body.querySelector(FOCUSABLE)) || dialog.querySelector(".pm-dialog__close");
    }
    if (target) {
      target.focus();
    }
  }

  function closeDialog(id, returnValue) {
    var dialog = byId(id);
    if (dialog && dialog.open) {
      dialog.close(returnValue);
    }
  }

  function initDialog(dialog) {
    if (dialog.hasAttribute("data-pm-ready")) {
      return;
    }
    dialog.setAttribute("data-pm-ready", "");
    dialog.addEventListener("close", function () {
      var inner = dialog.querySelector(":scope > .pm-toasts");
      moveToasts(inner, toastContainer());
      var opener = openers.get(dialog);
      openers.delete(dialog);
      if (opener && opener.isConnected && typeof opener.focus === "function") {
        opener.focus();
      }
    });
    dialog.addEventListener("click", function (event) {
      if (event.target !== dialog) {
        return;
      }
      // A click on the dialog element itself, outside its content, is on the backdrop
      var box = dialog.getBoundingClientRect();
      var inside = event.clientX >= box.left && event.clientX <= box.right &&
          event.clientY >= box.top && event.clientY <= box.bottom;
      if (!inside) {
        dialog.close("cancel");
      }
    });
  }

  function confirmDialog(message, okLabel) {
    return new Promise(function (resolve) {
      var dialog = byId("pm-confirm");
      if (!dialog || typeof dialog.showModal !== "function") {
        resolve(window.confirm(message));
        return;
      }
      if (confirmResolve) {
        confirmResolve(false);
      }
      confirmResolve = resolve;
      dialog.querySelector("[data-testid=confirm-message]").textContent = message;
      dialog.querySelector("[data-testid=confirm-ok]").textContent = okLabel || "OK";
      dialog.returnValue = "";
      openDialog("pm-confirm", document.activeElement);
      dialog.querySelector("[data-testid=confirm-cancel]").focus();
    });
  }

  function initConfirmDialog() {
    var dialog = byId("pm-confirm");
    if (!dialog) {
      return;
    }
    initDialog(dialog);
    dialog.querySelector("[data-testid=confirm-ok]").addEventListener("click", function () {
      dialog.close("ok");
    });
    dialog.querySelector("[data-testid=confirm-cancel]").addEventListener("click", function () {
      dialog.close("cancel");
    });
    dialog.addEventListener("close", function () {
      var resolve = confirmResolve;
      confirmResolve = null;
      if (resolve) {
        resolve(dialog.returnValue === "ok");
      }
    });
  }

  function initDialogEvents() {
    document.addEventListener("click", function (event) {
      var target = event.target;
      if (!(target instanceof Element)) {
        return;
      }
      var opener = target.closest("[data-pm-open]");
      if (opener) {
        event.preventDefault();
        openDialog(opener.getAttribute("data-pm-open"), opener);
        return;
      }
      var closer = target.closest("[data-pm-close]");
      if (closer) {
        var dialog = closer.closest("dialog");
        if (dialog && dialog.open) {
          event.preventDefault();
          dialog.close("cancel");
        }
      }
    });

    // Plain links and form buttons: data-pm-confirm="Question?"
    document.addEventListener("click", function (event) {
      var target = event.target instanceof Element ? event.target.closest("[data-pm-confirm]") : null;
      if (!target) {
        return;
      }
      if (target.hasAttribute("data-pm-confirmed")) {
        target.removeAttribute("data-pm-confirmed");
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      confirmDialog(target.getAttribute("data-pm-confirm"), target.getAttribute("data-pm-confirm-label"))
          .then(function (ok) {
            if (ok) {
              target.setAttribute("data-pm-confirmed", "");
              target.click();
            }
          });
    }, true);

    // HTMX actions: hx-confirm="Question?"
    document.body.addEventListener("htmx:confirm", function (event) {
      var question = event.detail && event.detail.question;
      if (!question) {
        return;
      }
      event.preventDefault();
      var element = event.detail.elt;
      var label = element ? element.getAttribute("data-pm-confirm-label") : null;
      confirmDialog(question, label).then(function (ok) {
        if (ok) {
          event.detail.issueRequest(true);
        }
      });
    });

    document.body.addEventListener("pm-dialog-open", function (event) {
      if (event.detail && event.detail.id) {
        openDialog(event.detail.id, event.target);
      }
    });
    document.body.addEventListener("pm-dialog-close", function (event) {
      if (event.detail && event.detail.id) {
        closeDialog(event.detail.id, "cancel");
      }
    });
  }

  // -------------------------------------------------------------------------------------------
  // Tables: column resizing, truncation tooltips and per-priority column visibility
  // -------------------------------------------------------------------------------------------

  function headers(table) {
    var head = table.tHead;
    return head && head.rows.length ? Array.prototype.slice.call(head.rows[0].cells) : [];
  }

  function visibleHeaders(table) {
    return headers(table).filter(function (th) {
      return window.getComputedStyle(th).display !== "none";
    });
  }

  function storageKey(table, signature) {
    return "pm-table-widths:" + table.id + ":" + signature;
  }

  function loadWidths(table, signature) {
    try {
      var stored = window.localStorage.getItem(storageKey(table, signature));
      return stored ? JSON.parse(stored) : null;
    } catch (e) {
      return null;
    }
  }

  function saveWidths(table) {
    var widths = {};
    visibleHeaders(table).forEach(function (th) {
      if (th.style.width) {
        widths[th.getAttribute("data-col")] = parseFloat(th.style.width);
      }
    });
    try {
      window.localStorage.setItem(storageKey(table, table.pmSignature), JSON.stringify(widths));
    } catch (e) {
      // Storage unavailable: widths last until the page is left
    }
  }

  function clearWidths(table) {
    headers(table).forEach(function (th) {
      th.style.width = "";
    });
    try {
      window.localStorage.removeItem(storageKey(table, table.pmSignature));
    } catch (e) {
      // Nothing stored
    }
  }

  function onTableLayout(table) {
    var visible = visibleHeaders(table);
    var signature = visible.map(function (th) {
      return th.getAttribute("data-col");
    }).join(",");
    if (table.pmSignature === signature) {
      return;
    }
    table.pmSignature = signature;
    headers(table).forEach(function (th) {
      th.style.width = "";
    });
    var stored = loadWidths(table, signature);
    if (stored) {
      visible.forEach(function (th) {
        var width = stored[th.getAttribute("data-col")];
        if (typeof width === "number" && width > 0) {
          th.style.width = width + "%";
        }
      });
    }
    var last = visible[visible.length - 1];
    headers(table).forEach(function (th) {
      var resizer = th.querySelector(".pm-resizer");
      if (resizer) {
        resizer.classList.toggle("pm-resizer--hidden", th === last);
      }
    });
  }

  function nextVisible(table, th) {
    var visible = visibleHeaders(table);
    var index = visible.indexOf(th);
    return index >= 0 ? visible[index + 1] || null : null;
  }

  // Sets every visible column to its current width as a percentage of the table
  function freezeWidths(table) {
    var tableWidth = table.getBoundingClientRect().width;
    var visible = visibleHeaders(table);
    var widths = visible.map(function (th) {
      return th.getBoundingClientRect().width;
    });
    visible.forEach(function (th, i) {
      th.style.width = (widths[i] / tableWidth * 100) + "%";
    });
    return tableWidth;
  }

  // Gives the pair of columns new widths with the same total, so the table's width never changes
  function setPairWidths(th, next, width, nextWidth, tableWidth) {
    var total = width + nextWidth;
    var minimum = Math.min(MIN_COLUMN_PX, total / 2);
    var clamped = Math.max(minimum, Math.min(total - minimum, width));
    th.style.width = (clamped / tableWidth * 100) + "%";
    next.style.width = ((total - clamped) / tableWidth * 100) + "%";
  }

  function initResizer(table, resizer) {
    var th = resizer.closest("th");

    resizer.addEventListener("pointerdown", function (event) {
      if (event.button !== 0) {
        return;
      }
      var next = nextVisible(table, th);
      if (!next) {
        return;
      }
      event.preventDefault();
      var tableWidth = freezeWidths(table);
      var startX = event.clientX;
      var startWidth = th.getBoundingClientRect().width;
      var startNext = next.getBoundingClientRect().width;
      resizer.classList.add("pm-resizer--active");
      if (resizer.setPointerCapture) {
        resizer.setPointerCapture(event.pointerId);
      }

      function move(moveEvent) {
        var delta = moveEvent.clientX - startX;
        setPairWidths(th, next, startWidth + delta, startNext - delta, tableWidth);
      }

      function end() {
        resizer.classList.remove("pm-resizer--active");
        resizer.removeEventListener("pointermove", move);
        resizer.removeEventListener("pointerup", end);
        resizer.removeEventListener("pointercancel", end);
        saveWidths(table);
      }

      resizer.addEventListener("pointermove", move);
      resizer.addEventListener("pointerup", end);
      resizer.addEventListener("pointercancel", end);
    });

    resizer.addEventListener("keydown", function (event) {
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        var next = nextVisible(table, th);
        if (!next) {
          return;
        }
        event.preventDefault();
        var tableWidth = freezeWidths(table);
        var delta = event.key === "ArrowRight" ? RESIZE_STEP_PX : -RESIZE_STEP_PX;
        setPairWidths(th, next, th.getBoundingClientRect().width + delta,
            next.getBoundingClientRect().width - delta, tableWidth);
        saveWidths(table);
      } else if (event.key === "Enter") {
        event.preventDefault();
        clearWidths(table);
      }
    });

    resizer.addEventListener("dblclick", function (event) {
      event.preventDefault();
      clearWidths(table);
    });

    // Resizing shouldn't also click the header
    resizer.addEventListener("click", function (event) {
      event.stopPropagation();
    });
  }

  function initTable(table) {
    if (table.hasAttribute("data-pm-ready")) {
      return;
    }
    table.setAttribute("data-pm-ready", "");
    Array.prototype.forEach.call(table.querySelectorAll("thead .pm-resizer"), function (resizer) {
      initResizer(table, resizer);
    });
    onTableLayout(table);
    if (typeof ResizeObserver === "function") {
      new ResizeObserver(function () {
        onTableLayout(table);
      }).observe(table.closest(".pm-table-wrap") || table);
    }
  }

  // Shows the full text of a truncated cell as its title
  function updateCellTitle(cell) {
    if (cell.hasAttribute("data-pm-title-fixed")) {
      return;
    }
    if (cell.scrollWidth > cell.clientWidth) {
      cell.title = cell.textContent.trim().replace(/\s+/g, " ");
      cell.setAttribute("data-pm-title-auto", "");
    } else if (cell.hasAttribute("data-pm-title-auto")) {
      cell.removeAttribute("title");
      cell.removeAttribute("data-pm-title-auto");
    }
  }

  function initCellTitles() {
    function handle(event) {
      var cell = event.target instanceof Element ? event.target.closest(".pm-cell") : null;
      if (cell) {
        updateCellTitle(cell);
      }
    }
    document.addEventListener("pointerover", handle);
    document.addEventListener("focusin", handle);
  }

  // -------------------------------------------------------------------------------------------
  // Relative times: the same rules as RelativeTime.format on the server
  // -------------------------------------------------------------------------------------------

  function relativeTime(date, now) {
    var elapsed = now.getTime() - date.getTime();
    if (!(elapsed >= 60000)) {
      return "just now";
    }
    var minutes = Math.floor(elapsed / 60000);
    if (minutes < 60) {
      return minutes + "m ago";
    }
    var hours = Math.floor(elapsed / 3600000);
    if (hours < 24) {
      return hours + "h ago";
    }
    return Math.floor(elapsed / 86400000) + "d ago";
  }

  function refreshTimes(root) {
    var now = new Date();
    each(root || document, "[data-pm-relative][datetime]", function (element) {
      var date = new Date(element.getAttribute("datetime"));
      if (!isNaN(date.getTime())) {
        element.textContent = relativeTime(date, now);
      }
    });
  }

  // -------------------------------------------------------------------------------------------
  // Forms: colour fields, settings edited in place, rule rows, prompt editor, dialog titles
  // -------------------------------------------------------------------------------------------

  var HEX_COLOUR = /^#[0-9a-fA-F]{6}$/;

  function syncColour(input) {
    var field = input.closest("[data-pm-colour]");
    if (!field) {
      return;
    }
    var picker = field.querySelector("input[type=color]");
    var hex = field.querySelector("input[name=colour]");
    if (input === picker) {
      hex.value = picker.value;
    } else if (input === hex && HEX_COLOUR.test(hex.value.trim())) {
      picker.value = hex.value.trim().toLowerCase();
    }
  }

  function setVisible(element, visible) {
    element.hidden = !visible;
    Array.prototype.forEach.call(element.querySelectorAll("input, select, textarea"), function (control) {
      control.disabled = !visible;
    });
  }

  // Shows only the match types that apply to the attribute (Q9), the value only for match types
  // that take one (Q10), and only the fields the chosen action uses
  function updateRuleRow(row) {
    var attribute = row.querySelector("[data-pm-attribute]");
    var match = row.querySelector("[data-pm-match]");
    if (attribute && match) {
      var options = Array.prototype.slice.call(match.options);
      options.forEach(function (option) {
        var applies = (option.getAttribute("data-attributes") || "").split(" ").indexOf(attribute.value) >= 0;
        option.disabled = !applies;
        option.hidden = !applies;
      });
      if (match.selectedOptions.length === 0 || match.selectedOptions[0].disabled) {
        var first = options.filter(function (option) {
          return !option.disabled;
        })[0];
        if (first) {
          match.value = first.value;
        }
      }
      var test = row.querySelector("[data-pm-test]");
      var selected = match.selectedOptions[0];
      if (test) {
        setVisible(test, !selected || selected.getAttribute("data-needs-test") === "true");
      }
    }
    var actionType = row.querySelector("[data-pm-action-type]");
    if (actionType) {
      Array.prototype.forEach.call(row.querySelectorAll("[data-pm-for]"), function (field) {
        setVisible(field, field.getAttribute("data-pm-for").split(" ").indexOf(actionType.value) >= 0);
      });
      var empty = row.querySelector("option[data-llm-label]");
      if (empty) {
        empty.textContent = actionType.value === "create_events" ?
            empty.getAttribute("data-llm-label") : empty.getAttribute("data-plain-label");
      }
    }
  }

  function updateRemoveButtons(container) {
    var rows = container.querySelectorAll(":scope > [data-pm-row]");
    Array.prototype.forEach.call(rows, function (row) {
      var button = row.querySelector("[data-pm-remove-row]");
      if (button) {
        button.disabled = rows.length <= 1;
      }
    });
  }

  function addRow(button) {
    var template = byId(button.getAttribute("data-pm-add-row"));
    var container = byId(button.getAttribute("data-pm-rows"));
    if (!template || !container) {
      return;
    }
    var next = 0;
    Array.prototype.forEach.call(container.querySelectorAll("[name]"), function (control) {
      var match = /\[(-?\d+)\]/.exec(control.getAttribute("name"));
      if (match) {
        next = Math.max(next, parseInt(match[1], 10) + 1);
      }
    });
    container.insertAdjacentHTML("beforeend", template.innerHTML.split("__i__").join(String(next)));
    var row = container.lastElementChild;
    updateRuleRow(row);
    updateRemoveButtons(container);
    var first = row.querySelector("select, input:not([type=hidden])");
    if (first) {
      first.focus();
    }
  }

  function removeRow(button) {
    var row = button.closest("[data-pm-row]");
    var container = row && row.parentElement;
    if (!container || container.querySelectorAll(":scope > [data-pm-row]").length <= 1) {
      return;
    }
    row.remove();
    updateRemoveButtons(container);
  }

  function insertPlaceholder(button) {
    var select = byId(button.getAttribute("data-pm-insert-from"));
    var text = byId(button.getAttribute("data-pm-insert-into"));
    if (!select || !text) {
      return;
    }
    var start = text.selectionStart;
    var end = text.selectionEnd;
    text.focus();
    text.setRangeText(select.value, start, end, "end");
    text.dispatchEvent(new Event("input", { bubbles: true }));
  }

  // The request for the preview is made by HTMX (hx-post on the button)
  function togglePreview(button) {
    var text = byId(button.getAttribute("data-pm-preview"));
    var preview = byId(button.getAttribute("aria-controls"));
    if (!text || !preview) {
      return;
    }
    var previewing = button.getAttribute("aria-pressed") !== "true";
    button.setAttribute("aria-pressed", previewing ? "true" : "false");
    button.textContent = previewing ? "Edit" : "Preview";
    text.hidden = previewing;
    preview.hidden = !previewing;
    if (!previewing) {
      text.focus();
    }
  }

  function initForms() {
    document.addEventListener("input", function (event) {
      if (event.target instanceof HTMLInputElement) {
        syncColour(event.target);
      }
    });
    document.addEventListener("change", function (event) {
      var row = event.target instanceof Element ? event.target.closest("[data-pm-row]") : null;
      if (row) {
        updateRuleRow(row);
      }
    });
    // Enter saves a setting edited in place, by leaving the field (it saves on blur)
    document.addEventListener("keydown", function (event) {
      var target = event.target;
      if (event.key === "Enter" && target instanceof HTMLInputElement && target.closest(".pm-setting-edit")) {
        event.preventDefault();
        target.blur();
      }
    });
    document.addEventListener("click", function (event) {
      var target = event.target instanceof Element ? event.target : null;
      var button = target && target.closest("button");
      if (!button || button.disabled) {
        return;
      }
      if (button.hasAttribute("data-pm-add-row")) {
        addRow(button);
      } else if (button.hasAttribute("data-pm-remove-row")) {
        removeRow(button);
      } else if (button.hasAttribute("data-pm-insert-from")) {
        insertPlaceholder(button);
      } else if (button.hasAttribute("data-pm-preview")) {
        togglePreview(button);
      }
    });
  }

  function initFormContent(root) {
    each(root, "[data-pm-row]", updateRuleRow);
    each(root, "[data-pm-rows]", function (button) {
      var container = byId(button.getAttribute("data-pm-rows"));
      if (container) {
        updateRemoveButtons(container);
      }
    });
    each(root, "[data-pm-dialog-title]", function (element) {
      var dialog = element.closest("dialog");
      var title = dialog && dialog.querySelector(".pm-dialog__title");
      if (title) {
        title.textContent = element.getAttribute("data-pm-dialog-title");
      }
    });
  }

  // -------------------------------------------------------------------------------------------
  // Row links: a click anywhere on a tr[data-pm-href] opens it, except on its own controls
  // -------------------------------------------------------------------------------------------

  function initRowLinks() {
    document.addEventListener("click", function (event) {
      var row = event.target.closest && event.target.closest("tr[data-pm-href]");
      if (!row || event.defaultPrevented || event.button !== 0) {
        return;
      }
      if (event.target.closest("a, button, input, select, textarea, label, .pm-resizer")) {
        return;
      }
      // Leave a text selection alone
      var selection = window.getSelection && window.getSelection();
      if (selection && selection.toString()) {
        return;
      }
      var href = row.getAttribute("data-pm-href");
      if (event.ctrlKey || event.metaKey) {
        window.open(href, "_blank");
      } else {
        window.location.href = href;
      }
    });
  }

  // -------------------------------------------------------------------------------------------
  // Start up
  // -------------------------------------------------------------------------------------------

  function initContent(root) {
    each(root, "[data-pm-table]", initTable);
    each(root, "dialog", initDialog);
    each(root, ".pm-toast", initToast);
    initFormContent(root);
  }

  function start() {
    initMenu();
    initToastEvents();
    initConfirmDialog();
    initDialogEvents();
    initCellTitles();
    initForms();
    initRowLinks();
    if (window.htmx && typeof window.htmx.onLoad === "function") {
      // Runs for the initial page and for all content HTMX swaps in later
      window.htmx.onLoad(initContent);
    } else {
      initContent(document.body);
    }
    setInterval(refreshTimes, TIME_REFRESH_MS);
  }

  window.Postmaster = {
    toast: toast,
    openDialog: openDialog,
    closeDialog: closeDialog,
    confirm: confirmDialog,
    relativeTime: relativeTime,
    refreshTimes: refreshTimes
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
