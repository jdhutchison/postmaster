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
  // Start up
  // -------------------------------------------------------------------------------------------

  function initContent(root) {
    each(root, "[data-pm-table]", initTable);
    each(root, "dialog", initDialog);
    each(root, ".pm-toast", initToast);
  }

  function start() {
    initMenu();
    initToastEvents();
    initConfirmDialog();
    initDialogEvents();
    initCellTitles();
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
