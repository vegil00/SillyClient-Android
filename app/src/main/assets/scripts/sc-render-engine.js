/**
 * SillyClient Render Engine v0.2.5
 * 位于 SillyTavern 与 WebView/Chromium 之间的深度渲染调度引擎
 *
 * 核心架构组件:
 * 1. ChameleonEngine: 变色龙精准事件感知取色 (首帧瞬时同步 + 定向 DOM 变动通知，彻底 0 轮询)
 * 2. Streaming Diagnostics: HUD-only native-frame metrics without intercepting DOM writes
 * 3. Optional Performance Preset: 长聊天视口外消息跳过排版绘制、资源按需渲染与既有抽屉动画
 * 4. Existing Visual Rules: 保持原有样式与动画，包含抽屉打开时既有的聊天毛玻璃规则
 * 5. FrameScheduler: 读写严格分离原子化调度 (Read/Write Phase 严格隔离)
 * 6. Offscreen Animation Freeze: 预设开启时按需观察并挂起视口外动画
 * 7. Zero-Timer Interaction Governor: 手势触控优先调频器 (0 Timer 堆开销)
 * 8. Performance Monitor & SC Slate-900 Glass HUD: 极客监控面板与全站微震触感开关
 */
(function() {
    if (window.__scRenderEngineInstalled) return;
    window.__scRenderEngineInstalled = true;

    let disposed = false;
    let pageSuspended = false;
    const ownedListeners = [];
    const patchRestorers = [];

    function isPageActive() {
        return !disposed && !pageSuspended && !document.hidden;
    }

    function listen(target, type, callback, options, owners = ownedListeners) {
        target.addEventListener(type, callback, options);
        owners.push(() => target.removeEventListener(type, callback, options));
    }

    // ========================================================
    // 0. 全局引擎状态 & 触感持久化配置
    // ========================================================
    // 存储可能被 WebView 策略拒绝（错误页/分区上下文）；引擎必须带降级地自持。
    const safeStorageGet = (key) => {
        try { return window.localStorage.getItem(key); } catch (_) { return null; }
    };
    const safeStorageSet = (key, value) => {
        try { window.localStorage.setItem(key, value); } catch (_) { /* 持久化不可用时仅用内存态 */ }
    };
    const engineState = {
        fps: 90.0,
        frameTime: 11.1,
        p95: 11.1,
        p99: 11.1,
        longTaskCount: 0,
        domCount: 0,
        mutationsPerSec: 0,
        streamingFps: 0,
        lastFrameJsTime: 1.2,
        virtualizationActive: false,
        frozenAnimCount: 0,
        isInteracting: false,
        lastTopColor: null,
        hapticEnabled: safeStorageGet('__sc_haptic_enabled') === '1',
        hudVisible: false
    };
    const initialSettings = window.__scInitialRenderSettings || {};
    const renderSettings = {
        colorEnabled: initialSettings.colorEnabled !== false,
        mode: initialSettings.mode === 'once' ? 'once' : 'realtime',
        clickEffect: initialSettings.clickEffect !== false,
        performancePreset: initialSettings.performancePreset !== false
    };

    // ========================================================
    // 1. RUNTIME PATCHES (原生底座加固与事件纠偏)
    // ========================================================
    function installRuntimePatches() {
        try {
            // 1.1 彻底清理历史遗留旧样式表与标记
            const oldStyle = document.getElementById('sc-drawer-perf');
            if (oldStyle) oldStyle.remove();

            if (window.__scJQueryFxWatcher) {
                clearInterval(window.__scJQueryFxWatcher);
                window.__scJQueryFxWatcher = null;
            }
            if (window.jQuery && window.jQuery.fx) {
                window.jQuery.fx.off = false;
            }

            // 1.2 声明 CSS field-sizing 支持，绕过 textarea 同步重排死循环
            if (window.CSS && !CSS.supports('field-sizing', 'content')) {
                const originalSupports = CSS.supports.bind(CSS);
                const patchedSupports = function(property, value) {
                    if (property === 'field-sizing') return true;
                    return originalSupports(property, value);
                };
                const previousSupports = CSS.supports;
                CSS.supports = patchedSupports;
                patchRestorers.push(() => {
                    if (CSS.supports === patchedSupports) CSS.supports = previousSupports;
                });
            }

            // 1.3 取消进角色对话页面自动展开输入法 (用户主动轻触才弹起)
            if (!window.__scAutoFocusBlockerInstalled) {
                window.__scAutoFocusBlockerInstalled = true;
                let userTappedTextarea = false;

                const markUserTap = (e) => {
                    userTappedTextarea = !!(e.target && (e.target.id === 'send_textarea' || (e.target.closest && e.target.closest('#send_textarea'))));
                };
                listen(document, 'touchstart', markUserTap, { capture: true, passive: true });
                listen(document, 'mousedown', markUserTap, { capture: true, passive: true });

                const origTextareaFocus = HTMLTextAreaElement.prototype.focus;
                const patchedTextareaFocus = function(options) {
                    if (this.id === 'send_textarea' && !userTappedTextarea) return;
                    return origTextareaFocus.call(this, options);
                };
                HTMLTextAreaElement.prototype.focus = patchedTextareaFocus;
                patchRestorers.push(() => {
                    if (HTMLTextAreaElement.prototype.focus === patchedTextareaFocus) {
                        HTMLTextAreaElement.prototype.focus = origTextareaFocus;
                        window.__scAutoFocusBlockerInstalled = false;
                    }
                });

                if (window.jQuery) {
                    const jq = window.jQuery;
                    const origTrigger = jq.fn.trigger;
                    const patchedTrigger = function(type, data) {
                        if (this.is('#send_textarea') && !userTappedTextarea && (type === 'focus' || type === 'click' || type === 'focusin')) {
                            return this;
                        }
                        return origTrigger.apply(this, arguments);
                    };
                    jq.fn.trigger = patchedTrigger;
                    patchRestorers.push(() => {
                        if (jq.fn.trigger === patchedTrigger) jq.fn.trigger = origTrigger;
                    });
                }
            }

            // 1.4 清理 form_sheld 遗留 lift/spacer 结构，恢复纯净 DOM
            const formSheld = document.getElementById('form_sheld');
            if (formSheld) {
                const lift = formSheld.querySelector('[data-sc-ime-lift]');
                const spacer = formSheld.querySelector('[data-sc-ime-spacer]');
                if (lift) {
                    while (lift.firstChild) formSheld.insertBefore(lift.firstChild, lift);
                    lift.remove();
                }
                if (spacer) spacer.remove();
            }

        } catch(_) {}
    }
    installRuntimePatches();

    // ========================================================
    // 2. NATIVE HAPTIC BRIDGE (原生线性马达精密触感引擎)
    // ========================================================
    window.__scHaptic = function(type) {
        if (!isPageActive() || !engineState.hapticEnabled) return;
        try {
            if (window.SillyClientHaptic && window.SillyClientHaptic.trigger) {
                window.SillyClientHaptic.trigger(type || 'tick');
            }
        } catch(_) {}
    };

    // 酒馆全站高频交互触觉委托捕获
    listen(document, 'pointerdown', (e) => {
        if (!engineState.hapticEnabled) return;
        const target = e.target;
        if (!target || !(target instanceof Element)) return;

        // 1. 发送与生成中止
        if (target.closest('#send_but, .send_button, #abort_button, #send_textarea_container button')) {
            window.__scHaptic('click');
            return;
        }
        // 2. 左右侧边栏与抽屉切换按钮
        if (target.closest('#right-nav-panel, #left-nav-panel, #nav-toggle, .drawer-toggle, #open_character_drawer, #open_world_info, #floating_prompt_manager, #option_toggle, #persona-management-button')) {
            window.__scHaptic('tick');
            return;
        }
        // 3. Swipe 滑动切换与消息操作菜单
        if (target.closest('.swipe_left, .swipe_right, .swipe_counter, .mes_edit, .mes_copy, .mes_del, .mes_favorite, .mes_btn')) {
            window.__scHaptic('tick');
            return;
        }
        // 4. 角色卡选择与头像轻触
        if (target.closest('.character_select, .avatar, .select_character, .character_item, #character_list .character, .dry_run_character')) {
            window.__scHaptic('tick');
            return;
        }
        // 5. 顶栏操作项、弹窗确认与通用按钮
        if (target.closest('#top-bar button, .menu_button, .list-group-item, .dialog_button, .popup_button, .interactive_element')) {
            window.__scHaptic('tick');
            return;
        }
    }, { capture: true, passive: true });

    // ========================================================
    // 2. FRAME SCHEDULER (统一单帧调度器 - Read/Write 严格分离)
    // ========================================================
    const scheduler = {
        readQueue: [],
        writeQueue: [],
        scheduled: false,
        frameId: null,
        flushing: false,

        read: function(fn) {
            if (disposed) return;
            this.readQueue.push(fn);
            this.schedule();
        },

        write: function(fn) {
            if (disposed) return;
            this.writeQueue.push(fn);
            this.schedule();
        },

        schedule: function() {
            if (this.scheduled || !isPageActive()) return;
            this.scheduled = true;
            this.frameId = requestAnimationFrame(() => {
                this.frameId = null;
                this.flush();
            });
        },

        flush: function() {
            if (this.flushing) return;
            if (!isPageActive()) {
                this.pause();
                return;
            }
            if (this.frameId !== null) cancelAnimationFrame(this.frameId);
            this.frameId = null;
            this.scheduled = true;
            this.flushing = true;
            const start = performance.now();
            const reads = this.readQueue.splice(0);
            for (let i = 0; i < reads.length; i++) {
                if (disposed) break;
                try { reads[i](); } catch(e) {}
            }

            const writes = this.writeQueue.splice(0);
            for (let i = 0; i < writes.length; i++) {
                if (disposed) break;
                try { writes[i](); } catch(e) {}
            }

            this.scheduled = false;
            this.flushing = false;
            engineState.lastFrameJsTime = performance.now() - start;
            if (this.readQueue.length || this.writeQueue.length) this.schedule();
        },

        pause: function() {
            if (this.frameId !== null) cancelAnimationFrame(this.frameId);
            this.frameId = null;
            this.scheduled = this.flushing;
        }
    };
    window.__scFrameScheduler = scheduler;

    // ========================================================
    // 3. STREAMING DIAGNOSTICS (native HTML and scroll remain upstream-owned)
    // ========================================================
    const streamingBatcher = {
        streamingFpsCount: 0,
        currentStreamingFps: 0,
        isStreamingActive: false,
    };
    window.__scStreamingBatcher = streamingBatcher;

    // ========================================================
    // 4. CHAMELEON ENGINE (变色龙精准事件感知取色 - 0 轮询)
    // ========================================================
    let chameleonStartupTimer = null;
    window.__scUpdateRenderSettings = function(colorEnabled, mode, clickEffect, performancePreset) {
        if (disposed) return;
        const nextColorEnabled = !!colorEnabled;
        const nextMode = mode === 'once' ? 'once' : 'realtime';
        const samplingChanged = renderSettings.colorEnabled !== nextColorEnabled || renderSettings.mode !== nextMode;
        renderSettings.colorEnabled = nextColorEnabled;
        renderSettings.mode = nextMode;
        renderSettings.clickEffect = !!clickEffect;
        if (performancePreset !== undefined) renderSettings.performancePreset = !!performancePreset;
        syncPerformancePreset();
        if (!samplingChanged) return;
        chameleonEngine.pause();
        if (renderSettings.mode === 'realtime') {
            chameleonEngine.hasSampledOnce = false;
        }
        if (!chameleonEngine.canSample()) return;
        startChameleon(renderSettings.mode === 'realtime');
        if (renderSettings.mode === 'once' && chameleonEngine.isThemeMounted() &&
            chameleonEngine.lastReportedColor !== null) {
            chameleonEngine.settleOnce(400);
        }
    };

    const chameleonEngine = {
        lastReportedColor: null,
        debounceTimer: null,
        settleTimer: null,
        observer: null,
        hasSampledOnce: false,

        canSample: function(force) {
            return isPageActive() && renderSettings.colorEnabled &&
                (force || renderSettings.mode !== 'once' || !this.hasSampledOnce);
        },

        isThemeMounted: function() {
            if (document.getElementById('top-bar')) return true;
            try {
                const tint = window.getComputedStyle(document.documentElement).getPropertyValue('--SmartThemeBlurTintColor');
                if (tint && tint.trim()) return true;
            } catch(_) {}
            if (document.getElementById('bg1') || document.getElementById('chat') || document.getElementById('sheld')) return true;
            return false;
        },

        parseCssColor: function(str) {
            if (!str || str === 'transparent' || str === 'inherit' || str === 'initial') return null;
            const m = str.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
            if (m) {
                const a = m[4] !== undefined ? parseFloat(m[4]) : 1.0;
                if (a > 0.05) return { r: parseInt(m[1], 10), g: parseInt(m[2], 10), b: parseInt(m[3], 10), a: a };
            }
            if (str.startsWith('#')) {
                let hex = str.substring(1);
                if (hex.length === 3) hex = hex[0]+hex[0] + hex[1]+hex[1] + hex[2]+hex[2];
                if (hex.length === 6) {
                    return {
                        r: parseInt(hex.substring(0, 2), 16),
                        g: parseInt(hex.substring(2, 4), 16),
                        b: parseInt(hex.substring(4, 6), 16),
                        a: 1.0
                    };
                }
            }
            return null;
        },

        blend: function(fg, bg) {
            if (!fg) return bg;
            if (fg.a >= 0.999) return fg;
            const bgR = bg ? bg.r : 36;
            const bgG = bg ? bg.g : 36;
            const bgB = bg ? bg.b : 37;
            const a = fg.a;
            return {
                r: Math.round(fg.r * a + bgR * (1 - a)),
                g: Math.round(fg.g * a + bgG * (1 - a)),
                b: Math.round(fg.b * a + bgB * (1 - a)),
                a: 1.0
            };
        },

        computeTopColor: function(strict) {
            let bodyBg = null;
            if (document.body) {
                bodyBg = this.parseCssColor(window.getComputedStyle(document.body).backgroundColor);
            }
            const bg1 = document.getElementById('bg1');
            if (bg1) {
                const bg1Color = this.parseCssColor(window.getComputedStyle(bg1).backgroundColor);
                if (bg1Color) bodyBg = bg1Color;
            }
            const topBar = document.getElementById('top-bar');
            if (topBar) {
                const cs = window.getComputedStyle(topBar);
                const c = this.parseCssColor(cs.backgroundColor);
                if (c) {
                    const res = (c.a >= 0.90) ? c : this.blend(c, bodyBg);
                    return (0xFF000000 | (res.r << 16) | (res.g << 8) | res.b);
                }
            }
            try {
                const rootStyle = window.getComputedStyle(document.documentElement);
                const tint = rootStyle.getPropertyValue('--SmartThemeBlurTintColor');
                if (tint) {
                    const tc = this.parseCssColor(tint.trim());
                    if (tc) {
                        const res = (tc.a >= 0.90) ? tc : this.blend(tc, bodyBg);
                        return (0xFF000000 | (res.r << 16) | (res.g << 8) | res.b);
                    }
                }
            } catch (_) {}
            const meta = document.querySelector('meta[name="theme-color"]');
            if (meta) {
                const mc = this.parseCssColor(meta.getAttribute('content'));
                if (mc) return (0xFF000000 | (mc.r << 16) | (mc.g << 8) | mc.b);
            }
            if (strict) return null;
            if (bodyBg) return (0xFF000000 | (bodyBg.r << 16) | (bodyBg.g << 8) | bodyBg.b);
            return null;
        },

        sampleAndReport: function(force) {
            if (!this.canSample(force)) return;
            try {
                const strict = (renderSettings.mode === 'once' && !force && !this.isThemeMounted());
                const color = this.computeTopColor(strict);
                if (color !== null && (force || color !== this.lastReportedColor)) {
                    this.lastReportedColor = color;
                    engineState.lastTopColor = color;
                    if (window.SillyClientRenderBridge && window.SillyClientRenderBridge.onColorChanged) {
                        window.SillyClientRenderBridge.onColorChanged(color);
                    }
                }
            } catch(_) {}
        },

        onTargetMutated: function() {
            if (!this.canSample()) return;
            if (this.debounceTimer) clearTimeout(this.debounceTimer);
            this.debounceTimer = setTimeout(() => {
                this.debounceTimer = null;
                if (!this.canSample()) return;
                this.bindTargets();
                this.sampleAndReport(false);
                if (renderSettings.mode === 'once') {
                    if (this.isThemeMounted() && this.lastReportedColor !== null) {
                        this.settleOnce(500);
                    }
                }
            }, 120);
        },

        settleOnce: function(delay) {
            if (!this.canSample() || renderSettings.mode !== 'once') return;
            if (this.settleTimer !== null) clearTimeout(this.settleTimer);
            this.settleTimer = setTimeout(() => {
                this.settleTimer = null;
                if (!this.canSample() || renderSettings.mode !== 'once') return;
                this.sampleAndReport(true);
                this.hasSampledOnce = true;
                this.pause();
            }, delay);
        },

        initObserver: function() {
            if (!this.canSample() || !window.MutationObserver) return;
            try {
                if (!this.observer) this.observer = new MutationObserver(() => this.onTargetMutated());
                this.bindTargets();
            } catch(_) {}
        },

        bindTargets: function() {
            if (!this.observer || !this.canSample()) return;
            this.observer.disconnect();
            const candidates = [
                document.documentElement,
                document.body,
                document.getElementById('top-bar'),
                document.getElementById('bg1'),
                document.getElementById('bg2'),
                document.querySelector('meta[name="theme-color"]')
            ];
            candidates.forEach(el => {
                if (el) {
                    try {
                        this.observer.observe(el, {
                            attributes: true,
                            attributeFilter: ['style', 'class', 'content'],
                            childList: false,
                            subtree: false
                        });
                    } catch(_) {}
                }
            });
        },

        pause: function() {
            if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
            this.debounceTimer = null;
            if (this.settleTimer !== null) clearTimeout(this.settleTimer);
            this.settleTimer = null;
            if (chameleonStartupTimer !== null) clearTimeout(chameleonStartupTimer);
            chameleonStartupTimer = null;
            if (this.observer) this.observer.disconnect();
        }
    };
    window.__scChameleonEngine = chameleonEngine;

    function startChameleon(force) {
        if (!chameleonEngine.canSample()) return;
        chameleonEngine.initObserver();
        chameleonEngine.sampleAndReport(force);
        if (chameleonStartupTimer !== null) clearTimeout(chameleonStartupTimer);
        chameleonStartupTimer = setTimeout(() => {
            chameleonStartupTimer = null;
            if (!chameleonEngine.canSample()) return;
            chameleonEngine.bindTargets();
            chameleonEngine.sampleAndReport(false);
            if (renderSettings.mode === 'once' && !chameleonEngine.hasSampledOnce && isPageActive()) {
                chameleonEngine.sampleAndReport(true);
                chameleonEngine.hasSampledOnce = true;
                chameleonEngine.pause();
            }
        }, 1500);
    }

    if (document.readyState === 'loading') {
        listen(document, 'DOMContentLoaded', () => {
            chameleonEngine.initObserver();
            chameleonEngine.sampleAndReport(false);
        }, { once: true });
    }
    startChameleon();
    if (renderSettings.mode === 'once' && chameleonEngine.isThemeMounted() &&
        chameleonEngine.lastReportedColor !== null) {
        chameleonEngine.settleOnce(400);
    }

    // ========================================================
    // 5. DOM MUTATION BATCHER (突变监控与聚合，HUD 按需挂载)
    // ========================================================
    let mutationCounter = 0;
    let domMutationObserver = null;
    let streamingFramePending = false;

    function startMutationObserver() {
        if (domMutationObserver || !isPageActive() || !engineState.hudVisible || !window.MutationObserver) return;
        try {
            domMutationObserver = new MutationObserver((mutations) => {
                if (!isPageActive() || !engineState.hudVisible) return;
                mutationCounter += mutations.length;
                for (const mutation of mutations) {
                    const target = mutation.target instanceof Element ? mutation.target : mutation.target.parentElement;
                    if (target && target.closest('.mes_text')) {
                        streamingFramePending = true;
                        streamingBatcher.isStreamingActive = true;
                        break;
                    }
                }
            });
            domMutationObserver.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: false,
                characterData: true
            });
        } catch(e) {}
    }

    function stopMutationObserver() {
        if (domMutationObserver) {
            domMutationObserver.disconnect();
            domMutationObserver = null;
        }
        mutationCounter = 0;
        streamingFramePending = false;
        streamingBatcher.streamingFpsCount = 0;
        streamingBatcher.currentStreamingFps = 0;
        streamingBatcher.isStreamingActive = false;
        engineState.mutationsPerSec = 0;
        engineState.streamingFps = 0;
    }

    function updateMutationMetrics() {
        if (!isPageActive() || !engineState.hudVisible) return;
        engineState.mutationsPerSec = mutationCounter;
        mutationCounter = 0;
        engineState.streamingFps = streamingBatcher.streamingFpsCount;
        streamingBatcher.currentStreamingFps = streamingBatcher.streamingFpsCount;
        streamingBatcher.streamingFpsCount = 0;
        if (engineState.streamingFps === 0 && !streamingFramePending) {
            streamingBatcher.isStreamingActive = false;
        }
    }

    // ========================================================
    // 6. 基础布局与可选性能样式 (Mandatory Layout & Optional Containment)
    // ========================================================
    let mandatoryStyle = null;
    let performanceStyle = null;
    let performancePresetActive = false;

    function setupMandatoryLayout() {
        if (disposed || mandatoryStyle || !document.head) return;
        const style = document.createElement('style');
        style.id = 'sc-render-layout';
        style.textContent = `
            html, body {
                overflow: hidden !important;
                height: 100% !important;
            }
            #chat {
                -webkit-overflow-scrolling: touch !important;
                touch-action: pan-y pinch-zoom;
            }
            #send_textarea {
                field-sizing: content !important;
                max-height: 160px !important;
            }
            #chat .mes_text pre,
            #chat .mes_text table,
            #chat .katex-display {
                max-width: 100% !important;
                overflow-x: auto !important;
                -webkit-overflow-scrolling: touch !important;
            }
            button, .menu_button, .drawer-toggle, #send_but {
                touch-action: manipulation;
            }
            * {
                -webkit-tap-highlight-color: transparent !important;
            }
        `;
        document.head.appendChild(style);
        mandatoryStyle = style;
    }
    if (document.head) {
        setupMandatoryLayout();
    } else {
        listen(document, 'DOMContentLoaded', () => {
            setupMandatoryLayout();
            syncPerformancePreset();
        }, { once: true });
    }

    function setupPerformanceStyle() {
        if (performanceStyle || !document.head) return;
        const style = document.createElement('style');
        style.id = 'sc-performance-preset';
        style.textContent = `
            html, body {
                overscroll-behavior: none !important;
                text-rendering: optimizeSpeed !important;
            }
            #chat {
                overscroll-behavior: contain;
                overscroll-behavior-y: contain !important;
                contain: layout style paint;
                isolation: isolate;
                transform: translateZ(0);
                scroll-behavior: auto !important;
                text-rendering: optimizeSpeed !important;
            }
            #send_textarea {
                contain: layout style !important;
            }
            .mes {
                contain: layout style;
            }
            .mes_text {
                contain: content;
            }
            #chat .mes {
                contain: layout style !important;
            }
            #chat .mes.__sc-mes-skipped {
                content-visibility: auto;
                contain-intrinsic-block-size: auto 160px;
            }
            #chat .mes:last-child {
                transform: translateZ(0);
            }
            .mes_avatar {
                contain: paint layout !important;
                user-select: none !important;
                -webkit-user-select: none !important;
            }
            #chat img {
                content-visibility: auto;
                contain-intrinsic-size: auto 120px;
                decoding: async !important;
            }
            .character_select {
                content-visibility: auto;
                contain-intrinsic-size: auto 72px;
            }
            .recentChat {
                content-visibility: auto;
                contain-intrinsic-size: auto 58px;
            }
            #top-bar, #send_form {
                will-change: transform;
            }
            .typing_indicator, .cursor, .typing, .spinner, .rotating {
                will-change: opacity, transform !important;
                transform: translateZ(0) !important;
            }
            #chat .mes_text pre,
            #chat .mes_text table,
            #chat .katex-display {
                contain: layout paint;
            }
            body:has(.drawer-content.openDrawer) #chat,
            body:has(.drawer-content[style*="display: block"]) #chat,
            body:has(.popup[open]) #chat,
            body:has(.popup[opening]) #chat {
                backdrop-filter: none !important;
                -webkit-backdrop-filter: none !important;
            }
            .__sc-anim-frozen, .__sc-anim-frozen * {
                animation-play-state: paused !important;
            }
        `;
        document.head.appendChild(style);
        performanceStyle = style;
    }

    let drawerPatch = null;
    const drawerAnimations = new Map();

    function settleDrawerAnimation(entry) {
        entry.animation.onfinish = null;
        try {
            entry.complete();
        } finally {
            entry.animation.cancel();
            if (drawerAnimations.get(entry.element) === entry) drawerAnimations.delete(entry.element);
        }
    }

    function installDrawerAnimations() {
        const jq = window.jQuery;
        if (drawerPatch || !jq || !jq.fn) return;
        const originals = {
            slideToggle: jq.fn.slideToggle,
            slideDown: jq.fn.slideDown,
            slideUp: jq.fn.slideUp
        };
        if (Object.values(originals).some(method => typeof method !== 'function')) return;
        const patch = { jq, originals, methods: {} };
        drawerPatch = patch;
        window.__scSlideTogglePatched = true;
        const selector = '.inline-drawer-content';
        const isHidden = element => element.style.display === 'none' ||
            window.getComputedStyle(element).display === 'none';
        const fastAnimate = (element, toOpen, callback) => {
            const previous = drawerAnimations.get(element);
            if (previous) settleDrawerAnimation(previous);
            let completed = false;
            const complete = () => {
                if (completed) return;
                completed = true;
                if (!toOpen) element.style.display = 'none';
                if (typeof callback === 'function') callback.call(element);
            };
            if (toOpen) element.style.display = 'block';
            try {
                if (element.animate) {
                    const animation = element.animate(toOpen ? [
                        { opacity: 0.15, transform: 'translateY(-4px)' },
                        { opacity: 1, transform: 'translateY(0)' }
                    ] : [
                        { opacity: 1, transform: 'translateY(0)' },
                        { opacity: 0, transform: 'translateY(-3px)' }
                    ], {
                        duration: toOpen ? 90 : 70,
                        easing: toOpen ? 'cubic-bezier(0.12, 0.98, 0.24, 1)' : 'cubic-bezier(0.4, 0, 1, 1)',
                        fill: 'forwards'
                    });
                    drawerAnimations.set(element, { element, animation, complete });
                    if (!toOpen) animation.onfinish = complete;
                    if (toOpen) complete();
                    return;
                }
            } catch(_) {}
            complete();
        };
        for (const name of Object.keys(originals)) {
            const original = originals[name];
            const method = function(duration, easing, complete) {
                if (drawerPatch !== patch || !isPerformancePresetActive()) {
                    return original.apply(this, arguments);
                }
                const callback = typeof easing === 'function' ? easing : complete;
                const inline = this.filter(selector);
                const rest = this.not(selector);
                inline.each(function() {
                    fastAnimate(this, name === 'slideToggle' ? isHidden(this) : name === 'slideDown', callback);
                });
                if (rest.length) original.apply(rest, arguments);
                return this;
            };
            patch.methods[name] = method;
            jq.fn[name] = method;
        }
    }

    function stopDrawerAnimations() {
        const patch = drawerPatch;
        drawerPatch = null;
        if (patch) {
            for (const name of Object.keys(patch.originals)) {
                if (patch.jq.fn[name] === patch.methods[name]) patch.jq.fn[name] = patch.originals[name];
            }
            window.__scSlideTogglePatched = false;
        }
        settleOwnedDrawerAnimations();
    }

    function settleOwnedDrawerAnimations() {
        for (const entry of Array.from(drawerAnimations.values())) {
            try { settleDrawerAnimation(entry); } catch(_) {}
        }
    }

    // ========================================================
    // 7. 可选视口外动画观察与挂起
    // ========================================================
    const frozenElements = new Set();
    const observedAnimations = new Set();
    let animObserver = null;
    let animationScanTimer = null;
    let animationIdleId = null;
    let animationGeneration = 0;

    function isPerformancePresetActive() {
        return performancePresetActive && renderSettings.performancePreset && isPageActive();
    }

    function animationScanIsCurrent(generation) {
        return generation === animationGeneration && isPerformancePresetActive() && animObserver !== null;
    }

    function startAnimationScan() {
        if (animObserver || !isPerformancePresetActive() || !window.IntersectionObserver) return;
        const generation = ++animationGeneration;
        try {
            const observer = new IntersectionObserver((entries) => {
                if (!animationScanIsCurrent(generation) || animObserver !== observer) return;
                for (let i = 0; i < entries.length; i++) {
                    const entry = entries[i];
                    const target = entry.target;
                    if (!target.isConnected || !observedAnimations.has(target)) continue;
                    if (entry.isIntersecting) {
                        target.classList.remove('__sc-anim-frozen');
                        frozenElements.delete(target);
                    } else {
                        target.classList.add('__sc-anim-frozen');
                        frozenElements.add(target);
                    }
                }
                engineState.frozenAnimCount = frozenElements.size;
            }, {
                rootMargin: '80px 0px 80px 0px'
            });
            animObserver = observer;
            animationScanTimer = setTimeout(() => scheduleIdleScan(generation), 2000);
        } catch(e) {}
    }


    function scanAndObserveAnimations() {
        if (!animObserver || !isPerformancePresetActive()) return;
        for (const entry of Array.from(drawerAnimations.values())) {
            if (!entry.element.isConnected) {
                try { settleDrawerAnimation(entry); } catch(_) {}
            }
        }
        for (const element of observedAnimations) {
            if (!element.isConnected || !element.matches('.spinner, .rotating, .typing_indicator')) {
                animObserver.unobserve(element);
                observedAnimations.delete(element);
                frozenElements.delete(element);
                element.classList.remove('__sc-anim-frozen');
            }
        }
        const candidates = document.querySelectorAll('.spinner, .rotating, .typing_indicator');
        for (let i = 0; i < candidates.length; i++) {
            if (!observedAnimations.has(candidates[i])) {
                animObserver.observe(candidates[i]);
                observedAnimations.add(candidates[i]);
            }
        }
        engineState.frozenAnimCount = frozenElements.size;
    }

    // 利用 requestIdleCallback 在浏览器主线程空闲阶段（>3ms）执行后台扫描，杜绝关键帧竞争
    function scheduleIdleScan(generation) {
        if (!animationScanIsCurrent(generation)) return;
        animationScanTimer = null;
        const run = (deadline) => {
            if (!animationScanIsCurrent(generation)) return;
            animationIdleId = null;
            animationScanTimer = null;
            if (!isCurrentlyInteracting() && (!deadline || deadline.timeRemaining() > 3)) {
                scanAndObserveAnimations();
                bindChatMessages();
            }
            animationScanTimer = setTimeout(() => scheduleIdleScan(generation), 4000);
        };
        if (window.requestIdleCallback) {
            animationIdleId = window.requestIdleCallback(run, { timeout: 6000 });
        } else {
            animationScanTimer = setTimeout(run, 4000);
        }
    }
    function pauseAnimationScan() {
        animationGeneration++;
        if (animationScanTimer !== null) clearTimeout(animationScanTimer);
        animationScanTimer = null;
        if (animationIdleId !== null && window.cancelIdleCallback) {
            window.cancelIdleCallback(animationIdleId);
        }
        animationIdleId = null;
        if (animObserver) animObserver.disconnect();
        animObserver = null;
        observedAnimations.clear();
        for (const element of frozenElements) element.classList.remove('__sc-anim-frozen');
        frozenElements.clear();
        engineState.frozenAnimCount = 0;
    }

    // ========================================================
    // 7b. 长聊天视口外消息：先量到真实高度，再按该高度跳过排版
    // ========================================================
    const SKIPPED_MESSAGE = '__sc-mes-skipped';
    const observedMessages = new Set();
    let chatVisibilityObserver = null;
    let chatListObserver = null;
    let boundChat = null;
    let chatVirtualizationGeneration = 0;

    function rememberMessageHeight(message, height) {
        if (height <= 0) return;
        const next = `auto ${height}px`;
        if (message.style.containIntrinsicBlockSize !== next) {
            message.style.containIntrinsicBlockSize = next;
        }
    }

    function clearMessageSkip(message) {
        message.classList.remove(SKIPPED_MESSAGE);
        message.style.containIntrinsicBlockSize = '';
    }

    function releaseObservedMessage(message) {
        if (chatVisibilityObserver) chatVisibilityObserver.unobserve(message);
        observedMessages.delete(message);
        clearMessageSkip(message);
    }

    function chatVirtualizationIsCurrent(generation) {
        return generation === chatVirtualizationGeneration &&
            isPerformancePresetActive() && chatVisibilityObserver !== null;
    }

    function applyMessageVisibility(generation, entries) {
        if (!chatVirtualizationIsCurrent(generation)) return;
        const observer = chatVisibilityObserver;
        const reveal = [];
        const skip = [];
        for (let i = 0; i < entries.length; i++) {
            const message = entries[i].target;
            if (!message.isConnected || !observedMessages.has(message)) continue;
            if (entries[i].isIntersecting) reveal.push(message);
            else skip.push(message);
        }
        for (let i = 0; i < reveal.length; i++) reveal[i].classList.remove(SKIPPED_MESSAGE);
        const revealHeights = [];
        for (let i = 0; i < reveal.length; i++) {
            revealHeights.push(Math.ceil(reveal[i].getBoundingClientRect().height));
        }
        const skipHeights = [];
        for (let i = 0; i < skip.length; i++) {
            const message = skip[i];
            skipHeights.push(message.classList.contains(SKIPPED_MESSAGE) ? 0 : Math.ceil(message.getBoundingClientRect().height));
        }
        if (!chatVirtualizationIsCurrent(generation) || chatVisibilityObserver !== observer) return;
        for (let i = 0; i < reveal.length; i++) rememberMessageHeight(reveal[i], revealHeights[i]);
        for (let i = 0; i < skip.length; i++) {
            const message = skip[i];
            rememberMessageHeight(message, skipHeights[i]);
            if (message.style.containIntrinsicBlockSize) message.classList.add(SKIPPED_MESSAGE);
        }
    }

    function observeChatMessage(message) {
        if (!chatVisibilityObserver || observedMessages.has(message)) return;
        chatVisibilityObserver.observe(message);
        observedMessages.add(message);
    }

    function collectMessages(node, into) {
        if (!(node instanceof Element)) return;
        if (node.classList.contains('mes')) into.push(node);
        const nested = node.querySelectorAll('.mes');
        for (let i = 0; i < nested.length; i++) into.push(nested[i]);
    }

    function stopChatListObserver() {
        if (chatListObserver) chatListObserver.disconnect();
        chatListObserver = null;
        boundChat = null;
    }

    function bindChatMessages() {
        if (!chatVisibilityObserver || !isPerformancePresetActive()) return;
        const chat = document.getElementById('chat');
        if (!chat) {
            stopChatListObserver();
            for (const message of Array.from(observedMessages)) releaseObservedMessage(message);
            return;
        }
        if (boundChat !== chat) {
            stopChatListObserver();
            boundChat = chat;
            const generation = chatVirtualizationGeneration;
            try {
                chatListObserver = new MutationObserver((mutations) => {
                    if (!chatVirtualizationIsCurrent(generation) || boundChat !== chat) return;
                    const removed = [];
                    for (let i = 0; i < mutations.length; i++) {
                        const mutation = mutations[i];
                        for (let n = 0; n < mutation.addedNodes.length; n++) {
                            const added = [];
                            collectMessages(mutation.addedNodes[n], added);
                            for (let m = 0; m < added.length; m++) observeChatMessage(added[m]);
                        }
                        for (let n = 0; n < mutation.removedNodes.length; n++) {
                            collectMessages(mutation.removedNodes[n], removed);
                        }
                    }
                    for (let i = 0; i < removed.length; i++) releaseObservedMessage(removed[i]);
                });
                chatListObserver.observe(chat, { childList: true });
            } catch (_) {
                chatListObserver = null;
            }
        }
        const live = chat.querySelectorAll('.mes');
        const seen = new Set();
        for (let i = 0; i < live.length; i++) {
            seen.add(live[i]);
            observeChatMessage(live[i]);
        }
        for (const message of Array.from(observedMessages)) {
            if (!seen.has(message)) releaseObservedMessage(message);
        }
    }

    function startChatVirtualization() {
        if (chatVisibilityObserver || !isPerformancePresetActive() || !window.IntersectionObserver) return;
        const generation = ++chatVirtualizationGeneration;
        try {
            const observer = new IntersectionObserver((entries) => {
                applyMessageVisibility(generation, entries);
            }, { root: null, rootMargin: '240px 0px' });
            chatVisibilityObserver = observer;
            engineState.virtualizationActive = true;
            bindChatMessages();
            if (!chatVirtualizationIsCurrent(generation) || chatVisibilityObserver !== observer) {
                stopChatVirtualization();
            }
        } catch (_) {
            stopChatVirtualization();
        }
    }

    function stopChatVirtualization() {
        chatVirtualizationGeneration++;
        const observer = chatVisibilityObserver;
        chatVisibilityObserver = null;
        if (observer) observer.disconnect();
        stopChatListObserver();
        for (const message of observedMessages) clearMessageSkip(message);
        observedMessages.clear();
        engineState.virtualizationActive = false;
    }

    function syncPerformancePreset() {
        if (!disposed && renderSettings.performancePreset) {
            performancePresetActive = true;
            setupPerformanceStyle();
            installDrawerAnimations();
            if (isPageActive()) {
                startAnimationScan();
                startChatVirtualization();
            } else {
                pauseAnimationScan();
                stopChatVirtualization();
                settleOwnedDrawerAnimations();
            }
        } else {
            performancePresetActive = false;
            pauseAnimationScan();
            stopChatVirtualization();
            stopDrawerAnimations();
            if (performanceStyle) performanceStyle.remove();
            performanceStyle = null;
            engineState.virtualizationActive = false;
        }
        syncInteractionListeners();
    }

    // ========================================================
    // 8. 交互优先调频器 (Zero-Timer Interaction Governor)
    // ========================================================
    let lastInteractionTimestamp = 0;
    const interactionListeners = [];
    function notifyInteraction() {
        if (!isPageActive()) return;
        lastInteractionTimestamp = performance.now();
        engineState.isInteracting = true;
    }
    function isCurrentlyInteracting() {
        if (!engineState.isInteracting) return false;
        if (performance.now() - lastInteractionTimestamp > 180) {
            engineState.isInteracting = false;
            return false;
        }
        return true;
    }
    function syncInteractionListeners() {
        const needed = isPageActive() && (performancePresetActive || engineState.hudVisible);
        if (!needed) {
            for (const remove of interactionListeners.splice(0)) remove();
            engineState.isInteracting = false;
            return;
        }
        if (interactionListeners.length) return;
        listen(window, 'touchstart', notifyInteraction, { passive: true, capture: true }, interactionListeners);
        listen(window, 'touchmove', notifyInteraction, { passive: true }, interactionListeners);
        listen(window, 'scroll', notifyInteraction, { passive: true, capture: true }, interactionListeners);
    }

    // ========================================================
    // 9. PERFORMANCE MONITOR & HUD (美化对齐 SC 整体前端规范)
    // ========================================================
    let longTaskObserver = null;
    let mutationInterval = null;
    let domCountInterval = null;
    let hudInterval = null;

    const frameTimes = [];
    let lastTimestamp = performance.now();
    let frameLoopId = null;
    let frameLoopCounter = 0;

    function frameLoop(now) {
        if (!isPageActive() || !engineState.hudVisible) {
            frameLoopId = null;
            return;
        }
        const delta = now - lastTimestamp;
        lastTimestamp = now;
        if (streamingFramePending) {
            streamingFramePending = false;
            streamingBatcher.streamingFpsCount++;
        }

        if (delta > 0 && delta < 500) {
            frameTimes.push(delta);
            if (frameTimes.length > 60) frameTimes.shift();

            frameLoopCounter++;
            // 仅每 20 帧（约 220ms）才进行一次排序汇总，单帧内 0 对象分配、0 GC 压力
            if (frameLoopCounter >= 20) {
                frameLoopCounter = 0;
                const avg = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length;
                engineState.fps = Math.min(120, +(1000 / avg).toFixed(1));
                engineState.frameTime = +avg.toFixed(1);

                const sorted = [...frameTimes].sort((a, b) => a - b);
                const idx95 = Math.floor(sorted.length * 0.95);
                const idx99 = Math.floor(sorted.length * 0.99);
                engineState.p95 = +(sorted[idx95] || avg).toFixed(1);
                engineState.p99 = +(sorted[idx99] || avg).toFixed(1);
            }
        }

        frameLoopId = requestAnimationFrame(frameLoop);
    }

    function startFrameLoop() {
        if (frameLoopId !== null || !isPageActive() || !engineState.hudVisible) return;
        lastTimestamp = performance.now();
        frameLoopCounter = 0;
        frameTimes.length = 0;
        frameLoopId = requestAnimationFrame(frameLoop);
    }

    function stopFrameLoop() {
        if (frameLoopId !== null) {
            cancelAnimationFrame(frameLoopId);
            frameLoopId = null;
        }
    }

    function updateDomCount() {
        if (!isPageActive() || !engineState.hudVisible) return;
        if (!isCurrentlyInteracting()) {
            engineState.domCount = document.getElementsByTagName('*').length;
        }
    }

    let hudElement = null;
    let hudRevealFrame = null;
    let hudHideTimer = null;

    function createHud() {
        if (disposed || hudElement || !document.body) return;
        hudElement = document.createElement('div');
        hudElement.id = 'sc-perf-hud';
        hudElement.style.cssText = `
            display: none;
            position: fixed;
            top: calc(var(--topBarBlockSize, 42px) + 8px);
            right: 12px;
            width: 242px;
            background: rgba(15, 17, 23, 0.90);
            backdrop-filter: blur(24px) saturate(1.4);
            -webkit-backdrop-filter: blur(24px) saturate(1.4);
            border: 1px solid rgba(255, 255, 255, 0.10);
            border-radius: 16px;
            padding: 12px 14px;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 11px;
            color: #E2E8F0;
            z-index: 999999;
            box-shadow: 0 16px 40px -4px rgba(0, 0, 0, 0.65), 0 0 0 1px rgba(255, 255, 255, 0.05);
            pointer-events: auto;
            user-select: none;
            transition: opacity 160ms cubic-bezier(0.12, 0.98, 0.24, 1), transform 160ms cubic-bezier(0.12, 0.98, 0.24, 1);
            transform-origin: top right;
        `;

        listen(hudElement, 'dblclick', (e) => {
            e.stopPropagation();
            window.__scHaptic('tick');
            window.__scTogglePerfHud();
        });

        hudElement.innerHTML = `
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:6px;">
                <div style="display:flex; align-items:center; gap:6px;">
                    <span style="display:inline-block; width:6px; height:6px; border-radius:50%; background:#10B981; box-shadow:0 0 8px #10B981;"></span>
                    <span style="font-weight:700; color:#EAB308; letter-spacing:0.6px; font-size:11px;">SC RENDER ENGINE</span>
                </div>
                <span id="sc-perf-health" style="font-size:9.5px; padding:2px 6px; border-radius:999px; background:rgba(16, 185, 129, 0.15); color:#34D399; font-weight:600; border:1px solid rgba(16, 185, 129, 0.3);">90Hz Ultra</span>
            </div>
            <div style="display:grid; grid-template-columns: 1fr auto; row-gap:4px; font-size:10.5px;">
                <span style="color:#64748B;">FPS</span>
                <span id="sc-perf-fps" style="font-weight:600; color:#38BDF8;">--</span>

                <span style="color:#64748B;">Frame Time</span>
                <span id="sc-perf-frame">-- ms</span>

                <span style="color:#64748B;">P95 / P99</span>
                <span id="sc-perf-p95">-- / --</span>

                <span style="color:#64748B;">Long Tasks</span>
                <span id="sc-perf-longtasks" style="color:#F43F5E;">0</span>

                <span style="color:#64748B;">DOM Count</span>
                <span id="sc-perf-dom">--</span>

                <span style="color:#64748B;">Mutations</span>
                <span id="sc-perf-mut">--/s</span>

                <span style="color:#64748B;">Streaming</span>
                <span id="sc-perf-stream">Idle</span>

                <span style="color:#64748B;">JS Frame</span>
                <span id="sc-perf-js">-- ms</span>

                <span style="color:#64748B;">Chameleon</span>
                <span id="sc-perf-cham" style="color:#34D399; font-weight:600;">Event-0Poll</span>

                <span style="color:#64748B;">Layer Engine</span>
                <span id="sc-perf-layer" style="color:#10B981; font-weight:600;">Anti-Explosion</span>

                <span style="color:#64748B;">Frozen Anims</span>
                <span id="sc-perf-frozen" style="color:#A78BFA;">0</span>

                <span style="color:#64748B;">Governor</span>
                <span id="sc-perf-gov" style="color:#38BDF8;">Smooth</span>
            </div>

            <!-- 触感反馈控制开关 -->
            <div style="display:flex; justify-content:space-between; align-items:center; margin-top:10px; padding-top:8px; border-top:1px solid rgba(255,255,255,0.08);">
                <div style="display:flex; flex-direction:column;">
                    <span style="color:#94A3B8; font-size:10.5px; font-weight:500;">触感反馈</span>
                    <span style="color:#475569; font-size:9px;">全站微米级振动</span>
                </div>
                <div id="sc-haptic-toggle-btn" style="
                    position: relative;
                    width: 36px;
                    height: 20px;
                    background: ${engineState.hapticEnabled ? '#10B981' : '#334155'};
                    border-radius: 999px;
                    cursor: pointer;
                    transition: background 180ms cubic-bezier(0.12, 0.98, 0.24, 1);
                ">
                    <div id="sc-haptic-knob" style="
                        position: absolute;
                        top: 2px;
                        left: ${engineState.hapticEnabled ? '18px' : '2px'};
                        width: 16px;
                        height: 16px;
                        background: #FFFFFF;
                        border-radius: 50%;
                        box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
                        transition: left 180ms cubic-bezier(0.12, 0.98, 0.24, 1);
                    "></div>
                </div>
            </div>

            <div style="margin-top:10px; text-align:center; font-size:9px; color:#475569;">
                连续点击右上角 7 次或双击面板关闭
            </div>
        `;

        // 绑定触感开关切换
        const toggleBtn = hudElement.querySelector('#sc-haptic-toggle-btn');
        const knob = hudElement.querySelector('#sc-haptic-knob');
        if (toggleBtn && knob) {
            listen(toggleBtn, 'click', (e) => {
                e.stopPropagation();
                engineState.hapticEnabled = !engineState.hapticEnabled;
                safeStorageSet('__sc_haptic_enabled', engineState.hapticEnabled ? '1' : '0');
                toggleBtn.style.background = engineState.hapticEnabled ? '#10B981' : '#334155';
                knob.style.left = engineState.hapticEnabled ? '18px' : '2px';
                if (engineState.hapticEnabled) {
                    window.__scHaptic('click');
                }
            });
        }

        document.body.appendChild(hudElement);
    }

    function updateHud() {
        if (!isPageActive() || !engineState.hudVisible || !hudElement) return;
        const elFps = hudElement.querySelector('#sc-perf-fps');
        const elFrame = hudElement.querySelector('#sc-perf-frame');
        const elP95 = hudElement.querySelector('#sc-perf-p95');
        const elLong = hudElement.querySelector('#sc-perf-longtasks');
        const elDom = hudElement.querySelector('#sc-perf-dom');
        const elMut = hudElement.querySelector('#sc-perf-mut');
        const elStream = hudElement.querySelector('#sc-perf-stream');
        const elJs = hudElement.querySelector('#sc-perf-js');
        const elLayer = hudElement.querySelector('#sc-perf-layer');
        const elFrozen = hudElement.querySelector('#sc-perf-frozen');
        const elGov = hudElement.querySelector('#sc-perf-gov');
        const elHealth = hudElement.querySelector('#sc-perf-health');
        const elCham = hudElement.querySelector('#sc-perf-cham');

        if (elFps) elFps.textContent = engineState.fps;
        if (elFrame) elFrame.textContent = engineState.frameTime + 'ms';
        if (elP95) elP95.textContent = engineState.p95 + ' / ' + engineState.p99;
        if (elLong) elLong.textContent = engineState.longTaskCount;
        if (elDom) elDom.textContent = Number(engineState.domCount).toLocaleString();
        if (elMut) elMut.textContent = engineState.mutationsPerSec + '/s';
        if (elStream) {
            if (engineState.streamingFps > 0) {
                elStream.textContent = 'Frames (' + engineState.streamingFps + ' FPS)';
                elStream.style.color = '#10B981';
            } else {
                elStream.textContent = 'Idle';
                elStream.style.color = '#64748B';
            }
        }
        if (elJs) elJs.textContent = engineState.lastFrameJsTime.toFixed(1) + 'ms';
        if (elLayer) elLayer.textContent = 'Single+Tail';
        if (elFrozen) elFrozen.textContent = engineState.frozenAnimCount;
        if (elCham) elCham.textContent = 'Event-0Poll';
        if (elGov) {
            const interacting = isCurrentlyInteracting();
            elGov.textContent = interacting ? 'Touch Priority' : 'Smooth';
            elGov.style.color = interacting ? '#F59E0B' : '#38BDF8';
        }

        if (elHealth) {
            if (engineState.fps >= 75 && engineState.p95 < 18) {
                elHealth.textContent = '90Hz Ultra';
                elHealth.style.background = 'rgba(16, 185, 129, 0.15)';
                elHealth.style.color = '#34D399';
            } else if (engineState.fps >= 55) {
                elHealth.textContent = 'Healthy';
                elHealth.style.background = 'rgba(16, 185, 129, 0.15)';
                elHealth.style.color = '#34D399';
            } else if (engineState.fps >= 35) {
                elHealth.textContent = 'Warning';
                elHealth.style.background = 'rgba(245, 158, 11, 0.15)';
                elHealth.style.color = '#FBBF24';
            } else {
                elHealth.textContent = 'Lagging';
                elHealth.style.background = 'rgba(239, 68, 68, 0.15)';
                elHealth.style.color = '#F87171';
            }
        }
    }

    function startDiagnostics() {
        if (!isPageActive() || !engineState.hudVisible || hudInterval !== null) return;
        syncInteractionListeners();
        startFrameLoop();
        startMutationObserver();
        if (window.PerformanceObserver) {
            try {
                longTaskObserver = new PerformanceObserver((list) => {
                    if (!isPageActive() || !engineState.hudVisible) return;
                    engineState.longTaskCount += list.getEntries().length;
                });
                longTaskObserver.observe({ entryTypes: ['longtask'] });
            } catch(_) {
                if (longTaskObserver) longTaskObserver.disconnect();
                longTaskObserver = null;
            }
        }
        mutationInterval = setInterval(updateMutationMetrics, 1000);
        domCountInterval = setInterval(updateDomCount, 2000);
        hudInterval = setInterval(updateHud, 200);
        updateDomCount();
        updateHud();
    }

    function stopDiagnostics() {
        for (const id of [mutationInterval, domCountInterval, hudInterval]) {
            if (id !== null) clearInterval(id);
        }
        mutationInterval = null;
        domCountInterval = null;
        hudInterval = null;
        stopFrameLoop();
        stopMutationObserver();
        if (longTaskObserver) longTaskObserver.disconnect();
        longTaskObserver = null;
        syncInteractionListeners();
    }

    function cancelHudTransition() {
        if (hudRevealFrame !== null) cancelAnimationFrame(hudRevealFrame);
        hudRevealFrame = null;
        if (hudHideTimer !== null) clearTimeout(hudHideTimer);
        hudHideTimer = null;
    }

    window.__scTogglePerfHud = function() {
        if (disposed) return false;
        if (!hudElement) createHud();
        if (!hudElement) return false;
        cancelHudTransition();
        engineState.hudVisible = !engineState.hudVisible;
        if (engineState.hudVisible) {
            startDiagnostics();
            hudElement.style.display = 'block';
            hudElement.style.opacity = '0';
            hudElement.style.transform = 'scale(0.92) translateY(-4px)';
            if (isPageActive()) {
                hudRevealFrame = requestAnimationFrame(() => {
                    hudRevealFrame = null;
                    if (!isPageActive() || !engineState.hudVisible || !hudElement) return;
                    hudElement.style.opacity = '1';
                    hudElement.style.transform = 'scale(1) translateY(0)';
                });
            } else {
                hudElement.style.opacity = '1';
                hudElement.style.transform = 'scale(1) translateY(0)';
            }
        } else {
            stopDiagnostics();
            hudElement.style.opacity = '0';
            hudElement.style.transform = 'scale(0.92) translateY(-4px)';
            if (isPageActive()) {
                hudHideTimer = setTimeout(() => {
                    hudHideTimer = null;
                    if (!engineState.hudVisible && hudElement) hudElement.style.display = 'none';
                }, 160);
            } else {
                hudElement.style.display = 'none';
            }
        }
        return engineState.hudVisible;
    };

    function pausePageResources() {
        scheduler.pause();
        stopDiagnostics();
        cancelHudTransition();
        chameleonEngine.pause();
        syncPerformancePreset();
        engineState.isInteracting = false;
        if (hudElement) {
            hudElement.style.display = engineState.hudVisible ? 'block' : 'none';
            hudElement.style.opacity = engineState.hudVisible ? '1' : '0';
            hudElement.style.transform = 'scale(1) translateY(0)';
        }
    }

    // 锁屏会让 #chat 先变矮再变高。贴底时变矮只钳住 scrollTop，变高时酒馆的
    // ResizeObserver 再把 scrollTop 减一截，解锁后阅读位置就偏上。隐藏时记下
    // 贴底或滚动偏移，重新可见后的高度补偿都按这个位置收回。
    const CHAT_BOTTOM_GAP = 4;
    const CHAT_SCROLL_HOLD_MS = 640;
    let stableChatAnchor = null;
    let chatScrollHold = null;
    let chatScrollHoldTimer = null;
    let chatScrollObserver = null;
    let observedChat = null;
    let restoringChatScroll = false;
    let suppressChatAnchorSample = false;
    let pendingChatAnchor = null;

    function releaseChatScrollHold() {
        chatScrollHold = null;
        if (chatScrollHoldTimer !== null) {
            clearTimeout(chatScrollHoldTimer);
            chatScrollHoldTimer = null;
        }
    }

    function readChatAnchor() {
        const chat = document.getElementById('chat');
        if (!chat) return null;
        const gap = chat.scrollHeight - chat.scrollTop - chat.clientHeight;
        return {
            pinned: gap <= CHAT_BOTTOM_GAP,
            scrollTop: chat.scrollTop,
            scrollHeight: chat.scrollHeight
        };
    }

    function commitPendingChatAnchor() {
        if (!pendingChatAnchor || suppressChatAnchorSample || restoringChatScroll || chatScrollHold || !isPageActive()) {
            pendingChatAnchor = null;
            return;
        }
        stableChatAnchor = pendingChatAnchor;
        pendingChatAnchor = null;
    }

    function bindChatScrollObserver() {
        const chat = document.getElementById('chat');
        if (!chat || !window.ResizeObserver) return;
        if (chatScrollObserver && observedChat === chat) return;
        if (chatScrollObserver) chatScrollObserver.disconnect();
        observedChat = chat;
        try {
            chatScrollObserver = new ResizeObserver(() => {
                // 酒馆的高度补偿也在这一轮回调里改 scrollTop。微任务晚于整轮回调，
                // 能盖掉它，同时不把这次补偿写进稳定锚点。
                suppressChatAnchorSample = true;
                queueMicrotask(() => {
                    restoreChatScroll();
                    suppressChatAnchorSample = false;
                });
            });
            chatScrollObserver.observe(chat);
        } catch (_) {
            chatScrollObserver = null;
            observedChat = null;
        }
    }

    function holdChatScroll() {
        bindChatScrollObserver();
        // 隐藏这一刻的位置就是读者正在看的位置。补偿滚动发生在之后的高度变化里。
        if (!suppressChatAnchorSample) {
            const live = readChatAnchor();
            if (live) stableChatAnchor = live;
        }
        pendingChatAnchor = null;
        chatScrollHold = stableChatAnchor;
    }

    function activeScrollHold() {
        if (chatScrollHold) return chatScrollHold;
        // 重新生成把当前楼拆掉时，页面仍可见，锁屏那条 hold 不会武装。
        // 这里单独把视口吸在正在生成的楼底，盖过随后的高度补偿。
        if (generationScrollFollow) return { pinned: true, scrollTop: 0, scrollHeight: 0 };
        return null;
    }

    function restoreChatScroll() {
        const hold = activeScrollHold();
        if (restoringChatScroll || !hold) return;
        const chat = document.getElementById('chat');
        if (!chat) return;
        restoringChatScroll = true;
        try {
            const max = Math.max(0, chat.scrollHeight - chat.clientHeight);
            const target = hold.pinned
                ? max
                : Math.min(max, Math.max(0, hold.scrollTop + (chat.scrollHeight - hold.scrollHeight)));
            if (Math.abs(chat.scrollTop - target) > 1) chat.scrollTop = target;
        } finally {
            restoringChatScroll = false;
        }
    }

    function armChatScrollHoldRelease() {
        if (chatScrollHoldTimer !== null) clearTimeout(chatScrollHoldTimer);
        chatScrollHoldTimer = setTimeout(() => {
            chatScrollHoldTimer = null;
            restoreChatScroll();
            const fresh = readChatAnchor();
            if (fresh) stableChatAnchor = fresh;
            chatScrollHold = null;
        }, CHAT_SCROLL_HOLD_MS);
    }

    function noteChatScrollIntent() {
        if (chatScrollHold) releaseChatScrollHold();
        if (generationScrollFollow) releaseGenerationFollow();
    }

    function onChatScroll(event) {
        const chat = document.getElementById('chat');
        if (!chat || event.target !== chat) return;
        // 脚本改 scrollTop 时，Chrome 仍会派发 isTrusted 的 scroll，不能拿它当读者手势。
        if (restoringChatScroll || suppressChatAnchorSample || chatScrollHold || generationScrollFollow || !isPageActive()) return;
        pendingChatAnchor = readChatAnchor();
        queueMicrotask(commitPendingChatAnchor);
        queueMicrotask(rememberTrackedMessage);
    }

    // 重新生成会先把这一楼收成 "..." 或整段拆掉。读者当时多半不在最底 5px，
    // 酒馆的 scrollLock 已经关上，而浏览器钳 scrollTop 时又不派发 scroll，
    // 锁就清不掉，新文字在视口下面长，画面停在上面几楼。
    // 这一楼还在视口里时，把滚动吸回楼底，并派发一次 scroll，让酒馆重新跟着生成。
    const GENERATION_FOLLOW_QUIET_MS = 1200;
    let generationScrollFollow = false;
    let generationQuietTimer = null;
    let chatMutationObserver = null;
    let trackedMessageObserver = null;
    let trackedChat = null;
    let trackedLastMessage = null;
    let trackedMessageHeight = 0;
    let trackedMessageInView = false;

    function releaseGenerationFollow() {
        generationScrollFollow = false;
        if (generationQuietTimer !== null) {
            clearTimeout(generationQuietTimer);
            generationQuietTimer = null;
        }
    }

    function rememberTrackedMessage() {
        const chat = document.getElementById('chat');
        const message = trackedLastMessage;
        if (!chat || !message || !message.isConnected) return;
        trackedMessageHeight = message.offsetHeight;
        const top = message.offsetTop;
        const bottom = top + trackedMessageHeight;
        trackedMessageInView = bottom > chat.scrollTop - 8 && top < chat.scrollTop + chat.clientHeight + 8;
    }

    function nudgeTavernScrollLock(chat) {
        // 酒馆的 scrollLock 只在 scroll 回调里、且空隙小于 5px 时才会打开自动跟滚。
        // 钳制 scrollTop 不派发 scroll，所以这里在已经吸底之后补一次。
        if (!chat) return;
        chat.dispatchEvent(new Event('scroll'));
    }

    function scheduleGenerationQuiet() {
        if (generationQuietTimer !== null) clearTimeout(generationQuietTimer);
        generationQuietTimer = setTimeout(() => {
            generationQuietTimer = null;
            if (!generationScrollFollow) return;
            const chat = document.getElementById('chat');
            suppressChatAnchorSample = true;
            restoreChatScroll();
            if (chat && isPageActive()) nudgeTavernScrollLock(chat);
            const fresh = readChatAnchor();
            if (fresh) stableChatAnchor = fresh;
            suppressChatAnchorSample = false;
            generationScrollFollow = false;
        }, GENERATION_FOLLOW_QUIET_MS);
    }

    function armGenerationFollow() {
        if (!isPageActive()) return;
        generationScrollFollow = true;
        suppressChatAnchorSample = true;
        restoreChatScroll();
        const chat = document.getElementById('chat');
        if (chat) nudgeTavernScrollLock(chat);
        const fresh = readChatAnchor();
        if (fresh) stableChatAnchor = fresh;
        suppressChatAnchorSample = false;
        rememberTrackedMessage();
        scheduleGenerationQuiet();
    }

    function placeholderText(message) {
        const text = message && message.querySelector ? message.querySelector('.mes_text') : null;
        if (!text) return '';
        return (text.textContent || '').replace(/\s+/g, '');
    }

    function isGenerationPlaceholder(message) {
        const value = placeholderText(message);
        return value === '...' || value === '…';
    }

    function bindTrackedMessage(message) {
        if (trackedMessageObserver) trackedMessageObserver.disconnect();
        trackedMessageObserver = null;
        trackedLastMessage = message;
        trackedMessageHeight = 0;
        trackedMessageInView = false;
        if (!message || !window.ResizeObserver) {
            rememberTrackedMessage();
            return;
        }
        try {
            trackedMessageObserver = new ResizeObserver(() => onTrackedMessageResize());
            trackedMessageObserver.observe(message);
        } catch (_) {
            trackedMessageObserver = null;
        }
        rememberTrackedMessage();
    }

    function noteTrackedMessageHeight(height) {
        const message = trackedLastMessage;
        const chat = document.getElementById('chat');
        if (!message || !chat || !isPageActive()) return false;
        const drop = trackedMessageHeight - height;
        const wasInView = trackedMessageInView;
        const wasPinned = !!(stableChatAnchor && stableChatAnchor.pinned);
        if (generationScrollFollow) {
            suppressChatAnchorSample = true;
            restoreChatScroll();
            nudgeTavernScrollLock(chat);
            const fresh = readChatAnchor();
            if (fresh) stableChatAnchor = fresh;
            suppressChatAnchorSample = false;
            scheduleGenerationQuiet();
            rememberTrackedMessage();
            return true;
        }
        const collapsed = drop > 64 && height < 140;
        const placeholder = isGenerationPlaceholder(message) && drop > 24;
        if ((collapsed || placeholder) && (wasInView || wasPinned)) {
            trackedMessageHeight = height;
            armGenerationFollow();
            return true;
        }
        // 普通变矮记下新高度；收到 140px 以下时保留原高度，后面的动画帧仍能看出整段塌缩。
        if (height >= 140 || height >= trackedMessageHeight - 8) rememberTrackedMessage();
        return false;
    }

    function onTrackedMessageResize() {
        if (restoringChatScroll) return;
        const message = trackedLastMessage;
        if (!message || !message.isConnected) return;
        noteTrackedMessageHeight(message.offsetHeight);
    }

    function syncTrackedMessage() {
        const chat = document.getElementById('chat');
        if (!chat) {
            if (trackedMessageObserver) trackedMessageObserver.disconnect();
            trackedMessageObserver = null;
            trackedLastMessage = null;
            return;
        }
        let message = null;
        const children = chat.children;
        for (let i = children.length - 1; i >= 0; i--) {
            if (children[i].classList && children[i].classList.contains('mes')) {
                message = children[i];
                break;
            }
        }
        if (message !== trackedLastMessage) bindTrackedMessage(message);
    }

    function onChatMutations(mutations) {
        if (!isPageActive()) {
            syncTrackedMessage();
            return;
        }
        const previous = trackedLastMessage;
        const wasInView = trackedMessageInView;
        const wasPinned = !!(stableChatAnchor && stableChatAnchor.pinned);
        let removedCollapsedTail = false;
        let removedMany = false;
        for (let i = 0; i < mutations.length; i++) {
            const mutation = mutations[i];
            if (mutation.type !== 'childList' || mutation.target !== trackedChat) continue;
            let removed = 0;
            for (let n = 0; n < mutation.removedNodes.length; n++) {
                const node = mutation.removedNodes[n];
                if (!(node instanceof Element) || !node.classList.contains('mes')) continue;
                removed++;
                if (node === previous && (node.style.display === 'none' || isGenerationPlaceholder(node))) {
                    removedCollapsedTail = true;
                }
            }
            if (removed > 1) removedMany = true;
        }
        syncTrackedMessage();
        if (removedMany) return;
        if (generationScrollFollow) {
            suppressChatAnchorSample = true;
            restoreChatScroll();
            const chat = document.getElementById('chat');
            if (chat) nudgeTavernScrollLock(chat);
            const fresh = readChatAnchor();
            if (fresh) stableChatAnchor = fresh;
            suppressChatAnchorSample = false;
            scheduleGenerationQuiet();
            return;
        }
        const message = trackedLastMessage;
        if (removedCollapsedTail && (wasInView || wasPinned)) {
            armGenerationFollow();
            return;
        }
        if (message && message !== previous && isGenerationPlaceholder(message) && (wasPinned || messageInViewNow(message))) {
            armGenerationFollow();
            return;
        }
        // 文字变成 "..." 和高度塌缩发生在同一轮任务里，ResizeObserver 晚于这次
        // 回调。若这里先把基线记成塌缩后的高度，后面就看不出这一楼被收掉了。
        if (message && message === previous && message.isConnected &&
            noteTrackedMessageHeight(message.offsetHeight)) {
            return;
        }
        if (!chatScrollHold && !suppressChatAnchorSample) {
            const fresh = readChatAnchor();
            if (fresh) stableChatAnchor = fresh;
        }
    }

    function messageInViewNow(message) {
        const chat = document.getElementById('chat');
        if (!chat || !message) return false;
        const top = message.offsetTop;
        const bottom = top + message.offsetHeight;
        return bottom > chat.scrollTop - 8 && top < chat.scrollTop + chat.clientHeight + 8;
    }

    function unbindGenerationScrollWatch() {
        if (chatMutationObserver) chatMutationObserver.disconnect();
        chatMutationObserver = null;
        if (trackedMessageObserver) trackedMessageObserver.disconnect();
        trackedMessageObserver = null;
        trackedChat = null;
        trackedLastMessage = null;
    }

    function bindGenerationScrollWatch() {
        if (!isPageActive()) return;
        const chat = document.getElementById('chat');
        if (!chat) return;
        if (chatMutationObserver && trackedChat === chat) {
            syncTrackedMessage();
            return;
        }
        if (chatMutationObserver) chatMutationObserver.disconnect();
        trackedChat = chat;
        syncTrackedMessage();
        if (!window.MutationObserver) return;
        try {
            chatMutationObserver = new MutationObserver(onChatMutations);
            chatMutationObserver.observe(chat, { childList: true, subtree: true, characterData: true });
        } catch (_) {
            chatMutationObserver = null;
        }
    }

    let resourcesActive = isPageActive();
    function syncPageResources() {
        if (disposed) return;
        if (!isPageActive()) {
            // 只在第一次进入隐藏时取样。视口外消息随后会恢复排版，滚动值已经不可信。
            if (resourcesActive) holdChatScroll();
            if (chatScrollHoldTimer !== null) {
                clearTimeout(chatScrollHoldTimer);
                chatScrollHoldTimer = null;
            }
            resourcesActive = false;
            unbindGenerationScrollWatch();
            pausePageResources();
            return;
        }
        if (resourcesActive) return;
        resourcesActive = true;
        startChameleon();
        syncPerformancePreset();
        bindGenerationScrollWatch();
        restoreChatScroll();
        if (chatScrollHold) armChatScrollHoldRelease();
        if (scheduler.readQueue.length || scheduler.writeQueue.length) scheduler.schedule();
        startDiagnostics();
    }

    function dispose() {
        if (disposed) return;
        disposed = true;
        engineState.hudVisible = false;
        releaseChatScrollHold();
        releaseGenerationFollow();
        unbindGenerationScrollWatch();
        if (chatScrollObserver) chatScrollObserver.disconnect();
        chatScrollObserver = null;
        observedChat = null;
        if (chatMutationObserver) chatMutationObserver.disconnect();
        chatMutationObserver = null;
        if (trackedMessageObserver) trackedMessageObserver.disconnect();
        trackedMessageObserver = null;
        trackedChat = null;
        trackedLastMessage = null;
        pausePageResources();
        scheduler.readQueue.length = 0;
        scheduler.writeQueue.length = 0;
        for (const remove of ownedListeners.splice(0)) remove();
        for (const restore of patchRestorers.splice(0).reverse()) restore();
        if (mandatoryStyle) mandatoryStyle.remove();
        mandatoryStyle = null;
        if (hudElement) hudElement.remove();
        hudElement = null;
        window.__scRenderEngineInstalled = false;
    }

    listen(window, 'touchstart', noteChatScrollIntent, { capture: true, passive: true });
    listen(window, 'pointerdown', noteChatScrollIntent, { capture: true, passive: true });
    listen(window, 'wheel', noteChatScrollIntent, { capture: true, passive: true });
    listen(document, 'scroll', onChatScroll, { capture: true, passive: true });
    listen(document, 'visibilitychange', syncPageResources);
    bindChatScrollObserver();
    bindGenerationScrollWatch();
    stableChatAnchor = readChatAnchor();
    listen(window, 'pagehide', (event) => {
        pageSuspended = true;
        if (event.persisted) syncPageResources();
        else dispose();
    });
    listen(window, 'pageshow', () => {
        pageSuspended = false;
        syncPageResources();
    });

    // ========================================================
    // 10. UNIFIED ENGINE NAMESPACE
    // ========================================================
    window.SillyClientEngine = {
        version: '0.2.5',
        state: engineState,
        scheduler: scheduler,
        batcher: streamingBatcher,
        chameleon: chameleonEngine,
        haptic: window.__scHaptic,
        toggleHud: window.__scTogglePerfHud,
        dispose: dispose
    };
    syncPerformancePreset();
})();

