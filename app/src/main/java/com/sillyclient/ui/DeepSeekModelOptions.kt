package com.sillyclient.ui

import org.json.JSONArray

/**
 * SillyTavern 的 DeepSeek 模型下拉在拉取 `/models` 之前只有「连接到 API」。
 * 官方当前可调用的模型是一份很小的固定目录，这里把它们补进下拉，
 * 让用户不必先连上接口也能选中具体模型。接口稍后返回的型号会保留，
 * 已选中的值不会被改写。
 */
object DeepSeekModelOptions {
    const val SELECT_ID = "model_deepseek_select"
    private val MODEL_ID = Regex("^[a-z0-9][a-z0-9._-]{0,80}$")

    /**
     * 顺序即下拉里的补充顺序。前两项是当前模型；后两项是仍被接受的旧名，
     * 其中 `deepseek-v4-flash` 也是酒馆内置默认值。
     */
    val modelIds: List<String> = listOf(
        "deepseek-flash",
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "deepseek-v4-flash-vision-exp",
    )

    init {
        require(modelIds.distinct().size == modelIds.size)
        require(modelIds.all { MODEL_ID.matches(it) })
    }

    fun missingIds(existingValues: Collection<String>): List<String> {
        val present = existingValues.toSet()
        return modelIds.filter { it !in present }
    }

    fun script(): String {
        val encoded = JSONArray(modelIds).toString()
        return """
            (function () {
              try {
                const marker = '__scDeepSeekModelOptions';
                if (window[marker]) return;
                window[marker] = true;
                const models = $encoded;
                const selectId = '$SELECT_ID';
                let bound = null;
                let observer = null;
                let applying = false;

                function ensure(select) {
                  if (!select || applying) return;
                  applying = true;
                  try {
                    const present = Object.create(null);
                    for (let index = 0; index < select.options.length; index++) {
                      present[select.options[index].value] = true;
                    }
                    const previous = select.value;
                    for (let index = 0; index < models.length; index++) {
                      const id = models[index];
                      if (present[id]) continue;
                      const option = document.createElement('option');
                      option.value = id;
                      option.text = id;
                      select.appendChild(option);
                      present[id] = true;
                    }
                    if (select.value !== previous) select.value = previous;
                  } finally {
                    applying = false;
                  }
                }

                function bind(select) {
                  if (!select) return;
                  if (bound !== select) {
                    if (observer) observer.disconnect();
                    bound = select;
                    observer = new MutationObserver(function () { ensure(select); });
                    observer.observe(select, { childList: true });
                  }
                  ensure(select);
                }

                function tryBind(attempts) {
                  const select = document.getElementById(selectId);
                  if (select) {
                    bind(select);
                    return;
                  }
                  if (attempts <= 0) return;
                  setTimeout(function () { tryBind(attempts - 1); }, 250);
                }

                tryBind(20);
                if (document.addEventListener) {
                  document.addEventListener('pointerdown', function (event) {
                    const target = event && event.target;
                    if (!target) return;
                    const select = target.id === selectId
                      ? target
                      : (target.closest ? target.closest('#' + selectId) : null);
                    if (select) bind(select);
                  }, true);
                }
              } catch (error) {}
            })();
        """.trimIndent()
    }
}
