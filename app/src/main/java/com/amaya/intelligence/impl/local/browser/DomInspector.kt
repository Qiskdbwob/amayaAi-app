package com.amaya.intelligence.impl.local.browser

import org.json.JSONObject

object DomInspector {
    fun getDomScript(): String = baseInspector("return JSON.stringify(collectDom());")

    fun getVisibleTextScript(): String = """
        (function() {
          return document.body ? document.body.innerText.slice(0, 50000) : '';
        })();
    """.trimIndent()

    fun findElementScript(query: String): String {
        val q = JSONObject.quote(query)
        return baseInspector(
            """
            var query = $q;
            var found = findElement(query);
            return JSON.stringify(found || null);
            """.trimIndent()
        )
    }

    fun selectorExistsScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            return JSON.stringify(el ? summarize(el) : null);
            """.trimIndent()
        )
    }

    fun humanVerificationScript(): String = """
        (function() {
          var title = (document.title || '').toLowerCase();
          var text = (document.body ? document.body.innerText : '').slice(0, 50000).toLowerCase();
          var frames = Array.prototype.slice.call(document.querySelectorAll('iframe[src],iframe[title]'));
          var frameText = frames.map(function(frame) { return ((frame.src || '') + ' ' + (frame.title || '')).toLowerCase(); }).join(' ');
          var selectors = [
            '.g-recaptcha','iframe[src*="recaptcha"]','iframe[src*="hcaptcha"]',
            'iframe[src*="challenges.cloudflare.com"]','[class*="turnstile"]','[id*="turnstile"]',
            '[class*="h-captcha"]','[id*="captcha"]','[class*="captcha"]'
          ];
          var widget = null;
          selectors.some(function(selector) { try { widget = document.querySelector(selector); } catch (e) {} return !!widget; });
          var evidence = title + ' ' + text + ' ' + frameText;
          var phrase = /checking your browser|verify you are human|human verification|security check|complete the captcha|challenge-platform/.test(text + ' ' + frameText);
          var challengeTitle = /checking your browser|human verification|security check|attention required|challenge/.test(title);
          var provider = /turnstile|challenges\.cloudflare/.test(evidence) ? 'cloudflare_turnstile' :
            /hcaptcha|h-captcha/.test(evidence) ? 'hcaptcha' :
            /recaptcha|g-recaptcha/.test(evidence) ? 'recaptcha' :
            /captcha/.test(evidence) ? 'captcha' : 'anti_bot_challenge';
          return JSON.stringify({required:!!widget || (phrase && challengeTitle), provider:provider, widget:!!widget, phrase:phrase, challenge_title:challengeTitle});
        })();
    """.trimIndent()

    fun findTextScript(query: String): String {
        val q = JSONObject.quote(query)
        return """
            (function() {
              var needle = String($q).trim();
              if (!needle) return JSON.stringify({matches:[], total:0});
              var folded = needle.toLocaleLowerCase();
              var matches = [];
              var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
              var node;
              while ((node = walker.nextNode()) && matches.length < 100) {
                var value = (node.nodeValue || '').replace(/\s+/g, ' ').trim();
                var index = value.toLocaleLowerCase().indexOf(folded);
                if (index < 0) continue;
                var parent = node.parentElement;
                if (!parent || !parent.getClientRects().length || getComputedStyle(parent).display === 'none' || getComputedStyle(parent).visibility === 'hidden') continue;
                var rect = parent.getBoundingClientRect();
                matches.push({
                  text: value.slice(Math.max(0, index - 100), Math.min(value.length, index + needle.length + 180)),
                  selector: (parent.id ? '#' + CSS.escape(parent.id) : parent.tagName.toLowerCase()),
                  tag: parent.tagName.toLowerCase(),
                  bounds: {x:Math.round(rect.left), y:Math.round(rect.top), width:Math.round(rect.width), height:Math.round(rect.height)},
                  in_viewport: rect.bottom > 0 && rect.right > 0 && rect.top < innerHeight && rect.left < innerWidth
                });
              }
              return JSON.stringify({query:needle, matches:matches, total:matches.length, truncated:matches.length >= 100});
            })();
        """.trimIndent()
    }

    fun clickPreflightScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Element not found', selector:$s });
            el = clickTargetFor(el);
            if (!visible(el)) return JSON.stringify({ ok:false, error:'Element is not visible', selector:$s, element:summarize(el), href:(el.href || '') });
            if (!enabledState(el)) return JSON.stringify({ ok:false, error:'Element is disabled', selector:$s, element:summarize(el), href:(el.href || '') });
            el.scrollIntoView({block:'center', inline:'center', behavior:'auto'});
            var rect = el.getBoundingClientRect();
            var cx = Math.max(0, Math.min(window.innerWidth - 1, rect.left + rect.width / 2));
            var cy = Math.max(0, Math.min(window.innerHeight - 1, rect.top + rect.height / 2));
            var top = document.elementFromPoint(cx, cy);
            var covered = !!(top && top !== el && !el.contains(top) && !top.contains(el));
            var anchor = el.closest ? el.closest('a[href]') : null;
            return JSON.stringify({
              ok:true,
              element:summarize(el),
              click:{x:cx,y:cy},
              in_viewport: rect.bottom > 0 && rect.right > 0 && rect.top < window.innerHeight && rect.left < window.innerWidth,
              covered: covered,
              blocker: covered ? summarize(top) : null,
              href: el.href || (anchor ? anchor.href : '') || '',
              target: el.getAttribute('target') || (anchor ? (anchor.getAttribute('target') || '') : ''),
              tag: (el.tagName || '').toLowerCase(),
              submits_form: !!(el.form && ((el.tagName || '').toLowerCase() === 'button' || /^(submit|image)$/i.test(el.type || '')))
            });
            """.trimIndent()
        )
    }

    fun clickScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var info = JSON.parse((function() {
              var el = resolveElement($s);
              if (!el) return JSON.stringify({ ok:false, error:'Element not found', selector:$s });
              el = clickTargetFor(el);
              if (!visible(el)) return JSON.stringify({ ok:false, error:'Element is not visible', selector:$s, element:summarize(el), href:(el.href || '') });
              if (!enabledState(el)) return JSON.stringify({ ok:false, error:'Element is disabled', selector:$s, element:summarize(el), href:(el.href || '') });
              el.scrollIntoView({block:'center', inline:'center', behavior:'auto'});
              var rect = el.getBoundingClientRect();
              var cx = Math.max(0, Math.min(window.innerWidth - 1, rect.left + rect.width / 2));
              var cy = Math.max(0, Math.min(window.innerHeight - 1, rect.top + rect.height / 2));
              var top = document.elementFromPoint(cx, cy);
              var covered = !!(top && top !== el && !el.contains(top) && !top.contains(el));
              var anchor = el.closest ? el.closest('a[href]') : null;
              return JSON.stringify({ ok:true, element:summarize(el), click:{x:cx,y:cy}, covered:covered, blocker:covered ? summarize(top) : null, href:el.href || (anchor ? anchor.href : '') || '' });
            })());
            if (!info.ok) return JSON.stringify(info);
            if (info.covered) {
              return JSON.stringify({ ok:false, error:'Element is covered by another element', selector:$s, blocker:info.blocker, element:info.element, href:info.href });
            }
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Element not found after scroll', selector:$s });
            el = clickTargetFor(el);
            var cx = info.click.x;
            var cy = info.click.y;
            el.focus && el.focus();
            // HTMLElement.click preserves browser default actions, including form submit.
            // Synthetic MouseEvent("click") only runs listeners and leaves submit buttons inert.
            if (typeof el.click === 'function') el.click();
            else el.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window, clientX:cx, clientY:cy}));
            return JSON.stringify({ ok:true, element:summarize(el), click:{x:cx,y:cy}, strategy:'dom_click_fallback', href:info.href });
            """.trimIndent()
        )
    }

    fun hoverScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Element not found', selector:$s });
            el.scrollIntoView({block:'center', inline:'center', behavior:'auto'});
            var rect = el.getBoundingClientRect();
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;
            ['pointerover','mouseover','mouseenter','mousemove'].forEach(function(type) {
              var Ctor = type.indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
              el.dispatchEvent(new Ctor(type, {bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,pointerType:'mouse'}));
            });
            return JSON.stringify({ ok:true, element:summarize(el) });
            """.trimIndent()
        )
    }

    fun selectOptionScript(selector: String, value: String): String {
        val s = JSONObject.quote(selector)
        val v = JSONObject.quote(value)
        return baseInspector(
            """
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Select not found', selector:$s });
            if ((el.tagName || '').toLowerCase() !== 'select') return JSON.stringify({ ok:false, error:'Element is not a select', selector:$s, element:summarize(el) });
            var wanted = String($v);
            var option = Array.prototype.find.call(el.options, function(o) { return o.value === wanted || (o.textContent || '').trim() === wanted; });
            if (!option) return JSON.stringify({ ok:false, error:'Option not found', selector:$s });
            var setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
            if (setter && setter.set) setter.set.call(el, option.value); else el.value = option.value;
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return JSON.stringify({ ok:true, element:summarize(el), value:option.value, text:(option.textContent || '').trim() });
            """.trimIndent()
        )
    }

    fun focusScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Element not found', selector:$s });
            el = focusTargetFor(el);
            el.scrollIntoView({block:'center', inline:'center', behavior:'auto'});
            el.focus && el.focus();
            return JSON.stringify({ ok:true, element:summarize(el) });
            """.trimIndent()
        )
    }

    fun boundsScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Element not found', selector:$s });
            el = clickTargetFor(el);
            var rect = el.getBoundingClientRect();
            return JSON.stringify({ ok:true, x:rect.left, y:rect.top, width:rect.width, height:rect.height, center_x:rect.left + rect.width/2, center_y:rect.top + rect.height/2, element:summarize(el) });
            """.trimIndent()
        )
    }

    fun enterFallbackScript(): String = baseInspector(
        """
        var el = document.activeElement;
        if (el) {
          ['keydown','keypress','keyup'].forEach(function(type) {
            el.dispatchEvent(new KeyboardEvent(type, {bubbles:true, cancelable:true, key:'Enter', code:'Enter', keyCode:13, which:13}));
          });
          if (el.form && typeof el.form.requestSubmit === 'function') el.form.requestSubmit();
          else if (el.form) el.form.submit();
        }
        return JSON.stringify({ ok:true, active: el ? summarize(el) : null });
        """.trimIndent()
    )

    fun submitFromContextScript(selector: String?): String {
        val s = selector?.let { JSONObject.quote(it) } ?: "null"
        return baseInspector(
            """
            var anchor = $s ? resolveElement($s) : document.activeElement;
            if (!anchor) return JSON.stringify({ ok:false, error:'No active or target element available for submit' });
            var purpose = inferSourcePurpose(anchor);
            var button = nearestActionButton(anchor, purpose) || nearestActionButton(anchor, 'submit');
            if (button) {
              button.scrollIntoView({block:'center', inline:'center', behavior:'auto'});
              button.focus && button.focus();
              if (typeof button.click === 'function') button.click();
              else button.dispatchEvent(new MouseEvent('click', { bubbles:true, cancelable:true, view:window }));
              return JSON.stringify({ ok:true, strategy:'related_button', purpose:purpose, button:summarize(button), anchor:summarize(anchor) });
            }
            if (anchor.form && typeof anchor.form.requestSubmit === 'function') {
              anchor.form.requestSubmit();
              return JSON.stringify({ ok:true, strategy:'form_request_submit', purpose:purpose, anchor:summarize(anchor) });
            }
            if (anchor.form) {
              anchor.form.submit();
              return JSON.stringify({ ok:true, strategy:'form_submit', purpose:purpose, anchor:summarize(anchor) });
            }
            ['keydown','keypress','keyup'].forEach(function(type) {
              try {
                anchor.dispatchEvent(new KeyboardEvent(type, {bubbles:true, cancelable:true, key:'Enter', code:'Enter', keyCode:13, which:13}));
              } catch (e) {
                anchor.dispatchEvent(new Event(type, { bubbles:true, cancelable:true }));
              }
            });
            return JSON.stringify({ ok:true, strategy:'enter_fallback', purpose:purpose, anchor:summarize(anchor) });
            """.trimIndent()
        )
    }

    fun findSearchInputScript(): String = baseInspector(
        """
        var nodes = Array.prototype.slice.call(document.querySelectorAll('input,textarea,[contenteditable],[role=textbox]')).filter(function(el) {
          return visible(el) && enabledState(el);
        });
        var best = null;
        var bestScore = -1;
        nodes.forEach(function(el) {
          var score = scoreSearchInput(el);
          if (score > bestScore) {
            bestScore = score;
            best = el;
          }
        });
        return JSON.stringify(best ? (summarize(best).element_id) : null);
        """.trimIndent()
    )

    fun typeScript(selector: String?, text: String, append: Boolean): String {
        val s = selector?.let(JSONObject::quote) ?: "null"
        val t = JSONObject.quote(text)
        return baseInspector(
            """
            var el = $s ? resolveElement($s) : document.activeElement;
            if (!el) return JSON.stringify({ ok:false, error:'No focused editable element' });
            el = focusTargetFor(el);
            if (!isEditable(el)) return JSON.stringify({ ok:false, error:'Focused element is not editable', selector:$s, element:summarize(el) });
            focusEditable(el);
            var value = $t;
            if (!${append}) clearEditable(el);
            var result = insertTextLikeUser(el, value, true);
            return JSON.stringify({ ok:true, element:summarize(el), typed:value.length, strategy:result.strategy, value_after:result.value });
            """.trimIndent()
        )
    }

    fun clearScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            if (!el) return JSON.stringify({ ok:false, error:'Input not found', selector:$s });
            if (!isEditable(el)) return JSON.stringify({ ok:false, error:'Element is not editable', selector:$s, element:summarize(el) });
            focusEditable(el);
            var result = clearEditable(el);
            return JSON.stringify({ ok:true, element:summarize(el), strategy:result.strategy, value_after:result.value });
            """.trimIndent()
        )
    }

    fun elementSensitiveScript(selector: String): String {
        val s = JSONObject.quote(selector)
        return baseInspector(
            """
            var el = resolveElement($s);
            return JSON.stringify(el ? summarize(el) : null);
            """.trimIndent()
        )
    }

    fun elementStateScript(selector: String?): String {
        val s = selector?.let(JSONObject::quote) ?: "null"
        return baseInspector(
            """
            var el = $s ? resolveElement($s) : document.activeElement;
            if (el) el = focusTargetFor(el);
            if (!el) return JSON.stringify({ ok:false, exists:false, selector:$s });
            var active = document.activeElement;
            return JSON.stringify({
              ok:true,
              exists:true,
              selector:selectorFor(el),
              visible:visible(el),
              enabled:enabledState(el),
              focused:active === el || !!(el.contains && active && el.contains(active)),
              checked:el.checked === true || el.getAttribute('aria-checked') === 'true',
              expanded:el.getAttribute('aria-expanded') === 'true',
              editable:isEditable(el),
              clickable:/button|a/i.test(el.tagName || '') || !!el.onclick || el.getAttribute('role') === 'button',
              value:currentEditableValue(el).slice(0, 400),
              text:textForElement(el).trim().replace(/\s+/g, ' ').slice(0, 400)
            });
            """.trimIndent()
        )
    }

    private fun baseInspector(body: String): String = """
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
          if (!window.__amayaMutationObserver && document.documentElement) {
            window.__amayaMutationVersion = 0;
            window.__amayaMutationObserver = new MutationObserver(function() { window.__amayaMutationVersion += 1; });
            window.__amayaMutationObserver.observe(document.documentElement, {subtree:true, childList:true, attributes:true, characterData:true});
          }
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
            if (el.id) return '#' + cssEscape(el.id);
            var data = ['data-testid','data-test','aria-label','name','placeholder'];
            for (var d of data) {
              var v = el.getAttribute(d);
              if (v) return el.tagName.toLowerCase() + '[' + d + '=' + JSON.stringify(v) + ']';
            }
            var path = [];
            var node = el;
            while (node && node.nodeType === 1 && path.length < 5) {
              var name = node.tagName.toLowerCase();
              var parent = node.parentElement;
              if (parent) {
                var siblings = Array.prototype.filter.call(parent.children, function(x) { return x.tagName === node.tagName; });
                if (siblings.length > 1) name += ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')';
              }
              path.unshift(name);
              node = parent;
            }
            return path.join(' > ');
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
          function resolveElement(selector) {
            if (window.__amayaElementMap && window.__amayaElementMap[selector]) selector = window.__amayaElementMap[selector];
            if (typeof selector === 'string' && selector.indexOf('url:') === 0) {
              var targetUrl = selector.slice(4);
              var urlMatch = Array.prototype.find.call(document.querySelectorAll('a[href]'), function(node) { return node.href === targetUrl; });
              if (urlMatch) return urlMatch;
            }
            try { var direct = document.querySelector(selector); if (direct) return direct; } catch (e) {}
            return findElementNode(selector);
          }
          function elementMatchesQuery(node, query, idx) {
            var x = summarize(node, idx + 1);
            var haystack = [x.selector,x.text,x.label,x.accessible_name,x.data_testid,x.title,x.placeholder,x.name,x.id,x.href,x.type,x.role].join(' ').toLowerCase();
            var tokens = queryTokens(query);
            var score = 0;
            if (haystack.indexOf(String(query || '').toLowerCase()) >= 0) score += 80;
            tokens.forEach(function(token) {
              if (haystack.indexOf(token) >= 0) score += 25;
            });
            if (x.visible) score += 10;
            if (x.enabled) score += 8;
            return score;
          }
          function findElementNode(query) {
            var q = String(query || '').toLowerCase();
            var nodes = Array.prototype.slice.call(document.querySelectorAll('button,[role=button],[role=link],input,textarea,select,a[href],[onclick],label,[contenteditable],[role=textbox],article,[role=article],li,[role=listitem],[data-testid],[tabindex]'));
            var best = null;
            var bestScore = -1;
            nodes.forEach(function(node, idx) {
              var score = elementMatchesQuery(node, q, idx);
              if (score > bestScore) {
                best = node;
                bestScore = score;
              }
            });
            return bestScore > 0 ? best : null;
          }
          function findElement(query) {
            var node = findElementNode(query);
            return node ? summarize(node, 1) : null;
          }
          function collect(selector, offset) {
            return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(visible).slice(0, 120).map(function(node, idx) { return summarize(node, (offset || 0) + idx + 1); });
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
          $body
        })();
    """.trimIndent()
}
