(function() {
  function visible(el) {
    if (!el || !el.isConnected) return false;
    var rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    for (var node = el; node && node !== document.documentElement; node = node.parentElement) {
      var style = window.getComputedStyle(node);
      if (!style || style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || style.contentVisibility === 'hidden' || parseFloat(style.opacity || '1') <= 0.01 || node.getAttribute('aria-hidden') === 'true') return false;
    }
    return true;
  }
  function actionability(el) {
    if (!el || !el.isConnected) return { attached:false };
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    var cx = Math.max(0, Math.min(window.innerWidth - 1, rect.left + rect.width / 2));
    var cy = Math.max(0, Math.min(window.innerHeight - 1, rect.top + rect.height / 2));
    var top = document.elementFromPoint(cx, cy);
    var inViewport = rect.bottom > 0 && rect.right > 0 && rect.top < window.innerHeight && rect.left < window.innerWidth;
    var unclipped = rect.top >= 0 && rect.left >= 0 && rect.bottom <= window.innerHeight && rect.right <= window.innerWidth;
    var unoccluded = !!top && (top === el || el.contains(top) || top.contains(el));
    var painted = style.opacity !== '0' && style.visibility !== 'hidden' && style.pointerEvents !== 'none';
    var clipped = false;
    for (var parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
      var ps = window.getComputedStyle(parent);
      if (/(auto|scroll|hidden|clip)/.test((ps.overflow || '') + (ps.overflowX || '') + (ps.overflowY || ''))) {
        var pr = parent.getBoundingClientRect();
        if (rect.right <= pr.left || rect.left >= pr.right || rect.bottom <= pr.top || rect.top >= pr.bottom) { clipped = true; break; }
      }
    }
    var mutationVersion = window.__amayaMutationVersion || 0;
    var previous = window.__amayaStableRects && window.__amayaStableRects.get(el);
    var stable = !!previous && previous.version === mutationVersion && Math.abs(previous.x - rect.left) < 1 && Math.abs(previous.y - rect.top) < 1 && Math.abs(previous.w - rect.width) < 1 && Math.abs(previous.h - rect.height) < 1;
    if (!window.__amayaStableRects) window.__amayaStableRects = new WeakMap();
    window.__amayaStableRects.set(el, {x:rect.left,y:rect.top,w:rect.width,h:rect.height,version:mutationVersion});
    return {
      attached:true,
      css_visible:visible(el),
      in_viewport:inViewport,
      unclipped:unclipped && !clipped,
      painted:painted,
      unoccluded:unoccluded,
      receives_events:style.pointerEvents !== 'none' && !!top,
      stable:stable,
      enabled:enabledState(el),
      editable:isEditable(el),
      human_actionable:visible(el) && inViewport && painted && unoccluded && style.pointerEvents !== 'none' && enabledState(el),
      bounds:{x:Math.round(rect.left),y:Math.round(rect.top),width:Math.round(rect.width),height:Math.round(rect.height)}
    };
  }
  function observeMutationRoot(root) {
    if (!root || root.__amayaMutationObserver) return;
    root.__amayaMutationVersion = 0;
    root.__amayaMutationObserver = new MutationObserver(function() { root.__amayaMutationVersion += 1; });
    root.__amayaMutationObserver.observe(root, {subtree:true, childList:true, attributes:true, characterData:true});
  }
  if (document.documentElement) observeMutationRoot(document.documentElement);
  Array.prototype.forEach.call(document.querySelectorAll('*'), function(node) { if (node.shadowRoot) observeMutationRoot(node.shadowRoot); });
  function enabledState(el) {
    return !!el && !el.disabled && el.getAttribute('aria-disabled') !== 'true';
  }
  function isSemanticallyClickable(el) {
    if (!el || !el.tagName) return false;
    var tag = (el.tagName || '').toLowerCase();
    var role = el.getAttribute('role') || '';
    return !!(tag === 'a' || tag === 'button' || role === 'button' || role === 'link' || role === 'menuitem' || el.onclick || el.hasAttribute('onclick') || el.hasAttribute('data-testid') || el.hasAttribute('data-test') || (el.hasAttribute('tabindex') && !isEditable(el)));
  }
  function cssEscape(value) {
    if (window.CSS && CSS.escape) return CSS.escape(value);
    return String(value).replace(/[^a-zA-Z0-9_-]/g, '\\$&');
  }
  function selectorFor(el) {
    if (!el || !el.tagName) return '';
    var segments = [];
    var node = el;
    while (node && node.nodeType === 1) {
      var part = '';
      if (node.id) part = '#' + cssEscape(node.id);
      else {
        var data = ['data-testid','data-test','aria-label','name','placeholder'];
        for (var d of data) {
          var v = node.getAttribute(d);
          if (v) { part = node.tagName.toLowerCase() + '[' + d + '=' + JSON.stringify(v) + ']'; break; }
        }
        if (!part) {
          part = node.tagName.toLowerCase();
          var parent = node.parentElement;
          if (parent) {
            var siblings = Array.prototype.filter.call(parent.children, function(x) { return x.tagName === node.tagName; });
            if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')';
          }
        }
      }
      segments.unshift(part);
      var root = node.getRootNode && node.getRootNode();
      if (root && root.host) {
        segments.unshift('>>>');
        node = root.host;
      } else node = node.parentElement;
      if (segments.length >= 11 || part.charAt(0) === '#') break;
    }
    return segments.join(' ');
  }
  function labelFor(el) {
    if (!el) return '';
    if (el.labels && el.labels.length) return Array.prototype.map.call(el.labels, function(x){return x.innerText || x.textContent || '';}).join(' ').trim();
    var ariaLabel = el.getAttribute('aria-label');
    if (ariaLabel) return ariaLabel;
    var labelledBy = el.getAttribute('aria-labelledby');
    if (labelledBy) {
      var labelText = labelledBy.split(/\s+/).map(function(id) {
        var node = document.getElementById(id);
        return node ? (node.innerText || node.textContent || '') : '';
      }).join(' ').trim();
      if (labelText) return labelText;
    }
    var p = el.closest('label');
    if (p) return (p.innerText || p.textContent || '').trim();
    return '';
  }
  function accessibleName(el) {
    if (!el) return '';
    return [
      labelFor(el),
      el.getAttribute && el.getAttribute('title'),
      el.getAttribute && el.getAttribute('aria-description'),
      el.getAttribute && el.getAttribute('data-testid'),
      el.getAttribute && el.getAttribute('data-test'),
      textForElement(el)
    ].join(' ').trim();
  }
  function primaryAnchorFor(el) {
    if (!el || !el.querySelectorAll) return null;
    var links = Array.prototype.slice.call(el.querySelectorAll('a[href]')).filter(visible);
    if (!links.length) return null;
    var statusLink = links.find(function(link) { return /\/status\/|\/posts\//.test(link.getAttribute('href') || ''); });
    return statusLink || links[0];
  }
  function actionContainerFor(el) {
    if (!el || !el.closest) return null;
    return el.closest('article,[role=article],[data-testid="cellInnerDiv"],[data-testid="tweet"],[data-testid="tweetText"],li,[role=listitem],[data-testid="UserCell"]');
  }
  function clickTargetFor(el) {
    if (!el) return el;
    // A label can be human-clickable, but automation must activate its contained
    // submit control instead of focusing the first field.
    if ((el.tagName || '').toLowerCase() === 'label') {
      var nestedControl = el.querySelector && el.querySelector('button,input[type=submit],input[type=button],input[type=reset]');
      if (nestedControl && visible(nestedControl) && enabledState(nestedControl)) return nestedControl;
    }
    if (isEditable(el)) return el;
    if (isSemanticallyClickable(el) && visible(el)) return el;
    var directAnchor = primaryAnchorFor(el);
    if (directAnchor) return directAnchor;
    var clickableAncestor = el.closest && el.closest('a[href],button,[role=button],[role=link],[onclick],[data-testid],[data-test],[tabindex]');
    if (clickableAncestor && visible(clickableAncestor)) return clickableAncestor;
    var card = actionContainerFor(el);
    if (card) {
      var cardAnchor = primaryAnchorFor(card);
      if (cardAnchor) return cardAnchor;
      if (isSemanticallyClickable(card) && visible(card)) return card;
    }
    return el;
  }
  function focusTargetFor(el) {
    if (!el) return el;
    if (isEditable(el)) return el;
    var field = el.querySelector && el.querySelector('input,textarea,select,[contenteditable],[role=textbox]');
    return field || clickTargetFor(el);
  }
  function isSensitive(el) {
    var text = [el.type, el.autocomplete, el.name, el.id, el.placeholder, el.getAttribute('aria-label'), labelFor(el)].join(' ').toLowerCase();
    return /password|passwd|passcode|otp|one-time|2fa|mfa|verification|email|username|login|card|credit|cc-number|cvc|cvv|expiry|payment|billing|address|phone|ssn|nik|ktp|passport|private|secret|token/.test(text);
  }
  function isEditable(el) {
    if (!el || !el.tagName) return false;
    return /input|textarea|select/i.test(el.tagName || '') || el.isContentEditable || el.getAttribute('role') === 'textbox';
  }
  function currentEditableValue(el) {
    if (!el) return '';
    if ('value' in el) return el.value || '';
    return el.innerText || el.textContent || '';
  }
  function textForElement(el) {
    if (!el) return '';
    return currentEditableValue(el) || el.innerText || el.textContent || '';
  }
  function keywordText(el) {
    return [
      textForElement(el),
      accessibleName(el),
      el.type,
      el.name,
      el.id,
      el.placeholder,
      el.getAttribute && el.getAttribute('role'),
      el.getAttribute && el.getAttribute('data-testid'),
      el.getAttribute && el.getAttribute('data-test')
    ].join(' ').toLowerCase();
  }
  function queryTokens(query) {
    return String(query || '').toLowerCase().split(/\s+/).filter(Boolean);
  }
  function distanceBetween(a, b) {
    if (!a || !b || !a.getBoundingClientRect || !b.getBoundingClientRect) return 99999;
    var ar = a.getBoundingClientRect();
    var br = b.getBoundingClientRect();
    var ax = ar.left + ar.width / 2;
    var ay = ar.top + ar.height / 2;
    var bx = br.left + br.width / 2;
    var by = br.top + br.height / 2;
    var dx = ax - bx;
    var dy = ay - by;
    return Math.sqrt(dx * dx + dy * dy);
  }
  function scoreSearchInput(el) {
    if (!visible(el) || !enabledState(el) || isSensitive(el) || !isEditable(el)) return -1000;
    var text = keywordText(el);
    var score = 0;
    if ((el.type || '').toLowerCase() === 'search') score += 120;
    if (/search|query|find|where|destination|place|location/.test(text)) score += 100;
    if (/input|textbox|combobox/.test(el.getAttribute('role') || '')) score += 20;
    if (el.tagName && el.tagName.toLowerCase() === 'textarea') score -= 10;
    if (/post|comment|reply|message|tweet|status|caption/.test(text)) score -= 60;
    return score;
  }
  function inferSourcePurpose(el) {
    if (!el) return 'submit';
    var text = keywordText(el);
    if ((el.type || '').toLowerCase() === 'search' || /search|query|find|where|destination|place|location/.test(text)) return 'search';
    if (el.isContentEditable || (el.tagName || '').toLowerCase() === 'textarea' || /post|comment|reply|message|tweet|status|caption|chat/.test(text)) return 'post';
    return 'submit';
  }
  function inferActionPurpose(el) {
    var text = keywordText(el);
    if (/search|find|go/.test(text)) return 'search';
    if (/repost|retweet|quote/.test(text)) return 'repost';
    if (/like|favorite/.test(text)) return 'like';
    if (/post|send|reply|comment|publish|share|tweet|save/.test(text)) return 'post';
    return 'submit';
  }
  function scoreActionButton(button, source, purpose) {
    if (!visible(button) || !enabledState(button)) return -1000;
    var text = keywordText(button);
    var score = 0;
    if (purpose === 'search' && /search|find|go|apply/.test(text)) score += 120;
    if (purpose === 'repost' && /repost|retweet|quote/.test(text)) score += 180;
    if (purpose === 'like' && /like|favorite/.test(text)) score += 180;
    if (purpose === 'post' && /post|send|reply|comment|publish|share|tweet|save/.test(text)) score += 140;
    if (purpose === 'submit' && /submit|continue|next|done|ok|save|apply|send|post|search|repost|retweet/.test(text)) score += 100;
    if (/submit|continue|next|done|ok|save|apply|send|post|search|repost|retweet|quote|like|favorite/.test(text)) score += 20;
    if (source && source.form && button.form && source.form === button.form) score += 120;
    if (source && source.closest && button.closest && source.closest('form') && source.closest('form') === button.closest('form')) score += 60;
    if (source && source.parentElement && source.parentElement.contains(button)) score += 35;
    if (source && source.compareDocumentPosition && (source.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING)) score += 15;
    var distance = distanceBetween(source, button);
    score += Math.max(0, 60 - Math.min(distance, 1200) / 20);
    return score;
  }
  function nearestActionButton(source, purpose) {
    var buttons = Array.prototype.slice.call(document.querySelectorAll('button,[type=submit],[type=button],[role=button],input[type=submit],input[type=button]'));
    var best = null;
    var bestScore = -1000;
    buttons.forEach(function(button) {
      if (button === source) return;
      var score = scoreActionButton(button, source, purpose);
      if (score > bestScore) {
        best = button;
        bestScore = score;
      }
    });
    return bestScore >= 40 ? best : null;
  }
  function nativeValueSetter(el) {
    if (!el) return null;
    var proto = null;
    if (el instanceof HTMLTextAreaElement) proto = HTMLTextAreaElement.prototype;
    else if (el instanceof HTMLInputElement) proto = HTMLInputElement.prototype;
    else if (el instanceof HTMLSelectElement) proto = HTMLSelectElement.prototype;
    if (!proto) return null;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    return desc && desc.set ? desc.set : null;
  }
  function dispatchTextEvents(el, inputType, data) {
    var before = null;
    if (window.InputEvent) {
      try {
        before = new InputEvent('beforeinput', { bubbles:true, cancelable:true, data:data, inputType:inputType });
      } catch (e) {}
    }
    if (before) el.dispatchEvent(before);
    else el.dispatchEvent(new Event('beforeinput', { bubbles:true, cancelable:true }));
    var input = null;
    if (window.InputEvent) {
      try {
        input = new InputEvent('input', { bubbles:true, data:data, inputType:inputType });
      } catch (e) {}
    }
    if (input) el.dispatchEvent(input);
    else el.dispatchEvent(new Event('input', { bubbles:true }));
    el.dispatchEvent(new Event('change', { bubbles:true }));
  }
  function dispatchKeyboardText(el, text) {
    for (var i = 0; i < text.length; i++) {
      var ch = text.charAt(i);
      ['keydown','keypress','keyup'].forEach(function(type) {
        try {
          el.dispatchEvent(new KeyboardEvent(type, { bubbles:true, cancelable:true, key:ch, code:'', charCode:ch.charCodeAt(0), keyCode:ch.charCodeAt(0), which:ch.charCodeAt(0) }));
        } catch (e) {
          el.dispatchEvent(new Event(type, { bubbles:true, cancelable:true }));
        }
      });
    }
  }
  function moveCaretToEnd(el) {
    if (!el) return;
    if ('selectionStart' in el && typeof el.value === 'string') {
      var end = el.value.length;
      try { el.setSelectionRange(end, end); } catch (e) {}
      return;
    }
    if (el.isContentEditable && window.getSelection && document.createRange) {
      var selection = window.getSelection();
      var range = document.createRange();
      range.selectNodeContents(el);
      range.collapse(false);
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }
  function focusEditable(el) {
    if (!el) return;
    el.focus && el.focus();
    moveCaretToEnd(el);
  }
  function setNativeValue(el, value, inputType) {
    var setter = nativeValueSetter(el);
    if (setter) setter.call(el, value);
    else if ('value' in el) el.value = value;
    else el.textContent = value;
    moveCaretToEnd(el);
    dispatchTextEvents(el, inputType, inputType === 'deleteContentBackward' ? null : value);
    return { strategy: setter ? 'native_value_setter' : 'value_assignment', value: currentEditableValue(el) };
  }
  function clearEditable(el) {
    if (!el) return { strategy:'none', value:'' };
    if (el.isContentEditable || el.getAttribute('role') === 'textbox') {
      focusEditable(el);
      if (document.execCommand) {
        try {
          document.execCommand('selectAll', false, null);
          document.execCommand('delete', false, null);
        } catch (e) {}
      }
      if (currentEditableValue(el)) {
        el.innerHTML = '';
        el.textContent = '';
      }
      dispatchTextEvents(el, 'deleteContentBackward', null);
      return { strategy:'contenteditable_clear', value:currentEditableValue(el) };
    }
    return setNativeValue(el, '', 'deleteContentBackward');
  }
  function insertTextLikeUser(el, text, preferKeyboardEvents) {
    if (!el) return { strategy:'none', value:'' };
    text = String(text || '');
    if (!text.length) return { strategy:'noop', value:currentEditableValue(el) };
    focusEditable(el);
    if (preferKeyboardEvents) dispatchKeyboardText(el, text);
    if (el.isContentEditable || el.getAttribute('role') === 'textbox') {
      var inserted = false;
      if (document.execCommand) {
        try { inserted = document.execCommand('insertText', false, text); } catch (e) {}
      }
      if (!inserted) {
        moveCaretToEnd(el);
        var selection = window.getSelection ? window.getSelection() : null;
        var range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
        if (range) {
          range.deleteContents();
          range.insertNode(document.createTextNode(text));
          range.collapse(false);
          selection.removeAllRanges();
          selection.addRange(range);
        } else {
          el.textContent = currentEditableValue(el) + text;
        }
      }
      dispatchTextEvents(el, 'insertText', text);
      moveCaretToEnd(el);
      return { strategy: inserted ? 'contenteditable_execCommand' : 'contenteditable_range_insert', value: currentEditableValue(el) };
    }
    var nextValue = currentEditableValue(el) + text;
    return setNativeValue(el, nextValue, 'insertText');
  }
  window.__amayaElementMap = window.__amayaElementMap || {};
  function sensitiveType(el) {
    var text = [el.type, el.autocomplete, el.name, el.id, el.placeholder, el.getAttribute('aria-label'), labelFor(el)].join(' ').toLowerCase();
    if (/password|passwd|passcode/.test(text)) return 'password';
    if (/otp|one-time|2fa|mfa|verification/.test(text)) return 'otp';
    if (/card|credit|cc-number|cvc|cvv|expiry|payment|billing/.test(text)) return 'payment';
    if (/email/.test(text)) return 'email_login';
    if (/username|login/.test(text)) return 'username';
    if (/address|phone|ssn|nik|ktp|passport/.test(text)) return 'personal_data';
    if (/private|secret|token/.test(text)) return 'secret';
    return null;
  }
  function makeElementId(el, index) {
    var base = (el.getAttribute('data-testid') || el.name || el.id || el.type || el.getAttribute('role') || el.tagName || 'node').toString().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '').slice(0, 24);
    return 'el_' + (base || 'node') + '_' + index;
  }
  function hashText(value) {
    var s = String(value || '');
    var hash = 0;
    for (var i = 0; i < s.length; i++) {
      hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0;
    }
    return Math.abs(hash).toString(36);
  }
  function primaryUrlFor(el) {
    if (!el) return '';
    function safeUrl(url) {
      try {
        var parsed = new URL(url, location.href);
        if (parsed.hostname === 'x.com' && parsed.pathname.indexOf('/i/jf/') === 0) return '';
        return parsed.href;
      } catch (e) { return ''; }
    }
    if (el.href) return safeUrl(el.href);
    var primary = primaryAnchorFor(el);
    if (primary && primary.href) return safeUrl(primary.href);
    var clickable = clickTargetFor(el);
    if (clickable && clickable.href) return safeUrl(clickable.href);
    var closestAnchor = el.closest && el.closest('a[href]');
    return closestAnchor && closestAnchor.href ? safeUrl(closestAnchor.href) : '';
  }
  function stableElementIdFor(el, target) {
    var url = primaryUrlFor(target || el);
    if (url) return 'stable_' + hashText(url);
    var testId = (el.getAttribute('data-testid') || el.getAttribute('data-test') || '').trim();
    var nameBits = [
      (target && target.tagName) || el.tagName || '',
      testId,
      el.getAttribute('role') || '',
      labelFor(el),
      textForElement(el).trim().replace(/\s+/g, ' ').slice(0, 80)
    ].filter(Boolean).join('|');
    return 'stable_' + hashText(nameBits || selectorFor(target || el));
  }
  function summarize(el, index) {
    var target = clickTargetFor(el);
    var sensitive = isSensitive(el);
    var elementId = makeElementId(el, index || 0);
    var stableId = stableElementIdFor(el, target);
    var text = textForElement(el).trim().replace(/\s+/g, ' ');
    var selector = selectorFor(el);
    var clickSelector = selectorFor(target);
    var primaryUrl = primaryUrlFor(el);
    window.__amayaElementMap[elementId] = clickSelector || selector;
    window.__amayaElementMap[stableId] = clickSelector || selector;
    if (primaryUrl) window.__amayaElementMap['url:' + primaryUrl] = clickSelector || selector;
    return {
      element_id: elementId,
      stable_id: stableId,
      selector: selector,
      click_selector: clickSelector || selector,
      primary_url: primaryUrl,
      open_url: primaryUrl,
      tag: (el.tagName || '').toLowerCase(),
      text: text.slice(0, 240),
      label: labelFor(el).slice(0, 160),
      accessible_name: accessibleName(el).slice(0, 220),
      data_testid: (el.getAttribute('data-testid') || el.getAttribute('data-test') || '').slice(0, 120),
      title: (el.getAttribute('title') || '').slice(0, 160),
      type: el.type || '',
      role: el.getAttribute('role') || '',
      href: el.href || '',
      src: el.src || '',
      placeholder: el.placeholder || '',
      name: el.name || '',
      id: el.id || '',
      visible: visible(el),
      enabled: enabledState(el),
      sensitive: sensitive,
      sensitive_type: sensitive ? sensitiveType(el) : null,
      hasValue: !!currentEditableValue(el),
      valuePreview: currentEditableValue(el).slice(0, 80),
      actionability: actionability(target),
      bounds: { x: Math.round(el.getBoundingClientRect().left), y: Math.round(el.getBoundingClientRect().top), width: Math.round(el.getBoundingClientRect().width), height: Math.round(el.getBoundingClientRect().height) },
      center: { x: Math.round(el.getBoundingClientRect().left + el.getBoundingClientRect().width / 2), y: Math.round(el.getBoundingClientRect().top + el.getBoundingClientRect().height / 2) },
      focused: document.activeElement === el || !!(el.contains && document.activeElement && el.contains(document.activeElement)),
      editable: isEditable(el),
      clickable: isSemanticallyClickable(target),
      actions: isEditable(el) ? ['focus','type_text','clear_input','press_key','tap'] : (primaryUrl ? ['click','tap','open_url'] : ['click','tap'])
    };
  }
  function allQueryRoots() {
    var roots = [document];
    for (var i = 0; i < roots.length; i++) {
      var nodes = roots[i].querySelectorAll ? roots[i].querySelectorAll('*') : [];
      Array.prototype.forEach.call(nodes, function(node) { if (node.shadowRoot) roots.push(node.shadowRoot); });
    }
    return roots;
  }
  function deepQuerySelector(selector) {
    var parts = String(selector || '').split(/\s*>>>\s*/).filter(Boolean);
    var root = document;
    for (var i = 0; i < parts.length; i++) {
      var node = root.querySelector(parts[i]);
      if (!node) return null;
      if (i < parts.length - 1) {
        if (!node.shadowRoot) return null;
        root = node.shadowRoot;
      } else return node;
    }
    return null;
  }
  function deepNodes(selector) {
    var out = [];
    allQueryRoots().forEach(function(root) { try { out = out.concat(Array.prototype.slice.call(root.querySelectorAll(selector))); } catch (e) {} });
    return out;
  }
  function resolveElement(selector) {
    if (window.__amayaElementMap && window.__amayaElementMap[selector]) selector = window.__amayaElementMap[selector];
    if (typeof selector === 'string' && selector.indexOf('url:') === 0) {
      var targetUrl = selector.slice(4);
      var urlMatch = deepNodes('a[href]').find(function(node) { return node.href === targetUrl; });
      if (urlMatch) return urlMatch;
    }
    try { var direct = deepQuerySelector(selector); if (direct) return direct; } catch (e) {}
    return findElementNode(selector);
  }
  function normalizedMatchText(value) {
    return String(value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
  }
  function elementMatchesQuery(node, query, idx) {
    var x = summarize(node, idx + 1);
    var q = normalizedMatchText(query);
    var primary = [x.text,x.label,x.accessible_name,x.placeholder,x.title,x.name,x.id,x.data_testid]
      .map(normalizedMatchText).filter(Boolean);
    var secondary = [x.selector,x.href,x.type,x.role].map(normalizedMatchText).filter(Boolean);
    var score = 0;
    if (primary.some(function(value) { return value === q; })) score = 1000;
    else if (primary.some(function(value) { return value.indexOf(q) >= 0; })) score = 600;
    else {
      var tokens = queryTokens(q);
      var matched = tokens.filter(function(token) {
        return primary.some(function(value) { return value.indexOf(token) >= 0; });
      }).length;
      // Fuzzy fallback requires every meaningful query token. This prevents
      // "Log in" selecting unrelated visible controls such as "Home".
      if (tokens.length && matched === tokens.length) score = 300 + matched * 20;
      else if (secondary.some(function(value) { return value === q || value.indexOf(q) >= 0; })) score = 180;
    }
    if (score > 0 && x.visible) score += 10;
    if (score > 0 && x.enabled) score += 8;
    return score;
  }
  function findElementNode(query) {
    var q = normalizedMatchText(query);
    if (!q) return null;
    var nodes = deepNodes('button,[role=button],[role=link],input,textarea,select,a[href],[onclick],label,[contenteditable],[role=textbox],article,[role=article],li,[role=listitem],[data-testid],[tabindex]');
    var best = null;
    var bestScore = 0;
    nodes.forEach(function(node, idx) {
      var score = elementMatchesQuery(node, q, idx);
      if (score > bestScore) {
        best = node;
        bestScore = score;
      }
    });
    return best;
  }
  function findElement(query) {
    var node = findElementNode(query);
    return node ? summarize(node, 1) : null;
  }
  function collect(selector, offset) {
    return deepNodes(selector).filter(visible).slice(0, 120).map(function(node, idx) { return summarize(node, (offset || 0) + idx + 1); });
  }
  function formSummary(form, idx) {
    var fields = Array.prototype.slice.call(form.querySelectorAll('input,textarea,select,[contenteditable],[role=textbox]')).filter(visible).map(function(el, i) { return summarize(el, (idx + 1) * 100 + i); });
    var primaryField = form.querySelector('textarea,[contenteditable],[role=textbox],input:not([type=hidden])');
    var submitNode = primaryField ? (nearestActionButton(primaryField, inferSourcePurpose(primaryField)) || nearestActionButton(primaryField, 'submit')) : null;
    var submit = submitNode ? summarize(submitNode, (idx + 1) * 200) : (Array.prototype.slice.call(form.querySelectorAll('button,[type=submit],[role=button]')).filter(visible).map(function(el, i) { return summarize(el, (idx + 1) * 200 + i); })[0] || null);
    var isLogin = fields.some(function(f) { return f.sensitive_type === 'password' || f.sensitive_type === 'email_login' || f.sensitive_type === 'username'; });
    return {
      form_id: 'form_' + (isLogin ? 'login_' : '') + (idx + 1),
      purpose: primaryField ? inferSourcePurpose(primaryField) : (isLogin ? 'login' : 'form'),
      fields: fields.map(function(f) { return f.element_id; }),
      field_stable_ids: fields.map(function(f) { return f.stable_id; }),
      submit_element_id: submit ? submit.element_id : null,
      submit_stable_id: submit ? submit.stable_id : null,
      submit_open_url: submit ? submit.open_url : null,
      sensitive: fields.some(function(f) { return !!f.sensitive; })
    };
  }
  function collectEditorClusters() {
    return Array.prototype.slice.call(document.querySelectorAll('textarea,[contenteditable],[role=textbox]')).filter(function(el) {
      return visible(el) && enabledState(el) && !isSensitive(el);
    }).slice(0, 40).map(function(el, idx) {
      var editor = summarize(el, 3000 + idx);
      var purpose = inferSourcePurpose(el);
      var submit = nearestActionButton(el, purpose) || nearestActionButton(el, 'submit');
      var submitSummary = submit ? summarize(submit, 3400 + idx) : null;
      return {
        editor_element_id: editor.element_id,
        editor_stable_id: editor.stable_id,
        submit_element_id: submitSummary ? submitSummary.element_id : null,
        submit_stable_id: submitSummary ? submitSummary.stable_id : null,
        submit_open_url: submitSummary ? submitSummary.open_url : null,
        purpose: purpose,
        focused: editor.focused,
        submit_enabled: submitSummary ? submitSummary.enabled : null,
        submit_text: submitSummary ? submitSummary.text : ''
      };
    });
  }
  function collectDom() {
    var bodyText = document.body ? document.body.innerText.trim().replace(/\s+/g, ' ') : '';
    var links = collect('a[href],[role=link]', 0);
    var buttons = collect('button,[role=button],input[type=button],input[type=submit],[onclick],[data-testid],[data-test]', 1000);
    var inputs = collect('input,textarea,select,[contenteditable],[role=textbox]', 2000);
    var cards = collect('article,[role=article],li,[role=listitem],[data-testid="cellInnerDiv"],[data-testid="tweet"]', 2600);
    return {
      mode: 'interactive_summary',
      url: location.href,
      title: document.title,
      visible_text_preview: bodyText.slice(0, 1200),
      truncated: bodyText.length > 1200,
      links: links,
      buttons: buttons,
      inputs: inputs,
      cards: cards,
      direct_urls: Array.from(new Set(links.concat(buttons).concat(cards).map(function(item) { return item.open_url || item.primary_url || ''; }).filter(Boolean))).slice(0, 200),
      forms: Array.prototype.slice.call(document.querySelectorAll('form')).slice(0, 30).map(formSummary),
      editor_clusters: collectEditorClusters()
    };
  }
  /*__AMAYA_BODY__*/
})();
