package com.sillyclient.runtime

/**
 * Reports an in-progress SillyTavern reply to [SessionContinuity].
 *
 * Text generation is the stream that must survive a locked or covered screen.
 * Image, video, and speech requests are intentionally ignored.
 */
object SessionReceiveScript {
    const val BRIDGE = "SillyClientSession"
    val CHAT_GENERATE = Regex(
        "^/api/(?:backends/(?:chat-completions|text-completions|kobold|koboldhorde)|novelai)/generate$"
    )
    const val GENERATING_PROBE =
        "(function(){return !!(document.body&&document.body.dataset.generating==='true');})()"

    fun isChatGenerate(url: String): Boolean {
        val path = extractPath(url) ?: return false
        return CHAT_GENERATE.matches(path)
    }

    fun source(): String = """
        (() => {
          const bridge = window.$BRIDGE;
          if (!bridge || typeof bridge.setReceiving !== 'function' || typeof bridge.pulse !== 'function') return;
          if (window.__scSessionReceiveInstalled) return;
          window.__scSessionReceiveInstalled = true;

          const chatGenerate = new RegExp('${CHAT_GENERATE.pattern}');
          const eventLimitMs = 180000;
          const streamLimitMs = 30 * 60 * 1000;
          let events = 0;
          let streams = 0;
          let reported = false;
          let eventTimer = null;

          function generating() {
            return !!(document.body && document.body.dataset.generating === 'true');
          }

          function pulse() {
            try { bridge.pulse(); } catch (_) {}
          }

          function publish() {
            const on = events > 0 || streams > 0 || generating();
            if (on === reported) {
              if (on) pulse();
              return;
            }
            reported = on;
            try { bridge.setReceiving(on); } catch (_) {}
            if (on) pulse();
          }

          function armEventTimeout() {
            if (eventTimer !== null) clearTimeout(eventTimer);
            eventTimer = setTimeout(() => {
              eventTimer = null;
              if (!generating() && streams === 0 && events > 0) {
                events = 0;
                publish();
              }
            }, eventLimitMs);
          }

          function requestPath(input) {
            const raw = typeof input === 'string' ? input : (input && input.url) || '';
            if (!raw) return '';
            try {
              const path = new URL(raw, location.href).pathname;
              return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path;
            } catch (_) {
              return '';
            }
          }

          function finishStream(state) {
            if (state.done) return;
            state.done = true;
            clearTimeout(state.timer);
            streams = Math.max(0, streams - 1);
            publish();
          }

          function adopt(response) {
            const state = { done: false, timer: 0 };
            state.timer = setTimeout(() => finishStream(state), streamLimitMs);
            const body = response && response.body;
            if (!response || !response.ok || !body || typeof body.getReader !== 'function' ||
                typeof ReadableStream !== 'function') {
              finishStream(state);
              return response;
            }
            let reader;
            try { reader = body.getReader(); }
            catch (_) { finishStream(state); return response; }
            const stream = new ReadableStream({
              pull(controller) {
                return reader.read().then((result) => {
                  if (result.done) { finishStream(state); controller.close(); }
                  else controller.enqueue(result.value);
                }, (error) => { finishStream(state); throw error; });
              },
              cancel(reason) {
                finishStream(state);
                try { return reader.cancel(reason); } catch (_) { return undefined; }
              }
            });
            try {
              return new Response(stream, {
                status: response.status,
                statusText: response.statusText,
                headers: response.headers
              });
            } catch (_) {
              finishStream(state);
              return response;
            }
          }

          const originalFetch = window.fetch;
          if (typeof originalFetch === 'function') {
            window.fetch = function(input, init) {
              if (!chatGenerate.test(requestPath(input))) return originalFetch.apply(this, arguments);
              streams += 1;
              if (eventTimer !== null) { clearTimeout(eventTimer); eventTimer = null; }
              publish();
              try {
                return Promise.resolve(originalFetch.apply(this, arguments)).then(adopt, (error) => {
                  streams = Math.max(0, streams - 1);
                  publish();
                  throw error;
                });
              } catch (error) {
                streams = Math.max(0, streams - 1);
                publish();
                throw error;
              }
            };
          }

          function bindEvents() {
            const api = window.SillyTavern;
            const context = api && typeof api.getContext === 'function' ? api.getContext() : null;
            const source = context && context.eventSource;
            const types = context && context.eventTypes;
            if (!source || !types || source.__scSessionBound) return !!(source && source.__scSessionBound);
            if (!types.GENERATION_STARTED || !types.GENERATION_ENDED || typeof source.on !== 'function') return false;
            source.__scSessionBound = true;
            source.on(types.GENERATION_STARTED, (_type, _options, dryRun) => {
              if (dryRun) return;
              events += 1;
              publish();
              armEventTimeout();
            });
            source.on(types.GENERATION_ENDED, () => {
              events = Math.max(0, events - 1);
              publish();
            });
            return true;
          }

          if (!bindEvents()) {
            const retry = setInterval(() => { if (bindEvents()) clearInterval(retry); }, 500);
            setTimeout(() => clearInterval(retry), 120000);
          }

          function watchGenerating() {
            if (!document.body || document.body.__scSessionObserved) return;
            document.body.__scSessionObserved = true;
            const observer = new MutationObserver(() => {
              if (generating() && eventTimer !== null) { clearTimeout(eventTimer); eventTimer = null; }
              publish();
            });
            observer.observe(document.body, { attributes: true, attributeFilter: ['data-generating'] });
          }

          if (document.body) watchGenerating();
          else document.addEventListener('DOMContentLoaded', watchGenerating, { once: true });
          setInterval(() => {
            if (generating() && eventTimer !== null) { clearTimeout(eventTimer); eventTimer = null; }
            publish();
          }, 10000);
          publish();
        })();
    """.trimIndent()

    private fun extractPath(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isISOControl() }) return null
        val withoutFragment = trimmed.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val path = when {
            withoutQuery.startsWith("/") -> withoutQuery
            withoutQuery.startsWith("http://") || withoutQuery.startsWith("https://") ->
                runCatching { java.net.URI(withoutQuery).path }.getOrNull() ?: return null
            else -> return null
        }
        if (path.isEmpty() || path.contains("\\")) return null
        return if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path
    }
}
